"""灵犀 — FastAPI 单端口服务器。

一个进程、一个端口，同时提供：
1. REST API（会话管理、图片上传、记忆清除）
2. WebSocket（聊天流式输出 + Agent 事件推送）
3. 静态文件托管（前端 HTML/CSS/JS + 用户上传图片 + AI生成图片 + 字体）
   —— serve_frontend=false 时切换为纯 API 模式（不托管前端静态文件，
      仅保留 REST/WebSocket 与 /uploads 图片代理）

启动：python server.py
访问：http://127.0.0.1:8765 或 http://<局域网IP>:8765
"""

from __future__ import annotations

import asyncio
import base64
import hmac
import io
import json
import logging
import os
import socket
import sys
import time
import uuid
from contextlib import asynccontextmanager
from pathlib import Path

import httpx
import uvicorn
from fastapi import (
    Depends,
    FastAPI,
    File,
    Form,
    HTTPException,
    Request,
    UploadFile,
    WebSocket,
    WebSocketDisconnect,
)
from fastapi.concurrency import run_in_threadpool
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from sqlalchemy import select, text

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# 路径设置
# ---------------------------------------------------------------------------
_SRC_DIR = Path(__file__).resolve().parent            # src/
_BASE_DIR = _SRC_DIR.parent                            # 项目根目录
_STATIC_DIR = _SRC_DIR / "static"                     # src/static/
# 支持环境变量 ASSETS_DIR 覆盖默认 assets/ 路径
_ASSETS_DIR = Path(os.environ.get("ASSETS_DIR") or (_BASE_DIR / "assets"))

sys.path.insert(0, str(_SRC_DIR))
# 项目根目录加入 sys.path，支持 src.* 包形式导入（src.db / src.utils）
sys.path.insert(0, str(_BASE_DIR))

# 确保目录存在（_STATIC_DIR 仅在 serve_frontend=True 时才需要，
# 推迟到 config 加载后的静态文件挂载块中创建，避免纯 API 模式下重建空目录）
_ASSETS_DIR.mkdir(parents=True, exist_ok=True)

# 图片处理工具（压缩/缩放）
from utils.image_utils import compress_image

# JWT 鉴权体系 ORM 模型与会话类型（阶段2）
# 注：src.db.database / src.utils.dependencies 在导入时依赖 DATABASE_URL
# 环境变量完成模块级初始化，故在下方 config 加载并注入环境变量后再导入
from src.db import models  # noqa: E402
from src.db.models import User  # noqa: E402

# ---------------------------------------------------------------------------
# 导入服务层（原有代码全部保留）
# ---------------------------------------------------------------------------
from config import Config
from services.agent_orchestrator import AgentOrchestrator
from services.auth_service import AuthService
from services.billing_service import BillingService
from services.image_service import ImageService
from services.llm_service import LLMService
from services.memory_manager import MemoryManager
from services.search_service import SearchService
from services.storage_service import StorageService
from services.webdav_service import WebDAVService

# ---------------------------------------------------------------------------
# 加载配置 & 初始化服务
# ---------------------------------------------------------------------------
config = Config.load(str(_BASE_DIR / "config.yaml"))

# ---------------------------------------------------------------------------
# 数据库引擎与会话工厂（阶段2：PostgreSQL + JWT）
# ---------------------------------------------------------------------------
if not config.database_url:
    raise RuntimeError("DATABASE_URL 未配置，请设置环境变量或 config.yaml")

# src.db.database 在导入时读取 DATABASE_URL 环境变量并完成模块级初始化，
# 提前注入以兼容仅在 config.yaml 中配置 database_url 的部署方式
os.environ.setdefault("DATABASE_URL", config.database_url)

# 直接复用 src.db.database 模块级引擎与会话工厂（pool_size=10/pre_ping/recycle
# 已在该模块配置），dependencies.get_db（require_admin 依赖链）与各服务共享同一连接池
from src.db.database import (  # noqa: E402
    engine as db_engine,
    async_session_factory,
)
from src.utils.dependencies import get_current_user, require_admin  # noqa: E402,F401


def _require_user(request) -> "tuple[str | None, str | None]":
    """返回 (user_id, username)；JWT 认证时均有值，旧 API_TOKEN 服务调用时为 (None, None)。"""
    user_id = getattr(request.state, "user_id", None)
    username = getattr(request.state, "username", None)
    if not user_id:
        return None, None
    return user_id, username


def _unauthorized() -> JSONResponse:
    """用户资源路由对旧 API_TOKEN / 未登录调用的统一 401 响应。"""
    return JSONResponse({"error": "需要用户登录"}, status_code=401)


# ---------------------------------------------------------------------------
# 余额熔断（预扣费模式）
# 防止余额为正但极小时用户无限调用消耗型 API。
# 流程：检查余额 >= 门槛 → 预扣预估费用 → 执行 → 成功后实际扣费/失败后退回
# ---------------------------------------------------------------------------

async def _check_balance_and_precharge(
    user_id: str | None, precharge_amount: float
) -> tuple[bool, JSONResponse | None, float]:
    """检查余额门槛并执行预扣费（REST API 用）。

    余额低于 min_balance 时返回 402，防止余额为正但极小时无限调用。
    预扣 precharge_amount 元（调用 auth.precharge 原子扣款）。
    调用方应在 API 失败时调用 auth.refund 退还预扣金额。

    返回 (ok, error_response, charged_amount)：
    - ok=True, None, charged：余额足够，已预扣 charged 元
    - ok=False, resp, 0：余额不足，resp 为 402 响应
    """
    if not user_id:
        # 旧 API_TOKEN 服务间调用，不检查余额
        return True, None, 0.0

    balance = await auth.get_balance(user_id)
    if balance is None:
        return False, JSONResponse({"error": "用户不存在"}, status_code=404), 0.0

    # 余额必须 >= max(min_balance, precharge_amount) 才放行
    required = max(config.min_balance, precharge_amount)
    if balance < required:
        logger.info(
            "余额熔断: user_id=%s balance=%.2f < 需要 %.2f (门槛=%.2f 预扣=%.2f)",
            user_id, balance, required, config.min_balance, precharge_amount,
        )
        return False, JSONResponse(
            {"error": "余额不足，请充值"},
            status_code=402,
        ), 0.0

    # 执行预扣费
    charged = 0.0
    if precharge_amount > 0:
        ok = await auth.precharge(user_id, precharge_amount)
        if not ok:
            # 并发导致余额变化，扣款失败
            return False, JSONResponse(
                {"error": "余额不足，请充值"},
                status_code=402,
            ), 0.0
        charged = precharge_amount

    return True, None, charged


async def _refund_on_failure(user_id: str | None, amount: float):
    """API 调用失败时退还预扣费。"""
    if not user_id or amount <= 0:
        return
    try:
        await auth.refund(user_id, amount)
    except Exception as e:
        logger.error("退款失败: user_id=%s amount=%g err=%s", user_id, amount, e)


async def _ws_check_balance(ws: WebSocket, user_id: str, precharge_amount: float) -> bool:
    """WebSocket 余额检查（聊天循环内，每次发消息前调用）。

    余额低于门槛时发送错误消息，返回 False。
    """
    balance = await auth.get_balance(user_id)
    if balance is None:
        await ws.send_json({"type": "error", "content": "用户不存在"})
        return False

    required = max(config.min_balance, precharge_amount)
    if balance < required:
        logger.info("WS 余额熔断: user_id=%s balance=%.2f < 需要 %.2f", user_id, balance, required)
        await ws.send_json({
            "type": "error",
            "content": "余额不足，请充值",
            "code": "INSUFFICIENT_BALANCE",
        })
        return False

    return True


storage = StorageService(db_session_factory=async_session_factory)

# 用户认证与计费服务（PostgreSQL + JWT）
if not config.jwt_secret:
    logger.warning(
        "JWT_SECRET 未配置，正在使用开发默认密钥；生产环境必须配置 jwt_secret / JWT_SECRET"
    )
auth = AuthService(
    db_session_factory=async_session_factory,
    jwt_secret=config.jwt_secret or "dev-secret-change-me",
    jwt_algorithm=config.jwt_algorithm,
    access_ttl=config.jwt_access_ttl,
    refresh_ttl=config.jwt_refresh_ttl,
)

# 计费服务：yunwu 集成 key（LLM + Image 共用）
_all_yunwu_keys = list(dict.fromkeys(  # 去重保序
    (config.llm_api_keys or [])
))
billing = BillingService(
    db_session_factory=async_session_factory,
    auth_service=auth,
    api_keys=_all_yunwu_keys,
    api_base=config.llm_api_base,
    billing_rate=config.billing_rate,
)
logger.info(
    "计费服务初始化：billing_rate=%s, yunwu keys=%d",
    config.billing_rate, len(_all_yunwu_keys),
)

memory = MemoryManager(
    db_session_factory=async_session_factory,
    api_base=config.llm_api_base,
    api_keys=config.llm_api_keys,
    model=config.llm_model,
)

llm = LLMService(config)
llm.set_memory_manager(memory)

# Tavily 搜索服务
search_service = SearchService(
    db_session_factory=async_session_factory,
    api_keys=getattr(config.tavily, "api_keys", [])
    if getattr(config, "tavily", None) else [],
)

# WebDAV 图片存储（未配置时 enabled=False，所有图片逻辑回退本地行为）
webdav = WebDAVService(
    base_url=getattr(config, "webdav_url", "") or "",
    username=getattr(config, "webdav_username", "") or "",
    password=getattr(config, "webdav_password", "") or "",
    cache_dir=_ASSETS_DIR / "webdav_cache",
    url_secret=config.jwt_secret or "dev-secret-change-me",
)

orchestrator = None
if getattr(config, "agents", None) and config.agents.enabled:
    agent_models = {
        key: sa.model
        for key, sa in config.agents.sub_agents.items()
        if sa.model
    }
    tier_models = {}
    models_cfg = getattr(config.agents, "models", None)
    if models_cfg:
        tier_models = {
            "light": models_cfg.light,
            "standard": models_cfg.standard,
            "heavy": models_cfg.heavy,
        }
    orchestrator = AgentOrchestrator(
        api_base=config.llm_api_base,
        api_keys=config.llm_api_keys,
        main_model=config.llm_model,
        agent_models=agent_models,
        tier_models=tier_models,
        search_service=search_service,
    )
    orchestrator.set_webdav(webdav)
    llm.set_orchestrator(orchestrator)

image_service = ImageService(config)

# ---------------------------------------------------------------------------
# FastAPI 应用
# ---------------------------------------------------------------------------


@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期管理（启动 / 关闭钩子）。"""
    # ---- Startup ----
    logger.info("应用启动中...")
    logger.info(
        "运行模式: %s (serve_frontend=%s)",
        "前端托管 + API" if config.serve_frontend else "纯 API",
        config.serve_frontend,
    )

    # WebDAV 连通性自检（失败仅 warning，不阻断启动；运行期读写仍有本地缓存兜底）
    if webdav.enabled:
        try:
            wd_status = await webdav.test_connection()
            if wd_status.get("ok"):
                logger.info("WebDAV 连接正常: %s", webdav.base_url)
            else:
                logger.warning(
                    "WebDAV 连接失败（图片读写将降级/报错）: %s", wd_status.get("error")
                )
        except Exception as ex:
            logger.warning("WebDAV 自检异常: %s", ex)

    # 开发环境自动创建表（生产环境使用 Alembic 迁移）
    # async with db_engine.begin() as conn:
    #     await conn.run_sync(models.Base.metadata.create_all)

    # 启动后台任务：每小时清理一次 assets 目录中的过期临时文件
    global _cleanup_task

    async def _cleanup_loop():
        while True:
            try:
                _cleanup_old_assets(days=7)
            except Exception as ex:
                logger.warning("[CLEANUP] assets 清理失败: %s", ex)
            await asyncio.sleep(3600)

    _cleanup_task = asyncio.create_task(_cleanup_loop())

    yield

    # ---- Shutdown ----
    logger.info("应用关闭中...")
    if _cleanup_task is not None:
        _cleanup_task.cancel()
        await asyncio.gather(_cleanup_task, return_exceptions=True)
    await db_engine.dispose()
    if orchestrator:
        await orchestrator.close()


app = FastAPI(title="灵犀", docs_url=None, redoc_url=None, lifespan=lifespan)

# ---------------------------------------------------------------------------
# CORS：来源由 config.cors_origins 控制（默认 * 允许所有）
# 规范限制：allow_origins 含 * 时响应不得携带 credentials（浏览器会拒绝），
# 前端使用 Bearer header 鉴权不依赖 cookie，故 * 时 credentials=False 无影响。
# Tauri 生产环境可在 config.yaml 配置 cors_origins 为 tauri://localhost 等源。
# ---------------------------------------------------------------------------
_cors_origins = config.cors_origins or ["*"]
app.add_middleware(
    CORSMiddleware,
    allow_origins=_cors_origins,
    allow_credentials=("*" not in _cors_origins),
    allow_methods=["*"],
    allow_headers=["*"],
)

# ---------------------------------------------------------------------------
# Bearer Token 鉴权（仅校验 /api/* 路径）
# 优先验证用户登录 token（AuthService），降级使用旧 API_TOKEN
# /api/auth/login 无需认证；/ 、/static/* 、/uploads/* 等不做校验
# ---------------------------------------------------------------------------
_API_TOKEN = os.environ.get("API_TOKEN")
if not _API_TOKEN:
    logger.warning("API_TOKEN 未配置，旧服务令牌回退已禁用")

# 无需认证的 API 路径白名单
_AUTH_FREE_PATHS = {
    "/api/auth/login",
    "/api/auth/register",
    "/api/auth/refresh",
    "/api/health",
}


@app.middleware("http")
async def bearer_auth_middleware(request, call_next):
    path = request.url.path
    # 只校验 /api/* 路径；放行 CORS 预检 OPTIONS 请求
    if not path.startswith("/api/") or request.method == "OPTIONS":
        return await call_next(request)
    # 白名单放行（登录/注册/刷新接口）
    if path in _AUTH_FREE_PATHS:
        return await call_next(request)

    auth_header = request.headers.get("Authorization", "")
    if not auth_header.startswith("Bearer "):
        return JSONResponse({"error": "登录已过期，请重新登录"}, status_code=401)

    token = auth_header[len("Bearer "):]

    # 验证 JWT token（返回 {user_id, username, role}，user_id 为 JWT sub 的 UUID 字符串）
    payload = await auth.verify_token(token)
    if payload:
        request.state.user_id = payload.get("user_id")
        request.state.username = payload.get("username")
        request.state.role = payload.get("role")
        return await call_next(request)

    # 兼容：验证旧 API_TOKEN（可选保留，用于服务间调用；未配置时禁用该回退）
    if _API_TOKEN and hmac.compare_digest(token, _API_TOKEN):
        request.state.user_id = None
        request.state.username = None
        request.state.role = "service"
        return await call_next(request)

    return JSONResponse({"error": "登录已过期，请重新登录"}, status_code=401)


# 禁用缓存的中间件（开发环境）—— no-cache 响应头单点负责，
# index 路由与 StaticFiles 不再重复设置
@app.middleware("http")
async def add_cache_control(request, call_next):
    response = await call_next(request)
    # 静态文件和 HTML 禁用缓存
    path = request.url.path
    if (path.endswith(('.html', '.js', '.css')) or path == '/'
            or path.startswith('/static/')):
        response.headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
        response.headers["Pragma"] = "no-cache"
        response.headers["Expires"] = "0"
    return response


# ---------------------------------------------------------------------------
# 上传文件统一校验
# ---------------------------------------------------------------------------
ALLOWED_TYPES = {"image/jpeg", "image/png", "image/webp"}
MAX_UPLOAD_BYTES = 20 * 1024 * 1024


async def read_validated_image(file: UploadFile) -> bytes:
    """读取并校验上传的图片文件（类型 / 大小 / 非空）。"""
    if file.content_type and file.content_type not in ALLOWED_TYPES:
        raise HTTPException(415, "仅支持 JPG/PNG/WebP 图片")
    content = await file.read(MAX_UPLOAD_BYTES + 1)
    if len(content) > MAX_UPLOAD_BYTES:
        raise HTTPException(413, "图片超过 20MB 限制")
    if not content:
        raise HTTPException(400, "文件内容为空")
    return content


# ---------------------------------------------------------------------------
# 后台定时清理 assets 临时文件（每小时执行一次，删除 7 天前的文件）
# ---------------------------------------------------------------------------
_cleanup_task: asyncio.Task | None = None  # 保存引用避免被 GC


def _cleanup_old_assets(days: int = 7):
    """删除 assets 根目录下 7 天前的 upload_/mask_/generated_/panorama_ 文件。

    只处理 assets 根目录下的匹配文件，不触碰 emoji/、fonts/、
    panorama_test/、design_refs/ 等子目录。
    """
    now = time.time()
    max_age = days * 86400
    prefixes = ("upload_", "mask_", "generated_", "panorama_")
    count = 0
    for f in _ASSETS_DIR.iterdir():
        if not f.is_file():
            continue
        if not f.name.startswith(prefixes):
            continue
        try:
            if now - f.stat().st_mtime > max_age:
                f.unlink()
                count += 1
        except OSError:
            pass
    if count:
        logger.info("[CLEANUP] 删除了 %d 个 7 天前的临时图片文件", count)


# ---------------------------------------------------------------------------
# 图片 URL 解析 / 全景历史写入（多租户 + WebDAV 兼容助手）
# ---------------------------------------------------------------------------


def _ext_mime(filename: str) -> str:
    """按扩展名推断图片 MIME（默认 jpeg）。"""
    name = (filename or "").lower()
    if name.endswith(".png"):
        return "image/png"
    if name.endswith(".webp"):
        return "image/webp"
    return "image/jpeg"


async def _resolve_image_bytes(image_url: str, current_uname: str) -> "tuple[bytes | None, str]":
    """把前端传来的图片 URL 解析为字节内容（兼容新旧两种 URL）。

    - 新格式 ``/uploads/{username}/{filename}``（strip 后两段）：
      webdav.enabled 时经 WebDAV/本地缓存读取；URL 中的 username 必须等于
      当前用户 sanitize 后的值，否则拒绝（记 warning，按无图处理）。
    - 旧格式 ``/uploads/{filename}`` 或 ``/{filename}``（一段）：从本地
      assets 目录读取（开发兼容）。
    返回 ``(bytes | None, mime)``。
    """
    if not image_url:
        return None, "image/jpeg"
    # put_file(sign=True) 生成的 URL 可能携带 ?sig=<32hex>，解析路径前剥离查询串
    rel = image_url.split("?", 1)[0].lstrip("/")
    if rel.startswith("uploads/"):
        rel = rel[len("uploads/"):]
    parts = rel.split("/")
    if len(parts) == 2 and parts[0] and parts[1]:
        img_user, filename = parts
        if ".." in filename:
            return None, _ext_mime(filename)
        if not webdav.enabled:
            # 未启用 WebDAV 时不存在用户目录格式，按无图处理
            return None, _ext_mime(filename)
        if WebDAVService.sanitize_username(img_user) != current_uname:
            logger.warning(
                "拒绝读取他人图片: url=%s current_user=%s", image_url, current_uname
            )
            return None, _ext_mime(filename)
        try:
            content = await webdav.get_file(img_user, filename)
        except Exception as ex:
            logger.warning("WebDAV 读取图片失败 %s: %s", image_url, ex)
            content = None
        return content, _ext_mime(filename)
    if len(parts) == 1 and parts[0]:
        filename = parts[0]
        if ".." in filename:
            return None, "image/jpeg"
        img_path = _ASSETS_DIR / filename
        if img_path.exists():
            return img_path.read_bytes(), _ext_mime(filename)
    return None, "image/jpeg"


async def _save_panorama_history(user_id: str, params: dict, result_url: str) -> None:
    """写入全景图生成历史（DB 异常仅 warning，不阻断主流程返回）。"""
    try:
        async with async_session_factory() as s:
            s.add(models.PanoramaHistory(
                user_id=uuid.UUID(user_id),
                params=params,
                result_url=result_url,
            ))
            await s.commit()
    except Exception as ex:
        logger.warning("panorama_history 写入失败: %s", ex)


# === Favicon ===

@app.get("/favicon.ico")
async def favicon():
    """返回简单的 SVG favicon，避免 404。"""
    from fastapi.responses import Response
    svg = (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">'
        '<rect width="32" height="32" rx="8" fill="#7A9B7A"/>'
        '<text x="16" y="22" font-size="18" text-anchor="middle" '
        'fill="white" font-family="sans-serif" font-weight="bold">灵</text>'
        '</svg>'
    )
    return Response(content=svg, media_type="image/svg+xml")


# === REST API: 健康检查（无需认证） ===

@app.get("/api/health")
async def api_health():
    """健康检查：ok 恒为 true（表示进程活着），各组件状态独立汇报。

    docker healthcheck 只看 HTTP 状态码 + ok 字段；任何组件故障时
    HTTP 仍返回 200，对应字段标记为 down。
    """
    # DB 检查（3s 超时保护，异常即 down）
    db_status = "up"
    try:
        async def _ping_db():
            async with async_session_factory() as s:
                await s.execute(text("SELECT 1"))
        await asyncio.wait_for(_ping_db(), timeout=3)
    except Exception as ex:
        logger.warning("health: db 检查失败: %s", ex)
        db_status = "down"

    # WebDAV 检查（未启用返回 disabled；启用时短超时探测）
    if webdav.enabled:
        try:
            wd = await asyncio.wait_for(webdav.test_connection(), timeout=3)
            webdav_status = "up" if wd.get("ok") else "down"
        except Exception as ex:
            logger.warning("health: webdav 检查失败: %s", ex)
            webdav_status = "down"
    else:
        webdav_status = "disabled"

    return {
        "ok": True,
        "time": time.time(),
        "db": db_status,
        "webdav": webdav_status,
    }


# === REST API: 用户认证 ===

from pydantic import BaseModel


class LoginRequest(BaseModel):
    username: str
    password: str


class ChangePasswordRequest(BaseModel):
    old_password: str
    new_password: str


@app.post("/api/auth/login")
async def auth_login(req: LoginRequest):
    """用户登录，返回 token 与账户信息。"""
    result = await auth.login(req.username, req.password)
    if result:
        return {"ok": True, **result}
    return JSONResponse(
        {"ok": False, "error": "用户名或密码错误"},
        status_code=401,
    )


@app.post("/api/auth/logout")
async def auth_logout(request: Request):
    """退出登录，使 token 失效。"""
    auth_header = request.headers.get("Authorization", "")
    token = auth_header[len("Bearer "):] if auth_header.startswith("Bearer ") else ""
    if token:
        await auth.logout(token)
    return {"ok": True}


@app.get("/api/auth/me")
async def auth_me(request: Request):
    """获取当前登录用户信息。"""
    user_id = getattr(request.state, "user_id", None)
    if not user_id:
        # 旧 API_TOKEN 服务间调用，返回 guest 信息
        return {"ok": True, "username": "guest", "balance": None}
    account = await auth.get_user_by_id(user_id)
    if account:
        return {"ok": True, **account}
    return JSONResponse({"ok": False, "error": "账户不存在"}, status_code=404)


@app.post("/api/auth/change-password")
async def auth_change_password(request: Request, req: ChangePasswordRequest):
    """修改密码。"""
    user_id = getattr(request.state, "user_id", None)
    if not user_id:
        return JSONResponse({"ok": False, "error": "请先登录"}, status_code=401)
    ok = await auth.change_password(user_id, req.old_password, req.new_password)
    if ok:
        return {"ok": True}
    return JSONResponse({"ok": False, "error": "旧密码错误"}, status_code=400)


class RegisterRequest(BaseModel):
    username: str
    password: str


class RefreshRequest(BaseModel):
    refresh_token: str


class ApproveUserRequest(BaseModel):
    user_id: str


@app.post("/api/auth/register")
async def auth_register(req: RegisterRequest):
    """用户注册。首个用户自动成为admin且免审批，其余用户需管理员审批。"""
    result = await auth.register(
        username=req.username,
        password=req.password,
        admin_username=config.admin_username,
    )
    if result["success"]:
        return {"ok": True, **result}
    return JSONResponse({"ok": False, "error": result["message"]}, status_code=400)


@app.post("/api/auth/refresh")
async def auth_refresh(req: RefreshRequest):
    """用refresh_token换取新的access_token。"""
    result = await auth.refresh_token(req.refresh_token)
    if result:
        return {"ok": True, **result}
    return JSONResponse({"ok": False, "error": "refresh_token无效或已过期"}, status_code=401)


@app.get("/api/auth/admin/users")
async def admin_list_users(user: User = Depends(require_admin)):
    """管理员查看所有用户列表。"""
    users = await auth.list_users()
    return {"ok": True, "users": users}


@app.post("/api/auth/admin/approve")
async def admin_approve_user(req: ApproveUserRequest, user: User = Depends(require_admin)):
    """管理员审批用户（pending -> active）。"""
    ok = await auth.approve_user(req.user_id)
    if ok:
        return {"ok": True}
    return JSONResponse({"ok": False, "error": "用户不存在或状态错误"}, status_code=400)


@app.post("/api/auth/admin/suspend")
async def admin_suspend_user(req: ApproveUserRequest, user: User = Depends(require_admin)):
    """管理员封禁用户（active -> suspended）。"""
    ok = await auth.suspend_user(req.user_id)
    if ok:
        return {"ok": True}
    return JSONResponse({"ok": False, "error": "用户不存在或状态错误"}, status_code=400)


@app.post("/api/auth/admin/activate")
async def admin_activate_user(req: ApproveUserRequest, user: User = Depends(require_admin)):
    """管理员解封用户（suspended -> active）。"""
    ok = await auth.activate_user(req.user_id)
    if ok:
        return {"ok": True}
    return JSONResponse({"ok": False, "error": "用户不存在或状态错误"}, status_code=400)


# === REST API: 计费 ===


@app.get("/api/billing/summary")
async def billing_summary(request: Request):
    """获取计费摘要（余额、yunwu 消耗、已扣费等）。"""
    user_id = getattr(request.state, "user_id", None)
    if not user_id:
        return JSONResponse({"ok": False, "error": "请先登录"}, status_code=401)
    try:
        summary = await billing.get_billing_summary(user_id)
        return {"ok": True, **summary}
    except Exception as ex:
        logger.error("获取计费摘要失败: %s", ex)
        return JSONResponse({"ok": False, "error": "计费查询失败"}, status_code=500)


@app.post("/api/billing/charge")
async def billing_charge(request: Request):
    """执行计费扣款：计算 yunwu 增量消耗并扣减余额。"""
    user_id = getattr(request.state, "user_id", None)
    if not user_id:
        return JSONResponse({"ok": False, "error": "请先登录"}, status_code=401)
    try:
        result = await billing.charge(user_id)
        return {"ok": True, **result}
    except Exception as ex:
        logger.error("计费扣款失败: %s", ex)
        return JSONResponse({"ok": False, "error": "计费扣款失败"}, status_code=500)


@app.get("/api/billing/keys")
async def billing_keys_usage(request: Request):
    """查询所有 yunwu key 的消耗详情。"""
    user_id = getattr(request.state, "user_id", None)
    if not user_id:
        return JSONResponse({"ok": False, "error": "请先登录"}, status_code=401)
    try:
        usage = await billing.get_all_keys_usage()
        return {"ok": True, **usage}
    except Exception as ex:
        logger.error("查询 key 消耗失败: %s", ex)
        return JSONResponse({"ok": False, "error": "查询失败"}, status_code=500)


@app.post("/api/billing/init-baseline")
async def billing_init_baseline(request: Request):
    """初始化用户的 yunwu 消耗基准。"""
    user_id = getattr(request.state, "user_id", None)
    if not user_id:
        return JSONResponse({"ok": False, "error": "请先登录"}, status_code=401)
    try:
        result = await billing.init_baseline(user_id)
        return {"ok": True, **result}
    except Exception as ex:
        logger.error("初始化基准失败: %s", ex)
        return JSONResponse({"ok": False, "error": "初始化失败"}, status_code=500)


# === REST API: 会话管理 ===

@app.get("/api/sessions")
async def list_sessions(request: Request):
    """获取当前用户的会话列表。"""
    user_id, _ = _require_user(request)
    if not user_id:
        return _unauthorized()
    sessions = await storage.list_sessions_async(user_id)
    return {"sessions": sessions}


@app.post("/api/sessions")
async def create_session(request: Request, title: str = "新对话"):
    """创建新会话。"""
    user_id, _ = _require_user(request)
    if not user_id:
        return _unauthorized()
    session = await storage.create_session_async(user_id, title)
    return session


@app.delete("/api/sessions/{session_id}")
async def delete_session(request: Request, session_id: str):
    """删除会话及其聊天记录。"""
    user_id, _ = _require_user(request)
    if not user_id:
        return _unauthorized()
    await storage.delete_session_async(user_id, session_id)
    return {"ok": True}


@app.get("/api/sessions/{session_id}/history")
async def get_history(request: Request, session_id: str):
    """获取指定会话的聊天记录（越权返回空列表）。"""
    user_id, _ = _require_user(request)
    if not user_id:
        return _unauthorized()
    history = await storage.load_chat_history_async(user_id, session_id)
    return {"history": history}


@app.post("/api/sessions/{session_id}/pin")
async def pin_session(request: Request, session_id: str):
    """置顶会话。"""
    user_id, _ = _require_user(request)
    if not user_id:
        return _unauthorized()
    await storage.pin_session_async(user_id, session_id)
    return {"ok": True}


@app.post("/api/sessions/{session_id}/unpin")
async def unpin_session(request: Request, session_id: str):
    """取消置顶。"""
    user_id, _ = _require_user(request)
    if not user_id:
        return _unauthorized()
    await storage.unpin_session_async(user_id, session_id)
    return {"ok": True}


@app.post("/api/sessions/{session_id}/rename")
async def rename_session(request: Request, session_id: str, title: str):
    """重命名会话。"""
    user_id, _ = _require_user(request)
    if not user_id:
        return _unauthorized()
    await storage.rename_session_async(user_id, session_id, title)
    return {"ok": True}


# === REST API: 图片上传 ===

@app.post("/api/upload")
async def upload_image(request: Request, file: UploadFile = File(...)):
    """上传图片，自动压缩到1024px以内。
    架构改造：不再落地存储，直接返回 base64 data URL 供前端本地保存。
    """
    _, username = _require_user(request)
    if not username:
        return _unauthorized()
    content = await read_validated_image(file)
    # 自动压缩到 1024px
    try:
        content = compress_image(content)
    except ValueError as ex:
        raise HTTPException(400, str(ex))
    # 架构改造：返回 base64 data URL，不再写 WebDAV/assets
    b64 = base64.b64encode(content).decode("utf-8")
    img_id = f"upl_{uuid.uuid4().hex[:12]}"
    return {"success": True, "image": f"data:image/jpeg;base64,{b64}", "id": img_id}


# === REST API: 记忆管理 ===

@app.post("/api/memory/clear")
async def clear_memory(request: Request):
    """清除长期记忆（用户画像）。"""
    user_id, _ = _require_user(request)
    if not user_id:
        return _unauthorized()
    await memory.clear_profile(user_id)
    return {"ok": True}


@app.get("/api/search/usage")
async def get_search_usage(request: Request):
    """获取 Tavily 搜索用量统计（用户视角返回当月用量；服务调用返回全局 Key 用量）。"""
    user_id = getattr(request.state, "user_id", None)  # None 时给全局统计
    stats = await search_service.get_usage_stats(user_id)
    total = sum(s["used"] for s in stats.values())
    return {"keys": stats, "total_used": total}


@app.get("/api/memory/profile")
async def get_memory_profile(request: Request):
    """获取用户画像文本。"""
    user_id, _ = _require_user(request)
    if not user_id:
        return _unauthorized()
    ctx = await memory.get_context(user_id, "_profile")
    return {"profile": LLMService._format_profile_text(ctx["profile"])}


# === REST API: 改图（独立模式，不走 Agent 编排）===

@app.post("/api/image-edit")
async def image_edit_direct(
    request: Request,
    file: UploadFile = File(...),
    prompt: str = Form(""),
    resolution: str = Form("1K"),
    ratio: str = Form("1:1"),
):
    """直接图生图编辑（改图卡片独立模式）。

    接收上传的图片 + 提示词，调用 image_service.edit_image。
    输入图落本地临时文件（image_service 需要本地路径）；结果图在
    WebDAV 启用时写入用户目录并返回 /uploads/{username}/{filename}。
    """
    from utils.size_validator import get_size

    _, username = _require_user(request)
    if not username:
        return _unauthorized()

    # 余额熔断：余额不足返回 402
    user_id = getattr(request.state, "user_id", None)
    _bal_ok, _bal_err, _charged = await _check_balance_and_precharge(user_id, config.precharge_image)
    if not _bal_ok:
        return _bal_err

    content = await read_validated_image(file)
    try:
        content = compress_image(content)  # 自动压缩到1024px
    except ValueError as ex:
        await _refund_on_failure(user_id, _charged)
        raise HTTPException(400, str(ex))
    ext = ".jpg"  # 压缩后统一为 JPEG

    size = get_size(resolution, ratio)

    # 保存原始图片（image_service 需要本地路径，临时文件处理后立即删除）
    tmp_filename = f"upload_{int(time.time())}_{uuid.uuid4().hex[:6]}{ext}"
    tmp_path = _ASSETS_DIR / tmp_filename
    tmp_path.write_bytes(content)

    try:
        result = await image_service.edit_image(
            image_path=str(tmp_path),
            prompt=prompt,
            size=size,
            quality="high",
            output_format="png",
        )
    finally:
        # 原图临时文件立即清理
        try:
            tmp_path.unlink()
        except OSError:
            pass

    try:
        if result.get("success") and result.get("images"):
            img_path = result["images"][0]["path"]
            img_bytes = Path(img_path).read_bytes()
            # 清理 image_service 生成的临时文件
            try:
                os.unlink(img_path)
            except OSError:
                pass
            # 架构改造：直接返回 base64 data URL，不再落地存储
            b64 = base64.b64encode(img_bytes).decode("utf-8")
            return {
                "success": True,
                "image": f"data:image/png;base64,{b64}",
            }
        else:
            logger.error("image-edit 上游失败: %s", result.get("error"))
            await _refund_on_failure(user_id, _charged)
            return JSONResponse(
                {"success": False, "error": "生成失败，请稍后重试"},
                status_code=502,
            )
    except httpx.TimeoutException:
        await _refund_on_failure(user_id, _charged)
        return JSONResponse(
            {"success": False, "error": "上游图片服务超时"},
            status_code=504,
        )
    except Exception as ex:
        logger.error("image-edit 异常: %s", ex)
        await _refund_on_failure(user_id, _charged)
        return JSONResponse(
            {"success": False, "error": "图片编辑服务暂时不可用"},
            status_code=502,
        )


# === REST API: VLM 物体检测（改图标注模式）===

@app.post("/api/vlm-detect")
async def vlm_detect(request: Request, file: UploadFile = File(...)):
    """用 qwen-vl-max 检测图中所有物品的位置区域。

    返回结构化 JSON：
    {
        "objects": [
            {"label": "沙发", "bbox": {"x": 0.1, "y": 0.2, "w": 0.5, "h": 0.3}, "id": 1},
            ...
        ]
    }
    """
    user_id, _ = _require_user(request)
    if not user_id:
        return _unauthorized()

    # 余额熔断
    _bal_ok, _bal_err, _charged = await _check_balance_and_precharge(user_id, config.precharge_vlm)
    if not _bal_ok:
        return _bal_err

    content = await read_validated_image(file)
    try:
        content = compress_image(content)  # 自动压缩到1024px
    except ValueError as ex:
        await _refund_on_failure(user_id, _charged)
        raise HTTPException(400, str(ex))

    # 编码为 base64
    image_b64 = base64.b64encode(content).decode("utf-8")
    ct = file.content_type or "image/jpeg"
    if "png" in ct:
        mime = "image/png"
    elif "webp" in ct:
        mime = "image/webp"
    else:
        mime = "image/jpeg"

    # 使用编排器的视觉 Agent 进行物体检测
    if orchestrator is None:
        await _refund_on_failure(user_id, _charged)
        return JSONResponse(
            {"success": False, "error": "视觉分析服务不可用"},
            status_code=503,
        )

    detect_prompt = (
        "请分析这张室内设计图片，识别图中所有可见的物品和装饰元素。\n"
        "对每个物品，返回其名称和位置区域（用归一化坐标表示）。\n\n"
        "返回JSON格式（只返回JSON，不要其他文字）：\n"
        '{\n'
        '  "objects": [\n'
        '    {"label": "物品名称", "bbox": {"x": 0.0-1.0, "y": 0.0-1.0, "w": 0.0-1.0, "h": 0.0-1.0}},\n'
        '    ...\n'
        '  ]\n'
        '}\n\n'
        "坐标说明：\n"
        "- x, y 是物品左上角的位置（0-1 归一化）\n"
        "- w, h 是物品的宽度和高度（0-1 归一化）\n"
        "- 例如 {x: 0.1, y: 0.2, w: 0.5, h: 0.3} 表示物品在图片左侧偏上位置\n"
        "请尽可能精确地标注每个物品的区域。"
    )

    try:
        result = await orchestrator.run_vision_agent(
            user_message=detect_prompt,
            image_base64=image_b64,
            image_mime=mime,
        )

        if result.success:
            # 尝试解析 JSON
            text = result.content.strip()
            # 清理 markdown 代码块
            if text.startswith("```"):
                parts = text.split("```")
                if len(parts) >= 2:
                    text = parts[1]
                    if text.startswith("json"):
                        text = text[4:]
                    text = text.strip()

            try:
                data = json.loads(text)
                # 为每个物品添加 id
                for i, obj in enumerate(data.get("objects", []), 1):
                    obj["id"] = i
                # 客户端依赖 success 字段判断检测是否成功，必须显式返回
                data["success"] = True
                return data
            except json.JSONDecodeError:
                return {"success": False, "objects": [], "raw": result.content}
        else:
            logger.error("vlm-detect 上游失败: %s", result.error)
            await _refund_on_failure(user_id, _charged)
            return JSONResponse(
                {"success": False, "error": "视觉分析服务暂时不可用"},
                status_code=502,
            )
    except httpx.TimeoutException:
        await _refund_on_failure(user_id, _charged)
        return JSONResponse(
            {"success": False, "error": "视觉分析服务超时"},
            status_code=504,
        )
    except Exception as ex:
        # 不向前端泄露上游 API 地址等内部细节，仅记录到服务端日志
        logger.error("vlm-detect 异常: %s", ex)
        await _refund_on_failure(user_id, _charged)
        return JSONResponse(
            {"success": False, "error": "视觉分析服务暂时不可用"},
            status_code=502,
        )


# === REST API: SAM 2 分割（精确轮廓提取）===

@app.post("/api/sam-segment")
async def sam_segment(
    request: Request,
    file: UploadFile = File(...),
    objects: str = Form(default="[]"),
):
    """用 SAM 2 对图片中的物体进行精确分割。

    接收图片 + VLM 检测到的物体列表，
    返回每个物体的多边形轮廓 + 像素级 mask。

    参数:
        file: 图片文件
        objects: JSON 字符串，格式 [{"label": "沙发", "bbox": [x, y, w, h]}, ...]

    返回:
        {
            "success": true,
            "objects": [
                {"label": "沙发", "polygon": [[x,y],...], "mask_png_b64": "...", "bbox": [...]},
                ...
            ]
        }
    """
    user_id, _ = _require_user(request)
    if not user_id:
        return _unauthorized()

    content = await read_validated_image(file)
    try:
        content = compress_image(content)  # 自动压缩到1024px
    except ValueError as ex:
        raise HTTPException(400, str(ex))

    try:
        obj_list = json.loads(objects)
    except json.JSONDecodeError:
        return JSONResponse(
            {"success": False, "error": "objects 参数不是合法 JSON"},
            status_code=422,
        )

    if not obj_list:
        return {"success": True, "objects": []}

    try:
        from services.sam_service import segment_objects

        # SAM 为 CPU 同步推理（2-5秒），放入线程池避免阻塞事件循环
        results = await run_in_threadpool(segment_objects, content, obj_list)

        if results is None:
            # SAM 不可用，回退到 bbox 模式
            return JSONResponse(
                {
                    "success": False,
                    "error": "SAM 2 模型不可用，使用 bbox 模式",
                    "objects": [],
                },
                status_code=503,
            )

        return {"success": True, "objects": results}

    except Exception as ex:
        logger.error("sam-segment 异常: %s", ex)
        return JSONResponse(
            {"success": False, "error": "SAM 分割服务暂时不可用"},
            status_code=502,
        )

@app.post("/api/image-edit-annotated")
async def image_edit_annotated(
    request: Request,
    file: UploadFile = File(...),
    prompt: str = Form(""),
    regions: str = Form("[]"),
    resolution: str = Form("1K"),
    ratio: str = Form("1:1"),
):
    """带区域标注的图生图编辑。

    接收图片 + 提示词 + 选中区域列表，
    自动生成 alpha mask，调用 image_service.edit_image。
    输入图/mask 落本地临时文件；结果图在 WebDAV 启用时写入用户目录。
    """
    from io import BytesIO
    from utils.size_validator import get_size

    _, username = _require_user(request)
    if not username:
        return _unauthorized()

    # 余额熔断
    user_id = getattr(request.state, "user_id", None)
    _bal_ok, _bal_err, _charged = await _check_balance_and_precharge(user_id, config.precharge_image)
    if not _bal_ok:
        return _bal_err

    content = await read_validated_image(file)
    try:
        content = compress_image(content)  # 自动压缩到1024px
    except ValueError as ex:
        await _refund_on_failure(user_id, _charged)
        raise HTTPException(400, str(ex))

    try:
        regions_list = json.loads(regions)
    except json.JSONDecodeError:
        await _refund_on_failure(user_id, _charged)
        return JSONResponse(
            {"success": False, "error": "regions 参数不是合法 JSON"},
            status_code=422,
        )

    # 保存原图（压缩后统一 JPEG）
    ext = ".jpg"

    size = get_size(resolution, ratio)

    # 生成 mask（如果有选中区域）
    mask_path = None
    if regions_list:
        try:
            from PIL import Image, ImageDraw
            img = Image.open(BytesIO(content))
            w, h = img.size
            mask = Image.new("RGBA", (w, h), (0, 0, 0, 255))
            draw = ImageDraw.Draw(mask)
            for region in regions_list:
                # 优先使用 SAM 多边形轮廓生成精确 mask
                polygon = region.get("polygon")
                if polygon and len(polygon) >= 3:
                    # 多边形坐标 → 像素坐标
                    poly_points = [(int(p[0] * w), int(p[1] * h)) for p in polygon]
                    # 透明区域 = 可编辑
                    draw.polygon(poly_points, fill=(0, 0, 0, 0))
                elif region.get("mask_png_b64"):
                    # 使用 SAM 返回的 mask PNG（像素级精确）
                    mask_bytes = base64.b64decode(region["mask_png_b64"])
                    sam_mask = Image.open(BytesIO(mask_bytes)).convert("L")
                    # sam_mask 中白色=物体区域，需要变为透明（可编辑）
                    sam_mask_resized = sam_mask.resize((w, h))
                    # 用 paste 高效处理，替代逐像素循环
                    transparent = Image.new("RGBA", (w, h), (0, 0, 0, 0))
                    # mask 中 >128 的像素变成透明（可编辑区域）
                    mask_rgba = Image.new("RGBA", (w, h), (0, 0, 0, 255))
                    mask_rgba.paste(transparent, mask=sam_mask_resized)
                    # 合并到总 mask（alpha_composite 返回新对象，需重建 draw）
                    mask = Image.alpha_composite(mask, mask_rgba)
                    draw = ImageDraw.Draw(mask)
                else:
                    # 回退：矩形 bbox mask
                    bbox = region.get("bbox", {})
                    x0 = int(bbox.get("x", 0) * w)
                    y0 = int(bbox.get("y", 0) * h)
                    x1 = int((bbox.get("x", 0) + bbox.get("w", 0)) * w)
                    y1 = int((bbox.get("y", 0) + bbox.get("h", 0)) * h)
                    # 透明区域 = 可编辑
                    draw.rectangle([x0, y0, x1, y1], fill=(0, 0, 0, 0))
            mask_filename = f"mask_{int(time.time()*1000)}_{uuid.uuid4().hex[:6]}.png"
            mask_path = _ASSETS_DIR / mask_filename
            mask.save(str(mask_path), format="PNG")
        except ImportError:
            import logging
            logging.warning("Pillow not installed, skipping mask generation")

    # 保存原始图片
    tmp_filename = f"upload_{int(time.time())}_{uuid.uuid4().hex[:6]}{ext}"
    tmp_path = _ASSETS_DIR / tmp_filename
    tmp_path.write_bytes(content)

    try:
        result = await image_service.edit_image(
            image_path=str(tmp_path),
            prompt=prompt,
            size=size,
            quality="high",
            output_format="png",
            mask_path=str(mask_path) if mask_path else None,
        )

        if result.get("success") and result.get("images"):
            img_path = result["images"][0]["path"]
            try:
                img_bytes = Path(img_path).read_bytes()
                # 与 /api/image-edit 保持一致：返回 base64 data URL，
                # 客户端 ImageEditResponse 只认 image 字段，不认 url 字段
                b64 = base64.b64encode(img_bytes).decode("utf-8")
                dest_name = f"generated_{int(time.time()*1000)}_{uuid.uuid4().hex[:6]}.png"
                if webdav.enabled:
                    # 结果图同时写入 WebDAV 用户目录（保留可追溯）
                    url = await webdav.put_file(username, dest_name, img_bytes)
                    return {"success": True, "image": f"data:image/png;base64,{b64}", "url": url}
                dest = _ASSETS_DIR / dest_name
                dest.write_bytes(img_bytes)
                return {"success": True, "image": f"data:image/png;base64,{b64}", "url": f"/uploads/{dest.name}"}
            finally:
                # 清理 image_service 临时文件（两种模式均执行）
                try:
                    os.unlink(img_path)
                except OSError:
                    pass
        else:
            logger.error("image-edit-annotated 上游失败: %s", result.get("error"))
            await _refund_on_failure(user_id, _charged)
            return JSONResponse(
                {"success": False, "error": "生成失败，请稍后重试"},
                status_code=502,
            )
    except httpx.TimeoutException:
        await _refund_on_failure(user_id, _charged)
        return JSONResponse(
            {"success": False, "error": "上游图片服务超时"},
            status_code=504,
        )
    except Exception as ex:
        logger.error("image-edit-annotated 异常: %s", ex)
        await _refund_on_failure(user_id, _charged)
        return JSONResponse(
            {"success": False, "error": "图片编辑服务暂时不可用"},
            status_code=502,
        )
    finally:
        # 清理上传临时文件和蒙版文件
        for p in (tmp_path, mask_path):
            if p:
                try:
                    p.unlink()
                except OSError:
                    pass


# === WebSocket: 聊天流式 ===

@app.websocket("/ws/chat")
async def ws_chat(ws: WebSocket):
    """WebSocket 聊天端点。

    鉴权（accept 后）：
        - 兼容旧方式：query 携带 ?token=...
        - 首帧鉴权：首条消息 {"type": "auth", "token": "..."}，成功回 {"type":"auth_ok"}

    客户端发送:
        {"type": "chat", "session_id": "...", "message": "...", "image_url": "/upload_xxx.jpg"}

    服务端流式返回 AgentEvent:
        {"type": "routing"}
        {"type": "dispatch", "agents_dispatched": [...], "route_reason": "..."}
        {"type": "status", "content": "..."}
        {"type": "agent_done", "agent_name": "...", "agent_key": "...", "content": "..."}
        {"type": "agent_error", "agent_name": "...", "error": "..."}
        {"type": "synthesis_start"}
        {"type": "delta", "content": "..."}
        {"type": "done", "route_reason": "..."}
        {"type": "error", "content": "..."}
    """
    await ws.accept()
    query_token = ws.query_params.get("token", "")
    if query_token:
        # 兼容旧客户端：query 携带 token 直接验证
        # TODO: 前端全部迁移为首帧鉴权后移除此兼容分支
        payload = await auth.verify_token(query_token)
    else:
        # 首帧鉴权：等待客户端首条 {"type":"auth","token":"..."}（5 秒超时）
        payload = None
        try:
            first = await asyncio.wait_for(ws.receive_json(), timeout=5)
            if first.get("type") == "auth":
                payload = await auth.verify_token(str(first.get("token", "")))
        except Exception:
            payload = None
    if not payload:
        # 未授权：告知后按 4401 关闭，前端据此清理登录态
        try:
            await ws.send_json({"type": "error", "content": "unauthorized"})
        finally:
            await ws.close(code=4401)
        return
    if not query_token:
        # 首帧模式显式确认鉴权成功
        await ws.send_json({"type": "auth_ok"})

    user_id = payload["user_id"]
    username = payload["username"]
    uname = WebDAVService.sanitize_username(username)

    try:
        while True:
            msg = await ws.receive_json()
            msg_type = msg.get("type", "")

            if msg_type == "ping":
                await ws.send_json({"type": "pong"})
                continue

            if msg_type != "chat":
                continue

            # 余额熔断：每次聊天前检查余额，不足则发送错误消息
            if not await _ws_check_balance(ws, user_id, config.precharge_chat):
                continue

            session_id = msg.get("session_id", "")
            message = msg.get("message", "")
            image_url = msg.get("image_url", "")

            # 加载历史（越权返回空列表）
            history = await storage.load_chat_history_async(user_id, session_id)

            # 初始化记忆
            await memory.init_session(user_id, session_id, history)

            # 处理图片（架构改造：前端传 data URL，后端只解析不存储）
            image_data = ""
            image_mime = "image/jpeg"
            # 用于历史记录的图片 ID（前端用此从本地 IndexedDB 取回）
            image_id_for_history = ""
            if image_url:
                if image_url.startswith("data:"):
                    # data URL：直接解码 base64 部分送给 LLM
                    try:
                        # data:image/jpeg;base64,xxxx
                        header, b64part = image_url.split(",", 1)
                        if "image/png" in header:
                            image_mime = "image/png"
                        image_bytes = base64.b64decode(b64part)
                        image_data = b64part
                    except Exception as ex:
                        logger.warning("data URL 解析失败: %s", ex)
                else:
                    # 兼容旧 URL 格式（/uploads/...）：从本地/WebDAV 读取
                    image_bytes, image_mime = await _resolve_image_bytes(image_url, uname)
                    if image_bytes:
                        image_data = base64.b64encode(image_bytes).decode("utf-8")

            # 流式输出
            full_response = ""
            has_image_result = False

            # 心跳任务：每10秒发一次心跳，防止 WebSocket 超时断连
            async def _heartbeat():
                while True:
                    await asyncio.sleep(10)
                    try:
                        await ws.send_json({"type": "heartbeat"})
                    except Exception:
                        break

            hb_task = asyncio.create_task(_heartbeat())

            try:
                if llm.has_orchestrator:
                    async for event in llm.chat_with_agents(
                        message, history, image_data, image_mime,
                        session_id=session_id, user_id=user_id,
                        username=username,
                    ):
                        event_dict = {
                            "type": event.type,
                            "content": event.content,
                            "agent_name": event.agent_name,
                            "agent_key": event.agent_key,
                            "agent_model": event.agent_model,
                            "agents_dispatched": event.agents_dispatched,
                            "route_reason": event.route_reason,
                            "error": event.error,
                        }

                        # 跟踪响应文本和图片结果
                        if event.type == "delta":
                            full_response += event.content
                        elif event.type == "agent_done" and event.agent_key == "image_generator":
                            has_image_result = True
                            img_content = event.content
                            if "[IMAGE]" in img_content:
                                img_url = img_content.split("[IMAGE]")[1].split("[/IMAGE]")[0]
                                event_dict["image_url"] = img_url
                                full_response = img_content

                        await ws.send_json(event_dict)
                else:
                    # 回退：直接流式
                    async for chunk in llm.chat_stream(
                        message, history, user_id=user_id, session_id=session_id
                    ):
                        full_response += chunk
                        await ws.send_json({"type": "delta", "content": chunk})
                    await ws.send_json({"type": "done"})

            except Exception as ex:
                import traceback
                tb = traceback.format_exc()
                logger.error("❌ WebSocket处理异常: %s\n%s", ex, tb)
                await ws.send_json({"type": "error", "content": "服务处理异常，请稍后重试"})
            finally:
                hb_task.cancel()

            # 保存到历史：增量追加 user/assistant 两条（替代全量覆盖写）。
            # 结构兼容性：load_chat_history_async 的 image_url 取自 images[0]，
            # 故 append 时把单图 URL 包成 images 列表，读取侧还原结果一致。
            await storage.append_message_async(
                user_id, session_id, "user", message,
                images=[image_url] if image_url else None,
            )
            ai_images = None
            # 如果是图片生成结果，同时保存 image_url 字段（兼容旧格式）
            if has_image_result and "[IMAGE]" in full_response:
                ai_images = [full_response.split("[IMAGE]")[1].split("[/IMAGE]")[0]]
            await storage.append_message_async(
                user_id, session_id, "assistant", full_response,
                images=ai_images,
            )
            await storage.touch_session_async(user_id, session_id)

            # 更新记忆（用户消息 + AI 回复各一条）
            ai_text_for_memory = "（生成图片）" if has_image_result else full_response
            await memory.add_message(user_id, session_id, "user", message)
            await memory.add_message(user_id, session_id, "assistant", ai_text_for_memory)

    except WebSocketDisconnect:
        pass
    except Exception as e:
        logger.warning("WebSocket 异常: %s", e)


# === 旧 URL 兼容：重定向到首页（仅托管前端时注册，纯 API 模式无意义） ===
from starlette.responses import RedirectResponse

if config.serve_frontend:
    @app.get("/inspiration")
    async def redirect_inspiration():
        """旧 Flet 灵感页面 → 新聊天页"""
        return RedirectResponse(url="/#/chat")

    @app.get("/image_edit")
    async def redirect_image_edit():
        """旧 Flet 改图页面 → 新改图页"""
        return RedirectResponse(url="/#/image-edit")

# === 全景 API ===

@app.get("/api/panorama/history")
async def panorama_history(request: Request):
    """获取当前用户的全景图生成历史（按创建时间倒序，最多 50 条，走数据库）。"""
    user_id, _ = _require_user(request)
    if not user_id:
        return _unauthorized()
    async with async_session_factory() as s:
        result = await s.execute(
            select(models.PanoramaHistory)
            .where(models.PanoramaHistory.user_id == uuid.UUID(user_id))
            .order_by(models.PanoramaHistory.created_at.desc())
            .limit(50)
        )
        rows = result.scalars().all()
    return {"history": [
        {
            "url": r.result_url,
            "style": (r.params or {}).get("style"),
            "mode": (r.params or {}).get("mode"),
            "created_at": r.created_at.timestamp(),
        }
        for r in rows
    ]}


@app.post("/api/panorama/stitch")
async def panorama_stitch(request: Request, files: list[UploadFile] = File(...)):
    """
    将6张立方体面图拼接成 equirectangular 全景图。
    files 顺序: front, right, back, left, top, bottom
    结果图在 WebDAV 启用时写入用户目录；成功后将历史写入数据库。
    """
    import numpy as np
    import py360convert
    from PIL import Image

    user_id, username = _require_user(request)
    if not user_id:
        return _unauthorized()

    # 余额熔断
    _bal_ok, _bal_err, _charged = await _check_balance_and_precharge(user_id, config.precharge_panorama)
    if not _bal_ok:
        return _bal_err

    if len(files) != 6:
        await _refund_on_failure(user_id, _charged)
        return JSONResponse({"error": "需要6张面图"}, status_code=400)

    # 逐张走统一上传校验（类型/大小/非空），压缩失败的非法图片返回 400
    contents = []
    for file in files:
        content = await read_validated_image(file)
        try:
            content = compress_image(content, max_size=1024)
        except ValueError as ex:
            await _refund_on_failure(user_id, _charged)
            raise HTTPException(400, str(ex))
        contents.append(content)

    def _stitch():
        """CPU 密集的解码/缩放/立方体转等距柱状重计算，放入线程池执行。"""
        face_names = ['F', 'R', 'B', 'L', 'U', 'D']
        faces = {}
        for name, content in zip(face_names, contents):
            img = Image.open(io.BytesIO(content)).convert('RGB')
            # 确保 1024x1024
            if img.size != (1024, 1024):
                img = img.resize((1024, 1024), Image.LANCZOS)
            faces[name] = np.array(img)
        # 拼接
        equi = py360convert.c2e(faces, h=1024, w=2048, cube_format='dict')
        return Image.fromarray(equi)

    try:
        equi_img = await run_in_threadpool(_stitch)
    except Exception:
        await _refund_on_failure(user_id, _charged)
        raise

    # 架构改造：直接返回 base64 data URL，不再落盘
    buf = io.BytesIO()
    equi_img.save(buf, 'PNG')
    pano_b64 = base64.b64encode(buf.getvalue()).decode("utf-8")
    image_data_url = f"data:image/png;base64,{pano_b64}"

    # 历史记录：只存图片标识，前端从本地 IndexedDB 读取
    # 用唯一 ID 标识，避免后端落地
    pano_id = f"pano_{uuid.uuid4().hex[:12]}"
    await _save_panorama_history(user_id, {"mode": "stitch"}, pano_id)
    return {"image": image_data_url, "id": pano_id}


@app.post("/api/panorama/generate-faces")
async def panorama_generate_faces(
    request: Request,
    floor_plan: UploadFile = File(...),
    style_desc: str = "现代北欧风格",
):
    """
    根据平面图和风格描述，AI生成6个面的图。
    调用 yunwu gpt-image-2 API。结果面图在 WebDAV 启用时写入用户目录。
    """
    _, username = _require_user(request)
    if not username:
        return _unauthorized()

    # 余额熔断
    user_id = getattr(request.state, "user_id", None)
    _bal_ok, _bal_err, _charged = await _check_balance_and_precharge(user_id, config.precharge_panorama)
    if not _bal_ok:
        return _bal_err

    # 注意：平面图当前仅用于触发生成，内容未传给 AI
    # 保存平面图（当前仅作为触发条件，图像内容未传给生成模型）
    plan_content = await read_validated_image(floor_plan)
    try:
        plan_content = compress_image(plan_content, max_size=1024)
    except ValueError as ex:
        await _refund_on_failure(user_id, _charged)
        raise HTTPException(status_code=400, detail=str(ex))

    # 6个面的提示词
    face_prompts = [
        ('front', f'Interior design photo, front view of a {style_desc} room, photorealistic, high quality, centered perspective, warm natural lighting'),
        ('right', f'Interior design photo, right wall view of the same {style_desc} room, photorealistic, high quality, centered perspective, warm natural lighting'),
        ('back', f'Interior design photo, back view of the same {style_desc} room, photorealistic, high quality, centered perspective, warm natural lighting'),
        ('left', f'Interior design photo, left wall view of the same {style_desc} room, photorealistic, high quality, centered perspective, warm natural lighting'),
        ('top', f'Interior design photo, ceiling view looking up in a {style_desc} room, white ceiling with recessed lighting, photorealistic, high quality'),
        ('bottom', f'Interior design photo, floor view looking down in a {style_desc} room, photorealistic, high quality'),
    ]

    results = {}
    for name, prompt in face_prompts:
        try:
            gen = await image_service.generate_image(
                prompt=prompt,
                size="1024x1024",
                quality="high",
                output_format="png",
                timeout=270,
                max_attempts=2,
            )
        except Exception as ex:
            logger.error("generate-faces 生成 %s 面异常: %s", name, ex)
            await _refund_on_failure(user_id, _charged)
            return JSONResponse(
                {"error": "生成失败，请稍后重试"},
                status_code=500,
            )
        if not gen.get("success") or not gen.get("images"):
            logger.error("generate-faces 生成 %s 面失败: %s", name, gen.get("error"))
            await _refund_on_failure(user_id, _charged)
            return JSONResponse(
                {"error": "生成失败，请稍后重试"},
                status_code=500,
            )
        img_path = gen["images"][0]["path"]
        new_name = f"pano_face_{name}_{int(time.time())}_{uuid.uuid4().hex[:6]}.png"
        img_bytes = Path(img_path).read_bytes()
        if webdav.enabled:
            # 面图写入 WebDAV 用户目录
            results[name] = await webdav.put_file(username, new_name, img_bytes)
        else:
            dest = _ASSETS_DIR / new_name
            dest.write_bytes(img_bytes)
            results[name] = f"/uploads/{new_name}"
        # 清理 image_service 缓存目录中的临时文件
        try:
            os.unlink(img_path)
        except OSError:
            pass

    return {"faces": results}


@app.post("/api/panorama/ai-generate")
async def panorama_ai_generate(
    request: Request,
    floor_plan: UploadFile = File(...),
    style_desc: str = "现代北欧风格",
):
    """
    方案A：AI 直接生成一张 equirectangular 全景图。
    跳过6面拼接，用一次 AI 调用生成 2:1 全景图。
    结果图在 WebDAV 启用时写入用户目录；历史走数据库。
    """
    from PIL import Image

    user_id, username = _require_user(request)
    if not user_id:
        return _unauthorized()

    # 余额熔断（与 stitch/generate-faces/ai-correct 一致）
    _bal_ok, _bal_err, _charged = await _check_balance_and_precharge(user_id, config.precharge_panorama)
    if not _bal_ok:
        return _bal_err

    plan_content = await read_validated_image(floor_plan)
    try:
        plan_content = compress_image(plan_content, max_size=1024)
    except ValueError as ex:
        await _refund_on_failure(user_id, _charged)
        raise HTTPException(status_code=400, detail=str(ex))

    # 构建全景图 prompt（增强版：加入构图、光影、材质细节 + negative prompt）
    pano_prompt = (
        f"A 360-degree equirectangular panoramic photo of a {style_desc} interior room. "
        f"The image is a complete 360-degree panorama with 2:1 aspect ratio, "
        f"showing all four walls, ceiling, and floor in a single seamless image. "
        f"Photorealistic, ultra high quality, 8K resolution. "
        f"Warm natural lighting from windows, soft ambient shadows, gentle highlights. "
        f"Rule of thirds composition with main furniture as focal point. "
        f"Rich material textures: oak wood floor, linen sofa fabric, brushed metal fixtures. "
        f"Depth of field: foreground furniture, midground living space, background walls. "
        f"Modern furniture with clean lines, indoor plants, minimalist decorative elements. "
        f"Consistent color palette and lighting throughout the entire panorama. "
        f"The left edge and right edge of the image must connect seamlessly (wraparound). "
        f"No visible seams, no stitching artifacts, no distortion, no blurry areas, "
        f"no duplicate furniture, no mismatched walls, no rendering artifacts."
    )

    # 先用 1536x1024 生成（API 支持的最大 2:1 比例），再 resize 到 2048x1024
    # 超时预算：客户端超时 600s，单次 270s × 至多 2 次尝试 = 最坏 540s，
    # 预留 60s 给读图/压缩/resize/base64，确保成功结果能在客户端放弃前返回
    result = await image_service.generate_image(
        prompt=pano_prompt,
        size="1536x1024",
        quality="high",
        output_format="png",
        timeout=270,
        max_attempts=2,
    )

    if not result.get("success") or not result.get("images"):
        logger.error("ai-generate 全景图生成失败: %s", result.get("error"))
        await _refund_on_failure(user_id, _charged)
        return JSONResponse({"error": "生成失败，请稍后重试"}, status_code=500)

    img_path = result["images"][0]["path"]

    # resize 到 2048x1024（标准 equirectangular 尺寸），结果统一走字节流
    pano_filename = f"panorama_{int(time.time())}_{uuid.uuid4().hex[:6]}.png"
    try:
        img = Image.open(img_path).convert("RGB")
        img = img.resize((2048, 1024), Image.LANCZOS)
        buf = io.BytesIO()
        img.save(buf, "PNG")
        pano_bytes = buf.getvalue()
    except Exception:
        # resize 失败则直接用原图字节
        pano_bytes = Path(img_path).read_bytes()

    # 删除原始生成图（image_service 临时文件）
    try:
        os.unlink(img_path)
    except OSError:
        pass

    # 架构改造：直接返回 base64 data URL，不再落盘
    # 历史只存 ID，前端从本地 IndexedDB 读取
    pano_id = f"pano_{uuid.uuid4().hex[:12]}"
    await _save_panorama_history(
        user_id, {"mode": "ai-direct", "style": style_desc}, pano_id
    )

    pano_b64 = base64.b64encode(pano_bytes).decode("utf-8")
    return {"image": f"data:image/png;base64,{pano_b64}", "id": pano_id}


@app.post("/api/panorama/ai-correct")
async def panorama_ai_correct(request: Request, data: dict):
    """
    AI修正全景图接缝和光照不一致。
    输入: {"panorama_url": "/uploads/{username}/panorama_xxx.png" 或旧 "/uploads/panorama_xxx.png"}
    使用 gpt-image-2 编辑拼接后的全景图，修正接缝。
    如果 API 不支持输入尺寸或修正失败，返回原图。
    """
    from PIL import Image

    user_id, username = _require_user(request)
    if not user_id:
        return _unauthorized()

    # 余额熔断
    _bal_ok, _bal_err, _charged = await _check_balance_and_precharge(user_id, config.precharge_panorama)
    if not _bal_ok:
        return _bal_err

    panorama_url = data.get("panorama_url", "")
    if not panorama_url:
        await _refund_on_failure(user_id, _charged)
        return {"error": "缺少 panorama_url"}

    # 兼容新旧 URL：新格式经 WebDAV 读取，旧格式读本地 assets
    uname = WebDAVService.sanitize_username(username)
    pano_bytes, _ = await _resolve_image_bytes(panorama_url, uname)
    if not pano_bytes:
        await _refund_on_failure(user_id, _charged)
        return {"error": "全景图文件不存在"}

    # 临时文件名加随机后缀，避免并发请求互相覆盖
    tmp_path = _ASSETS_DIR / f"_pano_correct_input_{int(time.time())}_{uuid.uuid4().hex[:6]}.jpg"
    try:
        # 读取全景图，调整到 API 支持的尺寸
        img = Image.open(io.BytesIO(pano_bytes))
        # 调整到 1024x1024 发送给 API（API 支持的尺寸）
        img_sq = img.convert("RGB").resize((1024, 1024), Image.LANCZOS)
        img_sq.save(tmp_path, "JPEG", quality=90)

        try:
            result = await image_service.edit_image(
                image_path=str(tmp_path),
                prompt=(
                    "This is a 360-degree equirectangular panoramic photo that has been "
                    "stitched from 6 cubemap faces. Fix any visible seams, lighting "
                    "inconsistencies, color mismatches, and blending artifacts at the "
                    "edges where different photos meet. Ensure smooth transitions and "
                    "consistent exposure throughout. Keep the same room and content."
                ),
                size="1024x1024",
                quality="high",
                output_format="png",
                timeout=270,
                max_attempts=2,
            )
        finally:
            # 清理临时文件（成功/异常均执行）
            try:
                tmp_path.unlink()
            except OSError:
                pass

        if result.get("success") and result.get("images"):
            img_path = result["images"][0]["path"]
            try:
                # 读取修正后的图，调整回 2:1 全景比例
                corrected = Image.open(img_path).convert("RGB")
                corrected = corrected.resize((2048, 1024), Image.LANCZOS)
                buf = io.BytesIO()
                corrected.save(buf, "PNG")
                out_bytes = buf.getvalue()
            finally:
                # 清理 image_service 临时文件（与 ai_generate/generate_faces 一致）
                try:
                    os.unlink(img_path)
                except OSError:
                    pass
            # 架构改造：直接返回 base64 data URL，不再落盘
            pano_id = f"pano_{uuid.uuid4().hex[:12]}"
            # 历史写入数据库（只存 ID）
            await _save_panorama_history(user_id, {"mode": "ai-correct"}, pano_id)
            out_b64 = base64.b64encode(out_bytes).decode("utf-8")
            return {"image": f"data:image/png;base64,{out_b64}", "id": pano_id}
        else:
            # AI 修正失败，返回原图（原 data URL 原样回传）
            await _refund_on_failure(user_id, _charged)
            return {"image": panorama_url, "corrected": False}

    except Exception:
        # 任何异常都返回原图，不阻塞流程（细节只进日志，不对外泄露）
        logger.exception("ai-correct 处理异常，返回原图")
        await _refund_on_failure(user_id, _charged)
        return {"image": panorama_url, "corrected": False}


# === 静态文件（serve_frontend=True 托管前端；False 为纯 API 模式） ===

if config.serve_frontend:
    @app.get("/")
    async def index():
        """主页面（no-cache 响应头由 add_cache_control 中间件统一设置）。"""
        return FileResponse(str(_STATIC_DIR / "index.html"))
else:
    @app.get("/")
    async def index():
        """纯 API 模式根路由：返回服务标识 JSON（供部署探测）。"""
        return {"ok": True, "service": "lingxi-api", "version": "1.0"}


# 挂载前端静态文件（CSS/JS）— no-cache 响应头由 add_cache_control 中间件统一设置
if config.serve_frontend:
    _STATIC_DIR.mkdir(parents=True, exist_ok=True)
    (_STATIC_DIR / "css").mkdir(exist_ok=True)
    (_STATIC_DIR / "js").mkdir(exist_ok=True)
    app.mount("/static", StaticFiles(directory=str(_STATIC_DIR)), name="static")
    # HTML 使用相对路径（css/xxx, js/xxx, vendor/xxx），需将子目录挂载到根路径
    for _sub in ("css", "js", "vendor", "assets"):
        _sub_dir = _STATIC_DIR / _sub
        if _sub_dir.is_dir():
            app.mount(f"/{_sub}", StaticFiles(directory=str(_sub_dir)), name=f"frontend_{_sub}")
    # config.json 和 favicon.svg 也需根路径路由
    @app.get("/config.json")
    async def _serve_config_json():
        cfg_path = _STATIC_DIR / "config.json"
        if cfg_path.is_file():
            return FileResponse(str(cfg_path), media_type="application/json")
        # 文件不存在时返回默认同源配置，避免 500 错误
        return JSONResponse({"apiBase": ""})
    @app.get("/favicon.svg")
    async def _serve_favicon_svg():
        fav_path = _STATIC_DIR / "favicon.svg"
        if fav_path.is_file():
            return FileResponse(str(fav_path), media_type="image/svg+xml")
        raise HTTPException(404, "favicon not found")

# /uploads 静态挂载改为代理路由：
#   - 两段路径 /uploads/{username}/{filename} → WebDAV（带本地缓存兜底）
#   - 单段路径 /uploads/{filename}           → 本地 assets 旧文件
@app.get("/uploads/{path:path}")
async def serve_upload(path: str, request: Request):
    """按段数分发上传文件访问（防目录穿越；两段路径强制签名校验）。"""
    if ".." in path:
        raise HTTPException(400, "非法路径")
    parts = [p for p in path.split("/") if p != ""]
    if len(parts) == 2:
        username, filename = parts
        if ".." in filename or "/" in filename:
            raise HTTPException(400, "非法路径")
        if not webdav.enabled:
            raise HTTPException(404, "文件不存在")
        # 用户目录文件必须携带有效 ?sig= 签名（url_secret 为空时 verify_signature 恒 True）
        sig = request.query_params.get("sig", "")
        if not webdav.verify_signature(username, filename, sig):
            return JSONResponse({"error": "签名无效"}, status_code=403)
        try:
            content = await webdav.get_file(username, filename)
        except Exception as ex:
            logger.warning("WebDAV 读取失败 %s/%s: %s", username, filename, ex)
            content = None
        if content is None:
            raise HTTPException(404, "文件不存在")
        resp = Response(content, media_type=_ext_mime(filename))
        resp.headers["Cache-Control"] = "public, max-age=86400"
        return resp
    if len(parts) == 1:
        filename = parts[0]
        file_path = _ASSETS_DIR / filename
        if file_path.is_file():
            return FileResponse(str(file_path))
        raise HTTPException(404, "文件不存在")
    raise HTTPException(404, "文件不存在")

# 兼容旧 URL 格式：/xxx.jpg → /uploads/xxx.jpg
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request as StarletteRequest

class _LegacyUrlRedirect(BaseHTTPMiddleware):
    """将 /xxx.ext 的旧格式 URL 重定向到 /uploads/xxx.ext。"""
    # 不需要拦截的路径前缀
    _SKIP_PREFIXES = ("/api/", "/ws/", "/static/", "/uploads/")
    # serve_frontend=True 时额外跳过的前端路径
    _FRONTEND_PREFIXES = ("/css/", "/js/", "/vendor/", "/assets/", "/config.json", "/favicon.svg")

    async def dispatch(self, request: StarletteRequest, call_next):
        path = request.url.path
        # favicon 有专属路由（返回 SVG），不得重定向到 /uploads/
        if path == "/favicon.ico":
            return await call_next(request)
        # 只拦截根路径下的文件请求（如 /upload_xxx.jpg, /generated_xxx.png）
        skip = self._SKIP_PREFIXES
        if config.serve_frontend:
            skip = skip + self._FRONTEND_PREFIXES
        if not any(path.startswith(p) for p in skip):
            if "." in os.path.basename(path):
                # 是文件请求，重定向到 /uploads/
                new_url = request.url.replace(path=f"/uploads{path}")
                return RedirectResponse(url=new_url, status_code=307)
        return await call_next(request)

app.add_middleware(_LegacyUrlRedirect)


# === 启动 ===

# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------


def _get_lan_ip() -> str:
    """获取局域网 IP。"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "0.0.0.0"
    finally:
        s.close()


if __name__ == "__main__":
    _cleanup_old_assets()

    lan_ip = _get_lan_ip()
    print("=" * 55)
    print("  灵犀 (LingXi) — Designer AI Assistant")
    print("  FastAPI + WebSocket + Static Files")
    print("  Local:   http://127.0.0.1:8765")
    print(f"  Network: http://{lan_ip}:8765")
    print("=" * 55)

    uvicorn.run(
        app,
        host="0.0.0.0",
        port=8765,
        log_level="info",
        ws_ping_interval=10,
        ws_ping_timeout=60,
        timeout_keep_alive=30,
    )
