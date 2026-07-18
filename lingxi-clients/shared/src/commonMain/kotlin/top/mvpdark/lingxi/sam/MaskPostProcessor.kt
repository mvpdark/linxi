package top.mvpdark.lingxi.sam

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * SAM Mask 后处理器（纯 Kotlin 实现）。
 *
 * 移植自 `sam-client.js` 的 maskToPolygon 算法，并扩展为找最大连通域：
 * 1. [maskToPolygon]: 8-连通域标记 → 找最大连通域 → Moore Neighbor Tracing 轮廓追踪
 *    → Douglas-Peucker 简化（epsilon = 周长 × 0.005）→ 归一化到 0-1
 * 2. [upsampleMask]: 双线性插值上采样 + 按 0 阈值二值化
 */
object MaskPostProcessor {

    /** 8 邻域方向（顺时针）：E, SE, S, SW, W, NW, N, NE */
    private val DX = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
    private val DY = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

    /**
     * 将二值 mask 转换为归一化多边形轮廓。
     *
     * 算法流程：
     * 1. 8-连通域 BFS 标记，找最大连通域
     * 2. 在最大连通域上找最上最左像素作为起点
     * 3. Moore Neighbor Tracing 追踪外轮廓
     * 4. Douglas-Peucker 简化（epsilon = 周长 × 0.005）
     * 5. 限制点数（默认 60）并归一化到 0-1
     *
     * @param mask 二值 mask（>0 视为前景）
     * @param width mask 宽度
     * @param height mask 高度
     * @param maxPoints 最大轮廓点数（默认 60）
     * @return 归一化轮廓 [(x, y), ...]，无轮廓时返回 null
     */
    fun maskToPolygon(
        mask: ByteArray,
        width: Int,
        height: Int,
        maxPoints: Int = 60,
    ): List<Pair<Float, Float>>? {
        if (mask.isEmpty() || width <= 0 || height <= 0) return null

        // 1. 8-连通域 BFS 标记，找最大连通域
        val labels = IntArray(width * height)
        var nextLabel = 1
        var maxLabel = 0
        var maxSize = 0

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (mask[idx] <= 0 || labels[idx] != 0) continue
                // BFS 标记新连通域
                val queue = ArrayDeque<Int>()
                queue.addLast(idx)
                labels[idx] = nextLabel
                var size = 1
                while (queue.isNotEmpty()) {
                    val cur = queue.removeFirst()
                    val cx = cur % width
                    val cy = cur / width
                    for (ny in maxOf(0, cy - 1)..minOf(height - 1, cy + 1)) {
                        for (nx in maxOf(0, cx - 1)..minOf(width - 1, cx + 1)) {
                            if (nx == cx && ny == cy) continue
                            val nIdx = ny * width + nx
                            if (mask[nIdx] > 0 && labels[nIdx] == 0) {
                                labels[nIdx] = nextLabel
                                queue.addLast(nIdx)
                                size++
                            }
                        }
                    }
                }
                if (size > maxSize) {
                    maxSize = size
                    maxLabel = nextLabel
                }
                nextLabel++
            }
        }

        if (maxLabel == 0) return null

        // 2. 找最大连通域的最上最左像素作为轮廓起点
        var startX = -1
        var startY = -1
        outer@ for (y in 0 until height) {
            for (x in 0 until width) {
                if (labels[y * width + x] == maxLabel) {
                    startX = x
                    startY = y
                    break@outer
                }
            }
        }
        if (startX < 0) return null

        // 3. Moore Neighbor Tracing 追踪外轮廓
        val contour = mooreNeighborTrace(labels, maxLabel, width, height, startX, startY)
        if (contour.size < 3) return null

        // 4. 计算周长 & Douglas-Peucker 简化（epsilon = 周长 × 0.005）
        val perimeter = computePerimeter(contour)
        val epsilon = perimeter * 0.005f
        val simplified = if (epsilon > 0f) {
            douglasPeucker(contour, epsilon)
        } else {
            contour
        }
        if (simplified.size < 3) return null

        // 5. 限制点数
        var result = simplified
        if (result.size > maxPoints) {
            val step = (result.size + maxPoints - 1) / maxPoints
            result = result.filterIndexed { i, _ -> i % step == 0 }
            if (result.size > maxPoints) result = result.take(maxPoints)
        }

        // 6. 归一化到 0-1
        val w = width.toFloat()
        val h = height.toFloat()
        return result.map { (x, y) -> (x / w) to (y / h) }
    }

    /**
     * Moore Neighbor Tracing 轮廓追踪。
     *
     * 追踪指定连通域 [targetLabel] 的外轮廓。算法：
     * - 从起点开始，顺时针搜索 8 邻域找下一个前景像素
     * - 找到后更新搜索方向为回溯方向 (nd + 6) % 8
     * - 回到起点或访问已访问像素时终止
     *
     * @param labels 连通域标记数组
     * @param targetLabel 目标连通域标签
     * @param width mask 宽度
     * @param height mask 高度
     * @param startX 起始 x 坐标
     * @param startY 起始 y 坐标
     * @return 轮廓点列表 [(x, y), ...]，像素坐标
     */
    private fun mooreNeighborTrace(
        labels: IntArray,
        targetLabel: Int,
        width: Int,
        height: Int,
        startX: Int,
        startY: Int,
    ): List<Pair<Float, Float>> {
        val contour = mutableListOf<Pair<Float, Float>>()
        val visited = BooleanArray(width * height)

        var cx = startX
        var cy = startY
        var dir = 0 // 初始搜索方向（East）
        val maxSteps = width * height * 4 // 防止无限循环

        var step = 0
        while (step < maxSteps) {
            val curIdx = cy * width + cx
            // 回到已访问像素则终止（Jacob's stopping criterion）
            if (step > 0 && visited[curIdx]) break
            if (labels[curIdx] == targetLabel) {
                visited[curIdx] = true
                contour.add(cx.toFloat() to cy.toFloat())
            }

            // 顺时针搜索下一个边界像素
            var found = false
            for (i in 0 until 8) {
                val nd = (dir + i) % 8
                val nx = cx + DX[nd]
                val ny = cy + DY[nd]
                if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue
                if (labels[ny * width + nx] == targetLabel) {
                    cx = nx
                    cy = ny
                    dir = (nd + 6) % 8 // 回溯一个方向
                    found = true
                    break
                }
            }
            if (!found) break
            if (cx == startX && cy == startY && contour.size > 2) break
            step++
        }
        return contour
    }

    /**
     * 计算轮廓周长（相邻点欧氏距离累加，不闭合）。
     */
    private fun computePerimeter(contour: List<Pair<Float, Float>>): Float {
        if (contour.size < 2) return 0f
        var perimeter = 0f
        for (i in 1 until contour.size) {
            val (x1, y1) = contour[i - 1]
            val (x2, y2) = contour[i]
            perimeter += sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
        }
        return perimeter
    }

    /**
     * Douglas-Peucker 轮廓简化算法（迭代实现，避免栈溢出）。
     *
     * @param points 待简化的点序列
     * @param epsilon 简化阈值（点到首尾连线距离大于此值则保留）
     * @return 简化后的点序列
     */
    fun douglasPeucker(
        points: List<Pair<Float, Float>>,
        epsilon: Float,
    ): List<Pair<Float, Float>> {
        if (points.size < 3) return points

        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true

        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)

        while (stack.isNotEmpty()) {
            val (start, end) = stack.removeLast()
            if (end - start < 2) continue

            var maxDist = 0f
            var maxIdx = -1
            val (sx, sy) = points[start]
            val (ex, ey) = points[end]
            for (i in start + 1 until end) {
                val d = perpendicularDistance(points[i], sx, sy, ex, ey)
                if (d > maxDist) {
                    maxDist = d
                    maxIdx = i
                }
            }
            if (maxIdx >= 0 && maxDist > epsilon) {
                keep[maxIdx] = true
                stack.addLast(start to maxIdx)
                stack.addLast(maxIdx to end)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /**
     * 点到线段（首尾连线）的垂直距离。
     */
    private fun perpendicularDistance(
        point: Pair<Float, Float>,
        startX: Float, startY: Float,
        endX: Float, endY: Float,
    ): Float {
        val dx = endX - startX
        val dy = endY - startY
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0f) {
            val ddx = point.first - startX
            val ddy = point.second - startY
            return sqrt(ddx * ddx + ddy * ddy)
        }
        return abs(dy * point.first - dx * point.second + endX * startY - endY * startX) / sqrt(lenSq)
    }

    /**
     * 双线性插值上采样 mask 并按 0 阈值二值化。
     *
     * 用于将 SAM 输出的低分辨率 mask 上采样到原图尺寸。
     * 采用 corner-aligned 双线性插值（与 PyTorch align_corners=False 一致），
     * 插值后值 > 0 判为前景（1），否则为背景（0）。
     *
     * @param lowResMask 低分辨率 mask（>0 视为前景；字节按有符号解释）
     * @param lowW 低分辨率宽度
     * @param lowH 低分辨率高度
     * @param targetW 目标宽度
     * @param targetH 目标高度
     * @return 上采样后的二值 mask（前景=1，背景=0），长度 = targetW × targetH
     */
    fun upsampleMask(
        lowResMask: ByteArray,
        lowW: Int,
        lowH: Int,
        targetW: Int,
        targetH: Int,
    ): ByteArray {
        if (lowResMask.isEmpty() || lowW <= 0 || lowH <= 0 || targetW <= 0 || targetH <= 0) {
            return ByteArray(0)
        }
        val out = ByteArray(targetW * targetH)
        val xRatio = lowW.toFloat() / targetW
        val yRatio = lowH.toFloat() / targetH

        for (ty in 0 until targetH) {
            val sy = (ty + 0.5f) * yRatio - 0.5f
            val y0 = floor(sy).toInt().coerceIn(0, lowH - 1)
            val y1 = (y0 + 1).coerceIn(0, lowH - 1)
            val fy = (sy - y0).coerceIn(0f, 1f)

            for (tx in 0 until targetW) {
                val sx = (tx + 0.5f) * xRatio - 0.5f
                val x0 = floor(sx).toInt().coerceIn(0, lowW - 1)
                val x1 = (x0 + 1).coerceIn(0, lowW - 1)
                val fx = (sx - x0).coerceIn(0f, 1f)

                // 字节按有符号解释（与 SAM logits 语义一致：>0 为前景）
                val v00 = lowResMask[y0 * lowW + x0].toFloat()
                val v01 = lowResMask[y0 * lowW + x1].toFloat()
                val v10 = lowResMask[y1 * lowW + x0].toFloat()
                val v11 = lowResMask[y1 * lowW + x1].toFloat()

                val top = v00 + (v01 - v00) * fx
                val bottom = v10 + (v11 - v10) * fx
                val v = top + (bottom - top) * fy

                // 按 0 阈值二值化
                out[ty * targetW + tx] = if (v > 0f) 1 else 0
            }
        }
        return out
    }
}
