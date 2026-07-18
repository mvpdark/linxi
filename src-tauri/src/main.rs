// 防止发布版在 Windows 上额外弹出控制台窗口，请勿删除!!
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    // 窗口尺寸、标题、图标等均已在 tauri.conf.json 中配置，
    // 前端静态资源（../src/static）由 Tauri 内置协议直接加载。
    tauri::Builder::default()
        .run(tauri::generate_context!())
        .expect("灵犀应用启动失败");
}
