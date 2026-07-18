@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion
REM ===========================================================================
REM 灵犀 - 一键构建脚本（Docker 后端 + Tauri Win64 + Capacitor Android）
REM
REM 用法（在项目根目录）：
REM   scripts\build-all.bat            全部构建
REM   scripts\build-all.bat backend    只构建后端
REM   scripts\build-all.bat tauri      只构建 Tauri Win64
REM   scripts\build-all.bat android    只构建 Capacitor Android (Debug APK)
REM
REM 前置条件：
REM   - Docker Desktop（后端镜像）
REM   - Node.js 18+ / Rust / VS Build Tools（Tauri）
REM   - JDK 17+ / Android SDK（Capacitor；首次需手动 npx cap add android）
REM ===========================================================================

set "ROOT=%~dp0.."
pushd "%ROOT%"

set "ARG=%~1"
if "%ARG%"=="" set "ARG=all"

echo ============================================================
echo   灵犀 LingXi - 一键构建  (模式: %ARG%)
echo ============================================================

REM ---------------------------------------------------------------------------
REM [1/3] Docker 后端
REM ---------------------------------------------------------------------------
if /i "%ARG%"=="all" goto :do_backend
if /i "%ARG%"=="backend" goto :do_backend
goto :skip_backend
:do_backend
echo.
echo [1/3] 构建并启动 Docker 后端...
docker compose -f backend\docker-compose.yml up -d --build
if errorlevel 1 (
    echo [错误] Docker 后端构建失败，请确认 Docker Desktop 已启动。
    goto :fail
)
echo [完成] 后端容器已启动: http://127.0.0.1:8765
:skip_backend

REM ---------------------------------------------------------------------------
REM [2/3] Tauri Win64
REM ---------------------------------------------------------------------------
if /i "%ARG%"=="all" goto :do_tauri
if /i "%ARG%"=="tauri" goto :do_tauri
goto :skip_tauri
:do_tauri
echo.
echo [2/3] 构建 Tauri Win64 桌面端...
pushd frontend-tauri
if not exist node_modules (
    echo 首次运行，安装 npm 依赖...
    call npm install
    if errorlevel 1 ( popd & goto :fail )
)
call npm run build:win64
if errorlevel 1 (
    popd
    echo [错误] Tauri 构建失败。
    goto :fail
)
popd
echo [完成] 安装包位于:
echo   frontend-tauri\src-tauri\target\x86_64-pc-windows-msvc\release\bundle\nsis\
echo   frontend-tauri\src-tauri\target\x86_64-pc-windows-msvc\release\bundle\msi\
:skip_tauri

REM ---------------------------------------------------------------------------
REM [3/3] Capacitor Android
REM ---------------------------------------------------------------------------
if /i "%ARG%"=="all" goto :do_android
if /i "%ARG%"=="android" goto :do_android
goto :skip_android
:do_android
echo.
echo [3/3] 构建 Capacitor Android (Debug APK)...
pushd frontend-capacitor
if not exist node_modules (
    echo 首次运行，安装 npm 依赖...
    call npm install
    if errorlevel 1 ( popd & goto :fail )
)
if not exist android (
    echo [提示] android\ 目录尚未生成，正在执行 npx cap add android ...
    call npx cap add android
    if errorlevel 1 ( popd & goto :fail )
)
call npx cap sync android
if errorlevel 1 ( popd & goto :fail )
pushd android
call gradlew.bat assembleDebug
if errorlevel 1 (
    popd & popd
    echo [错误] Gradle 构建失败，请检查 JDK 17+ 与 Android SDK 环境。
    goto :fail
)
popd & popd
echo [完成] APK 位于:
echo   frontend-capacitor\android\app\build\outputs\apk\debug\app-debug.apk
echo (Release AAB 请执行: cd frontend-capacitor ^&^& npm run build:aab)
:skip_android

echo.
echo ============================================================
echo   全部构建完成
echo ============================================================
popd
endlocal
exit /b 0

:fail
echo.
echo ============================================================
echo   构建中断，请根据上方错误信息排查后重试。
echo ============================================================
popd
endlocal
exit /b 1
