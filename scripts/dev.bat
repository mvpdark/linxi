@echo off
chcp 65001 >nul
setlocal
REM ===========================================================================
REM 灵犀 - 开发模式启动（后端 uvicorn + 前端热更新）
REM
REM   - 后端: python src/server.py  (uvicorn, http://127.0.0.1:8765, 挂载 /static)
REM   - 前端: live-server 托管 src/static (http://127.0.0.1:5173, 保存自动刷新)
REM
REM 两种访问方式：
REM   A. 热更新开发:  http://127.0.0.1:5173   （改前端文件浏览器自动刷新）
REM      注意此时 config.json 的 apiBase 决定连哪个后端：
REM      本地联调请把 src/static/config.json 的 apiBase 临时改为
REM      "http://127.0.0.1:8765"（改完刷新页面即可，config.json 不参与缓存）。
REM   B. 后端直出:    http://127.0.0.1:8765/static/index.html
REM      （后端挂载的同源静态页，无需改 config.json，手动 F5 刷新）
REM
REM 关闭本窗口不会停止已启动的两个服务窗口，请分别关闭。
REM ===========================================================================

set "ROOT=%~dp0.."
pushd "%ROOT%"

echo ============================================================
echo   灵犀 LingXi - 开发模式
echo ============================================================

REM --- 启动后端（独立窗口） ---
echo [启动] 后端 uvicorn  ->  http://127.0.0.1:8765
start "灵犀后端 (uvicorn:8765)" cmd /k "chcp 65001 >nul && python src\server.py"

REM --- 启动前端热更新（独立窗口） ---
where npx >nul 2>nul
if errorlevel 1 (
    echo [提示] 未检测到 npx，前端热更新不可用；仍可通过 http://127.0.0.1:8765/static/index.html 访问。
) else (
    echo [启动] 前端热更新  ->  http://127.0.0.1:5173  (live-server, 保存自动刷新)
    start "灵犀前端 (live-server:5173)" cmd /k "chcp 65001 >nul && npx --yes live-server src\static --port=5173 --no-browser"
)

echo.
echo 开发地址:
echo   热更新前端:  http://127.0.0.1:5173
echo   后端直出:    http://127.0.0.1:8765/static/index.html
echo   后端 API:    http://127.0.0.1:8765
echo.
echo 提示: 热更新模式联调本地后端时，请将 src\static\config.json 的
echo       apiBase 改为 "http://127.0.0.1:8765"（默认指向远端生产后端）。
echo ============================================================

popd
endlocal
