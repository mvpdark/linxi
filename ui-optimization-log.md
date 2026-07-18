# 灵犀 UI/CSS 持续优化记录

## 2026-07-17 第1轮（版本 v=20260717p）

### 检查方式
- Playwright 无头浏览器（390×844 移动视口，2x DPR）自动截图：首页、聊天页、改图页、全景页
- 控制台错误/页面异常采集、水平溢出与越界元素检测
- 并行代码审查：`style.css` / `chat.js` / `index.html`

### 检查结果
- 4 个页面均无 JS 控制台错误、无水平溢出
- 代码审查发现 1 个 high、6 个 medium、若干 low 级问题

### 本轮修复

**chat.js（功能性修复）**
1. [high] WebSocket 异常断开时 `sending` 状态永久锁定 → `ws.onclose` 中复位 `sending`、Agent 状态面板与表情
2. [medium] `connectWs()` 未判断 CLOSING 状态，可能创建重复 WS 实例 → CLOSING 时改为延迟重连
3. [medium] 图片放大弹窗不支持 Escape 键关闭 → 新增全局 `keydown` 监听，卸载时正确移除
4. [low] 侧边栏快速连续 touchstart 可能残留多个长按定时器 → `onTouchStart` 入口先清理旧 timer

**style.css（样式与可访问性）**
5. [medium] `.card-btn` 的 `background: transparent !important` 使 `index.html` 内联背景色失效 → 移除 `!important`，并重设计为药丸按钮（`align-self: flex-start` + `padding: 5px 12px` + `border-radius: 10px`），首页三卡片按钮现为绿/米/粉彩色药丸
6. [medium] 全局 `user-select: none` 导致 AI 回复无法复制 → 保留全局禁用（防止移动端长按误选），改为 `.msg-bubble` 单独开启 `user-select: text`
7. [medium] 按钮无键盘焦点样式 → 新增全局 `:focus-visible` 描边（`--primary` 2px）
8. [low] `.agent-status` 缺 `-webkit-backdrop-filter`（Safari 兼容）→ 已补
9. [low] 硬编码颜色变量化：`.msg-bubble.user` 渐变改用 `var(--accent-green)`；`.recent-item:active` / `.sidebar-item:active` 改用 `rgba(60,55,50,0.0x)` 体系，替代 `#F5F3F0` / `#F0F0F0`

### 验证
- 修复后重新截图 4 页，无回归；首页药丸按钮视觉确认通过
- `node --check chat.js` 语法通过

### 遗留（下轮候选）
- `--text-secondary` (#9E9EA4) 对比度约 2.5:1，低于 WCAG AA（需设计决策）
- viewport `user-scalable=no` 限制缩放（移动端体验与可访问性权衡）
- 过渡时长/easing 未统一为 token（0.15s~0.35s、5+ 种 easing 混用）
- `#FA5151` / `#2563EB` / `#000` 等仍有硬编码（`--tag-red`/`--tag-blue` 已存在）
- `.nav-icon-btn` 与 `.icon-btn` 样式重复可合并
