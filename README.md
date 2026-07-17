# 灵犀 Linxi

> 设计师的 AI 助手前端

[中文](#中文) | [English](#english) | [日本語](#日本語) | [한국어](#한국어)

---

## 中文

灵犀是一款面向设计师的 AI 助手应用，提供灵感对话、智能改图、360°全景生成等功能。

### 功能特性

- 💬 **灵感对话** - 与 AI 对话，激发设计灵感
- 🖼️ **智能改图** - 上传图片，AI 智能编辑
- 🌐 **全景生成** - 360°全景图生成与查看

### 技术栈

- Vue 3 (CDN 模式，纯静态前端)
- Capacitor (Android 打包)
- Tauri (Windows 桌面端打包)

### 项目结构

```
├── src/static/          # Vue 3 静态前端源码
│   ├── index.html
│   ├── css/
│   ├── js/
│   └── vendor/
├── frontend-capacitor/  # Android 打包工程
└── frontend-tauri/      # 桌面端打包工程
```

### 快速开始

1. 配置后端地址：复制 `src/static/config.example.json` 为 `src/static/config.json`，填写你的后端地址和 token
2. 部署 `src/static/` 到任意静态文件服务器
3. 或使用 Capacitor/Tauri 打包为原生应用

### 配置说明

前端通过 `src/static/config.json` 读取后端地址和鉴权 token（该文件已在 `.gitignore` 中排除，请勿提交真实配置）：

```json
{
  "apiBase": "https://your-backend-domain:port",
  "token": "your-token-here"
}
```

---

## English

Linxi is an AI assistant application for designers, featuring inspiration chat, smart image editing, and 360° panorama generation.

### Features

- 💬 **Inspiration Chat** - Chat with AI to spark design ideas
- 🖼️ **Smart Image Editing** - Upload images for AI editing
- 🌐 **Panorama Generation** - 360° panorama creation and viewing

### Tech Stack

- Vue 3 (CDN mode, pure static frontend)
- Capacitor (Android packaging)
- Tauri (Windows desktop packaging)

### Quick Start

1. Configure backend: Copy `src/static/config.example.json` to `src/static/config.json` and fill in your backend URL and token
2. Deploy `src/static/` to any static file server
3. Or package as native app using Capacitor/Tauri

---

## 日本語

霊犀（リンシ）は、デザイナー向けのAIアシスタントアプリです。

### 機能

- 💬 **インスピレーションチャット** - AIと対話してデザインのインスピレーションを
- 🖼️ **スマート画像編集** - 画像をアップロードしてAI編集
- 🌐 **パノラマ生成** - 360°パノラマの生成と表示

### クイックスタート

1. `src/static/config.example.json` を `config.json` にコピーし、バックエンドURLとトークンを記入
2. `src/static/` を任意の静的ファイルサーバーにデプロイ

---

## 한국어

영서는 디자이너를 위한 AI 어시스턴트 애플리케이션입니다.

### 기능

- 💬 **영감 채팅** - AI와 대화하며 디자인 영감을
- 🖼️ **스마트 이미지 편집** - 이미지 업로드 후 AI 편집
- 🌐 **파노라마 생성** - 360° 파노라마 생성 및 보기

### 빠른 시작

1. `src/static/config.example.json`을 `config.json`으로 복사하고 백엔드 URL과 토큰 입력
2. `src/static/`을 정적 파일 서버에 배포
