"""
图片处理工具 — 压缩/缩放到指定尺寸
"""
from PIL import Image, ImageOps
import io

MAX_IMAGE_SIZE = 1024  # 默认最大边长

def compress_image(content: bytes, max_size: int = MAX_IMAGE_SIZE) -> bytes:
    """
    将图片压缩到 max_size 像素以内（最长边）。
    返回 JPEG 格式的 bytes（质量85%）。
    如果原图已经小于 max_size，仍然重新编码为 JPEG 以统一格式。
    无效图片数据抛出 ValueError，由调用方转换为 400 响应。
    """
    try:
        img = Image.open(io.BytesIO(content))
        # 修正手机照片 EXIF 方向
        img = ImageOps.exif_transpose(img)
    except Exception:
        raise ValueError("无效图片数据")
    # 转为 RGB（去掉 alpha 通道）
    if img.mode in ('RGBA', 'P'):
        img = img.convert('RGB')
    
    w, h = img.size
    if max(w, h) > max_size:
        if w >= h:
            new_w = max_size
            new_h = int(h * max_size / w)
        else:
            new_h = max_size
            new_w = int(w * max_size / h)
        img = img.resize((new_w, new_h), Image.LANCZOS)
    
    buf = io.BytesIO()
    img.save(buf, format='JPEG', quality=85)
    return buf.getvalue()


def compress_image_png(content: bytes, max_size: int = MAX_IMAGE_SIZE) -> bytes:
    """同上但保持 PNG 格式（用于需要透明通道的场景）"""
    img = Image.open(io.BytesIO(content))
    w, h = img.size
    if max(w, h) > max_size:
        if w >= h:
            new_w = max_size
            new_h = int(h * max_size / w)
        else:
            new_h = max_size
            new_w = int(w * max_size / h)
        img = img.resize((new_w, new_h), Image.LANCZOS)
    buf = io.BytesIO()
    img.save(buf, format='PNG')
    return buf.getvalue()
