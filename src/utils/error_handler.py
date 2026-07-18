from __future__ import annotations

from typing import Tuple

import httpx


class ErrorHandler:
    """统一错误处理工具类。

    将各类异常格式化为用户友好的标题和消息。
    """

    @staticmethod
    def format_error(ex: Exception) -> Tuple[str, str]:
        """将异常格式化为 (标题, 消息) 元组。

        参数:
            ex: 捕获到的异常对象

        返回:
            包含错误标题和用户可读消息的元组
        """
        if isinstance(ex, httpx.TimeoutException):
            return ("请求超时", "API响应超时，请稍后重试。图片生成可能需要较长时间。")
        elif isinstance(ex, httpx.HTTPStatusError):
            return (
                f"API错误 ({ex.response.status_code})",
                f"服务器返回错误: {str(ex.response.text)[:200]}",
            )
        elif isinstance(ex, httpx.ConnectError):
            return ("网络错误", "无法连接到服务器，请检查网络连接。")
        else:
            return ("未知错误", str(ex))


