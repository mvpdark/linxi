#!/bin/sh
# ===========================================================================
# 灵犀后端容器启动入口
#   1. 检查 SAM2 权重（sam2.1_hiera_tiny.pt，约 150MB），缺失时自动下载
#      - 设置 SAM2_DOWNLOAD_CKPT=0 可关闭下载（此时请通过 volume 挂载权重）
#      - 下载失败不阻断启动：SAM 分割功能不可用，其余功能正常
#   2. exec 启动主进程（保证信号正确传递，容器可优雅停止）
# ===========================================================================
set -e

CKPT_DIR="/app/src/checkpoints"
CKPT_FILE="$CKPT_DIR/sam2.1_hiera_tiny.pt"
CKPT_URL="https://dl.fbaipublicfiles.com/segment_anything_2/092824/sam2.1_hiera_tiny.pt"

if [ ! -f "$CKPT_FILE" ]; then
    if [ "${SAM2_DOWNLOAD_CKPT:-1}" = "1" ]; then
        echo "[entrypoint] SAM2 权重不存在，开始下载（约 150MB）..."
        echo "[entrypoint]   $CKPT_URL"
        mkdir -p "$CKPT_DIR"
        if curl -fL --retry 3 --connect-timeout 15 -o "$CKPT_FILE" "$CKPT_URL"; then
            echo "[entrypoint] SAM2 权重下载完成"
        else
            echo "[entrypoint] 警告：SAM2 权重下载失败，分割功能将不可用"
            rm -f "$CKPT_FILE"   # 删除可能的不完整文件，避免误加载
        fi
    else
        echo "[entrypoint] SAM2_DOWNLOAD_CKPT=0，跳过权重下载（请确认已通过 volume 挂载权重）"
    fi
else
    echo "[entrypoint] SAM2 权重已存在，跳过下载"
fi

exec "$@"
