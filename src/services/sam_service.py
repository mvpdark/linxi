"""
SAM 2 分割服务 — 接收图片 + bbox 列表，返回每个物体的多边形轮廓 + 像素级 mask。

使用 SAM 2.1 Tiny 模型（CPU 推理），首次加载约 5-10 秒，后续每张图 2-5 秒。

用法:
    from services.sam_service import segment_objects
    result = segment_objects(image_bytes, [
        {"label": "沙发", "bbox": [0.1, 0.2, 0.5, 0.3]},  # x, y, w, h 归一化
    ])
    # result = [
    #   {"label": "沙发", "polygon": [[x,y], ...], "mask_png_b64": "..."},
    #   ...
    # ]
"""

import io
import base64
import logging
import inspect
import numpy as np
from PIL import Image

logger = logging.getLogger(__name__)

# ============================================================
# ⚠️ 模块级猴补丁（全局副作用，import 本模块即生效）
# 原因：Hydra/OmegaConf 在 Windows 上通过 inspect.getsource 读取
# 源码构建配置，失败会直接抛错导致 SAM2 模型无法加载。
# 副作用范围：替换的是标准库 inspect.getsource 本身，进程内
# 所有模块都会受影响——源码读取失败时返回 'pass' 而非抛错。
# SAM2 之外依赖 getsource 严格语义的代码可能观察到行为变化；
# 仅限本服务进程内使用，请勿复制到其他项目。
# ============================================================
_original_getsource = inspect.getsource
def _patched_getsource(obj):
    try:
        return _original_getsource(obj)
    except Exception:
        return 'pass'
inspect.getsource = _patched_getsource

# ============================================================
# 模型懒加载（首次调用时初始化）
# ============================================================

_predictor = None
_load_attempted = False
_model_error = None
_load_attempts = 0  # 防止无限重试


def _load_model():
    """懒加载 SAM 2 模型。"""
    global _predictor, _load_attempted, _model_error, _load_attempts

    if _load_attempted and _predictor is not None:
        return

    # 失败重试上限 2 次：达到上限仅放弃本次请求——重置计数使下次
    # 请求允许重试，避免一次性故障（如权重文件临时被占用、内存
    # 瞬时不足）导致服务永久不可用；加载成功后同样重置计数（见下）。
    if _load_attempts >= 2:
        _load_attempts = 0
        return

    _load_attempts += 1

    try:
        import torch  # noqa: F401 —— 延迟导入仅为验证 torch 可用性
        # （不可用则此处抛 ImportError 进入 except 分支）；实际使用在
        # segment_objects 的 195/203 行（torch.inference_mode）。
        from sam2.build_sam import build_sam2
        from sam2.sam2_image_predictor import SAM2ImagePredictor
        import os

        # 模型路径 — checkpoint 在 src/checkpoints，config 在 sam2_src 包中
        _src_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        checkpoint = os.path.join(_src_dir, "checkpoints", "sam2.1_hiera_tiny.pt")

        # 找到 sam2 包中的 config 文件
        import sam2
        _sam2_pkg_dir = os.path.dirname(sam2.__file__)
        model_cfg = os.path.join(_sam2_pkg_dir, "configs", "sam2.1", "sam2.1_hiera_t.yaml")

        if not os.path.exists(checkpoint):
            _model_error = f"模型权重不存在: {checkpoint}"
            return

        if not os.path.exists(model_cfg):
            _model_error = f"配置文件不存在: {model_cfg}"
            return

        logger.info("SAM 2 模型加载中 (CPU 模式)... checkpoint=%s", checkpoint)

        # CPU 模式构建
        device = "cpu"
        model = build_sam2(
            model_cfg,
            checkpoint,
            device=device,
            apply_postprocessing=False,
        )
        _predictor = SAM2ImagePredictor(model)

        _load_attempted = True
        _model_error = None
        _load_attempts = 0  # 成功后重置失败计数，后续异常卸载可重新计数重试
        logger.info("SAM 2 模型加载完成 ✓")

    except Exception as e:
        _model_error = str(e)
        _load_attempted = True  # 标记已尝试加载，避免重复尝试
        logger.error("SAM 2 模型加载失败: %s", e)


def _mask_to_polygon(mask):
    """将二值 mask 转为归一化多边形轮廓点。

    使用 OpenCV findContours 提取最大轮廓，简化为 <=50 个点。

    参数:
        mask: numpy array (H, W), bool/uint8

    返回:
        list of [x, y] 归一化坐标 (0-1)，或 None
    """
    try:
        import cv2
    except ImportError:
        # 没有 cv2，用 skimage
        try:
            from skimage import measure
            contours = measure.find_contours(mask.astype(np.uint8), 0.5)
            if not contours:
                return None
            # 取最长轮廓
            contour = max(contours, key=len)
            # skimage 返回 (row, col) = (y, x)，需要转为 (x, y)
            points = [[float(c[1]), float(c[0])] for c in contour]
        except ImportError:
            return None
    else:
        # OpenCV
        mask_u8 = (mask.astype(np.uint8)) * 255
        contours, _ = cv2.findContours(
            mask_u8, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE
        )
        if not contours:
            return None
        # 取最大轮廓
        contour = max(contours, key=cv2.contourArea)
        # 简化轮廓（减少点数）
        epsilon = 0.01 * cv2.arcLength(contour, True)
        approx = cv2.approxPolyDP(contour, epsilon, True)
        points = [[float(pt[0][0]), float(pt[0][1])] for pt in approx]

    if len(points) < 3:
        return None

    # 限制点数（最多 60 个点）
    if len(points) > 60:
        step = len(points) // 60
        points = points[::step][:60]

    # 归一化
    h, w = mask.shape
    normalized = [[p[0] / w, p[1] / h] for p in points]

    return normalized


def _mask_to_png_b64(mask):
    """将二值 mask 编码为 PNG base64（用于发送给前端或保存）。"""
    mask_u8 = (mask.astype(np.uint8)) * 255
    img = Image.fromarray(mask_u8, mode="L")
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode("ascii")


def segment_objects(image_bytes, objects):
    """对图片中的物体进行 SAM 2 分割。

    参数:
        image_bytes: 原始图片字节 (JPEG/PNG)
        objects: 物体列表，每个包含:
            - label: 物体名称
            - bbox: [x, y, w, h] 归一化坐标 (0-1)

    返回:
        list of dict:
            - label: 物体名称
            - polygon: [[x, y], ...] 归一化多边形点
            - mask_png_b64: base64 编码的 mask PNG
            - bbox: 原始 bbox
        如果模型不可用，返回 None
    """
    _load_model()

    if _model_error:
        logger.warning("SAM 2 不可用: %s", _model_error)
        return None

    if not _predictor:
        return None

    try:
        import torch

        # 加载图片
        img = Image.open(io.BytesIO(image_bytes)).convert("RGB")
        img_array = np.array(img)
        h, w = img_array.shape[:2]

        # 设置图片
        with torch.inference_mode():
            _predictor.set_image(img_array)

            results = []
            for obj in objects:
                bbox = obj.get("bbox", [])
                label = obj.get("label", "物体")

                # 兼容前端发送的 dict 格式 {"x":..,"y":..,"w":..,"h":..}
                if isinstance(bbox, dict):
                    bbox = [bbox["x"], bbox["y"], bbox["w"], bbox["h"]]

                if len(bbox) < 4:
                    continue

                # 归一化坐标 → 像素坐标
                x, y, bw, bh = bbox
                x1 = int(x * w)
                y1 = int(y * h)
                x2 = int((x + bw) * w)
                y2 = int((y + bh) * h)

                # 用 bbox 作为 prompt
                box = np.array([x1, y1, x2, y2])

                masks, scores, _ = _predictor.predict(
                    point_coords=None,
                    point_labels=None,
                    box=box[None, :],  # SAM 2 需要 (1, 4) 形状
                    multimask_output=False,
                )

                # 取最佳 mask
                mask = masks[0]  # (H, W) bool

                # 转多边形
                polygon = _mask_to_polygon(mask)

                # mask 转 PNG base64
                mask_b64 = _mask_to_png_b64(mask)

                results.append({
                    "label": label,
                    "polygon": polygon,
                    "mask_png_b64": mask_b64,
                    "bbox": bbox,
                })

            return results

    except Exception as e:
        logger.error("SAM 2 分割失败: %s", e)
        return None


def is_available():
    """检查 SAM 2 是否可用。"""
    _load_model()
    return _predictor is not None and _model_error is None
