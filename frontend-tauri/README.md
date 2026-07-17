# 灵犀 - Tauri Win64 桌面端

本目录是「灵犀」的 Tauri 2 桌面打包工程，目标平台 Windows 64 位（x86_64-pc-windows-msvc）。
前端不复制、不重新构建，直接引用项目根目录的 `src/static/`（Vue 3 CDN 模式）。

## 目录结构

```
frontend-tauri/
├── package.json               # npm 脚本与 @tauri-apps/api / @tauri-apps/cli 依赖
└── src-tauri/
    ├── tauri.conf.json        # 窗口标题「灵犀」、图标、bundle 目标（nsis/msi）
    ├── Cargo.toml             # Rust 依赖（tauri 2 + serde）
    ├── build.rs               # Tauri 构建脚本入口
    ├── src/
    │   └── main.rs            # 最简 Tauri 入口（无自定义命令）
    └── icons/                 # 应用图标（已生成：32/128/128@2x/ico/png）
```

## src/static 是如何被打包进去的

关键配置在 `src-tauri/tauri.conf.json`：

```json
"build": {
  "frontendDist": "../../src/static"
}
```

- `frontendDist` 以 **src-tauri/ 目录为基准** 解析相对路径，因此从
  `frontend-tauri/src-tauri/` 向上两级即项目根目录，再进入 `src/static/`。
- `tauri build` 时，Tauri 会把该目录**整体嵌入**到可执行文件的资源区，
  运行时通过内置的 `tauri://localhost` 协议直接加载 `index.html`，
  无需本地 HTTP 服务器，也没有跨域问题。
- 前端 `index.html` 全部使用**相对路径**（`css/`、`js/`、`vendor/`、`config.json`），
  与 webview 根目录加载方式天然兼容，无需任何改动。
- 后端地址由 `src/static/config.json` 的 `apiBase` 决定（当前指向
  `https://lx.mvpdark.top:8443`），桌面端打包后仍通过网络访问远端 Docker 后端。

> 注意：本工程没有 `beforeDevCommand` / `beforeBuildCommand`，
> 因为前端是纯静态文件，无需构建步骤。

## 构建 Win64 安装包

前置条件：Node.js 18+、Rust（含 `x86_64-pc-windows-msvc` target）、
Visual Studio Build Tools（C++ 工作负载）。

```bat
cd frontend-tauri
npm install
npm run build:win64
```

等价于 `npx tauri build --target x86_64-pc-windows-msvc`。

产物位置：

```
frontend-tauri/src-tauri/target/x86_64-pc-windows-msvc/release/bundle/
├── nsis/灵犀_1.0.0_x64-setup.exe   # NSIS 安装包（简体中文，当前用户安装）
└── msi/灵犀_1.0.0_x64_en-US.msi    # MSI 安装包
```

## 开发调试

```bat
cd frontend-tauri
npm run dev     # tauri dev：编译 Rust 并打开窗口，前端文件改动保存后窗口内刷新即可
```

开发模式下前端文件同样直接取自 `../src/static`，修改 HTML/JS/CSS 后
在窗口里 `Ctrl+R` 刷新即生效（Rust 代码改动会自动重编译重启）。

## 图标更新

替换 `src-tauri/icons/` 下文件，或使用官方工具从单张 PNG 重新生成：

```bat
npx tauri icon path\to\logo.png
```
