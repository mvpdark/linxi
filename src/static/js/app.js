/**
 * 灵犀 — 主应用入口
 * 使用 Vue 3 全局构建，hash 路由，管理首页和全局状态。
 * 组件 ChatView / ImageEditView / PanoramaView 分别在 chat.js / image-edit.js / panorama.js 中定义。
 *
 * 全局 API 配置：
 *   页面加载时从 config.json（与 index.html 同目录，相对路径）读取 { apiBase, token }，
 *   所有 API 请求统一走 apiFetch()（自动拼接 base + Authorization header），
 *   WebSocket 用 getWsUrl()，图片资源用 getImgUrl()。
 */
var { createApp, ref, reactive, onMounted, onUnmounted } = Vue;

// ============================================================
// 全局 API 配置
// ============================================================

/** 运行期配置（config.json 加载失败时回退为同源 + 无 token） */
window.API_CONFIG = {
  apiBase: '',
  token: '',
};

/**
 * 加载 config.json（相对路径，兼容 Web 部署与 Tauri/Capacitor 打包）。
 * 返回 Promise，resolve 值为最终生效的配置对象。
 */
window.apiConfigReady = (async function loadApiConfig() {
  try {
    const res = await fetch('config.json?v=' + Date.now());
    if (res.ok) {
      const cfg = await res.json();
      if (cfg && typeof cfg === 'object') {
        if (typeof cfg.apiBase === 'string') {
          // 去掉末尾斜杠，避免拼接出双斜杠
          window.API_CONFIG.apiBase = cfg.apiBase.replace(/\/+$/, '');
        }
        if (typeof cfg.token === 'string') {
          window.API_CONFIG.token = cfg.token;
        }
      }
    } else {
      console.warn('config.json 加载失败(HTTP ' + res.status + ')，使用默认同源配置');
    }
  } catch (e) {
    console.warn('config.json 加载失败，使用默认同源配置:', e);
  }
  return window.API_CONFIG;
})();

/**
 * 统一 API 请求封装：自动拼接 apiBase + Authorization header。
 * @param {string} path — 接口路径（如 /api/sessions）；绝对 http(s) URL 原样请求
 * @param {Object} [options] — fetch 选项
 * @returns {Promise<Response>}
 */
window.apiFetch = async function apiFetch(path, options) {
  await window.apiConfigReady;
  options = options || {};
  const headers = Object.assign({}, options.headers || {});
  if (window.API_CONFIG.token && !headers['Authorization']) {
    headers['Authorization'] = 'Bearer ' + window.API_CONFIG.token;
  }
  const url = /^https?:\/\//i.test(path) ? path : window.API_CONFIG.apiBase + path;
  return fetch(url, Object.assign({}, options, { headers: headers }));
};

/**
 * 构建 WebSocket URL：把 apiBase 的 http/https 转成 ws/wss，并附带 token。
 * @param {string} path — WS 路径（如 /ws/chat）
 * @returns {string}
 */
window.getWsUrl = function getWsUrl(path) {
  const base = window.API_CONFIG.apiBase || location.origin;
  const wsBase = base.replace(/^http/i, 'ws');
  let url = wsBase + path;
  if (window.API_CONFIG.token) {
    url += (path.indexOf('?') >= 0 ? '&' : '?') + 'token=' + encodeURIComponent(window.API_CONFIG.token);
  }
  return url;
};

/**
 * 把 /uploads/ 等相对路径转成完整 URL。
 * 绝对 URL（http/https/data/blob）原样返回。
 * @param {string} path — 资源路径
 * @returns {string}
 */
window.getImgUrl = function getImgUrl(path) {
  if (!path) return path;
  if (/^(https?:|data:|blob:)/i.test(path)) return path;
  return window.API_CONFIG.apiBase + path;
};

// ============================================================
// Vue 应用
// ============================================================

const app = createApp({
  setup() {
    const route = ref('home');
    const currentSessionId = ref('');
    const recentSessions = ref([]);
    const isDesktop = ref(false);

    // === 路由 ===
    function parseHash() {
      const hash = location.hash.slice(1);
      if (hash.startsWith('/chat')) {
        route.value = 'chat';
        const parts = hash.split('/');
        if (parts.length >= 3 && parts[2]) {
          currentSessionId.value = parts[2];
        }
      } else if (hash.startsWith('/image-edit')) {
        route.value = 'image-edit';
      } else if (hash.startsWith('/panorama')) {
        route.value = 'panorama';
      } else {
        route.value = 'home';
      }
    }

    parseHash();

    function navigate(target) {
      if (target === 'chat') {
        currentSessionId.value = '';
        location.hash = '/chat';
      } else if (target === 'image-edit') {
        location.hash = '/image-edit';
      } else if (target === 'panorama') {
        location.hash = '/panorama';
      } else {
        location.hash = '/';
        // 返回首页时刷新会话列表
        loadSessions();
      }
    }

    function openSession(sessionId) {
      currentSessionId.value = sessionId;
      location.hash = '/chat/' + sessionId;
    }

    // === 会话管理 ===
    async function loadSessions() {
      try {
        const res = await apiFetch('/api/sessions');
        const data = await res.json();
        recentSessions.value = data.sessions || [];
      } catch (e) {
        console.error('Failed to load sessions:', e);
      }
    }

    // === 格式化时间 ===
    function formatTime(timestamp) {
      if (!timestamp) return '';
      const now = Date.now();
      const ts = typeof timestamp === 'number' ? timestamp : Date.parse(timestamp);
      const diff = now - ts;
      if (diff < 60000) return '刚刚';
      if (diff < 3600000) return Math.floor(diff / 60000) + '分钟前';
      if (diff < 86400000) return Math.floor(diff / 3600000) + '小时前';
      if (diff < 604800000) return Math.floor(diff / 86400000) + '天前';
      const d = new Date(ts);
      return `${d.getMonth() + 1}月${d.getDate()}日`;
    }

    function onResize() {
      isDesktop.value = window.innerWidth >= 768;
    }

    onMounted(() => {
      window.addEventListener('hashchange', parseHash);
      window.addEventListener('resize', onResize);
      onResize();
      loadSessions();
    });

    onUnmounted(() => {
      window.removeEventListener('hashchange', parseHash);
      window.removeEventListener('resize', onResize);
    });

    return {
      route,
      currentSessionId,
      recentSessions,
      isDesktop,
      navigate,
      openSession,
      formatTime,
    };
  },
});

// 注册组件（从全局变量加载，由 chat.js / image-edit.js / panorama.js 定义）
if (window.ChatView) {
  app.component('chat-view', window.ChatView);
}
if (window.ImageEditView) {
  app.component('image-edit-view', window.ImageEditView);
}
if (window.PanoramaView) {
  app.component('panorama-view', window.PanoramaView);
}

// 等 API 配置加载完成后再挂载，保证组件 setup/onMounted 中
// 调用 apiFetch / getWsUrl / getImgUrl 时配置已就绪
window.apiConfigReady.then(() => {
  app.mount('#app');
});
