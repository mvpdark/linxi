# syntax=docker/dockerfile:1
# ===========================================================================
# 灵犀后端镜像（FastAPI + SAM2 CPU 推理）
#
# 构建上下文为【项目根目录】，用法：
#   在项目根目录:  docker build -f backend/Dockerfile -t lingxi-backend .
#   或直接使用编排: cd backend && docker compose up -d --build
#
# SAM2 权重（约 150MB）不打入镜像：
#   默认容器首次启动时自动下载（见 entrypoint.sh，可用 SAM2_DOWNLOAD_CKPT=0 关闭），
#   或在 docker-compose.yml 中改为挂载本地 src/checkpoints 目录。
# ===========================================================================
FROM python:3.10-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

# 运行期系统库：
#   libgomp1     - torch / opencv 的 OpenMP 运行库（CPU 推理必需）
#   libglib2.0-0 - opencv-python-headless 运行依赖
#   curl         - 健康检查 + 启动时下载 SAM2 权重
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgomp1 libglib2.0-0 curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 1) 先安装依赖：单独成层，requirements.txt 不变时可命中构建缓存
COPY backend/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

# 2) 复制应用代码
#    sam2_src 只保留 sam2/ 包本体（含其 configs/*.yaml 模型配置），
#    demo/ notebooks/ sav_dataset/ tools/ training/ 等均不进入镜像
COPY src/__init__.py src/server.py src/config.py /app/src/
COPY src/services      /app/src/services
COPY src/utils         /app/src/utils
COPY src/models        /app/src/models
COPY src/static        /app/src/static
COPY src/sam2_src/sam2 /app/src/sam2_src/sam2

# 3) 将 sam2 包暴露到 import 路径（等价 pip install -e，但跳过 CUDA 扩展编译）
ENV PYTHONPATH=/app/src/sam2_src

# 4) 数据目录（运行时由 volume 挂载覆盖；无挂载时由程序自动创建）
RUN mkdir -p /app/assets /app/cache /app/src/checkpoints

# 5) 启动脚本：权重缺失时自动下载；sed 兼容 Windows CRLF 行尾
COPY backend/entrypoint.sh /app/entrypoint.sh
RUN sed -i 's/\r$//' /app/entrypoint.sh && chmod +x /app/entrypoint.sh

EXPOSE 8765

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8765/ || exit 1

ENTRYPOINT ["/app/entrypoint.sh"]
CMD ["python", "src/server.py"]
