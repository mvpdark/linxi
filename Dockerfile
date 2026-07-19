# syntax=docker/dockerfile:1
# ===========================================================================
# 灵犀后端镜像（多租户 SaaS 纯 API：FastAPI + PostgreSQL + WebDAV + JWT）
#
# 构建上下文为【项目根目录】：
#   docker build -t lingxi-backend .
#   或：docker compose up -d --build
#
# 要点：
#   - 纯 API 模式：SERVE_FRONTEND=false 时不托管静态文件
#   - config.yaml 不打入镜像（含真实密钥），全部配置经环境变量注入
#   - 启动流程（docker/entrypoint.sh）：先 ensure_schema 建表，再拉起 uvicorn
#   - SAM2 权重（约 300MB）不入镜像；/api/sam-segment 为可选能力，
#     需要时挂载权重到 /app/src/checkpoints（src/sam2_src/sam2 包已内置）
# ===========================================================================
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

# 运行期系统库（最简集）：
#   libgomp1     - torch（SAM2 可选能力）的 OpenMP 运行库
#   libglib2.0-0 - opencv-python-headless 运行依赖
#   curl         - HEALTHCHECK 探测 /api/health
# 说明：pillow 官方 manylinux wheel 已自带 libjpeg-turbo/zlib，
#       asyncpg 自带预编译 wheel 且不依赖 libpq（自实现协议），均无需额外系统库。
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgomp1 libglib2.0-0 curl \
    && rm -rf /var/lib/apt/lists/*

# 非 root 运行
RUN useradd --create-home --shell /bin/sh lingxi

WORKDIR /app

# 1) 先安装依赖：单独成层，requirements.txt 不变时可命中构建缓存
COPY backend/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

# 2) 复制应用代码（根 .dockerignore 已排除权重、sam2_src 杂物与敏感配置；
#    config.yaml 刻意不复制——密钥不入镜像，运行时全部走环境变量注入）
# 建表/迁移职责划分：docker/entrypoint.sh 中的 ensure_schema 负责首次启动幂等建表，
# alembic 仅供后续高级迁移（手工执行 alembic upgrade head），不参与容器启动流程。
COPY src/ /app/src/
COPY alembic/ /app/alembic/
COPY alembic.ini /app/alembic.ini

# 3) 将 sam2 包暴露到 import 路径（/api/sam-segment 延迟导入时才需要）
#    同时包含 /app/src，使 server.py 的 `from utils.xxx import` 能正确解析
ENV PYTHONPATH=/app/src:/app/src/sam2_src

# 4) 运行期目录（非 root 运行需预先授权）
RUN mkdir -p /app/assets /app/src/checkpoints \
    && chown -R lingxi:lingxi /app

# 5) 启动脚本：ensure_schema 建表后再启动 uvicorn；sed 兼容 Windows CRLF 行尾，
#    chmod 兜底 Windows/git 丢失执行位的情况
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN sed -i 's/\r$//' /app/entrypoint.sh && chmod +x /app/entrypoint.sh

USER lingxi

EXPOSE 8765

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s \
    CMD curl -fsS http://127.0.0.1:8765/api/health || exit 1

ENTRYPOINT ["/app/entrypoint.sh"]
