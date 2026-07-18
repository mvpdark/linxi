# 设计师助手应用 - 实现方案

## 概要

面向设计师的跨平台应用，使用 Python + Flet 框架，一套代码同时编译为桌面应用和移动端 App。包含三大核心模块：灵感（LLM对话+作品推荐）、改图（草图→专业效果图）、360（占位）。UI 采用 Apple/Cupertino 风格。所有 AI 能力通过 yunwu.ai API 调用。

## 当前状态分析

- 项目从零开始，无现有代码
- 技术选型已确定：Flet 框架 + Python + httpx 异步 HTTP
- API 已就绪：yunwu.ai 提供 LLM (`/v1/responses`, model `gpt-5.6-luna`) 和 图片生成 (`/v1/images/generations` + `/v1/images/edits`, model `gpt-image-2`)
- 知识库方案待定（后续详聊），架构需预留可插拔接口

## API 配置

| 功能 | 端点 | 模型 | 说明 |
|------|------|------|------|
| LLM对话 | `POST /v1/responses` | `gpt-5.6-luna` | SSE流式输出，使用`input`字段而非`messages` |
| 文生图 | `POST /v1/images/generations` | `gpt-image-2` | 支持size/quality/n/format参数 |
| 图生图 | `POST /v1/images/edits` | `gpt-image-2` | multipart/form-data上传图片 |
| 认证 | Bearer Token | - | 所有请求Header: `Authorization: Bearer sk-xxx` |

API Key: `sk-OpkSwBi1sbNBjbf091qzMn2DbHYMbDfRfhLwEB9jloNkl7Ei`

## 项目结构

```
c:\TRAE\SJS\
├── pyproject.toml              # 项目配置 + Flet构建配置
├── requirements.txt            # 依赖: flet, httpx, pyyaml
├── config.yaml                 # API密钥与配置
├── config.example.yaml         # 配置模板(提交到git)
├── .gitignore                   # 忽略config.yaml, cache/
├── assets/
│   ├── icon.png                # 应用图标 (>=1024x1024)
│   └── fonts/
│       └── SF-Pro.otf          # SF Pro风格字体
├── src/
│   ├── main.py                 # 入口: ft.run(main)
│   ├── app.py                  # 应用主控制器(App类): 路由/主题/配置
│   ├── config.py               # Config数据类: 从yaml加载配置
│   ├── theme.py                # CupertinoTheme: 亮/暗主题定义
│   ├── services/
│   │   ├── llm_service.py      # LLM服务: Responses API流式/非流式调用
│   │   ├── image_service.py    # 图片服务: 生成+编辑, b64_json保存本地
│   │   ├── kb_service.py       # 知识库服务: 抽象接口, 当前返回空列表
│   │   └── storage_service.py  # 本地存储: 聊天记录JSON持久化
│   ├── models/
│   │   ├── chat_message.py     # 聊天消息模型
│   │   ├── design_work.py      # 设计作品模型
│   │   └── edit_task.py        # 编辑任务模型
│   ├── views/
│   │   ├── home_view.py        # 主页: 三卡片布局(响应式横/竖排)
│   │   ├── inspiration_view.py # 灵感: 聊天+作品推荐画廊
│   │   ├── image_edit_view.py  # 改图: 上传+提示+生成
│   │   └── placeholder_view.py # 360占位页
│   ├── widgets/
│   │   ├── cupertino_card.py   # Cupertino风格卡片(圆角+半透明)
│   │   ├── chat_bubble.py      # 聊天气泡(用户/AI/错误三态)
│   │   ├── image_gallery.py    # 图片网格画廊(10张)
│   │   ├── loading_overlay.py   # 加载指示器
│   │   └── frosted_container.py # 毛玻璃效果容器
│   └── utils/
│       ├── image_cache.py      # 图片缓存管理
│       ├── size_validator.py   # 尺寸校验(16倍数, max 3840)
│       └── error_handler.py    # 统一错误处理+Cupertino弹窗
└── cache/                      # 运行时缓存(自动创建)
    ├── generated/              # 生成的图片
    ├── edited/                 # 编辑后的图片
    └── storage/                # 聊天记录JSON
```

## 架构设计

### 分层架构

```
UI层 (views + widgets)  → 只负责渲染和事件分发
  ↓
Service层 (services)     → 封装所有API调用, 返回Model对象
  ↓
Data层 (models)           → 数据模型定义
  ↓
Config层 (config)         → 配置加载, API密钥管理
```

### 数据流

```
用户交互 → View事件 → page.run_task(async_handler)
  → Service层调用(httpx async) → API响应
  → Model解析 → View更新(page.update())
  → Storage持久化(异步写入JSON)
```

### 导航架构

Flet使用View栈式导航：
- `page.views[0]` = HomeView (三卡片选择页)
- `page.views[1]` = 对应功能页 (灵感/改图/360)

## 各模块实现方案

### 模块1: 灵感 (Inspiration)

**功能流程**: 设计师输入想法 → LLM流式回复 → 根据回复内容从知识库搜索10张匹配作品 → 画廊展示

**关键实现**:
- `LLMService.chat_stream()`: 调用 `/v1/responses`，SSE流式输出，逐字显示
- `LLMService.chat()`: 非流式调用，返回完整文本
- `KnowledgeBaseService.search_designs()`: 抽象接口，当前返回空列表，后续接入向量数据库
- 聊天记录通过 `StorageService` 持久化为本地JSON
- `ImageGallery` 组件展示10张推荐作品（5列网格，桌面端）

**Responses API 调用要点**:
- 使用 `input` 字段（非 `messages`）传递对话历史
- 流式事件类型: `response.output_text.delta` (增量文本), `response.completed` (完成)
- 同时兼容 `output` 和 `choices` 两种返回格式

### 模块2: 改图 (Image Edit)

**功能流程**: 上传草图 → 输入修改描述 → 调用图生图API → 展示专业效果图

**关键实现**:
- `ImageService.edit_image()`: multipart/form-data 上传图片到 `/v1/images/edits`
- 桌面端支持拖拽上传，移动端调用系统照片选择器
- 支持尺寸选择 (1024x1024, 1024x1536, 1536x1024等)
- 加载状态: CupertinoActivityIndicator + 进度文案（图片生成最长300秒）
- 结果图自动保存到 `cache/edited/` 目录

### 模块3: 360 (占位)

**实现**: 显示"开发中"状态页 + 基础UI框架（CupertinoAppBar + 图标 + 文案），功能留空待后续接入。

### 主页: 三卡片布局

**响应式策略**:
- 桌面端 (width >= 600): 三卡片横排，200x200每张
- 移动端 (width < 600): 三卡片竖排，全宽
- 通过 `page.on_resize` 动态切换布局

**Cupertino卡片样式**:
- 圆角: 20px (iOS大圆角风格)
- 背景: 半透明白色 (opacity 0.08)
- 边框: 0.5px 细线 (opacity 0.1)
- 点击波纹效果 (ink=True)
- 360卡片为禁用状态（灰色图标+低透明度）

## Cupertino UI 关键模式

### 核心设置
```python
page.adaptive = True  # iOS/macOS自动渲染Cupertino风格
page.theme = CupertinoTheme.light()
page.dark_theme = CupertinoTheme.dark()
page.theme_mode = ft.ThemeMode.SYSTEM
```

### Cupertino 控件清单

| Flet控件 | 用途 | iOS对应组件 |
|---------|------|------------|
| `ft.CupertinoAppBar` | 导航栏 | UINavigationBar |
| `ft.CupertinoButton` | 按钮 | UIButton |
| `ft.CupertinoTextField` | 输入框 | UITextField |
| `ft.CupertinoAlertDialog` | 错误弹窗 | UIAlertController |
| `ft.CupertinoActivityIndicator` | 加载指示 | UIActivityIndicator |
| `ft.CupertinoDialogAction` | 弹窗按钮 | UIAlertAction |

### 毛玻璃效果
Flet无原生BackdropFilter，通过半透明背景色模拟:
```python
ft.Container(
    bgcolor=ft.Colors.with_opacity(0.7, ft.Colors.WHITE),
    border_radius=20,
)
```

## 配置管理

### config.yaml
```yaml
llm:
  api_base: "https://yunwu.ai"
  api_key: "sk-OpkSwBi1sbNBjbf091qzMn2DbHYMbDfRfhLwEB9jloNkl7Ei"
  model: "gpt-5.6-luna"

image:
  api_base: "https://yunwu.ai"
  api_key: "sk-OpkSwBi1sbNBjbf091qzMn2DbHYMbDfRfhLwEB9jloNkl7Ei"
  model: "gpt-image-2"

cache_dir: "cache"
```

- `config.yaml` 加入 `.gitignore`
- `config.example.yaml` 作为模板提交
- `Config` 数据类从 yaml 加载，提供默认值兜底

## 错误处理与加载状态

### 错误处理层次
```
API层错误 → Service层捕获 → ErrorHandler格式化 → CupertinoAlertDialog展示
```

### 错误类型处理
| 异常类型 | 用户提示 |
|---------|---------|
| `httpx.TimeoutException` | "请求超时，图片生成可能需要较长时间" |
| `httpx.HTTPStatusError` | "API错误(状态码): 服务器返回错误" |
| `httpx.ConnectError` | "无法连接到服务器，请检查网络" |

### 加载状态策略
| 场景 | 组件 | 预期时长 | 策略 |
|------|------|---------|------|
| LLM流式对话 | 聊天气泡内实时更新 | 5-30秒 | SSE增量更新 |
| 图片生成 | ActivityIndicator + 文案 | 最长300秒 | 遮罩层+可取消 |
| 知识库搜索 | 骨架屏 | 1-5秒 | 异步不阻塞对话 |
| 图片上传 | 进度条 | <5秒 | FilePicker事件 |

## 知识库预留架构

```
KnowledgeBaseInterface (抽象接口)
    ├── VectorKBService        # 向量数据库(ChromaDB/Qdrant) + embedding
    ├── RemoteKBService        # 远程知识库API
    └── EmbeddingKBService     # yunwu.ai embedding + 本地索引
```

推荐流程: 用户对话 → LLM提取设计关键词 → 知识库搜索 → 返回10张室内设计作品 → Gallery展示

## 部署方案

### pyproject.toml 核心配置
```toml
[project]
dependencies = ["flet>=0.28.0", "httpx>=0.27.0", "pyyaml>=6.0"]

[tool.flet.app]
path = "src"
module = "main"
```

### 构建命令
| 目标 | 命令 | 输出 |
|------|------|------|
| Windows | `flet build windows` | .exe |
| macOS | `flet build macos` | .app |
| Android | `flet build apk` | .apk |
| iOS | `flet build ipa` (需macOS) | .ipa |

### 注意事项
- `flet build` 需要 Flutter SDK（首次自动下载）
- iOS 构建必须在 macOS 上执行，需 Xcode + Apple Developer 账号
- `config.yaml` 需配置打包include路径或使用环境变量
- 移动端缓存目录通过 `page.storage_paths` 获取跨平台路径
- 移动端 `FilePicker.pick_files()` 自动调用系统照片选择器

## 假设与决策

1. **框架选择**: Flet — 一套代码支持桌面+移动+Web，Python原生开发，Cupertino控件完善
2. **异步模式**: 所有API调用使用 httpx.AsyncClient + page.run_task()，不阻塞UI线程
3. **图片存储**: API返回b64_json，解码后保存到本地cache目录，避免URL过期问题
4. **聊天持久化**: 使用本地JSON文件，简单可靠，后续可升级为SQLite
5. **知识库**: 当前为接口预留，返回空列表不影响主功能流程
6. **Responses API**: 使用input字段（非messages），兼容output和choices两种返回格式
7. **字体**: 使用SF Pro风格字体，放在assets/fonts/目录

## 验证步骤

1. **环境验证**: `pip install flet httpx pyyaml` 成功安装
2. **桌面运行**: `flet run src/main.py` 打开桌面应用，显示三卡片主页
3. **灵感模块**: 输入设计想法 → LLM流式回复正常 → 聊天记录持久化
4. **改图模块**: 上传草图 → 输入提示 → 生成效果图保存到cache/edited/
5. **360占位**: 点击显示"开发中"页面
6. **响应式**: 调整窗口大小，卡片从横排切换为竖排
7. **错误处理**: 断网状态下显示Cupertino错误弹窗
8. **移动端构建**: `flet build apk` 生成APK文件
9. **API连通性**: 验证 `/v1/responses`、`/v1/images/generations`、`/v1/images/edits` 三个端点均可正常调用

## 实现顺序

1. **基础框架**: pyproject.toml + config + theme + main.py + app.py
2. **主页布局**: home_view + cupertino_card 组件
3. **Service层**: llm_service + image_service + storage_service
4. **灵感模块**: inspiration_view + chat_bubble + image_gallery 组件
5. **改图模块**: image_edit_view + 文件上传 + 生成展示
6. **360占位**: placeholder_view
7. **错误处理**: error_handler + loading_overlay
8. **测试调优**: 全流程测试 + 响应式验证
9. **打包部署**: flet build 各平台
