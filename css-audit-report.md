# 灵犀 (LingXi) style.css UI 样式审计报告

文件路径：`C:\TRAE\SJS\src\static\css\style.css`（共 2072 行）

---

## 一、颜色变量、字体、间距统一性问题

### 1.1 冗余/重复变量（P1 - 高优先级）

| 问题 | 位置 | 说明 |
|------|------|------|
| `--bg-warm` 与 `--bg-main` 值相同 | 第7-8行 | 两个变量都是 `#F8F6F3`，应删除其中一个或语义区分 |
| `--font-family` 已定义但未被使用 | 第24行 vs 第37行 | `:root` 中定义了字体变量，但 `html, body, #app` 中硬编码了字体栈，未使用 `var(--font-family)` |

### 1.2 大量硬编码颜色值（P1 - 高优先级）

以下颜色值在代码中反复硬编码，未通过变量管理：

**中性色硬编码（出现频率最高）：**
- `rgba(60,55,50,x)` —— 阴影/边框/背景中出现 **20+ 次**，应提取为 `--overlay-*` 系列变量
- `rgba(0,0,0,x)` —— 出现 **30+ 次**，应统一为变量
- `rgba(255,255,255,x)` —— 出现 **15+ 次**
- `#FFFFFF` / `white` —— 出现 **20+ 次**，应增加 `--bg-white: #FFFFFF`
- `#000` / 纯黑 —— 出现 **10+ 次**，用于深色页面背景

**功能色硬编码：**
- `#FA5151`（错误红）—— 第629、997、1000、1016行，与 `--accent-red: #D4736E` 不一致，存在两个不同的红色系
- `#2563EB`（选中蓝）—— 第1006-1008、1026行，未定义变量，是突然出现的蓝色
- `#D4C4B0`/`#C4B4A0`（用户头像渐变）—— 第508行，硬编码
- `#8FB98F`/`#7DA87D`（用户气泡渐变）—— 第530行，`#8FB98F` 虽与 `--accent-green` 相同但 `#7DA87D` 硬编码
- `#F5F3F0`（recent-item active背景）—— 第241行
- `#F0F0F0`（sidebar-item active背景）—— 第308行
- `#F7F7F7`（ie-bottom-bar背景）—— 第1103行
- `#8E8E93` / `#1C1C1E`（icon-rail颜色）—— 第1924、1931、1936、1953、1959行，iOS系统色硬编码
- `#9BB89B`（logo渐变）—— 第1899行

### 1.3 间距系统缺失（P1 - 高优先级）

当前 padding/margin/gap 值混乱，缺少 4px 基准的间距刻度：

| 当前使用的值 | 问题 |
|-------------|------|
| 1px, 2px, 4px, 6px, 8px, 10px, 12px, 14px, 16px, 20px, 24px, 32px, 40px | 建议统一为 4px 的倍数：4/8/12/16/20/24/32/40/48/64 |
| 同一模块左右padding不一致 | `.brand-section` 用 `24px`，`.cards-container` 用 `20px`，`.recent-section` 用 `20px`，`.recent-list` 用 `16px` |

### 1.4 圆角值不统一（P2 - 中优先级）

| 组件 | 圆角值 |
|------|--------|
| `.feature-card` | 22px (`--radius-card`) |
| `.msg-bubble` | 20px (`--radius-bubble`) |
| `.recent-empty`, `.recent-item`, `.ie-upload-area`, `.ie-preview`, `.ie-result`, `.pano-mode-card`, `.pano-history-item`, `.pano-upload-slot` 等 | 16px |
| `.new-chat-btn`, `.ie-submit`, `.pano-action-btn`, `.agent-status` | 14px |
| `.sidebar-clear-btn`, `.nav-icon-btn`, `.icon-btn`, `.ie-input`, `.ie-btn`, `.pano-upload-slot`, `.pano-floorplan-upload`, `.pano-gen-upload`, `.pano-camera-container` | 12px |
| `.card-icon-circle`, `.v360-btn`, `.pano-progress-item`, `.pano-gen-style textarea` | 16px/8px/10px |
| 多个圆形按钮 | 硬编码为宽度一半（17px/18px/19px）而非 `50%` |

建议扩展圆角变量体系：
```css
:root {
  --radius-sm: 8px;
  --radius-md: 12px;
  --radius-lg: 16px;
  --radius-xl: 20px;
  --radius-2xl: 22px;
  --radius-full: 9999px; /* 用于胶囊/圆形 */
}
```

### 1.5 阴影硬编码（P2 - 中优先级）

已定义 `--shadow-soft`、`--shadow-medium`、`--shadow-inner`，但以下位置仍硬编码阴影：
- 第280行 `4px 0 32px rgba(60,55,50,0.08)`（sidebar阴影）
- 第374行 `0 2px 8px rgba(122,155,122,0.2)`（new-chat-btn阴影）
- 第504行 `0 1px 4px rgba(122,155,122,0.25)`（ai头像阴影）
- 第533行 `0 2px 8px rgba(122,155,122,0.2)`（user气泡阴影）
- 第703行 `0 1px 4px rgba(122,155,122,0.25)`（发送按钮阴影）
- 第818行 `drop-shadow(0 1px 6px rgba(60,55,50,0.12))`（头像emoji阴影）
- 第1515行 `0 4px 16px rgba(122,155,122,0.3)`（toast阴影）
- 第1590行 `0 2px 12px rgba(60,55,50,0.06)`（pano卡片阴影）
- 第1630行 `0 2px 10px rgba(60,55,50,0.05)`（pano历史项阴影）
- 第1891行 `2px 0 16px rgba(60,55,50,0.06)`（icon-rail阴影）
- 第1979行 `4px 0 24px rgba(60,55,50,0.06)`（expand-panel阴影）

---

## 二、响应式设计问题

### 2.1 缺少断点体系（P1 - 高优先级）

当前只有一个断点 `768px`，缺少完整的响应式分层：
- 无小屏手机适配（<375px）
- 无平板横屏适配（1024px）
- 无桌面宽屏适配（1280px+，内容宽度限制）
- 无超宽屏适配（1440px+，最大宽度约束）

建议：
```css
/* 断点变量（通过媒体查询使用） */
@custom-media --sm (min-width: 480px);   /* 大手机 */
@custom-media --md (min-width: 768px);   /* 平板 */
@custom-media --lg (min-width: 1024px);  /* 小桌面 */
@custom-media --xl (min-width: 1280px);  /* 桌面 */
```

### 2.2 首页无桌面端布局（P1 - 高优先级）

`.page-home`、`.cards-container`、`.feature-card`、`.brand-section`、`.recent-section` 全部没有桌面端媒体查询。在桌面浏览器上：
- 功能卡片会被拉得非常宽
- 内容占满全屏宽度，阅读体验差
- 品牌区标题 30px 在大屏上显得过小

建议在 >=768px 时：
```css
@media (min-width: 768px) {
  .page-home {
    max-width: 720px;
    margin: 0 auto;
  }
  .cards-container {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: 16px;
  }
}
```

### 2.3 聊天气泡桌面端过宽（P1 - 高优先级）

`.msg-bubble` 设置了 `max-width: 100%`（第518行），在桌面端消息气泡会撑满几乎整个屏幕宽度，严重影响阅读体验。建议：
```css
.msg-bubble {
  max-width: min(70%, 480px); /* 桌面端70%且不超过480px */
}
@media (max-width: 480px) {
  .msg-bubble { max-width: 85%; } /* 手机端适当放宽 */
}
```

### 2.4 改图页/全景页/标注页桌面端适配不足（P2 - 中优先级）

- `.page-image-edit` 在桌面端仅有 `margin-left: 56px`，内部内容仍占满宽度
- `.page-panorama` 没有桌面端内容宽度约束
- `.page-annotate` 和 `.page-view-360` 完全无响应式适配

### 2.5 缺少深色模式支持（P2 - 中优先级）

整个样式表没有 `@media (prefers-color-scheme: dark)` 支持。虽然这是暖色调设计系统，但应提供深色模式变量覆盖。

### 2.6 侧边栏响应式问题（P3 - 低优先级）

- 移动端侧边栏 `max-width: 80vw` 在横屏平板上可能显得过窄
- 桌面端 expand-panel 展开时，`.page-chat` 的 margin-left 通过 JS 设置内联样式（`style*="336px"`选择器，第2037行），这是hack做法

---

## 三、组件样式问题

### 3.1 按钮样式不统一（P1 - 高优先级）

存在至少 **8 种不同按钮样式**，缺少统一的按钮基底类：

| 按钮类 | 圆角 | 内边距 | 字号 | 字重 |
|--------|------|--------|------|------|
| `.new-chat-btn` | 14px | 12px 16px | 15px | 500 |
| `.ie-submit` | 14px | 14px | 16px | 600 |
| `.ie-btn` | 12px | 12px 20px | 15px | 600 |
| `.ie-bottom-send` | 18px | 0 18px (h:36px) | 15px | 600 |
| `.pano-action-btn` | 14px | 14px | 16px | 600 |
| `.v360-btn` | 8px | 8px 16px | 14px | 500 |
| `.chat-send-btn` | 17px(圆形) | 34px圆 | - | - |
| `.sidebar-clear-btn` | 12px | 10px 14px | 13px | - |

建议建立统一按钮系统：
```css
/* 按钮基底 */
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: none;
  border-radius: var(--radius-md);
  font-family: inherit;
  font-weight: 600;
  cursor: pointer;
  transition: transform 0.15s, box-shadow 0.15s, background 0.15s;
  -webkit-tap-highlight-color: transparent;
}
.btn:active { transform: scale(0.97); }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }

/* 尺寸变体 */
.btn-sm { padding: 8px 14px; font-size: 13px; border-radius: var(--radius-sm); }
.btn-md { padding: 12px 18px; font-size: 15px; border-radius: var(--radius-md); }
.btn-lg { padding: 14px 20px; font-size: 16px; border-radius: var(--radius-lg); }

/* 颜色变体 */
.btn-primary { background: var(--primary); color: white; box-shadow: 0 2px 8px rgba(122,155,122,0.2); }
.btn-secondary { background: rgba(60,55,50,0.06); color: var(--text-primary); }
.btn-danger { background: rgba(212,115,110,0.08); color: var(--accent-red); }
.btn-ghost { background: transparent; color: var(--text-secondary); }

/* 胶囊按钮 */
.btn-pill { border-radius: var(--radius-full); }

/* 图标按钮（圆形） */
.btn-icon {
  width: 36px; height: 36px;
  padding: 0;
  border-radius: 50%;
}
```

### 3.2 输入框样式不统一（P1 - 高优先级）

存在 **5 种不同输入框样式**：

| 输入框类 | 圆角 | 边框 | 背景 | 焦点样式 |
|----------|------|------|------|----------|
| `.chat-input` | 20px | 无 | `--bg-cream` + inner shadow | box-shadow 环形光晕 |
| `.ie-input` | 16px | 1px solid rgba(0,0,0,0.08) | `#FFFFFF` | 无焦点样式（缺失！） |
| `.ie-prompt-input` | 12px | 1px solid rgba(0,0,0,0.08) | `#FFFFFF` | border-color 变化 |
| `.ie-bottom-input` | 18px | 1px solid rgba(0,0,0,0.08) | `#FFFFFF` | border-color 变化 |
| `.pano-gen-style textarea` | 10px | 1px solid rgba(60,55,50,0.1) | 未指定 | 无焦点样式 |

关键问题：
1. `.ie-input` 完全缺少 `:focus` 样式
2. 焦点指示不一致（有的用box-shadow ring，有的用border-color）
3. 背景色不统一（有的用 `--bg-cream`，有的用硬编码 `#FFFFFF`）
4. 圆角值差异大（10px-20px）

建议统一输入框基底：
```css
.input {
  width: 100%;
  padding: 10px 16px;
  border: 1px solid rgba(60,55,50,0.08);
  border-radius: var(--radius-lg);
  background: var(--bg-cream);
  font-size: 15px;
  line-height: 1.5;
  font-family: inherit;
  outline: none;
  resize: none;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.input:focus {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px rgba(122,155,122,0.12);
}
.input::placeholder { color: var(--text-secondary); opacity: 0.7; }
```

### 3.3 圆形按钮使用硬编码半径（P2 - 中优先级）

以下圆形按钮使用了硬编码的 `width/2` 作为圆角值，应统一改为 `border-radius: 50%`：

| 位置 | 选择器 | 宽高 | 硬编码圆角 |
|------|--------|------|-----------|
| 第433行 | `.nav-btn` | 34px | 17px |
| 第652行 | `.chat-input-btn` | 34px | 17px |
| 第695行 | `.chat-send-btn` | 34px | 17px |
| 第791行 | `.image-modal-close` | 36px | 18px |
| 第1015行 | `.ie-object-num` | 18px | 9px |
| 第1191行 | `.anno-tool` | 38px | 19px |
| 第1212行 | `.anno-color-dot` | 20px | 10px |
| 第1280行 | `.back-btn` | 36px | 18px |
| 第1292行 | `.menu-btn` | 36px | 18px |
| 第1304行 | `.user-btn` | 36px | 18px |

### 3.4 重复的工具按钮类（P2 - 中优先级）

第1277-1311行，`.back-btn`、`.menu-btn`、`.user-btn` 三个类的样式 **完全相同**：
```css
.back-btn, .menu-btn, .user-btn {
  width: 36px;
  height: 36px;
  border-radius: 18px;  /* 应改为50% */
  background: rgba(0,0,0,0.05);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: var(--text-primary);
}
```
应合并为一个通用类，如 `.icon-btn`（已存在于第2043行，但样式略有不同）或 `.btn-icon`。

### 3.5 导航按钮样式不一致（P2 - 中优先级）

存在三种导航图标按钮样式：
- `.nav-icon-btn` / `.nav-avatar`：36px，圆角12px，背景 `rgba(60,55,50,0.04)`
- `.nav-btn`：34px，圆角17px（圆形），背景 `rgba(60,55,50,0.04)`
- `.icon-btn`（chat-nav中）：36px，圆角12px，背景 `rgba(60,55,50,0.04)`

问题：`.nav-btn` 是圆形（34px/17px），而其他两个是圆角方形（36px/12px），尺寸和形状不一致。

### 3.6 卡片背景色不统一（P2 - 中优先级）

- `.feature-card`、`.recent-item`、`.recent-empty` 使用 `var(--bg-cream)` (#FEFDFB)
- `.pano-mode-card`、`.pano-history-item`、`.pano-upload-slot` 使用硬编码 `white` / `#FFFFFF`
- `.ie-upload-zone` 使用 `var(--bg-cream)`

应统一卡片背景色为 `--bg-cream` 或定义 `--bg-card` 变量。

### 3.7 头像尺寸体系混乱（P3 - 低优先级）

| 头像类 | 尺寸 | 形状 |
|--------|------|------|
| `.msg-avatar` | 28px | 圆形 |
| `.msg-avatar-emoji` | 56px | 圆形 |
| `.agent-avatar-emoji` | 28px | 无圆角（继承） |
| `.card-icon-circle` | 48px | 16px圆角方形 |
| `.nav-avatar` | 36px | 12px圆角方形 |

建议定义头像尺寸变量：`--avatar-sm: 28px; --avatar-md: 36px; --avatar-lg: 48px; --avatar-xl: 56px;`

---

## 四、动画和过渡效果问题

### 4.1 重复的旋转动画（P2 - 中优先级）

三个完全相同的旋转动画：
- 第596行 `@keyframes spin`
- 第969行 `@keyframes ieSpin`
- 第1841行 `@keyframes pano-spin`

应统一为一个 `@keyframes spin`。

### 4.2 过渡时长不统一（P2 - 中优先级）

过渡时长值包括：`0.15s`、`0.2s`、`0.25s`、`0.3s`、`0.35s`，建议统一为有限的几种：
```css
:root {
  --duration-fast: 0.15s;
  --duration-base: 0.2s;
  --duration-slow: 0.3s;
  --ease-out: cubic-bezier(0.32, 0.72, 0, 1);
  --ease-bounce: cubic-bezier(0.34, 1.56, 0.64, 1);
  --ease-standard: ease;
}
```

### 4.3 缓动函数硬编码（P3 - 低优先级）

同一种弹性缓动 `cubic-bezier(0.32, 0.72, 0, 1)` 出现约10次，`cubic-bezier(0.34, 1.56, 0.64, 1)` 出现3次，应提取为变量。

### 4.4 桌面端缺少 hover 效果（P2 - 中优先级）

当前交互几乎只有 `:active` 状态（移动端触摸），桌面端需要补充 `:hover` 状态：
- `.feature-card:hover` — 轻微上浮 + 阴影增强
- 按钮 hover 效果
- 可点击项 hover 背景变化

```css
@media (hover: hover) {
  .feature-card:hover {
    transform: translateY(-2px);
    box-shadow: var(--shadow-medium);
  }
  .btn-primary:hover {
    box-shadow: 0 4px 12px rgba(122,155,122,0.25);
  }
}
```

### 4.5 `.sidebar-overlay` 默认状态问题（P2 - 中优先级）

第254-262行，`.sidebar-overlay` 默认 `opacity: 1; pointer-events: auto;`，意味着遮罩层默认是显示的。应该默认隐藏，通过 `.open` 类控制：
```css
.sidebar-overlay {
  opacity: 0;
  pointer-events: none;
  /* ...其他样式... */
}
.sidebar-overlay.open {
  opacity: 1;
  pointer-events: auto;
}
```

### 4.6 `!important` 使用（P2 - 中优先级）

两处使用了 `!important`：
- 第176行 `.card-btn { background: transparent !important; }` —— 应通过提高选择器优先级解决
- 第2009行 `.page-chat { margin-left: 0 !important; }` —— 应重构选择器逻辑避免important

---

## 五、其他CSS问题

### 5.1 z-index 管理混乱（P2 - 中优先级）

当前 z-index 值：10、20、50、60、120、130、140、150、160、200、210、300，没有分层体系。建议定义z-index刻度：
```css
:root {
  --z-nav: 100;
  --z-sidebar: 150;
  --z-sidebar-overlay: 140;
  --z-icon-rail: 140;
  --z-expand-panel: 130;
  --z-chat-nav: 120;
  --z-input-bar: 20;
  --z-modal: 200;
  --z-toast: 300;
}
```

### 5.2 全局 `user-select: none` 影响体验（P2 - 中优先级）

第41行 `user-select: none; -webkit-user-select: none;` 应用于 `html, body, #app`，导致用户无法选择消息气泡中的文字内容。虽然 input/textarea 已排除，但消息文本、卡片文字等可读内容也被禁止选择。建议：
```css
body {
  user-select: none; /* 保持默认禁止 */
}
.msg-bubble, .card-title, .card-desc, .recent-title,
.sidebar-item-title, .pano-mode-card p, .brand-title {
  user-select: text;
  -webkit-user-select: text;
}
```

### 5.3 "补充缺失的CSS类"区域组织混乱（P3 - 低优先级）

第600-633行标记为"补充缺失的CSS类"，包含 `.msg-bubble-col`、`.sidebar-item-pin`、`.recent-item-title`、`.recent-item-time`、`.ie-error`，这些类分散在文件中，应归入对应的组件区块。

### 5.4 重复的上传区域类（P3 - 低优先级）

- `.ie-upload-area`（第864行）与 `.ie-upload-zone`（第1459行）是两个功能相同但样式有差异的上传区域，应统一。

### 5.5 无障碍访问缺失（P2 - 中优先级）

- 缺少 `:focus-visible` 样式，键盘导航用户看不到焦点指示
- 按钮缺少 `outline` 处理后的替代焦点样式
- 建议添加：
```css
button:focus-visible,
a:focus-visible,
[role="button"]:focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: 2px;
}
```

### 5.6 硬编码的导航栏高度（P3 - 低优先级）

第473行 `.chat-messages` 使用 `padding-top: 56px` 来避开固定导航栏。如果导航栏高度变化，需要同步修改。建议使用CSS变量：
```css
:root { --nav-height: 52px; }
.chat-messages { padding-top: calc(var(--nav-height) + 4px); }
```

### 5.7 滚动条样式未定制（P3 - 低优先级）

在桌面端，默认滚动条样式与暖色调设计不匹配。建议添加：
```css
::-webkit-scrollbar { width: 6px; }
::-webkit-scrollbar-track { background: transparent; }
::-webkit-scrollbar-thumb {
  background: rgba(60,55,50,0.15);
  border-radius: 3px;
}
::-webkit-scrollbar-thumb:hover {
  background: rgba(60,55,50,0.25);
}
```

---

## 六、问题优先级汇总与修改方案

### P0 - 必须立即修复（影响功能/体验一致性）

1. **修复硬编码颜色值** — 将 `rgba(60,55,50,x)` 系列、`rgba(0,0,0,x)` 系列、`#FA5151`、`#2563EB` 等提取为变量
2. **统一输入框焦点样式** — 为 `.ie-input` 添加 `:focus` 样式，统一所有输入框的焦点指示
3. **修复 `.sidebar-overlay` 默认状态** — 改为默认隐藏，通过 `.open` 类显示
4. **修复聊天气泡最大宽度** — 桌面端限制为 `max-width: min(70%, 480px)`

### P1 - 高优先级（影响设计一致性）

5. **完善CSS变量体系** — 补充 `--bg-white`、`--bg-card`、`--overlay-*`、`--error`、`--info/blue`、`--radius-*`、`--spacing-*`、`--duration-*`、`--ease-*`、`--z-*` 等变量
6. **消除重复代码** — 合并 `.back-btn`/`.menu-btn`/`.user-btn`，统一 `@keyframes spin`，使用 `--font-family` 变量
7. **圆形按钮统一使用 `border-radius: 50%`**
8. **首页桌面端布局适配** — 最大宽度限制 + 卡片网格布局
9. **建立按钮基底类系统** — `.btn` + 尺寸/颜色变体
10. **建立输入框基底类系统** — `.input` + 焦点/尺寸变体

### P2 - 中优先级（提升体验与质量）

11. **添加桌面端 `:hover` 交互效果**
12. **统一卡片背景色** — 使用 `--bg-cream` 或 `--bg-card`
13. **统一阴影使用变量** — 补充 `--shadow-colored`（主题色阴影）、`--shadow-sidebar` 等
14. **统一过渡时长和缓动函数为变量**
15. **完善响应式断点** — 增加 480px/1024px/1280px 断点
16. **添加深色模式支持** — `@media (prefers-color-scheme: dark)`
17. **修复全局 `user-select: none`** — 允许选择文本内容
18. **z-index 分层管理**
19. **添加 `:focus-visible` 无障碍焦点样式**
20. **消除 `!important` 使用**
21. **添加桌面端滚动条样式定制**
22. **改图页/全景页桌面端内容宽度约束**

### P3 - 低优先级（代码整洁与维护性）

23. **导航按钮尺寸/形状统一**
24. **头像尺寸体系化**
25. **CSS代码组织整理** — 将散落的类归入对应组件区块
26. **上传区域类统一**
27. **导航栏高度CSS变量化**
28. **补充圆角和间距变量体系**
29. **间距系统规范化（4px倍数）**

---

## 七、推荐的完整 :root 变量重构方案

```css
:root {
  /* 背景色 */
  --bg-warm: #F8F6F3;
  --bg-main: #F8F6F3;          /* 考虑合并为 --bg-warm */
  --bg-chat: #F2F0ED;
  --bg-cream: #FEFDFB;
  --bg-white: #FFFFFF;
  --bg-card: #FEFDFB;          /* 卡片背景，同 bg-cream */

  /* 文字色 */
  --text-primary: #2C2C2E;
  --text-secondary: #9E9EA4;
  --text-tertiary: #C4C4C8;

  /* 主题色 */
  --primary: #7A9B7A;
  --primary-light: rgba(122,155,122,0.12);
  --accent-orange: #C4A484;
  --accent-green: #8FB98F;
  --accent-red: #D4736E;
  --error: #FA5151;            /* 统一错误色 */
  --info: #2563EB;             /* 统一信息蓝 */

  /* 覆盖层（统一 rgba(60,55,50,x) 系列） */
  --overlay-4: rgba(60,55,50,0.04);
  --overlay-5: rgba(60,55,50,0.05);
  --overlay-6: rgba(60,55,50,0.06);
  --overlay-8: rgba(60,55,50,0.08);
  --overlay-10: rgba(60,55,50,0.10);
  --overlay-15: rgba(60,55,50,0.15);

  /* 圆角 */
  --radius-sm: 8px;
  --radius-md: 12px;
  --radius-lg: 16px;
  --radius-xl: 20px;
  --radius-2xl: 22px;
  --radius-card: 22px;
  --radius-bubble: 20px;
  --radius-full: 9999px;

  /* 间距（4px基准） */
  --space-1: 4px;
  --space-2: 8px;
  --space-3: 12px;
  --space-4: 16px;
  --space-5: 20px;
  --space-6: 24px;
  --space-8: 32px;
  --space-10: 40px;

  /* 阴影 */
  --shadow-soft: 0 1px 8px rgba(60,55,50,0.04), 0 2px 16px rgba(60,55,50,0.03);
  --shadow-medium: 0 4px 24px rgba(60,55,50,0.06);
  --shadow-inner: inset 0 1px 3px rgba(60,55,50,0.05);
  --shadow-primary: 0 2px 8px rgba(122,155,122,0.2);
  --shadow-sidebar: 4px 0 32px rgba(60,55,50,0.08);

  /* 动画 */
  --duration-fast: 0.15s;
  --duration-base: 0.2s;
  --duration-slow: 0.3s;
  --ease-out: cubic-bezier(0.32, 0.72, 0, 1);
  --ease-bounce: cubic-bezier(0.34, 1.56, 0.64, 1);

  /* 字体 */
  --font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto,
    "Helvetica Neue", Arial, "Noto Sans SC", sans-serif;

  /* z-index */
  --z-nav: 100;
  --z-sidebar-overlay: 140;
  --z-icon-rail: 140;
  --z-sidebar: 150;
  --z-modal: 200;
  --z-toast: 300;

  /* 布局 */
  --nav-height: 52px;
  --sidebar-width: 280px;
  --icon-rail-width: 56px;
  --max-content-width: 720px;
}
```

---

## 八、关键CSS修改示例

### 修复1：字体变量使用
```css
/* 修改前 */
html, body, #app {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, "Noto Sans SC", sans-serif;
}
/* 修改后 */
html, body, #app {
  font-family: var(--font-family);
}
```

### 修复2：圆形按钮统一
```css
/* 修改前 - 以 .nav-btn 为例 */
.nav-btn {
  width: 34px;
  height: 34px;
  border-radius: 17px;
}
/* 修改后 */
.nav-btn {
  width: 36px; /* 统一为36px */
  height: 36px;
  border-radius: 50%;
}
```

### 修复3：输入框统一焦点样式
```css
.ie-input {
  /* ...existing... */
  transition: border-color 0.2s, box-shadow 0.2s;
}
.ie-input:focus {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px rgba(122,155,122,0.12);
}
```

### 修复4：聊天气泡宽度限制
```css
.msg-bubble {
  max-width: min(70%, 480px);
}
```

### 修复5：侧边栏遮罩默认隐藏
```css
.sidebar-overlay {
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.25s ease;
}
.sidebar-overlay.open {
  opacity: 1;
  pointer-events: auto;
}
```

### 修复6：首页桌面端布局
```css
@media (min-width: 768px) {
  .page-home {
    max-width: var(--max-content-width);
    margin: 0 auto;
  }
  .cards-container {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: var(--space-4);
    padding: 0 var(--space-5) var(--space-4);
  }
  .brand-title {
    font-size: 36px;
  }
}
```

### 修复7：合并重复按钮类
```css
/* 删除 .back-btn, .menu-btn, .user-btn 各自的定义，合并为： */
.icon-btn-circle {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: var(--overlay-5);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: var(--text-primary);
  transition: background var(--duration-base);
}
.icon-btn-circle:active {
  background: var(--overlay-8);
}
```

### 修复8：消除 !important
```css
/* 修改前 */
.card-btn {
  background: transparent !important;
}
/* 修改后 - 提高选择器优先级或重构 */
.feature-card .card-btn {
  background: transparent;
}
```

---

*审计完成时间：2026-07-17*
*文件：C:\TRAE\SJS\src\static\css\style.css*
