/**
 * 灵犀 — 认证与计费前端模块
 *
 * 提供：
 * - 登录/登出管理
 * - Token 持久化（localStorage）
 * - 自动为 fetch 请求添加 Authorization header
 * - 余额查询与显示
 * - 计费扣款触发
 *
 * 使用：在 HTML 中引入本文件后，通过 window.AuthManager 访问
 */
(function () {
  "use strict";

  // --- 常量 ---
  var TOKEN_KEY = "lingxi_token"; // access_token (JWT)
  var REFRESH_KEY = "lingxi_refresh_token"; // refresh_token
  var USER_KEY = "lingxi_user";
  var AUTH_OVERLAY_ID = "auth-overlay";
  var BALANCE_BAR_ID = "balance-bar";
  var BALANCE_AMOUNT_ID = "balance-amount";
  var LOGIN_FORM_ID = "login-form";
  var LOGIN_USERNAME_ID = "login-username";
  var LOGIN_PASSWORD_ID = "login-password";
  var LOGIN_ERROR_ID = "login-error";
  var LOGIN_SUBMIT_ID = "login-submit";
  var LOGOUT_BTN_ID = "logout-btn";

  // --- 获取 API base ---
  function getApiBase() {
    if (window.API_CONFIG && window.API_CONFIG.apiBase) {
      return window.API_CONFIG.apiBase.replace(/\/$/, "");
    }
    return "";
  }

  // --- Token 管理 ---
  function getToken() {
    try {
      return localStorage.getItem(TOKEN_KEY) || null;
    } catch (e) {
      return null;
    }
  }

  function setToken(token) {
    try {
      localStorage.setItem(TOKEN_KEY, token);
    } catch (e) {
      console.warn("[Auth] 无法写入 localStorage", e);
    }
  }

  function getRefreshToken() {
    try {
      return localStorage.getItem(REFRESH_KEY) || null;
    } catch (e) {
      return null;
    }
  }

  function setRefreshToken(token) {
    try {
      if (token) {
        localStorage.setItem(REFRESH_KEY, token);
      } else {
        localStorage.removeItem(REFRESH_KEY);
      }
    } catch (e) {
      console.warn("[Auth] 无法写入 localStorage", e);
    }
  }

  function clearToken() {
    try {
      localStorage.removeItem(TOKEN_KEY);
      localStorage.removeItem(REFRESH_KEY);
      localStorage.removeItem(USER_KEY);
    } catch (e) {
      // 忽略
    }
  }

  // --- 用户信息 ---
  function getUser() {
    try {
      var raw = localStorage.getItem(USER_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch (e) {
      return null;
    }
  }

  function setUser(user) {
    try {
      localStorage.setItem(USER_KEY, JSON.stringify(user));
    } catch (e) {
      // 忽略
    }
  }

  // --- 解码 JWT payload 的 exp（base64url），失败返回 null ---
  function decodeJwtExp(token) {
    try {
      var parts = token.split(".");
      if (parts.length !== 3) return null;
      var payload = parts[1].replace(/-/g, "+").replace(/_/g, "/");
      while (payload.length % 4) payload += "=";
      var json = JSON.parse(atob(payload));
      return typeof json.exp === "number" ? json.exp : null;
    } catch (e) {
      return null;
    }
  }

  // --- 检查登录状态 ---
  function isLoggedIn() {
    var token = getToken();
    if (!token) return false;
    var now = Math.floor(Date.now() / 1000);
    // 优先直接解析 JWT 自身的 exp（不再依赖本地自存 expires_at）
    var exp = decodeJwtExp(token);
    if (exp !== null) {
      // 过期则视为未登录（不清理 refresh_token，留给 tryRefresh 静默续期）
      return exp > now;
    }
    // 解码失败回退旧逻辑（本地 expires_at）
    var user = getUser();
    if (!user || !user.expires_at) return false;
    if (user.expires_at < now) {
      return false;
    }
    return true;
  }

  // --- Toast 提示 ---
  function showToast(message, type) {
    var existing = document.getElementById("auth-toast");
    if (existing) existing.remove();

    var toast = document.createElement("div");
    toast.id = "auth-toast";
    toast.className = "auth-toast " + (type || "");
    toast.textContent = message;
    document.body.appendChild(toast);

    // 触发动画
    setTimeout(function () {
      toast.classList.add("show");
    }, 10);

    // 自动消失
    setTimeout(function () {
      toast.classList.remove("show");
      setTimeout(function () {
        if (toast.parentNode) toast.remove();
      }, 300);
    }, 2500);
  }

  // --- 显示/隐藏登录遮罩 ---
  function showLoginOverlay() {
    var overlay = document.getElementById(AUTH_OVERLAY_ID);
    if (overlay) {
      overlay.hidden = false;
    }
    var balanceBar = document.getElementById(BALANCE_BAR_ID);
    if (balanceBar) {
      balanceBar.hidden = true;
    }
  }

  function hideLoginOverlay() {
    var overlay = document.getElementById(AUTH_OVERLAY_ID);
    if (overlay) {
      overlay.hidden = true;
    }
    var balanceBar = document.getElementById(BALANCE_BAR_ID);
    if (balanceBar) {
      balanceBar.hidden = false;
    }
  }

  // --- 更新余额显示 ---
  function updateBalanceDisplay(balance) {
    var el = document.getElementById(BALANCE_AMOUNT_ID);
    if (!el) return;

    var formatted = parseFloat(balance || 0).toFixed(2);
    el.textContent = "¥" + formatted;

    // 余额状态着色
    el.classList.remove("low", "critical");
    if (balance < 20) {
      el.classList.add("critical");
    } else if (balance < 50) {
      el.classList.add("low");
    }

    // 同步更新 localStorage 中的余额
    var user = getUser();
    if (user) {
      user.balance = parseFloat(balance);
      setUser(user);
    }
  }

  // --- 登录 ---
  async function login(username, password) {
    var apiBase = getApiBase();
    var url = apiBase + "/api/auth/login";

    try {
      var resp = await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: username, password: password }),
      });

      var data = await resp.json();

      if (data.ok && data.access_token) {
        // 契约：{ok, user_id, username, role, balance, access_token, refresh_token, expires_in}
        setToken(data.access_token);
        setRefreshToken(data.refresh_token || "");
        setUser({
          username: data.username,
          user_id: data.user_id,
          role: data.role,
          expires_at: Math.floor(Date.now() / 1000) + (data.expires_in || 0),
          balance: data.balance,
        });
        updateBalanceDisplay(data.balance);
        hideLoginOverlay();
        showToast("登录成功", "success");

        // 触发状态变化回调
        _notifyAuthStateChange(true, data.username);
        return true;
      } else {
        showLoginError(data.error || "登录失败");
        return false;
      }
    } catch (e) {
      showLoginError("网络错误，请检查连接");
      console.error("[Auth] 登录失败", e);
      return false;
    }
  }

  // --- 登出 ---
  async function logout() {
    var token = getToken();
    if (token) {
      var apiBase = getApiBase();
      try {
        await fetch(apiBase + "/api/auth/logout", {
          method: "POST",
          headers: { Authorization: "Bearer " + token },
        });
      } catch (e) {
        // 忽略网络错误
      }
    }
    clearToken();
    showLoginOverlay();
    showToast("已退出登录", "");

    _notifyAuthStateChange(false, null);
  }

  // --- 显示登录错误 ---
  function showLoginError(msg) {
    var el = document.getElementById(LOGIN_ERROR_ID);
    if (el) {
      el.textContent = msg;
      el.classList.add("show");
      setTimeout(function () {
        el.classList.remove("show");
      }, 3000);
    }
  }

  // --- 设置按钮加载状态 ---
  function setButtonLoading(loading) {
    var btn = document.getElementById(LOGIN_SUBMIT_ID);
    if (!btn) return;
    btn.disabled = loading;
    var textEl = btn.querySelector(".auth-button-text");
    var spinnerEl = btn.querySelector(".auth-button-spinner");
    if (textEl) textEl.hidden = loading;
    if (spinnerEl) spinnerEl.hidden = !loading;
  }

  // --- 获取计费摘要 ---
  async function getBillingSummary() {
    var token = getToken();
    if (!token) return null;

    var apiBase = getApiBase();
    try {
      // fetchWithAuth 自带 401 → 刷新 → 重试；仍 401 时已走 logout 流程
      var resp = await fetchWithAuth(apiBase + "/api/billing/summary");
      if (resp.status === 401) {
        return null;
      }
      var data = await resp.json();
      if (data.ok) {
        updateBalanceDisplay(data.balance);
      }
      return data;
    } catch (e) {
      console.error("[Auth] 查询计费摘要失败", e);
      return null;
    }
  }

  // --- 执行计费扣款 ---
  async function chargeBilling() {
    var token = getToken();
    if (!token) return null;

    var apiBase = getApiBase();
    try {
      var resp = await fetchWithAuth(apiBase + "/api/billing/charge", {
        method: "POST",
      });
      if (resp.status === 401) {
        return null;
      }
      var data = await resp.json();
      if (data.ok) {
        updateBalanceDisplay(data.new_balance);
      }
      return data;
    } catch (e) {
      console.error("[Auth] 计费扣款失败", e);
      return null;
    }
  }

  // --- Token 刷新 ---
  // 并发去重：多个请求同时 401 时共享同一个刷新 Promise
  var _refreshing = null;

  /**
   * 用 refresh_token 换取新的 access_token。
   * 成功：更新本地 token 并返回 true；失败（含 401）返回 false。
   */
  async function tryRefresh() {
    if (_refreshing) return _refreshing;
    _refreshing = (async function () {
      var refreshToken = getRefreshToken();
      if (!refreshToken) return false;
      try {
        var resp = await fetch(getApiBase() + "/api/auth/refresh", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ refresh_token: refreshToken }),
        });
        if (!resp.ok) return false;
        var data = await resp.json();
        if (data.ok && data.access_token) {
          setToken(data.access_token);
          // 同步刷新本地 expires_at 缓存（仅作回退判断用）
          var user = getUser() || {};
          user.expires_at = Math.floor(Date.now() / 1000) + (data.expires_in || 0);
          setUser(user);
          return true;
        }
        return false;
      } catch (e) {
        console.warn("[Auth] 刷新 token 失败", e);
        return false;
      } finally {
        _refreshing = null;
      }
    })();
    return _refreshing;
  }

  // --- 封装 fetch：自动添加 Authorization（仅 JWT；401 先刷新再重试一次） ---
  async function fetchWithAuth(url, options, _retried) {
    options = options || {};
    options.headers = Object.assign({}, options.headers || {});

    var token = getToken();
    if (token) {
      options.headers["Authorization"] = "Bearer " + token;
    }

    var resp = await fetch(url, options);
    if (resp.status === 401) {
      if (!_retried && (await tryRefresh())) {
        // 刷新成功：用新 token 重发原请求一次
        return fetchWithAuth(url, options, true);
      }
      // 刷新失败或重试仍 401：清态 + 弹登录 + 通知
      await logout();
      showToast("登录已过期，请重新登录", "error");
    }
    return resp;
  }

  // --- 认证状态变化回调 ---
  var _callbacks = [];

  function onAuthStateChanged(callback) {
    _callbacks.push(callback);
  }

  function _notifyAuthStateChange(loggedIn, username) {
    _callbacks.forEach(function (cb) {
      try {
        cb(loggedIn, username);
      } catch (e) {
        console.error("[Auth] 状态变化回调异常", e);
      }
    });
  }

  // --- 初始化 ---
  function init() {
    var form = document.getElementById(LOGIN_FORM_ID);
    if (form) {
      form.addEventListener("submit", async function (e) {
        e.preventDefault();
        var usernameEl = document.getElementById(LOGIN_USERNAME_ID);
        var passwordEl = document.getElementById(LOGIN_PASSWORD_ID);
        if (!usernameEl || !passwordEl) return;

        var username = usernameEl.value.trim();
        var password = passwordEl.value;

        if (!username || !password) {
          showLoginError("请输入账号和密码");
          return;
        }

        setButtonLoading(true);
        await login(username, password);
        setButtonLoading(false);
      });
    }

    var logoutBtn = document.getElementById(LOGOUT_BTN_ID);
    if (logoutBtn) {
      logoutBtn.addEventListener("click", function () {
        logout();
      });
    }

    // 检查登录状态
    if (isLoggedIn()) {
      hideLoginOverlay();
      var user = getUser();
      if (user && user.balance !== undefined) {
        updateBalanceDisplay(user.balance);
      }
      // 异步刷新余额
      getBillingSummary();
    } else if (getRefreshToken()) {
      // access_token 已过期但 refresh_token 仍在：尝试静默续期，免重新登录
      tryRefresh().then(function (ok) {
        if (ok) {
          hideLoginOverlay();
          var u = getUser();
          if (u && u.balance !== undefined) {
            updateBalanceDisplay(u.balance);
          }
          getBillingSummary();
        } else {
          clearToken();
          showLoginOverlay();
        }
      });
    } else {
      showLoginOverlay();
    }
  }

  // --- DOM 就绪后初始化 ---
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }

  // --- 暴露全局接口 ---
  window.AuthManager = {
    login: login,
    logout: logout,
    isLoggedIn: isLoggedIn,
    getToken: getToken,
    getRefreshToken: getRefreshToken,
    tryRefresh: tryRefresh,
    getUser: getUser,
    fetchWithAuth: fetchWithAuth,
    getBillingSummary: getBillingSummary,
    chargeBilling: chargeBilling,
    updateBalanceDisplay: updateBalanceDisplay,
    onAuthStateChanged: onAuthStateChanged,
    showLoginOverlay: showLoginOverlay,
    hideLoginOverlay: hideLoginOverlay,
    showToast: showToast,
  };
})();
