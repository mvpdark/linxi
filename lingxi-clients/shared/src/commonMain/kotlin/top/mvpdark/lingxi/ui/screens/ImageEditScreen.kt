package top.mvpdark.lingxi.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.koin.compose.viewmodel.koinViewModel
import top.mvpdark.lingxi.data.model.DetectedObject
import top.mvpdark.lingxi.ui.imageedit.ImageEditUiState
import top.mvpdark.lingxi.ui.imageedit.ImageEditViewModel
import top.mvpdark.lingxi.ui.imageedit.rememberImagePickerLauncher

// 对齐 image-edit.js 的 Konva 颜色常量
private val AnnotationRed = Color(0xFFFA5151)
private val AnnotationBlue = Color(0xFF2563EB)

/**
 * 图像编辑页面。
 *
 * 状态机对齐 image-edit.js 的 step：
 * - Upload：显示选择图片按钮
 * - Analyzing：显示加载动画 "AI 正在分析图片..."
 * - Segmenting：显示加载动画 + 进度条 "AI 正在提取精确轮廓..."
 * - Edit：Coil 显示原图 + Canvas 绘制标注 + 点击选中 + 输入框 + 改图按钮
 * - Generating：显示加载动画 "AI 正在生成..."
 * - Result：显示结果图 + 继续编辑 / 重新编辑 / 重新开始按钮
 *
 * 文件选择器通过 [rememberImagePickerLauncher] 由各平台实现，选好图片后调用
 * [ImageEditViewModel.onPickImage] 传入字节流。
 *
 * @param viewModel 图像编辑 ViewModel（Koin 注入）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageEditScreen(
    viewModel: ImageEditViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val launchPicker = rememberImagePickerLauncher { bytes ->
        if (bytes != null) viewModel.onPickImage(bytes)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "改图",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    if (state.step != ImageEditViewModel.Step.Upload) {
                        IconButton(onClick = viewModel::resetAll) {
                            Icon(Icons.Default.Refresh, contentDescription = "换一张图")
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when (state.step) {
                ImageEditViewModel.Step.Upload -> UploadContent(
                    error = state.error,
                    onPickImage = launchPicker,
                )

                ImageEditViewModel.Step.Analyzing -> LoadingContent(
                    message = "AI 正在分析图片...",
                )

                ImageEditViewModel.Step.Segmenting -> SegmentingContent(
                    progress = state.samProgress,
                )

                ImageEditViewModel.Step.Edit -> EditContent(
                    state = state,
                    viewModel = viewModel,
                )

                ImageEditViewModel.Step.Generating -> LoadingContent(
                    message = "AI 正在生成修改后的图片...",
                )

                ImageEditViewModel.Step.Result -> ResultContent(
                    state = state,
                    viewModel = viewModel,
                )
            }
        }
    }
}

// ============================================================
// Upload 步骤
// ============================================================

/**
 * 上传步骤：显示选择图片按钮 + 提示文案。
 */
@Composable
private fun UploadContent(
    error: String?,
    onPickImage: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(24.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.AddAPhoto,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "点击上传图片",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "支持 JPG、PNG，自动压缩到 1024px",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onPickImage,
            shape = RoundedCornerShape(24.dp),
        ) {
            Icon(
                imageVector = Icons.Default.AddAPhoto,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("选择图片")
        }
        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ============================================================
// Loading 步骤（Analyzing / Generating）
// ============================================================

/**
 * 通用加载步骤：圆形进度指示器 + 提示文案。
 */
@Composable
private fun LoadingContent(
    message: String,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            strokeWidth = 4.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ============================================================
// Segmenting 步骤
// ============================================================

/**
 * SAM 分割步骤：圆形进度指示器 + 进度条 + 提示文案。
 */
@Composable
private fun SegmentingContent(
    progress: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            strokeWidth = 4.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "AI 正在提取精确轮廓...",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { (progress / 100f).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "$progress%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ============================================================
// Edit 步骤（核心交互）
// ============================================================

/**
 * 编辑步骤：原图 + Canvas 标注 + 物体列表 + 输入框。
 *
 * - 用 Coil AsyncImage 显示原图（ContentScale.FillWidth）
 * - Canvas 覆盖在图片上绘制标注（多边形 / bbox）+ 编号圆圈
 * - 点击 Canvas 上的物体可选中/取消选中（多边形用射线法命中检测）
 * - 图片下方显示物体标签列表（可点击选中）
 * - 底部输入栏：有选中显示区域描述输入框，无选中显示全局描述输入框 + 改图按钮
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditContent(
    state: ImageEditUiState,
    viewModel: ImageEditViewModel,
) {
    val hasSelected = state.objects.any { it.selected }
    val selectedCount = state.objects.count { it.selected }

    Column(modifier = Modifier.fillMaxSize()) {
        // 可滚动的内容区：图片 + 标注 + 物体列表 + 错误提示
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            // 原图 + Canvas 标注
            if (state.imageDisplayUrl != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    AsyncImage(
                        model = state.imageDisplayUrl,
                        contentDescription = "原图",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    // Canvas 覆盖在图片上，绘制标注并处理点击
                    val objects = state.objects
                    // 跟踪 Canvas 实际尺寸（AsyncImage 加载完成后会变化）
                    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
                    Canvas(
                        modifier = Modifier
                            .matchParentSize()
                            .onSizeChanged { canvasSize = it }
                            .pointerInput(objects, canvasSize) {
                                val canvasW = canvasSize.width.toFloat()
                                val canvasH = canvasSize.height.toFloat()
                                detectTapGestures { offset ->
                                    if (canvasW <= 0f || canvasH <= 0f) return@detectTapGestures
                                    val nx = offset.x / canvasW
                                    val ny = offset.y / canvasH
                                    // 遍历物体，判断点击位置是否在某个物体内
                                    val hit = objects.firstOrNull { obj ->
                                        if (obj.polygon != null && obj.polygon.size >= 3) {
                                            pointInPolygon(nx, ny, obj.polygon)
                                        } else {
                                            val b = obj.bbox
                                            nx >= b.x && nx <= b.x + b.w &&
                                                ny >= b.y && ny <= b.y + b.h
                                        }
                                    }
                                    if (hit != null) {
                                        viewModel.toggleSelect(hit.id)
                                    }
                                }
                            },
                    ) {
                        drawAnnotations(objects)
                    }
                }
            }

            // 物体标签列表
            if (state.objects.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.objects.forEach { obj ->
                        ObjectTag(
                            obj = obj,
                            onClick = { viewModel.toggleSelect(obj.id) },
                        )
                    }
                }
            } else {
                Text(
                    text = "未检测到物品，可直接输入描述进行改图",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // 已选中数量提示
            if (selectedCount > 0) {
                val labels = state.objects
                    .filter { it.selected }
                    .sortedBy { it.id }
                    .joinToString("、") { "${it.id}号 ${it.label}" }
                Text(
                    text = "已选中 $selectedCount 个区域：$labels",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // 错误信息
            if (state.error != null) {
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.clearError() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        // 底部输入栏
        if (hasSelected) {
            // 选中物体时：区域描述输入框 + 发送按钮
            EditInputBar(
                text = state.selectedPrompt,
                onTextChange = { viewModel.onPromptChange(it, selected = true) },
                onSend = viewModel::startEdit,
                isSending = state.isEditing,
                placeholder = if (selectedCount > 1) {
                    "描述修改，如：台面换成白色，水龙头换成金色"
                } else {
                    "描述对这个物品的修改..."
                },
            )
        } else {
            // 未选中时：全局描述输入框 + 开始改图按钮
            EditGlobalInputBar(
                text = state.promptText,
                onTextChange = { viewModel.onPromptChange(it, selected = false) },
                onSend = viewModel::startEdit,
                isSending = state.isEditing,
            )
        }
    }
}

/**
 * 物体标签：编号 + 名称，选中时高亮。
 */
@Composable
private fun ObjectTag(
    obj: DetectedObject,
    onClick: () -> Unit,
) {
    val bgColor = if (obj.selected) AnnotationBlue else AnnotationRed
    Card(
        modifier = Modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = obj.id.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = obj.label,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
        }
    }
}

/**
 * 选中物体时的底部输入栏：输入框 + 发送按钮。
 */
@Composable
private fun EditInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean,
    placeholder: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp),
            placeholder = { Text(placeholder) },
            maxLines = 3,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            trailingIcon = {
                IconButton(
                    onClick = onSend,
                    enabled = !isSending && text.isNotBlank(),
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
        )
    }
}

/**
 * 未选中物体时的底部输入栏：输入框 + 开始改图按钮。
 */
@Composable
private fun EditGlobalInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            placeholder = { Text("描述你想要的修改...") },
            maxLines = 4,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onSend,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSending && text.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            if (isSending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text("生成中...")
            } else {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("开始改图")
            }
        }
    }
}

// ============================================================
// Result 步骤
// ============================================================

/**
 * 结果步骤：显示结果图 + 操作按钮。
 */
@Composable
private fun ResultContent(
    state: ImageEditUiState,
    viewModel: ImageEditViewModel,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.resultUrl != null) {
            AsyncImage(
                model = state.resultUrl,
                contentDescription = "生成结果",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)),
            )
        }

        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = viewModel::resetEdit,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("返回编辑")
            }
            Button(
                onClick = viewModel::continueEdit,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("重新开始")
            }
        }
    }
}

// ============================================================
// Canvas 绘制 & 工具函数
// ============================================================

/**
 * 在 Canvas 上绘制所有物体的标注。
 *
 * - 有 polygon：用 Path 画闭合多边形轮廓
 * - 无 polygon：用 drawRect 画矩形框（回退模式）
 * - 选中：蓝色 + 加粗；未选中：红色
 * - 编号圆圈：左上角，半径 14px
 */
private fun DrawScope.drawAnnotations(
    objects: List<DetectedObject>,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    for (obj in objects) {
        val color = if (obj.selected) AnnotationBlue else AnnotationRed
        val strokeWidth = if (obj.selected) 3f else 2f

        // bbox 左上角像素坐标（用于编号圆圈定位）
        val px = obj.bbox.x * w
        val py = obj.bbox.y * h

        // 轮廓：多边形 或 矩形框
        if (obj.polygon != null && obj.polygon.size >= 3) {
            val path = Path().apply {
                obj.polygon.forEachIndexed { idx, point ->
                    // 顶点长度校验：跳过无效顶点，防止 IndexOutOfBoundsException
                    if (point.size < 2) return@forEachIndexed
                    val pointX = point[0] * w
                    val pointY = point[1] * h
                    if (idx == 0) moveTo(pointX, pointY) else lineTo(pointX, pointY)
                }
                close()
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = strokeWidth),
            )
        } else {
            val rectW = obj.bbox.w * w
            val rectH = obj.bbox.h * h
            drawRect(
                color = color,
                topLeft = Offset(px, py),
                size = Size(rectW, rectH),
                style = Stroke(width = strokeWidth),
            )
        }

        // 编号圆圈（半径 14px，对齐 image-edit.js）
        drawCircle(
            color = color,
            radius = 14f,
            center = Offset(px, py),
        )
        // 白色边框
        drawCircle(
            color = Color.White,
            radius = 14f,
            center = Offset(px, py),
            style = Stroke(width = 1.5f),
        )
    }
}

/**
 * 射线法判断点 (x, y) 是否在多边形内部。
 *
 * 对齐 image-edit.js 的 pointInPolygon 实现。
 * polygon 为归一化坐标 [[x, y], ...]，0-1。
 *
 * @param x 点 X（归一化 0-1）
 * @param y 点 Y（归一化 0-1）
 * @param polygon 多边形顶点列表 [[x, y], ...]
 * @return true 表示点在多边形内部
 */
private fun pointInPolygon(
    x: Float,
    y: Float,
    polygon: List<List<Float>>,
): Boolean {
    // 顶点长度校验：过滤掉长度不足 2 的无效顶点，防止 IndexOutOfBoundsException
    val safePolygon = polygon.filter { it.size >= 2 }
    if (safePolygon.size < 3) return false
    var inside = false
    val n = safePolygon.size
    for (i in 0 until n) {
        val j = (i + n - 1) % n
        val xi = safePolygon[i][0]
        val yi = safePolygon[i][1]
        val xj = safePolygon[j][0]
        val yj = safePolygon[j][1]
        val intersect = (yi > y) != (yj > y) &&
            x < (xj - xi) * (y - yi) / (yj - yi) + xi
        if (intersect) inside = !inside
    }
    return inside
}
