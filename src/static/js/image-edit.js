/**
 * 灵犀 — 改图模块 Vue 3 组件
 * 使用 Vue 3 CDN 全局构建 + Konva.js (Canvas 交互)。
 * 组件挂载到 window.ImageEditView，供 app.js 注册。
 *
 * 功能流程：
 *   上传图片 → VLM 自动检测物品 → SAM 2 前端本地分割 → 轮廓交互(点击选中) → 输入修改描述 → AI 改图 → 结果展示
 *
 * 依赖：
 *   - Vue 3 全局变量 (CDN)
 *   - Konva 全局变量 (CDN)
 *   - sam-client.js (ESM，动态 import) — EdgeTAM 前端本地分割
 *   - 后端 API: /api/upload, /api/vlm-detect, /api/image-edit, /api/image-edit-annotated
 */
var { ref, reactive, computed, onMounted, onUnmounted, nextTick, watch } = Vue;

// ============================================================
// Konva 颜色常量（Canvas 绘制不能使用 CSS 变量，用常量统一管理）
// ============================================================
const KONVA_COLOR_RED = '#FA5151';
const KONVA_COLOR_BLUE = '#2563EB';
const KONVA_COLOR_RED_FILL = 'rgba(250, 81, 81, 0.8)';
const KONVA_COLOR_BLUE_FILL = 'rgba(37, 99, 235, 0.8)';

window.ImageEditView = {
  emits: ['back', 'navigate'],
  props: {
    isDesktop: Boolean,
  },
  setup(props, { emit }) {
    // ============================================================
    // 响应式状态
    // ============================================================

    /**
     * 当前步骤:
     * 'upload' | 'analyzing' | 'segmenting' | 'edit' | 'generating' | 'result'
     * - analyzing:  VLM 检测中（"AI正在识别图中的物品..."）
     * - segmenting: SAM 分割中（"AI正在提取精确轮廓..."）
     * - edit:       完成，进入编辑模式
     */
    const step = ref('upload');

    /** 错误信息 */
    const errorMsg = ref('');

    /** 隐藏的 file input 引用 */
    const fileInputRef = ref(null);

    /** Konva canvas 容器引用 */
    const canvasContainerRef = ref(null);

    /** 上传后的图片 URL（用于显示和后续 API 调用） */
    const imageUrl = ref('');

    /** 上传后的图片文件名 */
    const imageName = ref('');

    /** VLM 检测到的物体列表 */
    const objects = ref([]);
    // 每个物体结构:
    // { id, label, bbox: {x, y, w, h}, selected: false,
    //   polygon: [[x,y], ...] | null,  // SAM 2 分割得到的归一化多边形轮廓
    //   mask_png_b64: string | null }  // SAM 2 分割得到的 mask（base64 PNG）

    /** 改图描述输入（无选中物体时的直接改图输入框） */
    const promptText = ref('');

    /** 选中物体后底部输入面板的内容 */
    const selectedPrompt = ref('');

    /** 生成结果图片 URL */
    const resultUrl = ref('');
    const toastMsg = ref('');
    let toastTimer = null;
    let errorTimer = null;

    /** 显示 toast 消息，2.5 秒后自动消失 */
    function showToast(msg) {
      toastMsg.value = msg;
      if (toastTimer) clearTimeout(toastTimer);
      toastTimer = setTimeout(() => {
        toastMsg.value = '';
      }, 2500);
    }

    /** 设置错误信息，3 秒后自动消失 */
    function setError(msg) {
      errorMsg.value = msg;
      if (errorTimer) clearTimeout(errorTimer);
      if (msg) {
        errorTimer = setTimeout(() => {
          errorMsg.value = '';
        }, 3000);
      }
    }

    /** 按钮波纹效果（事件委托场景需显式传入按钮元素） */
    function createRipple(e, btn) {
      btn = btn || e.currentTarget;
      const rect = btn.getBoundingClientRect();
      const size = Math.max(rect.width, rect.height);
      const ripple = document.createElement('span');
      ripple.className = 'ripple-effect';
      ripple.style.width = ripple.style.height = size + 'px';
      ripple.style.left = (e.clientX || rect.left + rect.width / 2) - rect.left - size / 2 + 'px';
      ripple.style.top = (e.clientY || rect.top + rect.height / 2) - rect.top - size / 2 + 'px';
      btn.appendChild(ripple);
      setTimeout(() => ripple.remove(), 600);
    }

    // ============================================================
    // 非响应式变量（Konva 实例 & 原始文件）
    // ============================================================

    /** 原始上传的 File 对象（用于后续 API 调用） */
    let originalFile = null;

    /** 已加载的 HTMLImageElement（用于 Konva.Image 和重新初始化） */
    let loadedImage = null;

    /** Konva.Stage 实例 */
    let stage = null;

    /** 底层 Layer — 放置 Konva.Image */
    let imageLayer = null;

    /** 上层 Layer — 放置标注 Group */
    let annotationLayer = null;

    /** 标注 Group — 包含所有物体的标注图形 */
    let annotationGroup = null;

    /** Stage 的像素宽高 */
    let stageWidth = 0;
    let stageHeight = 0;

    /** Konva.Image 节点引用（用于 resize 时更新底图尺寸） */
    let konvaImageNode = null;

    /** 物体 ID → Konva 图形引用 的映射 */
    const shapeMap = {};

    /** 双指缩放：上一次两指距离 */
    let lastDist = 0;

    /** 双指缩放结束时间戳（用于屏蔽 pinch 后误触发的 click） */
    let pinchEndTime = 0;

    /** pointerdown 事件监听器引用（用于 onUnmounted 清理） */
    let _pointerDownHandler = null;

    /** sessionStorage key */
    const SESSION_KEY = 'ie_edit_state';

    /**
     * 保存当前编辑状态到 sessionStorage，防止页面刷新/切换标签后丢失。
     * 只保存可序列化的数据（不保存 Konva 实例、File 对象等）。
     */
    function saveSession() {
      try {
        const state = {
          imageUrl: imageUrl.value,
          // 瞬态步骤（analyzing/segmenting/generating）刷新后无法恢复，
          // 统一保存为 edit，避免恢复后卡死在加载页
          step: sanitizeStep(step.value),
          // 剥离 mask_png_b64 大字段，避免超出 sessionStorage 容量
          objects: objects.value.map(({ mask_png_b64, ...rest }) => rest),
          promptText: promptText.value,
          selectedPrompt: selectedPrompt.value,
          resultUrl: resultUrl.value,
          savedAt: Date.now(),
        };
        sessionStorage.setItem(SESSION_KEY, JSON.stringify(state));
      } catch (e) {
        // 超过存储限制等，仅警告
        console.warn('保存 session 失败:', e);
      }
    }

    /**
     * 恢复 session 状态。
     * 返回 true 表示恢复成功，false 表示没有有效 session。
     */
    function restoreSession() {
      try {
        const raw = sessionStorage.getItem(SESSION_KEY);
        if (!raw) return false;

        const state = JSON.parse(raw);
        // 只恢复 5 分钟内的 session
        if (!state.savedAt || Date.now() - state.savedAt > 5 * 60 * 1000) {
          sessionStorage.removeItem(SESSION_KEY);
          return false;
        }

        imageUrl.value = state.imageUrl || '';
        step.value = sanitizeStep(state.step || 'upload');
        // 非上传步骤必须有图片，否则回退到上传页
        if (step.value !== 'upload' && !imageUrl.value) {
          step.value = 'upload';
        }
        objects.value = state.objects || [];
        promptText.value = state.promptText || '';
        selectedPrompt.value = state.selectedPrompt || '';
        resultUrl.value = state.resultUrl || '';
        // 不恢复 errorMsg，避免残留过期错误提示

        return true;
      } catch (e) {
        return false;
      }
    }

    /**
     * 清除 session 存储。
     */
    function clearSession() {
      try {
        sessionStorage.removeItem(SESSION_KEY);
      } catch (e) {
        // ignore
      }
    }

    // ============================================================
    // 计算属性
    // ============================================================

    /** 已选中的物体数量 */
    const selectedCount = computed(() =>
      objects.value.filter((o) => o.selected).length
    );

    /** 是否有选中的物体（控制底部输入面板显示） */
    const hasSelected = computed(() => selectedCount.value > 0);

    /** 选中物体的编号标签列表，如 "黑色台面、水龙头" */
    const selectedLabels = computed(() =>
      objects.value
        .filter((o) => o.selected)
        .sort((a, b) => a.id - b.id)
        .map((o) => `${o.id}号 ${o.label}`)
        .join('、')
    );

    // ============================================================
    // 工具函数
    // ============================================================

    /** 进行中的请求 AbortController 集合（组件卸载时统一取消） */
    const pendingControllers = new Set();

    /**
     * 带超时控制的 fetch 封装（基于全局 apiFetch，自动拼接 apiBase + token）。
     * @param {string} url — 请求地址
     * @param {Object} options — fetch 选项
     * @param {number} timeout — 超时毫秒数（默认 90s，生成类接口用 180s）
     */
    function fetchWithTimeout(url, options = {}, timeout = 90000) {
      const ctrl = new AbortController();
      pendingControllers.add(ctrl);
      const timer = setTimeout(() => ctrl.abort(), timeout);
      return apiFetch(url, { ...options, signal: ctrl.signal })
        .finally(() => {
          clearTimeout(timer);
          pendingControllers.delete(ctrl);
        });
    }

    /** 将底层异常转换为对用户友好的错误消息 */
    function friendlyError(err) {
      if (err.name === 'AbortError') return '请求超时，请检查网络后重试';
      if (err.message === 'Failed to fetch') return '网络连接失败，请检查网络';
      return err.message || '操作失败，请重试';
    }

    /** 瞬态步骤（页面刷新后无法安全恢复，统一回退到 edit） */
    const TRANSIENT_STEPS = ['analyzing', 'segmenting', 'generating'];

    /** 清洗步骤状态：瞬态步骤统一映射为 edit */
    function sanitizeStep(s) {
      return TRANSIENT_STEPS.includes(s) ? 'edit' : s;
    }

    /**
     * 加载图片为 HTMLImageElement。
     * @param {string} url — 图片 URL
     * @returns {Promise<HTMLImageElement>}
     */
    function loadImage(url) {
      return new Promise((resolve, reject) => {
        const img = new Image();
        img.onload = () => resolve(img);
        img.onerror = () => reject(new Error('图片加载失败'));
        img.src = url;
      });
    }

    /**
     * 调用 VLM 物体检测 API。
     * @param {File} file — 图片文件
     * @returns {Promise<{objects: Array}>}
     */
    async function detectObjects(file) {
      const formData = new FormData();
      formData.append('file', file);
      const res = await fetchWithTimeout('/api/vlm-detect', {
        method: 'POST',
        body: formData,
      });
      if (!res.ok) {
        const errData = await res.json().catch(() => ({}));
        throw new Error(errData.error || '检测失败');
      }
      return await res.json();
    }

    /**
     * 调用前端本地 SAM 2 分割（EdgeTAM）。
     * 在浏览器/WebView 本地运行，不消耗服务器资源。
     *
     * @param {File} file — 原始图片文件
     * @param {Array} objs — 物体列表（含 id 与 bbox）
     * @returns {Promise<{success: boolean, objects: Array}>}
     *   objects[i] = { id, polygon: [[x,y], ...], mask_png_b64: string }
     */
    async function segmentObjects(file, objs) {
      // 动态 import ESM 模块（首次加载会触发 transformers.js 下载）
      const samModule = await import('./js/sam-client.js');
      const { segmentImage, getModelStatus } = samModule;

      // 检查模型是否已加载，如未加载显示进度
      const status = getModelStatus();
      if (!status.loaded) {
        showToast('正在加载 AI 分割模型（首次约需 1-2 分钟）...');
      }

      const result = await segmentImage(
        file,
        objs.map((o) => ({ id: o.id, bbox: o.bbox })),
        (progress, fileName) => {
          if (progress > 0 && progress < 100) {
            console.log(`[SAM] 模型加载进度: ${progress}% (${fileName})`);
          }
        }
      );

      return result;
    }

    /** 上传文件大小上限（20MB） */
    const MAX_FILE_SIZE = 20 * 1024 * 1024;

    /** 压缩后的最长边像素 */
    const MAX_DIM = 1024;

    /**
     * 上传前压缩图片：最长边超过 MAX_DIM 或文件超过 2MB 时，
     * 用 Canvas 缩放到 1024px 以内并转为 JPEG(0.9)。
     * @param {File} file — 原始图片文件
     * @returns {Promise<File>}
     */
    async function compressImage(file) {
      const url = URL.createObjectURL(file);
      try {
        const img = await loadImage(url);
        const scale = Math.min(
          1,
          MAX_DIM / Math.max(img.naturalWidth, img.naturalHeight)
        );
        if (scale >= 1 && file.size < 2 * 1024 * 1024) return file;
        const canvas = document.createElement('canvas');
        canvas.width = Math.round(img.naturalWidth * scale);
        canvas.height = Math.round(img.naturalHeight * scale);
        canvas.getContext('2d').drawImage(img, 0, 0, canvas.width, canvas.height);
        const blob = await new Promise((r) => canvas.toBlob(r, 'image/jpeg', 0.9));
        return new File([blob], file.name.replace(/\.\w+$/, '.jpg'), {
          type: 'image/jpeg',
        });
      } finally {
        URL.revokeObjectURL(url);
      }
    }

    /**
     * 计算两点之间的欧氏距离。
     */
    function getDistance(x1, y1, x2, y2) {
      return Math.hypot(x2 - x1, y2 - y1);
    }

    /**
     * 射线法判断点 (x, y) 是否在多边形内部。
     * @param {number} x — 点 X
     * @param {number} y — 点 Y
     * @param {Array<Array<number>>} polygon — 多边形顶点 [[x,y], ...]
     * @returns {boolean}
     */
    function pointInPolygon(x, y, polygon) {
      let inside = false;
      const n = polygon.length;
      for (let i = 0, j = n - 1; i < n; j = i++) {
        const xi = polygon[i][0];
        const yi = polygon[i][1];
        const xj = polygon[j][0];
        const yj = polygon[j][1];
        const intersect =
          yi > y !== yj > y &&
          x < ((xj - xi) * (y - yi)) / (yj - yi) + xi;
        if (intersect) inside = !inside;
      }
      return inside;
    }

    /** 销毁 Konva 实例，释放资源 */
    function destroyKonva() {
      if (stage) {
        stage.destroy();
        stage = null;
      }
      imageLayer = null;
      annotationLayer = null;
      annotationGroup = null;
      konvaImageNode = null;
      lastDist = 0;
      Object.keys(shapeMap).forEach((k) => delete shapeMap[k]);
    }

    // ============================================================
    // Konva 初始化 & 绘制
    // ============================================================

    /**
     * 初始化 Konva Stage 和 Layer。
     * @param {HTMLImageElement} img — 已加载的图片元素
     */
    function initKonva(img, _retried) {
      if (typeof Konva === 'undefined') {
        setError('Canvas 库加载失败，请刷新页面重试');
        return;
      }

      // 防止重复初始化导致内存泄漏
      destroyKonva();

      const container = canvasContainerRef.value;
      if (!container) return;

      // 获取容器实际渲染宽度，按图片宽高比计算 Stage 高度
      const containerWidth = container.offsetWidth;
      if (containerWidth <= 0) {
        // 容器尚未布局（如 display:none 刚切换），延迟重试一次
        if (!_retried) {
          setTimeout(() => initKonva(img, true), 150);
        }
        return;
      }

      const imgRatio = img.naturalHeight / img.naturalWidth;
      stageWidth = containerWidth;
      stageHeight = Math.round(containerWidth * imgRatio);

      // 创建 Stage
      stage = new Konva.Stage({
        container: container,
        width: stageWidth,
        height: stageHeight,
      });

      // 底层 Layer — 图片
      imageLayer = new Konva.Layer();

      const konvaImage = new Konva.Image({
        x: 0,
        y: 0,
        image: img,
        width: stageWidth,
        height: stageHeight,
      });
      konvaImageNode = konvaImage;

      imageLayer.add(konvaImage);
      stage.add(imageLayer);

      // 上层 Layer — 标注
      annotationLayer = new Konva.Layer();
      annotationGroup = new Konva.Group();
      annotationLayer.add(annotationGroup);
      stage.add(annotationLayer);

      // 绘制所有检测到的物体标注
      drawObjects();

      // Stage 单击事件 — 选中点击位置所在的物体（多边形内部 / bbox 内部）
      stage.on('click', handleStageClick);

      // ============================================================
      // 双指缩放（pinch zoom）
      // 通过设置 stage.scaleX/scaleY 实现，缩放后所有内容（图片+标注）
      // 整体放大/缩小，drawObjects 中的坐标无需额外乘以 scale。
      // ============================================================
      stage.on('touchstart', (e) => {
        const evt = e.evt;
        if (evt.touches && evt.touches.length === 2) {
          lastDist = getDistance(
            evt.touches[0].clientX,
            evt.touches[0].clientY,
            evt.touches[1].clientX,
            evt.touches[1].clientY
          );
        }
      });

      stage.on('touchmove', (e) => {
        const evt = e.evt;
        if (evt.touches && evt.touches.length === 2) {
          // 阻止页面滚动
          evt.preventDefault();
          const t0 = evt.touches[0];
          const t1 = evt.touches[1];
          const dist = getDistance(t0.clientX, t0.clientY, t1.clientX, t1.clientY);
          if (lastDist > 0) {
            // 限制缩放范围 1x ~ 5x
            const newScale = Math.max(
              1,
              Math.min(5, stage.scaleX() * (dist / lastDist))
            );
            // 以两指中点为缩放锚点，保持中点位置不动
            const center = {
              x: (t0.clientX + t1.clientX) / 2,
              y: (t0.clientY + t1.clientY) / 2,
            };
            const oldScale = stage.scaleX();
            const pointer = {
              x: (center.x - stage.x()) / oldScale,
              y: (center.y - stage.y()) / oldScale,
            };
            stage.scale({ x: newScale, y: newScale });
            stage.position({
              x: center.x - pointer.x * newScale,
              y: center.y - pointer.y * newScale,
            });
            // 缩放回 1x 时复位平移，仅在放大后才允许拖动
            if (newScale <= 1.05) {
              stage.position({ x: 0, y: 0 });
            }
            stage.draggable(newScale > 1.05);
            stage.batchDraw();
          }
          lastDist = dist;
        }
      });

      stage.on('touchend', (e) => {
        const evt = e.evt;
        if (evt.touches && evt.touches.length < 2) {
          // pinch 刚结束，记录时间戳以屏蔽随后误触发的 click
          if (lastDist > 0) pinchEndTime = Date.now();
          lastDist = 0;
        }
      });

      // ============================================================
      // 拖拽平移（缩放后单指/鼠标拖动图片）
      // 缩放 > 1.05x 时允许拖动，否则锁定在 (0,0)
      // ============================================================
      stage.draggable(false);
      stage.dragBoundFunc((pos) => {
        const scale = stage.scaleX();
        // 缩放 ≤ 1.05 时不允许拖动
        if (scale <= 1.05) {
          return { x: 0, y: 0 };
        }
        // 缩放 > 1.05 时允许在范围内平移
        const maxX = stageWidth * (scale - 1);
        const maxY = stageHeight * (scale - 1);
        return {
          x: Math.max(-maxX, Math.min(0, pos.x)),
          y: Math.max(-maxY, Math.min(0, pos.y)),
        };
      });
    }

    /**
     * 在标注 Group 上绘制所有物体的标注。
     * - 若 obj.polygon 存在：用 Konva.Line 画闭合多边形轮廓
     * - 若 obj.polygon 不存在：用 Konva.Rect 画矩形框
     * 编号圆圈、编号文字、标签名称保持不变。
     */
    function drawObjects() {
      if (!annotationGroup) return;

      // 清除旧图形
      annotationGroup.destroyChildren();
      Object.keys(shapeMap).forEach((k) => delete shapeMap[k]);

      for (const obj of objects.value) {
        // 归一化坐标 → 像素坐标（bbox）
        const px = obj.bbox.x * stageWidth;
        const py = obj.bbox.y * stageHeight;
        const pw = obj.bbox.w * stageWidth;
        const ph = obj.bbox.h * stageHeight;

        const group = new Konva.Group();

        // --- 轮廓（多边形 或 矩形框）---
        let outline;
        if (obj.polygon && obj.polygon.length >= 3) {
          // 多边形：归一化坐标 → 像素坐标，并展平为 [x1,y1,x2,y2,...]
          const flatPoints = [];
          for (const p of obj.polygon) {
            flatPoints.push(p[0] * stageWidth, p[1] * stageHeight);
          }
          outline = new Konva.Line({
            points: flatPoints,
            stroke: KONVA_COLOR_RED,
            strokeWidth: 2,
            closed: true,
          });
        } else {
          // 矩形框（回退模式）
          outline = new Konva.Rect({
            x: px,
            y: py,
            width: pw,
            height: ph,
            stroke: KONVA_COLOR_RED,
            strokeWidth: 2,
            dash: [6, 4],
            cornerRadius: 2,
          });
        }

        // --- 编号圆圈（左上角，半径 14px） ---
        const circle = new Konva.Circle({
          x: px,
          y: py,
          radius: 14,
          fill: KONVA_COLOR_RED,
          stroke: 'white',
          strokeWidth: 1.5,
        });

        // --- 编号文字（白色，居中于圆圈） ---
        const numText = new Konva.Text({
          x: px - 14,
          y: py - 8,
          width: 28,
          height: 16,
          text: String(obj.id),
          fontSize: 12,
          fontStyle: 'bold',
          fontFamily: 'sans-serif',
          fill: 'white',
          align: 'center',
          verticalAlign: 'middle',
          listening: false,
        });

        // --- 标签文字（先创建用于测量宽度） ---
        const labelText = new Konva.Text({
          text: String(obj.label),
          fontSize: 12,
          fontFamily: '-apple-system, "Noto Sans SC", sans-serif',
          padding: 6,
          fill: 'white',
          align: 'center',
          listening: false,
        });

        const labelW = labelText.width();
        const labelH = labelText.height();

        // 定位标签：圆圈下方
        labelText.x(px);
        labelText.y(py + 16);

        // --- 标签背景（红色半透明） ---
        const labelBg = new Konva.Rect({
          x: px,
          y: py + 16,
          width: labelW,
          height: labelH,
          fill: KONVA_COLOR_RED_FILL,
          cornerRadius: 4,
          listening: false,
        });

        // --- 点击事件（轮廓线 & 圆圈均可点击） ---
        outline.on('click', (e) => {
          e.cancelBubble = true;
          toggleSelect(obj.id);
        });
        circle.on('click', (e) => {
          e.cancelBubble = true;
          toggleSelect(obj.id);
        });

        // 添加到 Group（顺序决定渲染层级：先添加的在底层）
        group.add(outline);
        group.add(labelBg);
        group.add(labelText);
        group.add(circle);
        group.add(numText);

        annotationGroup.add(group);

        // 保存图形引用
        shapeMap[obj.id] = {
          group,
          outline,
          circle,
          numText,
          labelBg,
          labelText,
        };
      }

      // 应用选中样式
      for (const obj of objects.value) {
        applyObjectStyle(obj.id);
      }

      annotationLayer.draw();
    }

    /**
     * 更新单个物体的标注样式（选中 / 未选中）。
     * 选中：蓝色 + 加粗；未选中：红色。
     * 多边形（Line）保持实线；矩形框（Rect）未选中时为虚线。
     * @param {number} objId — 物体 ID
     */
    function applyObjectStyle(objId) {
      const shapes = shapeMap[objId];
      if (!shapes) return;

      const obj = objects.value.find((o) => o.id === objId);
      if (!obj) return;

      const isRect = shapes.outline.getClassName() === 'Rect';

      if (obj.selected) {
        // 选中：蓝色实线 + 加粗
        shapes.outline.stroke(KONVA_COLOR_BLUE);
        shapes.outline.strokeWidth(3);
        if (isRect) shapes.outline.dash([]);
        shapes.circle.fill(KONVA_COLOR_BLUE);
        shapes.labelBg.fill(KONVA_COLOR_BLUE_FILL);
      } else {
        // 未选中：红色
        shapes.outline.stroke(KONVA_COLOR_RED);
        shapes.outline.strokeWidth(2);
        if (isRect) shapes.outline.dash([6, 4]);
        shapes.circle.fill(KONVA_COLOR_RED);
        shapes.labelBg.fill(KONVA_COLOR_RED_FILL);
      }
    }

    /** resize 防抖定时器 */
    let resizeTimer = null;

    /**
     * 窗口 resize 处理：防抖 300ms 后销毁并重建 Konva，
     * 按容器新宽度重新计算 Stage 尺寸并重绘所有标注。
     */
    function onResize() {
      if (resizeTimer) clearTimeout(resizeTimer);
      resizeTimer = setTimeout(() => {
        resizeTimer = null;
        if (stage && loadedImage) {
          destroyKonva();
          initKonva(loadedImage);
        }
      }, 300);
    }

    // ============================================================
    // 交互处理
    // ============================================================

    /**
     * 切换物体选中状态。
     * @param {number} objId — 物体 ID
     */
    function toggleSelect(objId) {
      if (step.value === 'generating') return;

      const obj = objects.value.find((o) => o.id === objId);
      if (!obj) return;

      obj.selected = !obj.selected;

      if (annotationLayer) {
        applyObjectStyle(objId);
        annotationLayer.draw();
      }
    }

    /**
     * Stage 单击事件处理 — 选中点击位置所在的物体。
     * - 多边形物体：用射线法判断点击点是否在多边形内部
     * - 矩形物体：判断点击点是否在 bbox 内部
     * 命中则 toggleSelect。
     *
     * 注意：缩放后 getPointerPosition 返回的是缩放后的坐标，
     * 需要除以 scale 还原为标注使用的本地坐标。
     */
    function handleStageClick() {
      if (!stage) return;
      if (step.value === 'generating') return;

      // 屏蔽 pinch 缩放刚结束时误触发的 click
      if (pinchEndTime && Date.now() - pinchEndTime < 350) return;

      const pos = stage.getPointerPosition();
      if (!pos) return;

      // 还原为本地坐标（需减去 stage 平移偏移，再除以缩放）
      const scale = stage.scaleX() || 1;
      const lx = (pos.x - stage.x()) / scale;
      const ly = (pos.y - stage.y()) / scale;

      for (const obj of objects.value) {
        if (obj.polygon && obj.polygon.length >= 3) {
          // 多边形命中检测（射线法），使用像素坐标
          const poly = obj.polygon.map((p) => [
            p[0] * stageWidth,
            p[1] * stageHeight,
          ]);
          if (pointInPolygon(lx, ly, poly)) {
            toggleSelect(obj.id);
            return;
          }
        } else {
          // 矩形命中检测
          const px = obj.bbox.x * stageWidth;
          const py = obj.bbox.y * stageHeight;
          const pw = obj.bbox.w * stageWidth;
          const ph = obj.bbox.h * stageHeight;
          if (lx >= px && lx <= px + pw && ly >= py && ly <= py + ph) {
            toggleSelect(obj.id);
            return;
          }
        }
      }
    }

    // ============================================================
    // 上传流程
    // ============================================================

    /** 触发隐藏的 file input */
    function triggerFileSelect() {
      if (fileInputRef.value) {
        fileInputRef.value.click();
      }
    }

    /** file input change 回调 */
    function onFileChange(e) {
      const file = e.target.files[0];
      if (!file) return;
      // 类型校验
      if (!file.type.startsWith('image/')) {
        setError('请选择图片文件');
        e.target.value = '';
        return;
      }
      // 大小校验
      if (file.size > MAX_FILE_SIZE) {
        setError('图片过大，请选择20MB以内的图片');
        e.target.value = '';
        return;
      }
      handleUpload(file);
      // 清空 input，允许重复选择同一文件
      e.target.value = '';
    }

    /**
     * 处理图片上传：上传 → 加载图片 → VLM 检测 → SAM 分割 → 进入编辑模式。
     * @param {File} file — 用户选择的图片文件
     */
    async function handleUpload(file) {
      if (!file) return;

      step.value = 'analyzing';
      errorMsg.value = '';

      try {
        // 上传前压缩：最长边限制 1024px，减小上传与后端处理压力
        file = await compressImage(file);
        originalFile = file;

        // 启动 VLM 检测（与上传并行）
        const detectPromise = detectObjects(file).catch((err) => {
          console.error('VLM 检测失败:', err);
          showToast('物品识别不可用，可直接描述修改');
          return null;
        });

        // 上传图片
        const uploadFormData = new FormData();
        uploadFormData.append('file', file);
        const uploadRes = await fetchWithTimeout('/api/upload', {
          method: 'POST',
          body: uploadFormData,
        });

        if (!uploadRes.ok) {
          throw new Error('上传失败');
        }

        const uploadData = await uploadRes.json();
        imageUrl.value = uploadData.url;
        imageName.value = uploadData.filename || 'upload.jpg';

        // 并行等待：图片加载 + VLM 检测结果
        const [img, detectData] = await Promise.all([
          loadImage(getImgUrl(uploadData.url)),
          detectPromise,
        ]);

        loadedImage = img;

        // 处理检测结果
        let detectedObjects = [];
        if (detectData && detectData.objects) {
          detectedObjects = detectData.objects.map((obj, i) => ({
            id: obj.id || i + 1,
            label: obj.label || '物品' + (i + 1),
            bbox: obj.bbox || { x: 0, y: 0, w: 0, h: 0 },
            selected: false,
            polygon: null,
            mask_png_b64: null,
          }));
        }

        // ============ SAM 2 前端本地分割 ============
        // VLM 检测完成后在前端本地运行 EdgeTAM 分割，提取每个物体的精确多边形轮廓。
        // 若分割失败（模型加载失败或推理异常），回退到 bbox 模式。
        if (detectedObjects.length > 0) {
          step.value = 'segmenting';
          try {
            const segData = await segmentObjects(file, detectedObjects);
            if (segData && segData.success && Array.isArray(segData.objects)) {
              // 构建 id → 分割结果 的映射
              const segMap = {};
              for (const s of segData.objects) {
                segMap[s.id] = s;
              }
              for (const obj of detectedObjects) {
                const seg = segMap[obj.id];
                if (
                  seg &&
                  Array.isArray(seg.polygon) &&
                  seg.polygon.length >= 3
                ) {
                  // 兼容 [x,y] 与 {x,y} 两种点格式
                  let poly = seg.polygon.map((p) =>
                    Array.isArray(p) ? [p[0], p[1]] : [p.x, p.y]
                  );
                  // 启发式：若存在坐标 > 1，视为图像像素坐标并归一化到 0~1
                  const needsNorm = poly.some(
                    (p) => p[0] > 1 || p[1] > 1
                  );
                  if (needsNorm && loadedImage) {
                    poly = poly.map((p) => [
                      p[0] / loadedImage.naturalWidth,
                      p[1] / loadedImage.naturalHeight,
                    ]);
                  }
                  obj.polygon = poly;
                  obj.mask_png_b64 = seg.mask_png_b64 || null;
                } else {
                  obj.polygon = null;
                  obj.mask_png_b64 = null;
                }
              }
            } else {
              // SAM 不可用 → 回退 bbox 模式
              showToast('精确轮廓提取失败，已使用框选模式');
              for (const obj of detectedObjects) {
                obj.polygon = null;
                obj.mask_png_b64 = null;
              }
            }
          } catch (e) {
            console.warn('SAM 分割失败，回退到 bbox 模式:', e);
            showToast('精确轮廓提取失败，已使用框选模式');
            for (const obj of detectedObjects) {
              obj.polygon = null;
              obj.mask_png_b64 = null;
            }
          }
        }

        objects.value = detectedObjects;

        // 进入编辑模式
        step.value = 'edit';

        // 保存 session（防止页面刷新丢失状态）
        // 必须在 step 置为 edit 之后保存，避免保存到瞬态步骤
        saveSession();

        // 等待 DOM 渲染 canvas 容器后初始化 Konva
        await nextTick();
        initKonva(img);
      } catch (err) {
        console.error('上传失败:', err);
        setError('上传失败: ' + friendlyError(err));
        step.value = 'upload';
      }
    }

    // ============================================================
    // 改图生成
    // ============================================================

    /**
     * 开始改图：收集选中区域 + prompt → 调用 API → 显示结果。
     * - 有选中物体时，prompt 取自底部输入面板 selectedPrompt，
     *   regions 中若物体含 polygon 与 mask_png_b64 则一并附带。
     * - 无选中物体时，prompt 取自 promptText，直接改图。
     */
    async function startEdit() {
      const selected = objects.value.filter((o) => o.selected);
      const prompt = (
        selected.length > 0 ? selectedPrompt.value : promptText.value
      ).trim();

      if (!prompt || step.value === 'generating') return;

      step.value = 'generating';
      setError('');

      try {
        // 准备 FormData
        const formData = new FormData();

        // 获取要发送的文件（优先使用原始 File，否则从 URL 获取）
        let fileToSend = originalFile;
        if (!fileToSend && imageUrl.value) {
          const response = await fetchWithTimeout(getImgUrl(imageUrl.value));
          if (!response.ok) throw new Error('原图已失效，请重新上传');
          fileToSend = await response.blob();
        }
        if (!fileToSend) {
          throw new Error('没有可用的图片文件');
        }

        formData.append('file', fileToSend, imageName.value || 'image.png');
        formData.append('resolution', '1K');
        formData.append('ratio', '1:1');

        let res;

        if (selected.length > 0) {
          // 有选中物体 → 带区域标注的改图
          // regions 中增加 id、label、polygon 与 mask_png_b64（若存在）
          const regions = selected.map((o) => {
            const r = {
              id: o.id,
              label: o.label,
              bbox: {
                x: o.bbox.x,
                y: o.bbox.y,
                w: o.bbox.w,
                h: o.bbox.h,
              },
            };
            if (o.polygon) r.polygon = o.polygon;
            if (o.mask_png_b64) r.mask_png_b64 = o.mask_png_b64;
            return r;
          });

          // 自动在 prompt 前拼接物体描述
          // gpt-image-2 是多模态模型，能看懂图片内容和 mask 透明区域，
          // 但不理解"1号""2号"这种自定义编号。所以用物体名称引导。
          // 例如用户输入 "换成白色"，选中了 黑色台面 和 水龙头
          // 则实际发送: "将黑色台面、水龙头换成白色"
          let finalPrompt = prompt;
          // 检查用户是否已经在 prompt 中引用了编号（如"1号"、"2号"等）
          const hasNumRef = /\d+号/.test(prompt);
          if (!hasNumRef) {
            const labels = selected
              .sort((a, b) => a.id - b.id)
              .map((o) => o.label)
              .join('、');
            finalPrompt = `将${labels}：${prompt}`;
          } else {
            // 用户用了编号引用，替换为物体名称
            // 例如 "1号换成白色，3号换成金色" → "台面换成白色，水龙头换成金色"
            finalPrompt = prompt;
            for (const o of selected) {
              const numPattern = new RegExp(`${o.id}号`, 'g');
              finalPrompt = finalPrompt.replace(numPattern, o.label);
            }
          }

          formData.append('prompt', finalPrompt);
          formData.append('regions', JSON.stringify(regions));

          res = await fetchWithTimeout('/api/image-edit-annotated', {
            method: 'POST',
            body: formData,
          }, 180000);
        } else {
          // 无选中物体 → 直接改图
          formData.append('prompt', prompt);
          res = await fetchWithTimeout('/api/image-edit', {
            method: 'POST',
            body: formData,
          }, 180000);
        }

        // 解析响应：非 JSON 或 HTTP 错误时给出友好提示
        const data = await res.json().catch(() => null);
        if (!res.ok) {
          throw new Error((data && data.error) || '服务器错误(' + res.status + ')');
        }

        if (data && data.success && data.url) {
          resultUrl.value = data.url;
          step.value = 'result';
          saveSession();
        } else {
          throw new Error((data && data.error) || '生成失败');
        }
      } catch (err) {
        console.error('改图失败:', err);
        setError('生成失败: ' + friendlyError(err));
        step.value = 'edit';
      }
    }

    // ============================================================
    // 结果操作
    // ============================================================

    /** 重新编辑 — 回到编辑步骤，保留图片和标注 */
    function resetEdit() {
      resultUrl.value = '';
      setError('');
      step.value = 'edit';
      // Konva 已被 watch 销毁，需要重新初始化
      nextTick(() => {
        if (loadedImage) {
          initKonva(loadedImage);
        }
      });
    }

    /** 保存结果图片 — 创建 <a> 标签下载 */
    function saveResult() {
      if (!resultUrl.value) return;
      const a = document.createElement('a');
      a.href = getImgUrl(resultUrl.value);
      a.download = 'generated_' + Date.now() + '.png';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      showToast('图片已保存');
    }

    /** 结果图加载失败（如服务器临时文件过期）— 提示并回到上传页 */
    function onResultImgError() {
      showToast('结果图已过期，请重新生成');
      step.value = 'upload';
    }

    /** 重新上传 — 重置所有状态，回到上传步骤 */
    function resetAll() {
      clearSession();
      destroyKonva();
      originalFile = null;
      loadedImage = null;
      imageUrl.value = '';
      imageName.value = '';
      objects.value = [];
      promptText.value = '';
      selectedPrompt.value = '';
      resultUrl.value = '';
      setError('');
      step.value = 'upload';
    }

    /**
     * 继续编辑 — 把生成结果图作为新底图，重新检测物体并进入编辑模式。
     * 用户可以在编辑新物体后再次生成，实现多轮迭代编辑。
     */
    async function continueEdit() {
      if (!resultUrl.value) return;

      try {
        // 把结果图作为新的上传文件
        const response = await fetchWithTimeout(getImgUrl(resultUrl.value));
        if (!response.ok) throw new Error('结果图已失效，请重新生成');
        const blob = await response.blob();
        const file = new File(
          [blob],
          'edited_' + Date.now() + '.png',
          { type: 'image/png' }
        );

        // 复用完整的上传→检测→分割流程
        await handleUpload(file);
      } catch (err) {
        console.error('继续编辑失败:', err);
        setError('继续编辑失败: ' + friendlyError(err));
        step.value = 'result';
      }
    }

    // ============================================================
    // 导航
    // ============================================================

    /** 返回上一页 */
    function goBack() {
      destroyKonva();
      emit('back');
    }

    /** 桌面端导航到其他页面 */
    function navigateTo(target) {
      destroyKonva();
      emit('navigate', target);
    }

    // ============================================================
    // 生命周期 & watch
    // ============================================================

    /**
     * 监听 step 变化：
     * 离开 canvas 步骤（edit/generating）时销毁 Konva，防止内存泄漏。
     */
    watch(step, (newStep, oldStep) => {
      const wasCanvas = oldStep === 'edit' || oldStep === 'generating';
      const isCanvas = newStep === 'edit' || newStep === 'generating';

      if (wasCanvas && !isCanvas) {
        destroyKonva();
      }
    });

    onMounted(async () => {
      // 尝试恢复 session（页面刷新/切换标签后回来时）
      const restored = restoreSession();
      // 仅 edit 步骤需要重建画布；result 步骤只展示结果图，无需初始化 Konva
      if (restored && imageUrl.value && step.value === 'edit') {
        try {
          const img = await loadImage(getImgUrl(imageUrl.value));
          loadedImage = img;
          await nextTick();
          initKonva(img);
        } catch (e) {
          console.warn('Session restore failed, image cannot be loaded:', e);
          clearSession();
          step.value = 'upload';
        }
      }

      // 绑定 ripple 波纹效果（事件委托）
      const root = document.querySelector('.page-image-edit');
      if (root) {
        _pointerDownHandler = (e) => {
          const btn = e.target.closest('.ripple-btn');
          if (btn && !btn.disabled) {
            createRipple(e, btn);
          }
        };
        root.addEventListener('pointerdown', _pointerDownHandler);
      }

      // 监听窗口 resize，防抖后重建 Canvas
      window.addEventListener('resize', onResize);
    });

    onUnmounted(() => {
      // 清理定时器，防止内存泄漏
      if (toastTimer) clearTimeout(toastTimer);
      if (errorTimer) clearTimeout(errorTimer);
      // 移除 pointerdown 事件监听器，防止内存泄漏
      const root = document.querySelector('.page-image-edit');
      if (root && _pointerDownHandler) {
        root.removeEventListener('pointerdown', _pointerDownHandler);
      }
      _pointerDownHandler = null;
      // 移除 resize 事件监听器
      window.removeEventListener('resize', onResize);
      if (resizeTimer) {
        clearTimeout(resizeTimer);
        resizeTimer = null;
      }
      // 取消所有进行中的请求，防止回调访问已销毁组件
      pendingControllers.forEach((c) => c.abort());
      pendingControllers.clear();
      destroyKonva();
    });

    // ============================================================
    // 暴露给模板
    // ============================================================

    return {
      // 状态
      step,
      errorMsg,
      fileInputRef,
      canvasContainerRef,
      imageUrl,
      imageName,
      objects,
      promptText,
      selectedPrompt,
      resultUrl,
      toastMsg,
      selectedCount,
      hasSelected,
      selectedLabels,
      isDesktop: computed(() => props.isDesktop),

      // 方法
      triggerFileSelect,
      onFileChange,
      toggleSelect,
      getImgUrl,
      startEdit,
      resetEdit,
      saveResult,
      resetAll,
      continueEdit,
      onResultImgError,
      goBack,
      navigateTo,
    };
  },

  template: `
<div class="page-image-edit" :class="{ 'desktop-mode': isDesktop }">

  <!-- ==================== 桌面端: Icon Rail ==================== -->
  <div v-if="isDesktop" class="icon-rail">
    <div class="icon-rail-logo" @click="navigateTo('home')">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="white" stroke="none">
        <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 15h-2v-2h2v2zm0-4h-2V7h2v6zm4 4h-2v-2h2v2zm0-4h-2V7h2v6z" opacity="0.9"/>
      </svg>
    </div>
    <div class="icon-rail-divider"></div>
    <div class="icon-rail-btn" title="首页" @click="navigateTo('home')">
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/>
      </svg>
    </div>
    <div class="icon-rail-btn" title="对话" @click="navigateTo('chat')">
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
      </svg>
    </div>
    <div class="icon-rail-btn active" title="改图">
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/>
      </svg>
    </div>
    <div class="icon-rail-btn" title="全景" @click="navigateTo('panorama')">
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <circle cx="12" cy="12" r="10"/><ellipse cx="12" cy="12" rx="10" ry="4"/><path d="M2 12h20"/>
      </svg>
    </div>
  </div>

  <!-- ==================== 顶部导航栏 ==================== -->
  <div class="ie-nav">
    <div v-if="!isDesktop" class="icon-btn" @click="goBack">
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <polyline points="15 18 9 12 15 6"/>
      </svg>
    </div>
    <div class="ie-nav-title">改图</div>
    <div class="flex-spacer"></div>
    <div v-if="step !== 'upload'" class="icon-btn" @click="resetAll">
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <polyline points="23 4 23 10 17 10"/>
        <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>
      </svg>
    </div>
  </div>

  <!-- ==================== 内容区 ==================== -->
  <div class="ie-content" :class="{ 'ie-content-has-bar': hasSelected && (step === 'edit' || step === 'generating') }">

    <!-- ---------- 上传步骤 ---------- -->
    <div v-if="step === 'upload'" class="ie-upload-zone" @click="triggerFileSelect">
      <div class="ie-upload-icon">
        <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <rect x="3" y="3" width="18" height="18" rx="2"/>
          <circle cx="8.5" cy="8.5" r="1.5"/>
          <polyline points="21 15 16 10 5 21"/>
        </svg>
      </div>
      <div class="ie-upload-text">点击上传图片</div>
      <div class="ie-upload-hint">支持 JPG、PNG，自动压缩到 1024px</div>
      <div v-if="errorMsg" class="ie-error error-shake ie-error-margin" :key="errorMsg">{{ errorMsg }}</div>
    </div>

    <!-- ---------- VLM 检测中 ---------- -->
    <div v-else-if="step === 'analyzing'" class="ie-loading">
      <div class="scan-loader">
        <div class="scan-grid"></div>
        <div class="scan-line"></div>
      </div>
      <div>AI正在识别图中的物品...</div>
    </div>

    <!-- ---------- SAM 分割中 ---------- -->
    <div v-else-if="step === 'segmenting'" class="ie-loading">
      <div class="pulse-dots">
        <div class="pulse-dot"></div>
        <div class="pulse-dot"></div>
        <div class="pulse-dot"></div>
        <div class="pulse-dot"></div>
      </div>
      <div>AI正在提取精确轮廓...</div>
    </div>

    <!-- ---------- 编辑 / 生成中 ---------- -->
    <div v-else-if="step === 'edit' || step === 'generating'">
      <!-- Konva Canvas 容器 -->
      <div class="ie-canvas-container" ref="canvasContainerRef"></div>

      <!-- 物体标签列表（stagger 入场） -->
      <transition-group v-if="objects.length > 0" name="tag" tag="div" class="ie-objects-list">
        <div
          v-for="obj in objects"
          :key="obj.id"
          class="ie-object-tag"
          :class="{ selected: obj.selected }"
          @click="toggleSelect(obj.id)"
        >
          <span class="ie-object-num">{{ obj.id }}</span>
          <span>{{ obj.label }}</span>
        </div>
      </transition-group>

      <!-- 未检测到物品时的提示 -->
      <div
        v-if="objects.length === 0 && step === 'edit'"
        class="ie-hint-text"
      >
        未检测到物品，可直接输入描述进行改图
      </div>

      <!-- 已选中数量提示 -->
      <div
        v-if="selectedCount > 0"
        class="ie-selected-hint"
      >
        已选中 {{ selectedCount }} 个区域：{{ selectedLabels }}
      </div>

      <!-- 错误信息（shake 动效） -->
      <div v-if="errorMsg" class="ie-error error-shake" :key="errorMsg">{{ errorMsg }}</div>

      <!-- 未选中物体：直接改图输入框 + 按钮 -->
      <textarea
        v-if="!hasSelected"
        class="ie-prompt-input"
        v-model="promptText"
        placeholder="描述你想要的修改..."
        :disabled="step === 'generating'"
      ></textarea>

      <!-- 生成中加载动画（流光进度条） -->
      <div v-if="!hasSelected && step === 'generating'" class="ie-loading">
        <div class="progress-loader"><div class="progress-bar"></div></div>
        <div>AI正在生成修改后的图片...</div>
      </div>
      <button
        v-if="!hasSelected && step !== 'generating'"
        class="ie-btn ie-btn-primary ripple-btn"
        @click="startEdit"
        :disabled="!promptText.trim()"
      >
        开始改图
      </button>

      <!-- 选中物体后：生成中加载动画 -->
      <div v-if="hasSelected && step === 'generating'" class="ie-loading">
        <div class="progress-loader"><div class="progress-bar"></div></div>
        <div>AI正在生成修改后的图片...</div>
      </div>

    </div>

    <!-- ---------- 结果展示 ---------- -->
    <div v-else-if="step === 'result'">
      <div class="ie-result">
        <img :src="getImgUrl(resultUrl)" alt="生成结果" @error="onResultImgError" />
      </div>
      <div class="ie-result-actions">
        <button class="ie-btn ie-btn-secondary ripple-btn" @click="resetEdit">重新编辑</button>
        <button class="ie-btn ie-btn-primary ripple-btn" @click="continueEdit">继续编辑</button>
        <button class="ie-btn ie-btn-primary ripple-btn" @click="saveResult">保存图片</button>
      </div>
    </div>
  </div>

  <!-- ==================== 底部输入面板（滑入动画） ==================== -->
  <transition name="bottom-bar">
    <div
      v-if="(step === 'edit' || step === 'generating') && hasSelected"
      class="ie-bottom-bar"
    >
      <textarea
        class="ie-bottom-input"
        v-model="selectedPrompt"
        :placeholder="selectedCount > 1 ? '描述修改，如：台面换成白色，水龙头换成金色' : '描述对这个物品的修改...'"
        :disabled="step === 'generating'"
        rows="1"
      ></textarea>
      <button
        class="ie-bottom-send ripple-btn"
        @click="startEdit"
        :disabled="!selectedPrompt.trim() || step === 'generating'"
      >
        发送
      </button>
    </div>
  </transition>

  <!-- ==================== 成功 Toast ==================== -->
  <transition name="toast">
    <div v-if="toastMsg" class="ie-toast">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
        <polyline points="20 6 9 17 4 12"/>
      </svg>
      {{ toastMsg }}
    </div>
  </transition>

  <!-- ==================== 隐藏的文件输入 ==================== -->
  <input
    ref="fileInputRef"
    type="file"
    accept="image/*"
    class="hidden-input"
    @change="onFileChange"
  />

</div>
  `,
};
