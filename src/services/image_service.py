from __future__ import annotations

import asyncio
import base64
import logging
import os
import time
from pathlib import Path
from typing import Optional

import httpx

from services.key_pool import KeyPool

logger = logging.getLogger(__name__)


class ImageService:
    """图片生成与编辑服务。

    封装 yunwu.ai Images API 的文生图和图生图功能。
    支持 b64_json 返回格式，自动保存到本地缓存目录。
    """

    SUPPORTED_SIZES = [
        "1024x1024", "1024x1536", "1536x1024",
        "512x512", "512x768", "768x512",
        "2048x2048", "2048x3072", "3072x2048",
    ]

    def __init__(self, config):
        """初始化图片服务。

        参数:
            config: 配置对象，需包含 image_api_base, llm_api_keys,
                    image_model, cache_dir 属性
        """
        self.api_base = config.image_api_base
        # yunwu 集成 key：image 与 LLM 共用同一组 key（llm_api_keys）
        keys = getattr(config, "llm_api_keys", []) or []
        self.key_pool = KeyPool(keys)
        self.model = config.image_model
        self.cache_dir = config.cache_dir

    async def generate_image(
        self,
        prompt: str,
        size: str = "1024x1024",
        quality: str = "auto",
        n: int = 1,
        output_format: str = "png",
        timeout: int = 600,
    ) -> dict:
        """文生图：根据文本提示生成图片。

        调用 POST {api_base}/v1/images/generations，
        返回 b64_json 格式的图片数据并保存到本地。

        参数:
            prompt: 图片生成提示词
            size: 图片尺寸，默认 1024x1024
            quality: 图片质量，默认 auto
            n: 生成数量，默认1
            output_format: 输出图片格式，默认 png
            timeout: 请求超时时间（秒），默认600

        返回:
            包含 success, images, raw 字段的结果字典
        """
        url = f"{self.api_base}/v1/images/generations"

        payload = {
            "model": self.model,
            "prompt": prompt,
            "n": n,
            "size": size,
            "quality": quality,
            "response_format": "b64_json",
        }

        # KeyPool 冷却模式：循环内取 key，失败 mark_failed 触发冷却，
        # 下一轮自动切换到其他可用 key（与 llm_service 重试模式一致）
        last_error = None
        data = None
        for _ in range(self._cooldown_attempts()):
            key = self.key_pool.get_next_key()
            headers = {
                "Authorization": f"Bearer {key}",
                "Content-Type": "application/json",
            }
            try:
                async with httpx.AsyncClient(timeout=timeout) as client:
                    response = await client.post(url, json=payload, headers=headers)
            except httpx.HTTPError as exc:
                # 网络/超时等请求异常：标记 key 失败，换 key 重试
                logger.error("文生图请求异常: %s", exc)
                self.key_pool.mark_failed(key)
                last_error = "图像服务暂时不可用（请求异常）"
                continue
            if response.status_code != 200:
                # 上游错误体可能含内部细节，不原样回传调用方，仅记日志
                logger.error(
                    "文生图 API 错误: HTTP %s body=%s",
                    response.status_code, response.text[:500],
                )
                last_error = f"图像服务暂时不可用（{response.status_code}）"
                if response.status_code in (401, 403, 429) or response.status_code >= 500:
                    # 认证/限流/服务端错误视为 key 故障，冷却后换 key 重试
                    self.key_pool.mark_failed(key)
                    continue
                # 其他 4xx 为请求本身问题，换 key 无意义，直接失败
                return {"success": False, "error": last_error}
            self.key_pool.mark_success(key)
            data = response.json()
            break

        if data is None:
            return {"success": False, "error": last_error or "图像服务暂时不可用"}

        return await self._save_images(data, "gen", output_format)

    def _cooldown_attempts(self) -> int:
        """KeyPool 冷却重试次数：最多 key 数，至少 1 次，封顶 3 次。"""
        return max(1, min(self.key_pool.size or 1, 3))

    async def edit_image(
        self,
        image_path: str,
        prompt: str,
        size: Optional[str] = None,
        quality: str = "auto",
        n: int = 1,
        output_format: str = "png",
        mask_path: Optional[str] = None,
        timeout: int = 600,
    ) -> dict:
        """图生图：基于原图和提示词编辑图片。

        调用 POST {api_base}/v1/images/edits，
        使用 multipart/form-data 上传原图（及可选蒙版），
        返回 b64_json 格式的编辑结果并保存到本地。

        参数:
            image_path: 源图片本地路径
            prompt: 编辑提示词
            size: 输出图片尺寸，None 表示使用默认
            quality: 图片质量，默认 auto
            n: 生成数量，默认1
            output_format: 输出图片格式，默认 png
            mask_path: 蒙版图片路径（可选）
            timeout: 请求超时时间（秒），默认600

        返回:
            包含 success, images, raw 字段的结果字典
        """
        url = f"{self.api_base}/v1/images/edits"

        # 读取源图片为二进制数据（图片可达数 MB，同步读放到 worker 线程，
        # 避免阻塞事件循环）
        image_data = await asyncio.to_thread(Path(image_path).read_bytes)

        files = [
            ("image", (os.path.basename(image_path), image_data, "image/png")),
        ]

        # 可选蒙版文件（同样异步读取）
        if mask_path:
            mask_data = await asyncio.to_thread(Path(mask_path).read_bytes)
            files.append(
                ("mask", (os.path.basename(mask_path), mask_data, "image/png"))
            )

        form_data = {
            "model": self.model,
            "prompt": prompt,
            "n": str(n),
            "response_format": "b64_json",
            "quality": quality,
        }

        if size:
            form_data["size"] = size

        # KeyPool 冷却模式：循环内取 key，失败 mark_failed 触发冷却，
        # 下一轮自动切换到其他可用 key（与 llm_service 重试模式一致）
        last_error = None
        data = None
        for _ in range(self._cooldown_attempts()):
            key = self.key_pool.get_next_key()
            headers = {"Authorization": f"Bearer {key}"}
            try:
                async with httpx.AsyncClient(timeout=timeout) as client:
                    response = await client.post(
                        url, data=form_data, files=files, headers=headers
                    )
            except httpx.HTTPError as exc:
                # 网络/超时等请求异常：标记 key 失败，换 key 重试
                logger.error("图生图请求异常: %s", exc)
                self.key_pool.mark_failed(key)
                last_error = "图像服务暂时不可用（请求异常）"
                continue
            if response.status_code != 200:
                # 上游错误体可能含内部细节，不原样回传调用方，仅记日志
                logger.error(
                    "图生图 API 错误: HTTP %s body=%s",
                    response.status_code, response.text[:500],
                )
                last_error = f"图像服务暂时不可用（{response.status_code}）"
                if response.status_code in (401, 403, 429) or response.status_code >= 500:
                    # 认证/限流/服务端错误视为 key 故障，冷却后换 key 重试
                    self.key_pool.mark_failed(key)
                    continue
                # 其他 4xx 为请求本身问题，换 key 无意义，直接失败
                return {"success": False, "error": last_error}
            self.key_pool.mark_success(key)
            data = response.json()
            break

        if data is None:
            return {"success": False, "error": last_error or "图像服务暂时不可用"}

        return await self._save_images(data, "edit", output_format)

    async def _save_images(self, data: dict, prefix: str, fmt: str) -> dict:
        """将 API 返回的图片数据保存到本地缓存目录。

        支持 b64_json（base64解码保存）和 url（下载保存）两种格式。
        生成图片保存到 cache_dir/generated/，编辑图片保存到 cache_dir/edited/。

        参数:
            data: API 返回的 JSON 数据
            prefix: 文件名前缀 ("gen" 或 "edit")
            fmt: 输出图片格式

        返回:
            成功: {"success": True, "images": [{"path": ..., "url": ...}], "raw": data}
            失败: {"success": False, "error": "错误信息"}
        """
        try:
            if prefix == "gen":
                output_dir = os.path.join(self.cache_dir, "generated")
            else:
                output_dir = os.path.join(self.cache_dir, "edited")

            os.makedirs(output_dir, exist_ok=True)

            images = []
            for item in data.get("data", []):
                timestamp = int(time.time() * 1000)
                filename = f"{prefix}_{timestamp}_{len(images)}.{fmt}"
                filepath = os.path.join(output_dir, filename)

                if "b64_json" in item:
                    # base64 解码并保存
                    image_bytes = base64.b64decode(item["b64_json"])
                    with open(filepath, "wb") as f:
                        f.write(image_bytes)
                    url = item.get("url", "")
                    images.append({"path": filepath, "url": url})

                elif "url" in item:
                    # 下载远程图片并保存（限制超时与大小）
                    max_bytes = 30 * 1024 * 1024  # 30MB
                    async with httpx.AsyncClient(timeout=30) as client:
                        img_response = await client.get(item["url"])
                        img_response.raise_for_status()
                        content_length = img_response.headers.get("Content-Length")
                        if content_length and int(content_length) > max_bytes:
                            raise ValueError("远程图片超过 30MB 限制，已拒绝下载")
                        if len(img_response.content) > max_bytes:
                            raise ValueError("远程图片超过 30MB 限制，已拒绝保存")
                        with open(filepath, "wb") as f:
                            f.write(img_response.content)
                    images.append({"path": filepath, "url": item["url"]})

            return {"success": True, "images": images, "raw": data}

        except Exception as ex:
            return {"success": False, "error": str(ex)}
