# 灵犀原生客户端重写方案

## 技术决策

| 项目 | 选型 | 理由 |
|------|------|------|
| 跨端框架 | Compose Multiplatform (CMP) | 一套 Kotlin 代码 → Android APK + Windows MSI |
| 网络层 | Ktor Client (多平台) + OkHttp(Android)/Java(Desktop) | 统一 HTTP + WebSocket |
| 依赖注入 | Koin 4.x (KMP) | Hilt 不支持 KMP |
| 导航 | Navigation Compose for Multiplatform | 官方支持 |
| 存储 | DataStore (token) + Room KMP (缓存) | 已有 KMP 版本 |
| 图片加载 | Coil 3 (全 KMP) | 原生支持 CMP |
| 状态管理 | StateFlow + Compose State + KMP ViewModel | 响应式统一 |

**核心策略**：KMP 共享全部业务逻辑 + Compose Multiplatform 共享全部 UI，Android 与 Desktop 仅入口和平台能力用 expect/actual 分流。

## 项目结构

```
lingxi-clients/
├── shared/                    # 共享模块 (KMP + CMP)
│   └── src/
│       ├── commonMain/        # 95% 代码：网络/存储/Repository/ViewModel/UI
│       ├── androidMain/       # Android 平台实现
│       └── desktopMain/       # Desktop 平台实现
├── androidApp/                # Android 入口
└── desktopApp/                # Desktop 入口
```

## 后端 API（47个端点，无需改动）

### 认证
- POST /api/auth/login - 登录
- POST /api/auth/refresh - 刷新token
- GET /api/auth/me - 用户信息
- POST /api/auth/register - 注册
- POST /api/auth/logout - 登出
- POST /api/auth/change-password - 改密
- GET /api/auth/admin/users - 用户列表(admin)
- POST /api/auth/admin/approve - 审批(admin)
- POST /api/auth/admin/suspend - 封禁(admin)
- POST /api/auth/admin/activate - 解封(admin)

### 会话管理
- GET /api/sessions - 列表
- POST /api/sessions - 创建
- DELETE /api/sessions/{id} - 删除
- GET /api/sessions/{id}/history - 历史
- POST /api/sessions/{id}/pin - 置顶
- POST /api/sessions/{id}/unpin - 取消置顶
- POST /api/sessions/{id}/rename - 重命名

### 图片与改图
- POST /api/upload - 上传
- POST /api/image-edit - 直接改图
- POST /api/vlm-detect - VLM检测
- POST /api/sam-segment - SAM2分割
- POST /api/image-edit-annotated - 标注改图

### 计费
- GET /api/billing/summary - 摘要
- POST /api/billing/charge - 扣款
- GET /api/billing/keys - Key用量
- POST /api/billing/init-baseline - 初始化基线

### 全景
- GET /api/panorama/history - 历史
- POST /api/panorama/stitch - 拼接
- POST /api/panorama/generate-faces - 生成六面
- POST /api/panorama/ai-generate - AI生成
- POST /api/panorama/ai-correct - AI修正

### 其他
- POST /api/memory/clear - 清除记忆
- GET /api/memory/profile - 用户画像
- GET /api/search/usage - 搜索用量

### WebSocket
- WS /ws/chat - 流式聊天（首帧鉴权）

## 开发阶段

### 阶段1：共享骨架打通 (Android)
- Monorepo 骨架 + 版本目录
- Ktor ApiClient + JWT 拦截器
- AuthRepository + LoginScreen
- WebSocketClient + ChatScreen（流式）
- Navigation 骨架

### 阶段2：Desktop 端接入
- desktopApp 入口 + Koin
- expect/actual 平台层
- 打包验证 (.msi)

### 阶段3：核心功能补全
- JWT 自动刷新
- 会话管理 + Room 缓存
- 图像编辑全链路 + Canvas 编辑器

### 阶段4：体验完善
- 灵感库 + 计费 UI
- 全景图功能
- 主题精细化

### 阶段5：发布
- Android: APK 自更新
- Desktop: 全局快捷键/托盘
- 签名发布

### 阶段6：旧前端下线
- 下线 frontend-capacitor + frontend-tauri
- 后端 serve_frontend=false
