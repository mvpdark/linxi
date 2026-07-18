/**
 * 灵犀 — 主应用入口
 * 使用 Vue 3 全局构建，hash 路由，管理首页和全局状态。
 * 组件 ChatView / ImageEditView / PanoramaView 分别在 chat.js / image-edit.js / panorama.js 中定义。
 *
 * 全局 API 配置：
 *   页面加载时从 config.json（与 index.html 同目录，相对路径）读取 { apiBase }，
 *   所有 API 请求统一走 apiFetch()（自动拼接 base + JWT Authorization header），
 *   WebSocket 用 getWsUrl()，图片资源用 getImgUrl()。
 *   多租户模式只认 JWT（AuthManager），config.json 不再下发静态 token。
 */
var { createApp, ref, reactive, onMounted, onUnmounted } = Vue;

// ============================================================
// 全局 API 配置
// ============================================================

/** 运行期配置（config.json 加载失败时回退为同源） */
window.API_CONFIG = {
  apiBase: '',
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
 * 统一 API 请求封装：自动拼接 apiBase + Authorization(JWT) header。
 * 401 统一处理：先 AuthManager.tryRefresh() 刷新并重发原请求一次，
 * 仍 401 则 AuthManager.logout() 清态并弹登录。
 * 无 JWT 时不加 Authorization 头（/api/health 等公开接口仍可用）。
 * @param {string} path — 接口路径（如 /api/sessions）；绝对 http(s) URL 原样请求
 * @param {Object} [options] — fetch 选项
 * @param {boolean} [_retried] — 内部标记：刷新后重试过一次，避免死循环
 * @returns {Promise<Response>}
 */
window.apiFetch = async function apiFetch(path, options, _retried) {
  await window.apiConfigReady;
  options = options || {};
  const headers = Object.assign({}, options.headers || {});
  const jwt = (window.AuthManager && window.AuthManager.getToken && window.AuthManager.getToken()) || '';
  if (jwt && !headers['Authorization']) {
    headers['Authorization'] = 'Bearer ' + jwt;
  }
  const url = /^https?:\/\//i.test(path) ? path : window.API_CONFIG.apiBase + path;
  const resp = await fetch(url, Object.assign({}, options, { headers: headers }));
  if (resp.status === 401 && window.AuthManager) {
    // 先尝试静默刷新并重发一次
    if (!_retried && window.AuthManager.tryRefresh && (await window.AuthManager.tryRefresh())) {
      return apiFetch(path, options, true);
    }
    // 刷新失败或重试仍 401：清态 + 弹登录
    if (window.AuthManager.logout) {
      window.AuthManager.logout();
    }
  }
  return resp;
};

/**
 * 构建 WebSocket URL：把 apiBase 的 http/https 转成 ws/wss。
 * 鉴权走连接后首帧 {"type":"auth","token":"<jwt>"}（服务端兼容旧 ?token= 查询参数）。
 * @param {string} path — WS 路径（如 /ws/chat）
 * @returns {string}
 */
window.getWsUrl = function getWsUrl(path) {
  const base = window.API_CONFIG.apiBase || location.origin;
  const wsBase = base.replace(/^http/i, 'ws');
  return wsBase + path;
};

/**
 * 把 /uploads/ 等相对路径转成完整 URL。
 * 绝对 URL（http/https/data/blob）原样返回。
 * @param {string} path — 资源路径
 * @returns {string}
 */
window.getImgUrl = function getImgUrl(path) {
  if (!path) return path;
  if (/^(https?:|data:|blob:|\.\/)/i.test(path)) return path; // ./ 开头为本地相对路径，不拼 apiBase
  return window.API_CONFIG.apiBase + path;
};

/**
 * 全局格式化时间（相对时间），供各视图复用（chat.js 等不再各自实现）。
 * app.js 在 index.html 中最后加载，但组件 setup 均在其挂载后执行，可安全引用。
 */
window.formatTime = function formatTime(timestamp) {
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
        if (!res.ok) {
          console.warn('加载会话列表失败: HTTP ' + res.status);
          return;
        }
        const data = await res.json();
        recentSessions.value = data.sessions || [];
      } catch (e) {
        console.error('Failed to load sessions:', e);
      }
    }

    // === 格式化时间（复用全局实现） ===
    const formatTime = window.formatTime;

    // === 用户菜单 ===
    const showUserMenu = Vue.ref(false);
    const userInfo = Vue.ref({
      username: '',
      role: '',
      balance: 0,
    });

    // === APK 自动更新 ===
    const updateModal = Vue.ref({
      show: false,
      state: 'idle', // idle, checking, available, downloading, error
      info: null,
      progress: 0,
    });

    function showUpdateModal() {
      updateModal.value.show = true;
    }

    function hideUpdateModal() {
      updateModal.value.show = false;
    }

    // 初始化更新检测
    function initUpdater() {
      if (!window.AppUpdater) return;

      // 监听更新状态变化
      window.AppUpdater.onChange(({ state, info, progress }) => {
        updateModal.value.state = state;
        updateModal.value.info = info;
        updateModal.value.progress = progress;

        // 发现新版本时自动弹窗
        if (state === 'available' && info && info.isNewer) {
          updateModal.value.show = true;
        }
      });
    }

    async function checkUpdateManual() {
      if (!window.AppUpdater) return;
      updateModal.value.show = true;
      updateModal.value.state = 'checking';
      await window.AppUpdater.checkUpdate(false);
    }

    async function confirmUpdate() {
      if (!window.AppUpdater) return;
      await window.AppUpdater.downloadAndInstall();
    }

    function dismissUpdate() {
      if (window.AppUpdater) window.AppUpdater.later();
      updateModal.value.show = false;
    }

    // 从 AuthManager 加载用户信息
    function loadUserInfo() {
      if (window.AuthManager && window.AuthManager.getUser) {
        const user = window.AuthManager.getUser() || {};
        userInfo.value = {
          username: user.username || '',
          role: user.role || '',
          balance: parseFloat(user.balance) || 0,
        };
      }
    }

    // 刷新余额（调用 /api/auth/me）
    async function refreshBalance() {
      try {
        const res = await apiFetch('/api/auth/me');
        if (res.ok) {
          const data = await res.json();
          if (data.ok) {
            userInfo.value.balance = parseFloat(data.balance) || 0;
            // 同步到 AuthManager
            if (window.AuthManager && window.AuthManager.updateBalanceDisplay) {
              window.AuthManager.updateBalanceDisplay(data.balance);
            }
          }
        }
      } catch (e) {
        console.error('刷新余额失败:', e);
      }
    }

    function handleUserLogout() {
      if (window.AuthManager && window.AuthManager.logout) {
        window.AuthManager.logout();
      }
    }

    // 点击空白处关闭菜单
    function onDocClick(e) {
      if (showUserMenu.value) {
        showUserMenu.value = false;
      }
    }

    // 监听 showUserMenu 变化，打开时刷新余额
    Vue.watch(showUserMenu, (val) => {
      if (val) {
        loadUserInfo();
        refreshBalance();
      }
    });

    function onResize() {
      isDesktop.value = window.innerWidth >= 768;
    }

    onMounted(() => {
      window.addEventListener('hashchange', parseHash);
      window.addEventListener('resize', onResize);
      document.addEventListener('click', onDocClick);
      onResize();
      loadSessions();
      loadUserInfo();
      initUpdater();
    });

    onUnmounted(() => {
      window.removeEventListener('hashchange', parseHash);
      window.removeEventListener('resize', onResize);
      document.removeEventListener('click', onDocClick);
    });

    return {
      route,
      currentSessionId,
      recentSessions,
      isDesktop,
      navigate,
      openSession,
      formatTime,
      showUserMenu,
      userInfo,
      handleUserLogout,
      updateModal,
      checkUpdateManual,
      confirmUpdate,
      dismissUpdate,
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
