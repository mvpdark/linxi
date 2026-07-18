/**
 * 灵犀 — APK 自动更新模块
 *
 * 功能：
 *   1. 应用启动时检查 GitHub Releases 是否有新版本
 *   2. 发现新版本时弹出更新提示
 *   3. 用户确认后下载 APK（显示进度）
 *   4. 下载完成自动触发安装
 *
 * 依赖：
 *   - Capacitor ApkUpdater 插件（原生 Android 端）
 *   - 浏览器环境则跳过（仅 Android 端生效）
 */
(function (window) {
    'use strict';

    const STATE = {
        IDLE: 'idle',
        CHECKING: 'checking',
        AVAILABLE: 'available',
        DOWNLOADING: 'downloading',
        READY: 'ready',
        ERROR: 'error',
    };

    // 更新状态
    let updateState = STATE.IDLE;
    let updateInfo = null; // { version, currentVersion, downloadUrl, releaseNotes, isNewer }
    let downloadProgress = 0;
    let listeners = [];

    // Capacitor 插件实例（Android 端注入）
    function getPlugin() {
        if (window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.ApkUpdater) {
            return window.Capacitor.Plugins.ApkUpdater;
        }
        return null;
    }

    /**
     * 检查是否在 Android Capacitor 环境中
     */
    function isNative() {
        return !!(window.Capacitor && window.Capacitor.isNative && window.Capacitor.isNative());
    }

    /**
     * 注册状态变更监听
     */
    function onChange(cb) {
        listeners.push(cb);
        return () => {
            listeners = listeners.filter((l) => l !== cb);
        };
    }

    function notify() {
        const data = { state: updateState, info: updateInfo, progress: downloadProgress };
        listeners.forEach((cb) => cb(data));
    }

    /**
     * 检查更新
     * @param {boolean} silent - 静默模式（不提示"已是最新"）
     */
    async function checkUpdate(silent = true) {
        const plugin = getPlugin();
        if (!plugin) {
            console.log('[updater] 非 Android 环境，跳过更新检查');
            return;
        }

        updateState = STATE.CHECKING;
        notify();

        try {
            const result = await plugin.checkUpdate();
            updateInfo = result;

            if (result.isNewer) {
                updateState = STATE.AVAILABLE;
                console.log(`[updater] 发现新版本: ${result.version} (当前 ${result.currentVersion})`);
            } else {
                updateState = STATE.IDLE;
                console.log(`[updater] 已是最新版本: ${result.currentVersion}`);
                if (!silent) {
                    // 非静默模式，提示用户已是最新
                }
            }
            notify();
            return result;
        } catch (e) {
            console.error('[updater] 检查更新失败:', e);
            updateState = STATE.ERROR;
            notify();
            return null;
        }
    }

    /**
     * 开始下载并安装更新
     */
    async function downloadAndInstall() {
        const plugin = getPlugin();
        if (!plugin || !updateInfo) {
            console.error('[updater] 无法下载：插件不可用或无更新信息');
            return;
        }

        updateState = STATE.DOWNLOADING;
        downloadProgress = 0;
        notify();

        // 监听下载进度
        plugin.addListener('downloadProgress', (data) => {
            downloadProgress = data.progress || 0;
            notify();
        });

        try {
            await plugin.downloadAndInstall({ url: updateInfo.downloadUrl });
            updateState = STATE.READY;
            notify();
            // Android 会自动弹出安装界面
        } catch (e) {
            console.error('[updater] 下载失败:', e);
            updateState = STATE.ERROR;
            notify();
        }
    }

    /**
     * 稍后再说（重置状态，4 小时后再次检查）
     */
    function later() {
        updateState = STATE.IDLE;
        updateInfo = null;
        notify();
        // 4 小时后重新检查
        setTimeout(() => checkUpdate(true), 4 * 60 * 60 * 1000);
    }

    /**
     * 应用启动时自动检查（延迟 3 秒，避免影响首屏加载）
     */
    function autoCheckOnStart() {
        if (!isNative()) return;
        setTimeout(() => checkUpdate(true), 3000);
    }

    // 暴露到全局
    window.AppUpdater = {
        STATE,
        isNative,
        checkUpdate,
        downloadAndInstall,
        later,
        onChange,
        autoCheckOnStart,
        getState: () => ({ state: updateState, info: updateInfo, progress: downloadProgress }),
    };

    // 启动时自动检查
    if (document.readyState === 'complete' || document.readyState === 'interactive') {
        autoCheckOnStart();
    } else {
        document.addEventListener('DOMContentLoaded', autoCheckOnStart);
    }

    console.log('[updater] APK 自动更新模块加载完成');
})(window);
