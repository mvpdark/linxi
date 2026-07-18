---
name: "unraid-ca-deploy"
description: "通过 Chrome DevTools MCP 操作 Unraid CA 面板部署/更新 Docker 容器。当用户要求重新部署、拉取镜像、更新容器、使用CA面板操作Unraid时调用。严禁使用SSH管理容器。"
---

# Unraid CA 面板容器部署技能

## 核心规则

**严禁使用 SSH 管理容器**。所有容器部署/更新操作必须通过 Chrome 浏览器操作 Unraid CA 面板完成。SSH 仅允许用于读取配置文件（如 config.yaml）获取密钥值，绝不用来执行 docker 命令或编辑容器配置。

## 服务器信息

| 项目 | 值 |
|------|-----|
| Unraid 地址 | `https://192.168.10.8` |
| 用户名 | root |
| 密码 | mvpdarkno1 |
| 容器名 | lingxi-backend |
| 模板名 | my-lingxi-backend.xml |
| 镜像地址 | `ghcr.io/mvpdark/lingxi-api:latest` |
| API 端口 | 8765 |
| 网络类型 | Host |
| 健康检查 | `http://192.168.10.8:8765/api/health` |

## 部署流程

### 第一步：导航到 CA 面板

使用 Chrome DevTools MCP 的 `navigate_page` 工具导航到容器更新页面：

```
URL: https://192.168.10.8/Docker/UpdateContainer?container=lingxi-backend
```

或通过模板方式：
```
URL: https://192.168.10.8/Docker/UpdateContainer?xmlTemplate=user%3A%2Fboot%2Fconfig%2Fplugins%2FdockerMan%2Ftemplates-user%2Fmy-lingxi-backend.xml
```

### 第二步：等待页面加载并获取快照

使用 `take_snapshot` 工具获取页面元素结构，找到各表单字段的 uid。

### 第三步：验证/填写表单字段

使用 `evaluate_script` 工具检查所有输入框的当前值：

```javascript
() => {
  const inputs = document.querySelectorAll('input');
  const result = [];
  inputs.forEach((input, i) => {
    if (input.value && (input.type === 'password' || input.type === 'text')) {
      result.push({
        index: i,
        name: input.name,
        type: input.type,
        value: input.value.substring(0, 50)
      });
    }
  });
  return result;
}
```

### 第四步：填写密钥字段（如有 CHANGE_ME）

从本地 `config.yaml`（`C:\TRAE\SJS\config.yaml`）读取以下值：

| 变量名 | 值 |
|--------|-----|
| JWT_SECRET | `0f3b9d2a7c4e4f8a1b6c5d9e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2` |
| WEBDAV_PASSWORD | `mvpdarkno1` |
| DATABASE_URL | `postgresql+asyncpg://lingxi:mvpdarkno1@127.0.0.1:5432/lingxi` |
| WEBDAV_URL | `http://192.168.10.8:19798/dav` |
| WEBDAV_USERNAME | `lingxi` |
| ADMIN_USERNAME | `admin` |
| BILLING_RATE | `2.0` |
| SERVE_FRONTEND | `false` |
| LLM_API_KEY_1 | `sk-PvFAotuPq2ksXX3R7Pg1JxYle99y15aKGo9FulTzkfdiTRSA` |
| LLM_API_KEY_2 | `sk-HSUmgipyCOIfHZfbVJLrDwkuRoNGON6V3IaWz8O5chgGFDoz` |
| TAVILY_API_KEY_1 | `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` |
| TAVILY_API_KEY_2 | `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` |
| TAVILY_API_KEY_3 | `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` |

使用 `fill` 工具填写字段（通过 uid）：
- 先用 `take_snapshot` 获取目标字段的 uid
- 再用 `fill` 填入正确值

### 第五步：点击"应用"按钮

使用 `evaluate_script` 查找并点击应用按钮：

```javascript
() => {
  const buttons = document.querySelectorAll('button, input[type="submit"]');
  let target = null;
  buttons.forEach((btn) => {
    if (btn.textContent.trim().includes('应用') || 
        (btn.value && btn.value.includes('应用'))) {
      target = btn;
    }
  });
  if (target) {
    target.scrollIntoView({block: 'center'});
    target.click();
    return 'clicked';
  }
  return 'not found';
}
```

### 第六步：验证部署结果

等待页面显示"命令成功完成!"后，使用以下方式验证：

1. **检查容器状态**：导航到 `https://192.168.10.8/Docker`，查看 lingxi-backend 是否显示"已启动"
2. **健康检查**：等待 10 秒后用 Python 检查健康端点：

```python
import time, urllib.request
time.sleep(10)
r = urllib.request.urlopen('http://192.168.10.8:8765/api/health', timeout=10)
print(r.read().decode())
# 预期返回: {"ok":true,"db":"up","webdav":"up"}
```

## 完整环境变量清单

容器启动时需要以下环境变量（通过 CA 面板表单填写）：

| 变量名 | 用途 | 示例值 |
|--------|------|--------|
| DATABASE_URL | PostgreSQL 连接串 | `postgresql+asyncpg://lingxi:mvpdarkno1@127.0.0.1:5432/lingxi` |
| JWT_SECRET | JWT 签名密钥 | `0f3b9d2a7c4e4f8a1b6c5d9e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2` |
| WEBDAV_URL | WebDAV 存储地址 | `http://192.168.10.8:19798/dav` |
| WEBDAV_USERNAME | WebDAV 用户名 | `lingxi` |
| WEBDAV_PASSWORD | WebDAV 密码 | `mvpdarkno1` |
| ADMIN_USERNAME | 管理员用户名 | `admin` |
| BILLING_RATE | 计费汇率（美元→人民币） | `2.0` |
| SERVE_FRONTEND | 是否托管前端 | `false` |
| LLM_API_KEY_1 | yunwu API Key #1 | `sk-PvFAotuPq2ksXX3R7Pg1JxYle99y15aKGo9FulTzkfdiTRSA` |
| LLM_API_KEY_2 | yunwu API Key #2 | `sk-HSUmgipyCOIfHZfbVJLrDwkuRoNGON6V3IaWz8O5chgGFDoz` |
| TAVILY_API_KEY_1 | Tavily 搜索 Key #1 | `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` |
| TAVILY_API_KEY_2 | Tavily 搜索 Key #2 | `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` |
| TAVILY_API_KEY_3 | Tavily 搜索 Key #3 | `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` |
| ASSETS_DIR | 资源缓存目录 | `/app/assets_cache` |
| CORS_ORIGINS | CORS 允许来源 | `tauri://localhost,http://tauri.localhost` |
| LLM_API_BASE | LLM API 地址 | `https://yunwu.ai` |
| LLM_MODEL | LLM 模型 | `gpt-5.6-luna` |
| IMAGE_MODEL | 图像模型 | `gpt-image-2` |

## 路径映射

| 宿主路径 | 容器路径 | 用途 |
|----------|----------|------|
| `/mnt/user/appdata/lingxi/cache` | `/app/assets_cache` | 图片/缓存持久化 |

## 注意事项

1. **网络类型必须为 Host**：因为 PostgreSQL 运行在 127.0.0.1:5432，Host 网络模式下容器可直接访问
2. **密钥字段不可为 CHANGE_ME**：JWT_SECRET 和 WEBDAV_PASSWORD 必须填入真实值
3. **强制更新镜像**：在 CA 面板勾选"强制更新"可拉取最新镜像
4. **等待启动**：容器启动后需等待 10-15 秒让 FastAPI 完成初始化
5. **密钥值来源**：从本地 `C:\TRAE\SJS\config.yaml` 读取，该文件是开发环境的配置源头

## Chrome DevTools MCP 工具使用要点

| 工具 | 用途 |
|------|------|
| `navigate_page` | 导航到指定 URL |
| `take_snapshot` | 获取页面 a11y 树，找到元素的 uid |
| `fill` | 通过 uid 填写表单字段 |
| `click` | 通过 uid 点击元素 |
| `evaluate_script` | 执行 JS 代码（用于查找元素、读取值、点击按钮）|
| `take_screenshot` | 截图用于调试 |

## 快速部署命令

当用户说"重新部署"、"拉取镜像"、"更新容器"时，按以下顺序执行：

1. `navigate_page` → `https://192.168.10.8/Docker/UpdateContainer?container=lingxi-backend`
2. `take_snapshot` → 获取表单字段 uid
3. `evaluate_script` → 检查所有字段值
4. 如有 CHANGE_ME → `fill` 填入正确密钥值
5. `evaluate_script` → 点击"应用"按钮
6. 等待"命令成功完成!"显示
7. `navigate_page` → `https://192.168.10.8/Docker` → 验证容器已启动
8. Python 健康检查 → `http://192.168.10.8:8765/api/health`
