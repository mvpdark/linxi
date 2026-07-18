/**
 * 图片本地存储模块（IndexedDB 封装）
 *
 * 架构改造：后端只返回 base64 data URL，前端负责持久化。
 * - saveImage(dataUrl) → id：存图，返回唯一 ID（写历史用）
 * - getImageUrl(id) → blobUrl：读图并转 blob URL 供 <img> 显示
 * - getImageDataUrl(id) → dataUrl：读图返回原始 data URL
 * - deleteImage(id)：删除单张
 * - clearAll()：清空（注销/换号时用）
 *
 * 存储策略：
 * - key = id（img_xxxxx 或 upl_xxxxx 或 pano_xxxxx）
 * - value = { dataUrl: "data:image/...;base64,...", createdAt: timestamp }
 * - 使用 IndexedDB（容量远超 localStorage 的 5MB 限制）
 *
 * Blob URL 生命周期：
 * - getImageUrl 返回 URL.createObjectURL(blob)，调用方负责 revokeObjectURL
 * - 组件 onUnmounted 时统一 revoke
 */
(function (window) {
    'use strict';

    const DB_NAME = 'lingxi-images';
    const DB_VERSION = 1;
    const STORE_NAME = 'images';
    let dbPromise = null;

    function openDB() {
        if (dbPromise) return dbPromise;
        dbPromise = new Promise((resolve, reject) => {
            const req = indexedDB.open(DB_NAME, DB_VERSION);
            req.onupgradeneeded = (e) => {
                const db = e.target.result;
                if (!db.objectStoreNames.contains(STORE_NAME)) {
                    db.createObjectStore(STORE_NAME, { keyPath: 'id' });
                }
            };
            req.onsuccess = () => resolve(req.result);
            req.onerror = () => reject(req.error);
        });
        return dbPromise;
    }

    /**
     * 保存 data URL 到 IndexedDB
     * @param {string} dataUrl data:image/xxx;base64,...
     * @param {string} [customId] 自定义 ID，不传则自动生成
     * @returns {Promise<string>} id
     */
    async function saveImage(dataUrl, customId) {
        if (!dataUrl) return '';
        // data URL 优先用自定义 ID（如后端返回的 ID），否则生成
        const id = customId || ('img_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8));
        const db = await openDB();
        return new Promise((resolve, reject) => {
            const tx = db.transaction(STORE_NAME, 'readwrite');
            tx.objectStore(STORE_NAME).put({
                id: id,
                dataUrl: dataUrl,
                createdAt: Date.now(),
            });
            tx.oncomplete = () => resolve(id);
            tx.onerror = () => reject(tx.error);
        });
    }

    /**
     * 获取 data URL
     * @param {string} id
     * @returns {Promise<string|null>} dataUrl 或 null
     */
    async function getImageDataUrl(id) {
        if (!id) return null;
        const db = await openDB();
        return new Promise((resolve, reject) => {
            const tx = db.transaction(STORE_NAME, 'readonly');
            const req = tx.objectStore(STORE_NAME).get(id);
            req.onsuccess = () => resolve(req.result ? req.result.dataUrl : null);
            req.onerror = () => reject(req.error);
        });
    }

    /**
     * 获取图片 URL（供 <img> 显示）
     * 直接返回 data URL，避免 Android WebView 中 fetch(dataUrl) 兼容性问题
     * @param {string} id
     * @returns {Promise<string|null>} dataUrl 或 null
     */
    async function getImageUrl(id) {
        return await getImageDataUrl(id);
    }

    /**
     * 批量获取 Blob URL
     * @param {string[]} ids
     * @returns {Promise<Object>} { id: blobUrl, ... }
     */
    async function getImageUrls(ids) {
        const result = {};
        await Promise.all((ids || []).map(async (id) => {
            result[id] = await getImageUrl(id);
        }));
        return result;
    }

    /**
     * 删除单张图片
     */
    async function deleteImage(id) {
        if (!id) return;
        const db = await openDB();
        return new Promise((resolve, reject) => {
            const tx = db.transaction(STORE_NAME, 'readwrite');
            tx.objectStore(STORE_NAME).delete(id);
            tx.oncomplete = () => resolve(true);
            tx.onerror = () => reject(tx.error);
        });
    }

    /**
     * 清空所有图片（注销/换号时调用）
     */
    async function clearAll() {
        const db = await openDB();
        return new Promise((resolve, reject) => {
            const tx = db.transaction(STORE_NAME, 'readwrite');
            tx.objectStore(STORE_NAME).clear();
            tx.oncomplete = () => resolve(true);
            tx.onerror = () => reject(tx.error);
        });
    }

    /**
     * 工具函数：判断字符串是否为 data URL
     */
    function isDataUrl(str) {
        return typeof str === 'string' && str.startsWith('data:');
    }

    /**
     * 工具函数：判断字符串是否为图片 ID（非 data URL、非 http URL）
     * 后端返回的 ID 格式：img_xxx / upl_xxx / pano_xxx
     */
    function isImageId(str) {
        if (!str || typeof str !== 'string') return false;
        if (isDataUrl(str)) return false;
        if (str.startsWith('http://') || str.startsWith('https://')) return false;
        // ID 格式：前缀_随机字符
        return /^(img|upl|pano)_[a-z0-9]+$/i.test(str);
    }

    /**
     * 统一的 URL 解析：支持 ID / data URL / http URL
     * - ID → 从 IndexedDB 读 blob URL（异步）
     * - data URL → 原样返回
     * - http URL → 原样返回
     * @returns {Promise<string|null>}
     */
    async function resolveUrl(str) {
        if (!str) return null;
        if (isDataUrl(str) || str.startsWith('http')) return str;
        if (isImageId(str)) return await getImageUrl(str);
        // 兼容旧 /uploads/ 格式：交给 getImgUrl 拼接 apiBase
        return window.getImgUrl ? window.getImgUrl(str) : str;
    }

    // 暴露到全局
    window.ImageStore = {
        saveImage,
        getImageDataUrl,
        getImageUrl,
        getImageUrls,
        deleteImage,
        clearAll,
        isDataUrl,
        isImageId,
        resolveUrl,
        DB_NAME,
        STORE_NAME,
    };

    console.log('[image-store] 模块加载完成，IndexedDB:', DB_NAME);
})(window);
