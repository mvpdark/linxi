/**
 * 灵犀 — SAM 2 前端本地分割模块（ESM）
 *
 * 使用 transformers.js + EdgeTAM-ONNX 在浏览器/WebView 本地运行分割模型，
 * 无需后端服务器资源。模型首次加载后自动缓存到 IndexedDB。
 *
 * 模型：onnx-community/EdgeTAM-ONNX（Meta EdgeTAM，SAM 2 移动端优化版）
 * 推理后端：WebGPU（优先）→ WASM（回退）
 *
 * 用法（动态 import）：
 *   const { segmentImage, preloadModel } = await import('./js/sam-client.js');
 *   const result = await segmentImage(imageFile, [
 *     { id: 1, bbox: { x: 0.1, y: 0.2, w: 0.3, h: 0.4 } }
 *   ]);
 *   // result = { success: true, objects: [{ id: 1, polygon: [[x,y],...], mask_png_b64: "..." }] }
 */

// ============================================================
// 模型配置
// ============================================================
const MODEL_ID = 'onnx-community/EdgeTAM-ONNX';

// transformers.js CDN（ESM 格式）
// 使用 jsdelivr 的 ESM 端点，自动解析最新 v3 版本
const TRANSFORMERS_URL =
  'https://cdn.jsdelivr.net/npm/@huggingface/transformers@3.7.1/dist/transformers.min.js';

// ============================================================
// 模块状态
// ============================================================
let _model = null;
let _processor = null;
let _loadingPromise = null;
let _backend = null; // 'webgpu' | 'wasm'

// ============================================================
// 辅助函数
// ============================================================

/**
 * 检测 WebGPU 是否可用
 */
function detectWebGPU() {
  return typeof navigator !== 'undefined' && !!navigator.gpu;
}

/**
 * 将 File/Blob 转换为 RawImage（transformers.js 格式）
 */
async function fileToRawImage(file) {
  const { RawImage } = await getTransformers();
  // RawImage.read 支持传入 URL 或 Blob
  if (file instanceof Blob) {
    const url = URL.createObjectURL(file);
    try {
      return await RawImage.read(url);
    } finally {
      URL.revokeObjectURL(url);
    }
  }
  // 如果已经是 URL 或 path
  return await RawImage.read(file);
}

/**
 * 动态导入 transformers.js（带缓存）
 */
let _transformersCache = null;
async function getTransformers() {
  if (_transformersCache) return _transformersCache;
  _transformersCache = await import(/* @vite-ignore */ TRANSFORMERS_URL);
  return _transformersCache;
}

// ============================================================
// 模型加载
// ============================================================

/**
 * 预加载模型（可在页面加载时提前调用）
 * @param {function} onProgress - 进度回调 (progress: 0-100, file: string)
 * @returns {Promise<{model, processor, backend}>}
 */
export async function preloadModel(onProgress) {
  if (_model && _processor) {
    return { model: _model, processor: _processor, backend: _backend };
  }
  if (_loadingPromise) return _loadingPromise;

  _loadingPromise = (async () => {
    try {
      const transformers = await getTransformers();
      const { EdgeTamModel, AutoProcessor, env } = transformers;

      // 配置运行环境
      // 优先使用本地打包的模型（APK 内 assets/public/models/）
      // Web 环境回退到 HuggingFace CDN
      env.allowLocalModels = true;
      env.allowRemoteModels = true; // 允许回退到 CDN（Web 环境）
      env.localModelPath = '/models/'; // Capacitor: assets/public/models/ → /models/
      env.useBrowserCache = true; // 使用 IndexedDB 缓存模型（CDN 回退时）

      // 检测推理后端
      _backend = detectWebGPU() ? 'webgpu' : 'wasm';
      console.log(`[SAM] 使用推理后端: ${_backend}`);

      // 进度回调
      const progressCallback = (data) => {
        if (onProgress && data.status === 'progress') {
          const pct = Math.round(data.progress || 0);
          const file = data.file || data.name || '';
          onProgress(pct, file);
        }
        if (data.status === 'ready') {
          console.log(`[SAM] 模型文件已就绪: ${data.file || ''}`);
        }
      };

      // 加载模型
      console.log(`[SAM] 加载模型: ${MODEL_ID}`);
      _model = await EdgeTamModel.from_pretrained(MODEL_ID, {
        dtype: 'q8', // 8 位量化，平衡大小和精度
        device: _backend,
        progress_callback: progressCallback,
      });

      // 加载预处理器
      _processor = await AutoProcessor.from_pretrained(MODEL_ID);

      console.log('[SAM] 模型加载完成 ✓');
      return { model: _model, processor: _processor, backend: _backend };
    } catch (e) {
      // 加载失败时清除状态，允许下次重试
      _model = null;
      _processor = null;
      _loadingPromise = null;
      console.error('[SAM] 模型加载失败:', e);
      throw e;
    }
  })();

  return _loadingPromise;
}

/**
 * 获取已加载的模型（如未加载则自动加载）
 */
async function getModel(onProgress) {
  return preloadModel(onProgress);
}

// ============================================================
// Mask 后处理
// ============================================================

/**
 * 将 mask 二值数组转换为多边形轮廓（Marching Squares 算法）
 *
 * @param {Uint8Array|Int32Array} mask - 二值 mask 数据（0 或 1）
 * @param {number} width - mask 宽度
 * @param {number} height - mask 高度
 * @param {number} maxPoints - 最大轮廓点数（默认 60）
 * @returns {Array<[number, number]>|null} 归一化轮廓点 [[x, y], ...]，或 null
 */
function maskToPolygon(mask, width, height, maxPoints = 60) {
  if (!mask || width <= 0 || height <= 0) return null;

  // Marching Squares 边缘追踪
  // 找到第一个非零像素作为起点
  let startX = -1,
    startY = -1;
  for (let y = 0; y < height && startX < 0; y++) {
    for (let x = 0; x < width; x++) {
      if (mask[y * width + x] > 0) {
        startX = x;
        startY = y;
        break;
      }
    }
  }
  if (startX < 0) return null;

  // Moore Neighbor Tracing 简化版
  const contour = [];
  const visited = new Set();
  // 8 邻域方向（顺时针）
  const dx = [1, 1, 0, -1, -1, -1, 0, 1];
  const dy = [0, 1, 1, 1, 0, -1, -1, -1];

  let cx = startX,
    cy = startY;
  let dir = 0; // 初始方向
  const maxSteps = width * height * 4; // 防止无限循环

  for (let step = 0; step < maxSteps; step++) {
    const key = `${cx},${cy}`;
    if (visited.has(key) && step > 0) break;
    if (mask[cy * width + cx] > 0) {
      visited.add(key);
      contour.push([cx, cy]);
    }

    // 寻找下一个边界像素
    let found = false;
    for (let i = 0; i < 8; i++) {
      const nd = (dir + i) % 8;
      const nx = cx + dx[nd];
      const ny = cy + dy[nd];
      if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
      if (mask[ny * width + nx] > 0) {
        cx = nx;
        cy = ny;
        dir = (nd + 6) % 8; // 回溯一个方向
        found = true;
        break;
      }
    }
    if (!found) break;
    if (cx === startX && cy === startY && contour.length > 2) break;
  }

  if (contour.length < 3) return null;

  // Douglas-Peucker 简化
  const simplified = douglasPeucker(contour, 2.0);

  // 限制点数
  let result = simplified;
  if (result.length > maxPoints) {
    const step = Math.ceil(result.length / maxPoints);
    result = result.filter((_, i) => i % step === 0);
    if (result.length > maxPoints) result = result.slice(0, maxPoints);
  }

  // 归一化到 0-1
  return result.map(([x, y]) => [x / width, y / height]);
}

/**
 * Douglas-Peucker 轮廓简化算法
 */
function douglasPeucker(points, epsilon) {
  if (points.length < 3) return points;

  let maxDist = 0;
  let maxIdx = 0;
  const first = points[0];
  const last = points[points.length - 1];

  for (let i = 1; i < points.length - 1; i++) {
    const dist = perpendicularDistance(points[i], first, last);
    if (dist > maxDist) {
      maxDist = dist;
      maxIdx = i;
    }
  }

  if (maxDist > epsilon) {
    const left = douglasPeucker(points.slice(0, maxIdx + 1), epsilon);
    const right = douglasPeucker(points.slice(maxIdx), epsilon);
    return [...left.slice(0, -1), ...right];
  } else {
    return [first, last];
  }
}

/**
 * 点到线段的垂直距离
 */
function perpendicularDistance(point, lineStart, lineEnd) {
  const dx = lineEnd[0] - lineStart[0];
  const dy = lineEnd[1] - lineStart[1];
  const len = Math.sqrt(dx * dx + dy * dy);
  if (len === 0) {
    const ddx = point[0] - lineStart[0];
    const ddy = point[1] - lineStart[1];
    return Math.sqrt(ddx * ddx + ddy * ddy);
  }
  return Math.abs(dy * point[0] - dx * point[1] + lineEnd[0] * lineStart[1] - lineEnd[1] * lineStart[0]) / len;
}

/**
 * 将 mask 二值数组编码为 PNG base64
 *
 * @param {Uint8Array|Int32Array} mask - 二值 mask（0 或 1）
 * @param {number} width
 * @param {number} height
 * @returns {string} base64 编码的 PNG 图片
 */
function maskToPngB64(mask, width, height) {
  // 使用 Canvas 编码 PNG
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  const imageData = ctx.createImageData(width, height);

  for (let i = 0; i < mask.length; i++) {
    const v = mask[i] > 0 ? 255 : 0;
    imageData.data[i * 4] = v; // R
    imageData.data[i * 4 + 1] = v; // G
    imageData.data[i * 4 + 2] = v; // B
    imageData.data[i * 4 + 3] = mask[i] > 0 ? 255 : 0; // Alpha
  }

  ctx.putImageData(imageData, 0, 0);
  return canvas.toDataURL('image/png').split(',')[1];
}

// ============================================================
// 核心分割 API
// ============================================================

/**
 * 对图片进行本地分割，提取每个物体的精确轮廓
 *
 * @param {File|Blob} file - 图片文件
 * @param {Array} objects - 物体列表 [{ id, bbox: {x, y, w, h} }]
 *   bbox 为归一化坐标 (0-1)
 * @param {function} [onProgress] - 模型加载进度回调
 * @returns {Promise<{success: boolean, objects: Array}>}
 *   objects[i] = { id, polygon: [[x,y], ...] | null, mask_png_b64: string | null }
 */
export async function segmentImage(file, objects, onProgress) {
  if (!objects || objects.length === 0) {
    return { success: false, objects: [], error: '无物体需要分割' };
  }

  // 加载模型
  const { model, processor } = await getModel(onProgress);
  const { RawImage } = await getTransformers();

  // 读取图片
  const rawImage = await fileToRawImage(file);
  const imgWidth = rawImage.width;
  const imgHeight = rawImage.height;
  console.log(`[SAM] 图片尺寸: ${imgWidth}x${imgHeight}`);

  // 准备所有 bbox 的 box prompt
  // EdgeTAM/SAM 2 的 input_boxes 格式: [[[[x1,y1,x2,y2], ...]]]
  // 坐标为像素坐标（非归一化）
  const boxes = objects.map((obj) => {
    const x1 = Math.round(obj.bbox.x * imgWidth);
    const y1 = Math.round(obj.bbox.y * imgHeight);
    const x2 = Math.round((obj.bbox.x + obj.bbox.w) * imgWidth);
    const y2 = Math.round((obj.bbox.y + obj.bbox.h) * imgHeight);
    return [x1, y1, x2, y2];
  });

  // 处理输入
  const input_boxes = [boxes]; // 外层 batch 维度
  const inputs = await processor(rawImage, { input_boxes });

  // 推理
  console.log('[SAM] 开始推理...');
  const outputs = await model(inputs);

  // 后处理：获取 masks
  const masks = await processor.post_process_masks(
    outputs.pred_masks,
    inputs.original_sizes,
    inputs.reshaped_input_sizes
  );

  console.log('[SAM] 推理完成，处理 masks...');

  // 转换每个物体的 mask 为 polygon 和 png_b64
  const results = [];
  for (let i = 0; i < objects.length; i++) {
    const obj = objects[i];
    let maskData = null;
    let maskWidth = 0;
    let maskHeight = 0;

    // masks 可能是 Tensor 或 TypedArray
    let mask = masks[i];
    if (!mask) {
      // 尝试其他索引方式
      mask = Array.isArray(masks) ? masks[0]?.[i] : null;
    }

    // 处理不同的 mask 格式
    if (mask && typeof mask.data !== 'undefined') {
      // ONNX Tensor 格式
      maskData = mask.data;
      maskWidth = mask.dims ? mask.dims[mask.dims.length - 1] : imgWidth;
      maskHeight = mask.dims ? mask.dims[mask.dims.length - 2] : imgHeight;
    } else if (mask && mask.length) {
      // 可能是嵌套数组
      maskData = mask;
      maskWidth = imgWidth;
      maskHeight = imgHeight;
    }

    if (maskData) {
      // 确保 maskData 是扁平数组
      let flatMask = maskData;
      if (Array.isArray(maskData) && Array.isArray(maskData[0])) {
        // 嵌套数组，展平
        flatMask = maskData.flat();
      }

      const polygon = maskToPolygon(flatMask, maskWidth, maskHeight);
      const maskPngB64 = maskToPngB64(flatMask, maskWidth, maskHeight);

      results.push({
        id: obj.id,
        polygon: polygon,
        mask_png_b64: maskPngB64,
      });
    } else {
      // mask 获取失败
      results.push({
        id: obj.id,
        polygon: null,
        mask_png_b64: null,
      });
    }
  }

  return { success: true, objects: results };
}

/**
 * 获取模型加载状态
 */
export function getModelStatus() {
  return {
    loaded: _model !== null && _processor !== null,
    loading: _loadingPromise !== null && _model === null,
    backend: _backend,
  };
}
