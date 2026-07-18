# 灵犀App 改图模块 全链路实测报告

- 测试时间：2026-07-17
- 目标服务：http://127.0.0.1:8765 （页面 `#/image-edit`）
- 测试图片：`C:\TRAE\SJS\assets\upload_1784191855_bf95f2.jpg`（137,978 字节，浴室洗手台场景）
- 测试方式：Python requests（API 层）+ agent-browser CDP 自动化（浏览器层），全程只测试、未修改任何代码

---

## 一、API 端点健康检查

| # | 测试项 | 结果 | 耗时 | HTTP | 返回结构 / 说明 |
|---|--------|------|------|------|----------------|
| 1 | GET / 主页 | 通过 | 0.01s | 200 | text/html，6,176B，完整 SPA 页面 |
| 2 | POST /api/upload（jpg） | 通过 | 0.04s | 200 | `{url, filename}`，返回 `/uploads/upload_*.jpg` |
| 3 | POST /api/vlm-detect（jpg） | 通过 | 19.37s | 200 | `{objects:[...]}`，检出 16 个物体（洗手台台面、水龙头、龙头把手×2、嵌入式水槽、木饰面背景墙 等），bbox 为归一化 dict |
| 4 | POST /api/sam-segment（**前端实际格式**：bbox=dict） | **业务失败**（HTTP 200） | 0.96s | 200 | `{success:false, error:"SAM 2 模型不可用，使用 bbox 模式", objects:[]}` —— 见问题 P1 |
| 5 | POST /api/sam-segment（**接口文档格式**：bbox=list） | 通过 | 2.23s | 200 | `{success:true, objects:[{label, polygon, mask_png_b64, bbox}]}`，返回多边形轮廓 + base64 mask，SAM 模型本身工作正常 |
| 6 | POST /api/sam-segment（空 objects） | 通过 | 0.09s | 200 | `{success:true, objects:[]}`，空列表快速路径正常 |

## 二、错误场景测试

| # | 测试项 | 结果 | 耗时 | HTTP | 实际行为 |
|---|--------|------|------|------|----------|
| 7 | /api/upload 上传 txt 文本文件 | **未被拒绝** | 0.18s | 200 | 直接保存为 `upload_*.jpg` 并返回 url。`compress_image` 对非图片静默返回原始字节，接口无任何类型校验 —— 问题 P2 |
| 8 | /api/upload 上传 19.4MB PNG | **未被拒绝** | 0.38s | 200 | 无大小限制；服务端压缩至 1024px 后存储，存储风险可控，但上传带宽/内存无保护 —— 问题 P3 |
| 9 | /api/vlm-detect file 字段传 URL 文本（非文件） | 通过（拒绝） | 0.01s | 422 | FastAPI 参数校验拦截：`Expected UploadFile, received: str`，处理正常 |
| 10 | /api/vlm-detect 文件内容为 URL 文本（垃圾字节） | **通过但处理粗糙** | 3.87s | 500 | 垃圾字节 base64 后直接送 VLM 上游，把上游 `400 Bad Request` 原文（含 `https://yunwu.ai/v1/chat/completions`）抛给客户端 —— 问题 P4 |
| 11 | /api/vlm-detect 空文件 | 通过（契约不一致） | 2.49s | 200 | 返回 `{objects:[], raw:"...未提供图片..."}`，走 JSON 解析失败的 raw 分支，与正常结构不一致 —— 问题 P5 |

## 三、浏览器端流程实测（agent-browser / CDP）

| 步骤 | 结果 | 耗时 | UI 反馈 |
|------|------|------|---------|
| 打开 `#/image-edit` | 通过 | <1s | 上传区正常渲染，无 JS 报错 |
| 上传图片 | 通过 | 即时 | 立即出现「AI正在识别图中的物品...」加载提示 |
| VLM 检测 + SAM 分割 | 通过（实为 bbox 回退） | **约 13.7s**（API 直连测得 19.4s，有波动） | 检测完成后渲染画布 + 编号标签列表；**画布上全部为矩形虚线框，无 SAM 多边形轮廓**，与 P1 吻合 |
| 点选物体「洗手台台面」 | 通过 | 即时 | 选中项变蓝色高亮，底部弹出「已选中 1 个区域：1号 洗手台台面」+ 提示词输入框 + 发送按钮，交互流畅 |
| 提交编辑「改成浅灰色布艺材质」 | 通过 | **45.8s** | 提交后出现「正在...」生成提示；完成后展示结果图 + 重新编辑/继续编辑/保存图片按钮 |
| 控制台 / 页面错误 | 通过 | — | **全程 0 条 JS 错误、0 条 warning**（二次确认：SAM 回退分支不打日志，属静默降级） |

补充观察：
- 同一张图两次检测，VLM 分别返回 16 / 12 个物体，且 label 命名不稳定（"洗手台台面" vs "洗手盆台面"）—— 问题 P6。
- 带区域标注的编辑实际为**整图重生成**（默认 1:1），生成图构图与原图有可见差异，并非局部 inpainting —— 问题 P7。

## 四、问题清单（按严重度）

| 编号 | 严重度 | 问题 | 证据 |
|------|--------|------|------|
| P1 | **高** | **前后端 bbox 格式契约不匹配，SAM 精确分割从未真正生效**。前端 `src/static/js/image-edit.js:278` 发送 `{id, bbox:{x,y,w,h}}`（dict），服务端 `src/services/sam_service.py:215` 按 `x,y,bw,bh = bbox` 解包（期望 list），dict 解包得到键名字符串后触发异常 → 返回 `success:false`。前端收到后**静默回退 bbox 模式**（image-edit.js:825-831 无日志、无 UI 提示）。实测：dict 格式 → "SAM 2 模型不可用"；list 格式 → 正常返回 polygon+mask（2.23s）。即线上所有用户始终在用粗 bbox 标注，SAM 模型形同虚设 | 用例 4 vs 5；浏览器画布全为矩形虚线框 |
| P2 | 中 | /api/upload 无文件类型校验，txt 可被保存为 .jpg（`compress_image` 对非法图片静默透传） | 用例 7 |
| P3 | 中 | /api/upload 无大小限制、无速率限制，19.4MB 文件直接读入内存处理 | 用例 8 |
| P4 | 中 | vlm-detect 对非图片内容无前置校验，垃圾字节直接消耗上游 API 配额；500 响应泄露上游接口地址，错误未包装 | 用例 10 |
| P5 | 低 | vlm-detect 异常/空图时返回 `{objects:[], raw:...}`，与正常契约不一致，前端需兼容两种结构 | 用例 11 |
| P6 | 低 | VLM 检测结果不稳定：同图 16/14/12 个物体，label 粒度与命名飘忽，影响标注一致性 | 用例 3 vs 浏览器两次检测 |
| P7 | 低（产品层） | 区域标注编辑为整图重生成，非局部修改；生成结果构图漂移，与"改选中区域"的用户预期有差距 | 浏览器结果图对比 |
| P8 | 观察 | SAM(list bbox) 返回的 polygon 仅 6 个顶点，轮廓较粗糙（Tiny 模型 + CPU 的精度上限） | 用例 5 |

## 五、分环节优化建议

**上传环节**
1. 增加魔数/MIME 校验：PIL 无法解码即返回 415 `{success:false, error:"仅支持图片文件"}`，不要静默落盘。
2. 增加大小上限（如 20MB 返回 413）与 Content-Length 预检，避免大文件直接进内存；可加简单速率限制。

**VLM 检测环节**
3. 送上游前先本地 PIL 验证图片有效性，垃圾输入直接 400，保护 API 配额。
4. 包装上游异常为用户友好错误（隐藏 yunwu.ai 等内部地址），统一 `{success:false, error}` 结构。
5. Prompt 中约束 label 粒度与同义词归一（或后处理合并），缓解 P6；可按图片 hash 缓存检测结果。

**SAM 分割环节（最高优先级）**
6. 修复 P1 契约：建议服务端同时兼容 dict 与 list 两种 bbox（一行归一化），或前端改为发送 `[x,y,w,h]`；修好后画布即可显示真实多边形轮廓。
7. `success:false` 时区分错误原因（模型未加载 / 参数格式错误 / 推理异常），避免一律报"模型不可用"。
8. 前端回退 bbox 模式时增加 `console.warn` 与轻提示（如"精确分割不可用，已切换框选模式"），让降级可观测。

**编辑生成环节**
9. 45.8s 等待较长：增加进度条/分阶段提示（上传→生成→回传），或 WebSocket/SSE 推送进度。
10. 若产品定位是"只改选中区域"，评估局部 inpainting 或在 UI 明确告知"将整图重新生成"。

**通用**
11. 所有 API 错误响应统一为 `{success:false, error, code?}`；新增 `/api/health` 轻量探活端点（含 SAM 模型状态）。

---

### 附：测试产物
- 测试脚本：`c:\Users\mvpdark\.trae-cn\work\6a57a864fc70ec0f718542ad\test_image_edit.py`
- 原始结果：`c:\Users\mvpdark\.trae-cn\work\6a57a864fc70ec0f718542ad\test_results.json`
- 浏览器截图（同目录）：`b01_initial.png`（初始页）、`b02_detected.png`（检测标注）、`b03_selected.png`（选中区域）、`b04_result.png`（生成结果）
