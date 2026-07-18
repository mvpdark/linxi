# 灵犀App UI/CSS优化记录

## 2026-07-17 优化轮次

### 检查范围
- 首页 (`#/`)、聊天页 (`#/chat`)、改图页 (`#/image-edit`)、全景页 (`#/panorama`)
- 桌面端 (1280x800) 和移动端 (375x812) 两种视口
- CSS文件: `src/static/css/style.css` (2507行)
- JS文件: `app.js`, `chat.js`, `image-edit.js`, `panorama.js`
- 控制台错误检查: 全部页面无error/warn

### 发现问题与修复

#### 1. 导航栏安全区域适配不一致 [已修复]
- **问题**: 首页 `.nav-bar` 和改图页 `.ie-nav` 缺少 `env(safe-area-inset-top)` 适配，在刘海屏设备上内容可能被遮挡
- **修复**: 
  - `.nav-bar`: `padding: 6px 14px` -> `padding: calc(6px + env(safe-area-inset-top)) 14px 6px`
  - `.ie-nav`: `padding: 8px 12px` -> `padding: calc(8px + env(safe-area-inset-top)) 12px 8px`
  - `.chat-nav`: 新增 `padding-top: env(safe-area-inset-top)`，`height: 52px` -> `min-height: 52px`

#### 2. 导航栏高度不统一 [已修复]
- **问题**: 各页面导航栏高度不一致（首页~48px、聊天页52px、改图页~52px、全景页~60px）
- **修复**: 所有导航栏统一添加 `min-height: 52px`
  - `.nav-bar`: 新增 `min-height: 52px`
  - `.ie-nav`: 新增 `min-height: 52px`
  - `.pano-nav`: 新增 `min-height: 52px`，调整 padding 从 `12px` 到 `8px`

#### 3. 聊天消息区域顶部padding未适配安全区域 [已修复]
- **问题**: `.chat-messages` 的 `padding: 52px 20px 80px` 未考虑刘海屏安全区域
- **修复**: 改为 `padding: calc(52px + env(safe-area-inset-top)) 20px 80px`

#### 4. 全景页使用废弃CSS属性 [已修复]
- **问题**: `.page-panorama` 使用了已废弃的 `overflow-y: overlay` 和可能导致不必要空白的 `scrollbar-gutter: stable`
- **修复**: 移除 `overflow-y: overlay` 和 `scrollbar-gutter: stable`，保留 `overflow-y: auto`，新增 `-webkit-overflow-scrolling: touch`

#### 5. CSS缓存版本号更新 [已修复]
- **问题**: CSS文件更新后浏览器可能使用缓存版本
- **修复**: `index.html` 中 CSS 版本号从 `v=20260717x` 更新为 `v=20260717u1`

### 验证结果
- 桌面端 (1280x800): 4个页面均正常显示，无控制台错误
- 移动端 (375x812): 4个页面均正常显示，无控制台错误
- 导航栏高度统一为52px，安全区域适配一致
- 各页面布局、间距、对齐、颜色保持一致
- 响应式布局正常（768px断点切换桌面/移动模式）
- 动画和交互反馈正常（卡片stagger入场、上传区呼吸动画、标签弹跳等）
