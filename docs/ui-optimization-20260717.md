# 灵犀App UI/CSS优化记录 - 2026-07-17

## 本轮优化概要

对灵犀App四个页面（首页、聊天页、改图页、全景页）进行桌面端(1440x900)和移动端(390x844)的UI/CSS优化。

## 修复内容

### 1. 首页 - 品牌色统一与间距对齐
- **Icon-rail激活态配色修复**: 将激活状态从灰色(`#1C1C1E` + `rgba(0,0,0,0.06)`)改为品牌绿(`var(--primary)` + `var(--primary-light)`)，与功能卡片绿色保持一致
- **水平间距统一**: 将最近记录区域(recent-section/recent-header/recent-list)的水平padding统一为24px，与功能卡片容器(cards-container)对齐
  - `recent-section`: `padding: 0 20px 32px` → `padding: 8px 0 32px`
  - `recent-header`: `padding: 8px 20px` → `padding: 8px 24px`
  - `recent-list`: `padding: 0 16px 24px` → `padding: 0 24px 24px`
- **最近记录项右箭头**: 添加`::after`伪元素右箭头指示器，增强可点击性暗示
- **最近记录标题弹性布局**: 为`recent-item-title`添加`flex: 1; min-width: 0`，确保标题与时间正确分布

### 2. 聊天页 - 发送按钮状态与输入栏边界
- **发送按钮禁用态**: 禁用状态从仅降低opacity改为灰色背景(`var(--text-tertiary)`)，更清晰地表示不可点击状态
- **输入栏上阴影**: 添加`box-shadow: 0 -2px 12px rgba(60,55,50,0.06)`向上阴影，增强输入栏与消息区的视觉分离

### 3. 改图页 - 上传区域比例优化
- **上传区域高度**: `min-height`从320px降至240px，padding从36px降至28px，改善视觉比例
- **桌面端上传区宽度**: 保持max-width 640px，避免过宽

### 4. 全景页 - 桌面端内容宽度扩展
- **内容区域宽度**: 从640px扩展至720px，添加`width: 100%`确保容器填满max-width
  - `pano-mode-select`: 添加`width: 100%`，卡片宽度从332px增至680px
  - `pano-history-section`: 添加`width: 100%`
  - `pano-content`: 添加`width: 100%`

### 5. 缓存版本更新
- CSS/JS版本号从`v=20260717t`更新至`v=20260717v`，强制浏览器刷新缓存

## 测试结果

| 测试项 | 桌面端 | 移动端 |
|--------|--------|--------|
| 首页截图 | ✅ 通过 | ✅ 通过 |
| 聊天页截图 | ✅ 通过 | ✅ 通过 |
| 改图页截图 | ✅ 通过 | ✅ 通过 |
| 全景页截图 | ✅ 通过 | ✅ 通过 |
| 控制台错误 | 0 | 0 |
| 页面错误 | 0 | 0 |
| 水平滚动 | 无 | 无 |
| Vue挂载 | 正常 | 正常 |

## 修改文件清单

1. `src/static/css/style.css` - 8处CSS修复
2. `src/static/index.html` - CSS/JS版本号更新

## 未处理的已知问题（建议后续迭代）

### JS级别问题（需代码修改）
1. **Canvas无resize监听** (image-edit.js): 窗口resize/手机旋转后Canvas坐标错位
2. **WebSocket多消息覆盖** (chat.js): 连续多条消息待发时后一条覆盖前一条
3. **ID替换正则多位数冲突** (image-edit.js): ID 1和11存在子串匹配问题
4. **图片弹窗快速点击竞态** (chat.js): 多个onload回调竞争设置modalImage
5. **全局querySelector应改ref** (image-edit.js/chat.js): 多实例场景可能出错

### UI增强建议（需JS模板修改）
1. 聊天页空状态可添加快捷问题推荐chips
2. 改图页底部空白可添加功能说明或最近文件
3. 全景页文件名可美化（去除时间戳前缀）
4. 原生confirm()/alert()可替换为自定义弹窗组件
