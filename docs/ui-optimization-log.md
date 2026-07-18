# 灵犀 App UI/CSS 优化日志

## 2026-07-17 第一轮优化

### 检查范围
- 首页（移动端 375x812 + 桌面端 1280x800）
- 聊天页（移动端 + 桌面端）
- 改图页（移动端）
- 全景页（移动端 + 桌面端）
- 控制台错误检查（全部页面无 error/warn）

### 修复内容

#### 1. 自定义滚动条样式（style.css）
- **问题**: 桌面端使用浏览器默认滚动条，与 Quiet Materiality 设计风格不搭
- **修复**: 添加 `::-webkit-scrollbar` 自定义样式（8px 宽、圆角 thumb、暖灰色），同时支持 Firefox `scrollbar-width: thin`
- **影响范围**: 所有桌面端页面

#### 2. 全景页导航栏宽度不一致（style.css）
- **问题**: `.page-panorama` 的 `overflow-y: auto` 产生滚动条，导致 `.pano-nav` 宽度比视口窄 15px
- **修复**: 添加 `overflow-y: overlay` 和 `scrollbar-gutter: stable`，预留滚动条空间避免布局偏移
- **影响范围**: 全景页

#### 3. 主按钮颜色统一（style.css）
- **问题**: `.pano-action-btn` 使用 `--accent-green` (#8FB98F)，而 `.ie-btn-primary` 和 `.chat-send-btn` 使用 `--primary` (#7A9B7A)，同系功能按钮颜色不一致
- **修复**: 统一 `.pano-action-btn` 背景色为 `--primary`，添加 `:active { transform: scale(0.97) }` 交互反馈
- **影响范围**: 全景页操作按钮

#### 4. 首页 recent-item 桌面端 hover 效果（style.css）
- **问题**: `.recent-item` 仅有 `:active` 效果，桌面端缺少 hover 反馈
- **修复**: 添加 `@media (min-width: 768px)` 下的 hover 样式（translateY + box-shadow）
- **影响范围**: 首页最近记录列表

#### 5. 聊天输入栏内联样式迁移（style.css + chat.js）
- **问题**: `.chat-input-bar` 使用内联 `style="flex-direction: column; align-items: stretch; gap: 0;"`，不利于维护
- **修复**: 新增 `.chat-input-bar.is-column` CSS 类，chat.js 中替换内联样式为类名
- **影响范围**: 聊天页输入栏

#### 6. 桌面端聊天内容居中（style.css）
- **问题**: 桌面端聊天区域宽 1224px，消息气泡和输入栏贴左/右边缘，视觉不均衡
- **修复**: 添加 `@media (min-width: 900px)` 规则，为 `.chat-messages`、`.chat-input-bar`、`.chat-nav` 设置 `max(20px, calc((100% - 760px) / 2))` 的左右 padding，实现内容居中
- **影响范围**: 聊天页桌面端

#### 7. 首页桌面端 Icon Rail 导航（index.html + app.js + style.css）
- **问题**: 桌面端聊天页有 56px icon-rail 侧边栏，但首页没有，跨页面体验不一致
- **修复**:
  - app.js: 添加 `isDesktop` 响应式属性和 resize 监听
  - index.html: 首页添加 icon-rail（logo、首页、对话、改图、全景、设置按钮），`v-if="isDesktop"` 条件渲染
  - 复用已有 icon-rail CSS，移动端自动隐藏
- **影响范围**: 首页桌面端

### 验证结果
- 移动端（375x812）: 首页、聊天、改图、全景页面布局正确，icon-rail 隐藏
- 桌面端（1280x800）: 首页 icon-rail 显示，聊天内容居中（padding 232px = (1224-760)/2）
- 全景页: 滚动条不再导致导航栏宽度偏移
- 全部页面: 无 JavaScript 错误或控制台警告

### 涉及文件
- `src/static/css/style.css` — 滚动条、hover、颜色统一、居中布局、is-column 类
- `src/static/js/chat.js` — 内联样式迁移为 CSS 类
- `src/static/js/app.js` — isDesktop 响应式属性
- `src/static/index.html` — 首页 icon-rail 导航

### 后续待优化项
- 改图页、全景页桌面端缺少 icon-rail 导航（需修改对应 Vue 组件）
- 桌面端首页 recent-section 可考虑增加 max-width 约束
- 消息气泡在超宽屏（>1600px）下的最佳宽度可能需要进一步调整
