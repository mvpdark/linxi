import type { CapacitorConfig } from '@capacitor/cli';

// ===========================================================================
// 灵犀 - Capacitor 配置
//
// webDir 以本文件所在目录（frontend-capacitor/）为基准解析：
//   ../src/static  ->  项目根目录下的 src/static（Vue 3 CDN 纯静态前端）
// `npx cap sync` 会把该目录整体复制进 android/app/src/main/assets/public，
// App 运行时由 WebView 通过 https://localhost 本地加载，无需网络服务器。
// 后端地址由 src/static/config.json 的 apiBase 决定（远端 Docker 后端）。
// ===========================================================================
const config: CapacitorConfig = {
  appId: 'com.mvpdark.lingxi',
  appName: '灵犀',
  webDir: '../src/static',

  // Android WebView 使用 https://localhost 方案（Cookie/安全上下文更友好）
  android: {
    allowMixedContent: false,
    // 启用 WebView 调试（生产环境可关闭，调试阶段开启）
    webContentsDebuggingEnabled: true,
  },
  server: {
    androidScheme: 'https',
    // 后端为独立 HTTPS 域名（见 config.json apiBase），无需 cleartext 放行；
    // 若联调局域网 HTTP 后端，可临时改为 true 并在原生侧配置 networkSecurityConfig
    cleartext: false,
  },
};

export default config;
