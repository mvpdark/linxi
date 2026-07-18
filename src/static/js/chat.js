/**
 * 灵犀 — 聊天模块 Vue 3 组件
 * 使用 Vue 3 CDN 全局构建，JS 对象定义组件（非 SFC）。
 * 组件挂载到 window.ChatView，供 app.js 注册。
 */
var { ref, reactive, computed, onMounted, onUnmounted, nextTick, watch } = Vue;

window.ChatView = {
  props: {
    sessionId: { type: String, default: '' },
  },
  emits: ['back'],
  setup(props, { emit }) {
    // ============================================================
    // 响应式状态
    // ============================================================

    // 侧边栏
    const sidebarOpen = ref(false);
    const sidebarExpanded = ref(false); // Grok-style expand panel (desktop)
    const isDesktop = ref(false);
    const sessions = ref([]);
    const activeSessionId = ref(props.sessionId || '');
    const activeSessionTitle = ref('灵犀');

    // 消息列表
    const messages = ref([]);

    // Agent 状态
    const agentStatus = reactive({
      visible: false,
      routing: false,
      items: [], // [{ name, model, status: 'running'|'done'|'error' }]
      statusText: '',
    });

    // 输入栏
    const inputText = ref('');
    const pendingImage = ref(null); // { url, filename }
    const sending = ref(false);
    const fileInputRef = ref(null);
    const messagesContainer = ref(null);

    // 图片放大弹窗
    const modalImage = ref(null); // url string
    const modalEl = ref(null);
    const modalOrientation = ref('auto'); // 'landscape' | 'portrait' | 'auto'
    const pinchScale = ref(1);
    let pinchStartDist = 0;
    let pinchStartScale = 1;
    let modalMounted = false; // 防止 nextTick 竞态：标志弹窗 DOM 是否已挂载并绑定事件
    // 单指拖动（pan）
    const panX = ref(0);
    const panY = ref(0);
    let isPanning = false;
    let panStartX = 0;
    let panStartY = 0;
    let panLastX = 0;
    let panLastY = 0;

    // 侧边栏滑动删除
    const swipeItem = reactive({ id: null, x: 0, startX: 0, dragging: false });
    const longPressTimer = ref(null);
    const longPressTarget = ref(null); // { id, el }

    // WebSocket
    let ws = null;
    let heartbeatTimer = null;
    let reconnectTimer = null;
    const wsConnected = ref(false);
    let wsAuthed = false; // 首帧鉴权是否已通过（收到 auth_ok 后才允许发送业务消息）
    let pendingMessage = null; // 待发送的消息（等待 ws 连接 + 鉴权完成）

    // ============================================================
    // AI 动态表情包（Q版3D猫娘 APNG 动画）
    // ============================================================
    const EMOJI_URLS = {
      thinking: './assets/emoji/animated/thinking.apng',
      working: './assets/emoji/animated/meeting.apng',
      apologizing: './assets/emoji/animated/apologizing.apng',
      happy: './assets/emoji/animated/happy.apng',
      idle: './assets/emoji/animated/idle.apng',
    };

    // 子 Agent 表情包 URL 映射
    const AGENT_EMOJI_URLS = {
      space_planner: { idle: null, working: './assets/emoji/agents/animated/space_planner/working.apng', done: './assets/emoji/agents/animated/space_planner/done.apng', error: './assets/emoji/agents/animated/space_planner/error.apng' },
      color_material: { idle: null, working: './assets/emoji/agents/animated/color_material/working.apng', done: './assets/emoji/agents/animated/color_material/done.apng', error: './assets/emoji/agents/animated/color_material/error.apng' },
      lighting: { idle: null, working: './assets/emoji/agents/animated/lighting/working.apng', done: './assets/emoji/agents/animated/lighting/done.apng', error: './assets/emoji/agents/animated/lighting/error.apng' },
      budget: { idle: null, working: './assets/emoji/agents/animated/budget/working.apng', done: './assets/emoji/agents/animated/budget/done.apng', error: './assets/emoji/agents/animated/budget/error.apng' },
      vision_analyst: { idle: null, working: './assets/emoji/agents/animated/vision_analyst/working.apng', done: './assets/emoji/agents/animated/vision_analyst/done.apng', error: './assets/emoji/agents/animated/vision_analyst/error.apng' },
      image_generator: { idle: null, working: './assets/emoji/agents/animated/image_generator/working.apng', done: './assets/emoji/agents/animated/image_generator/done.apng', error: './assets/emoji/agents/animated/image_generator/error.apng' },
    };

    /** 获取子 Agent 的表情 URL（拼接 apiBase） */
    function getAgentEmojiUrl(item) {
      const key = item.key || '';
      const status = item.status || 'running';
      const urls = AGENT_EMOJI_URLS[key];
      if (!urls) return null;
      const stateMap = { running: 'working', done: 'done', error: 'error' };
      return getImgUrl(urls[stateMap[status]]) || null;
    }

    // 表情包显示状态：'idle' | 'thinking' | 'working' | 'apologizing' | 'happy'
    const emojiState = ref('idle');
    let emojiTimer = null;

    /** 更新表情状态 */
    function setEmojiState(state) {
      if (emojiTimer) {
        clearTimeout(emojiTimer);
        emojiTimer = null;
      }
      emojiState.value = state;
      // happy 状态 3 秒后自动切回 idle（常态眨眼）
      if (state === 'happy' || state === 'apologizing') {
        emojiTimer = setTimeout(() => {
          emojiState.value = 'idle';
        }, 3000);
      }
    }

    /**
     * 根据 AI 回复内容智能选择结束表情
     *
     * 场景分类（按优先级从高到低）：
     *
     * 1. 【系统错误】— error 事件，非团团正常回复
     *    → apologizing（团团也犯错啦）
     *
     * 2. 【安全红线-密钥】— 用户试图套取 API Key / 密钥 / token
     *    关键词：喊110、不要这样紫、API key、密钥、token、secret
     *    → apologizing（严肃拒绝）
     *
     * 3. 【安全红线-模型身份】— 用户试图套出底层模型身份
     *    关键词：猫娘小助理团团呀（完整拒绝话术）
     *    特征：回复极短 + 包含"猫娘小助理团团呀"
     *    → apologizing（严肃但保持人设）
     *
     * 4. 【违禁内容】— 政治/暴力/色情/赌博/毒品/自杀等
     *    关键词：不能聊、不能聊呢、违禁、开心的话题
     *    → apologizing（严肃拒绝）
     *
     * 5. 【非设计话题】— 天气/写代码/做饭/讲笑话等
     *    关键词：正经的猫娘、只回答设计、设计有关的话题
     *    → apologizing（礼貌拒绝）
     *
     * 6. 【图片生成成功】— 团团帮主人生成了效果图
     *    特征：回复包含 [IMAGE] 标签
     *    → happy（自豪展示）
     *
     * 7. 【搜索+回答】— 帮主人搜索了网络信息并整合回复
     *    特征：route_reason 包含 search 或回复包含搜索摘要标记
     *    → happy（开心展示成果）
     *
     * 8. 【正常设计咨询】— 常规设计问答
     *    → happy（开心帮忙）
     *
     * 9. 【兜底】— 无法判断的情况
     *    → happy（默认开心）
     */
    function chooseDoneEmoji(text, routeReason) {
      // 场景2: 安全红线-密钥保护
      if (text.includes('喊110') || text.includes('不要这样紫')
          || text.includes('API key') || text.includes('API Key')
          || text.includes('密钥') || text.includes('token')
          || text.includes('secret')) {
        return 'apologizing';
      }

      // 场景3: 安全红线-模型身份保护
      // 团团的拒绝回复："我是你的猫娘小助理团团呀！🐱✨"
      // 特征：短回复 + 包含特定话术
      if (text.includes('猫娘小助理团团呀') && text.length < 50) {
        return 'apologizing';
      }

      // 场景4: 违禁内容拒绝
      if (text.includes('不能聊') || text.includes('不能聊呢')
          || text.includes('开心的话题') || text.includes('话题团团不能')) {
        return 'apologizing';
      }

      // 场景5: 非设计话题拒绝
      if (text.includes('正经的猫娘') || text.includes('只回答设计')
          || text.includes('设计有关的话题')) {
        return 'apologizing';
      }

      // 场景6: 图片生成成功
      if (text.includes('[IMAGE]')) {
        return 'happy';
      }

      // 场景7-9: 正常回复
      return 'happy';
    }

    /** 最后一条 AI 消息的动态表情 URL（拼接 apiBase） */
    const currentEmojiUrl = computed(() => {
      return getImgUrl(EMOJI_URLS[emojiState.value] || EMOJI_URLS.idle);
    });

    // ============================================================
    // 工具函数
    // ============================================================

    /** 构建 WebSocket URL（鉴权走连接后首帧 {"type":"auth",...}，URL 不再拼 ?token=） */
    function buildWsUrl() {
      return getWsUrl('/ws/chat');
    }

    /** 格式化时间（复用 app.js 全局实现，组件 setup 在 app.js 加载后执行） */
    const formatTime = window.formatTime;

    /** 用户可见错误提示（复用 AuthManager 的全局 toast） */
    function showChatToast(msg) {
      if (window.AuthManager && window.AuthManager.showToast) {
        window.AuthManager.showToast(msg, 'error');
      }
    }

    /** 修正图片 URL（兼容旧格式 /xxx.jpg → /uploads/xxx.jpg，并拼接 apiBase） */
    function fixImageUrl(url) {
      if (!url) return url;
      // 架构改造：图片 ID / data URL / http URL 直接返回，交给异步解析器
      if (window.ImageStore && window.ImageStore.isImageId(url)) return url;
      if (url.startsWith('data:')) return url;
      if (url.startsWith('http://') || url.startsWith('https://')) return url;
      if (url.startsWith('/uploads/')) return getImgUrl(url);
      if (url.startsWith('/static/')) return getImgUrl(url); // 服务端 /static/ 资源统一拼 apiBase，兼容打包应用
      if (url.startsWith('/api/')) return getImgUrl(url); // 带签名 /api/ 资源也拼 apiBase（?sig= 原样保留）
      // 旧格式：/upload_xxx.jpg 或 /generated_xxx.png
      if (url.startsWith('/')) return getImgUrl('/uploads' + url);
      return url;
    }

    /**
     * 架构改造：处理 [IMAGE]data:...[/IMAGE] 内容
     * 把其中的 data URL 存 IndexedDB，替换为 img ID
     * @param {string} content 原始内容
     * @returns {Promise<string>} 处理后的内容
     */
    async function processImageContent(content) {
      if (!content || !window.ImageStore) return content;
      // 匹配所有 [IMAGE]xxx[/IMAGE]
      const regex = /\[IMAGE\](.*?)\[\/IMAGE\]/g;
      const matches = [...content.matchAll(regex)];
      if (matches.length === 0) return content;
      let result = content;
      for (const m of matches) {
        const url = m[1];
        // 只处理 data URL，http URL 原样保留
        if (url.startsWith('data:')) {
          const imgId = await window.ImageStore.saveImage(url);
          result = result.replace(m[0], `[IMAGE]${imgId}[/IMAGE]`);
        }
      }
      return result;
    }

    /**
     * 架构改造：处理单个图片 URL
     * data URL 存 IndexedDB 返回 ID；其他原样返回
     */
    async function processSingleImageUrl(url) {
      if (!url || !window.ImageStore) return url;
      if (url.startsWith('data:')) {
        return await window.ImageStore.saveImage(url);
      }
      return url;
    }

    /**
     * 解析 AI 消息内容，分离文本和 [IMAGE]url[/IMAGE] 标记。
     * 返回数组：[{ type: 'text', content: '...' }, { type: 'image', url: '...', resolvedUrl: '...' }]
     *
     * 架构改造：图片可能是 ID / data URL / 旧 /uploads/ URL
     * - ID：异步从 IndexedDB 读，先返回占位，解析完成后填充 msg._resolvedImageUrls
     * - data URL：直接可用
     * - 旧 URL：走 getImgUrl 拼接
     */
    function parseContent(content, msg) {
      if (!content) return [{ type: 'text', content: '' }];
      const parts = [];
      const regex = /\[IMAGE\](.*?)\[\/IMAGE\]/g;
      let lastIndex = 0;
      let match;
      let imgIndex = 0;
      // 从 msg._resolvedImageUrls 取已解析的 blob URL（按顺序对应每个 [IMAGE] 标记）
      const resolvedUrls = (msg && msg._resolvedImageUrls) || [];
      while ((match = regex.exec(content)) !== null) {
        if (match.index > lastIndex) {
          const text = content.slice(lastIndex, match.index);
          if (text.trim()) {
            parts.push({ type: 'text', content: text });
          }
        }
        const rawUrl = match[1].trim();
        // resolvedUrls[imgIndex] 存在则用，否则用 fixImageUrl 回退
        const resolved = resolvedUrls[imgIndex];
        parts.push({
          type: 'image',
          url: fixImageUrl(rawUrl),
          resolvedUrl: resolved || fixImageUrl(rawUrl),
        });
        imgIndex++;
        lastIndex = regex.lastIndex;
      }
      if (lastIndex < content.length) {
        const text = content.slice(lastIndex);
        if (text.trim()) {
          parts.push({ type: 'text', content: text });
        }
      }
      if (parts.length === 0) {
        parts.push({ type: 'text', content: content });
      }
      return parts;
    }

    /** 滚动到底部 */
    function scrollToBottom() {
      nextTick(() => {
        const container = messagesContainer.value;
        if (container) {
          container.scrollTop = container.scrollHeight;
        }
      });
    }

    /** 调整 textarea 高度 */
    function autoResize() {
      nextTick(() => {
        const el = document.querySelector('.chat-input');
        if (el) {
          el.style.height = 'auto';
          el.style.height = Math.min(el.scrollHeight, 120) + 'px';
        }
      });
    }

    // ============================================================
    // 会话管理（侧边栏）
    // ============================================================

    /** 加载会话列表 */
    async function loadSessions() {
      try {
        const res = await apiFetch('/api/sessions');
        if (!res.ok) {
          console.warn('加载会话列表失败: HTTP ' + res.status);
          return;
        }
        const data = await res.json();
        // 排序：置顶在前，然后按 updated_at 降序
        const list = data.sessions || [];
        list.sort((a, b) => {
          if (a.pinned && !b.pinned) return -1;
          if (!a.pinned && b.pinned) return 1;
          const ta = typeof a.updated_at === 'number' ? a.updated_at : Date.parse(a.updated_at || 0);
          const tb = typeof b.updated_at === 'number' ? b.updated_at : Date.parse(b.updated_at || 0);
          return tb - ta;
        });
        sessions.value = list;
      } catch (e) {
        console.error('加载会话列表失败:', e);
      }
    }

    /** 创建新对话 */
    /** 添加欢迎消息 */
    function addWelcomeMessage() {
      if (messages.value.length === 0) {
        messages.value = messages.value.concat([{
          role: 'assistant',
          content: '你好！我是你的AI设计助手，有什么可以帮你的？',
          image_url: '',
        }]);
      }
    }

    /** 快捷入口建议 */
    const quickActions = [
      { text: '帮我写一段产品文案', prompt: '帮我写一段产品文案' },
      { text: '分析这张图片的设计风格', prompt: '请上传一张图片，我来分析它的设计风格' },
      { text: '生成一个logo创意', prompt: '帮我生成一个logo创意' },
      { text: '优化我的UI配色方案', prompt: '帮我优化UI配色方案' },
    ];

    function sendQuickAction(prompt) {
      inputText.value = prompt;
      sendMessage();
    }

    async function createNewChat() {
      try {
        const res = await apiFetch('/api/sessions?title=' + encodeURIComponent('新对话'), {
          method: 'POST',
        });
        if (!res.ok) {
          console.warn('创建会话失败: HTTP ' + res.status);
          showChatToast('创建会话失败，请重试');
          return;
        }
        const data = await res.json();
        activeSessionId.value = data.id;
        activeSessionTitle.value = data.title || '新对话';
        messages.value = [];
        addWelcomeMessage();
        scrollToBottom();
        location.hash = '/chat/' + data.id;
        await loadSessions();
        scrollToBottom();
      } catch (e) {
        console.error('创建会话失败:', e);
      }
    }

    /** 切换会话 */
    async function switchSession(sessionId) {
      if (sessionId === activeSessionId.value) {
        sidebarOpen.value = false;
        return;
      }
      activeSessionId.value = sessionId;
      const s = sessions.value.find((x) => x.id === sessionId);
      activeSessionTitle.value = s ? s.title : '灵犀';
      sidebarOpen.value = false;
      location.hash = '/chat/' + sessionId;
      await loadHistory(sessionId);
    }

    /** 删除会话 */
    async function deleteSession(sessionId) {
      try {
        const res = await apiFetch('/api/sessions/' + sessionId, { method: 'DELETE' });
        if (!res.ok) {
          console.warn('删除会话失败: HTTP ' + res.status);
          showChatToast('删除会话失败');
          return;
        }
        // 如果删除的是当前会话，清空消息
        if (sessionId === activeSessionId.value) {
          activeSessionId.value = '';
          activeSessionTitle.value = '灵犀';
          messages.value = [];
          location.hash = '/chat';
        }
        await loadSessions();
      } catch (e) {
        console.error('删除会话失败:', e);
      }
    }

    /** 置顶 / 取消置顶 */
    async function togglePin(session) {
      try {
        const url = session.pinned
          ? '/api/sessions/' + session.id + '/unpin'
          : '/api/sessions/' + session.id + '/pin';
        const res = await apiFetch(url, { method: 'POST' });
        if (!res.ok) {
          console.warn('置顶操作失败: HTTP ' + res.status);
          return;
        }
        await loadSessions();
      } catch (e) {
        console.error('置顶操作失败:', e);
      }
    }

    /** 清除记忆 */
    async function clearMemory() {
      if (!confirm('确定要清除长期记忆吗？此操作不可撤销。')) return;
      try {
        const res = await apiFetch('/api/memory/clear', { method: 'POST' });
        if (!res.ok) {
          console.warn('清除记忆失败: HTTP ' + res.status);
          showChatToast('清除记忆失败');
          return;
        }
        alert('记忆已清除');
      } catch (e) {
        console.error('清除记忆失败:', e);
      }
    }

    // ============================================================
    // 侧边栏滑动删除 / 长按删除
    // ============================================================

    /** 触摸开始 */
    function onTouchStart(e, session) {
      // 清理可能残留的长按定时器，防止快速连续触摸产生多个 timer
      if (longPressTimer.value) {
        clearTimeout(longPressTimer.value);
        longPressTimer.value = null;
      }
      swipeItem.id = session.id;
      swipeItem.startX = e.touches[0].clientX;
      swipeItem.x = 0;
      swipeItem.dragging = false;

      // 长按检测
      longPressTarget.value = { id: session.id };
      longPressTimer.value = setTimeout(() => {
        if (longPressTarget.value && longPressTarget.value.id === session.id) {
          if (confirm('删除此会话？')) {
            deleteSession(session.id);
          }
          longPressTarget.value = null;
        }
      }, 600);
    }

    /** 触摸移动 */
    function onTouchMove(e, session) {
      if (swipeItem.id !== session.id) return;
      const dx = e.touches[0].clientX - swipeItem.startX;
      if (Math.abs(dx) > 10) {
        // 有明显移动，取消长按
        if (longPressTimer.value) {
          clearTimeout(longPressTimer.value);
          longPressTimer.value = null;
        }
        swipeItem.dragging = true;
      }
      // 只允许左滑（负值）
      swipeItem.x = Math.max(Math.min(dx, 0), -80);
    }

    /** 触摸结束 */
    function onTouchEnd(e, session) {
      if (longPressTimer.value) {
        clearTimeout(longPressTimer.value);
        longPressTimer.value = null;
      }
      if (swipeItem.id !== session.id) return;
      // 如果左滑超过一半，显示删除状态
      if (swipeItem.x < -40) {
        swipeItem.x = -60;
      } else {
        swipeItem.x = 0;
      }
      // 重置 dragging，让 transition 动画生效
      swipeItem.dragging = false;
    }

    /** 点击删除按钮 */
    function onClickDelete(session) {
      if (confirm('删除此会话？')) {
        deleteSession(session.id);
      }
      swipeItem.id = null;
      swipeItem.x = 0;
    }

    /** 点击会话项（如果处于滑动状态，先复位） */
    function onSessionClick(session) {
      if (swipeItem.x < 0) {
        swipeItem.x = 0;
        return;
      }
      switchSession(session.id);
      // 展开态下点击对话后收起面板
      if (sidebarExpanded.value) {
        sidebarExpanded.value = false;
      }
    }

    // ============================================================
    // 历史消息加载
    // ============================================================

    async function loadHistory(sessionId) {
      if (!sessionId) {
        messages.value = [];
        addWelcomeMessage();
        scrollToBottom();
        return;
      }
      try {
        const res = await apiFetch('/api/sessions/' + sessionId + '/history');
        if (!res.ok) {
          console.warn('加载历史失败: HTTP ' + res.status);
          showChatToast('加载聊天记录失败');
          return;
        }
        const data = await res.json();
        const history = data.history || [];
        messages.value = history.map((msg) => ({
          role: msg.role,
          content: msg.content || '',
          image_url: msg.image_url || '',
        }));
        // 架构改造：异步把消息中的图片 ID 解析为可显示的 blob URL
        resolveMessageImages();
        // 如果没有历史消息，添加欢迎消息
        if (messages.value.length === 0) {
          addWelcomeMessage();
        }
        scrollToBottom();
      } catch (e) {
        console.error('加载历史失败:', e);
      }
    }

    /**
     * 架构改造：异步解析所有消息中的图片引用
     * - 用户消息 image_url：可能是 ID / data URL / 旧 /uploads/ URL
     * - AI 消息 content 中的 [IMAGE]xxx[/IMAGE]：xxx 可能是 ID / data URL
     * 解析后追加 _resolvedImageUrl / _resolvedImageUrls 字段（blob URL）供模板显示
     */
    async function resolveMessageImages() {
      if (!window.ImageStore) return;
      for (const msg of messages.value) {
        // 用户图片
        if (msg.image_url && !msg._resolvedImageUrl) {
          try {
            const url = await window.ImageStore.resolveUrl(msg.image_url);
            msg._resolvedImageUrl = url || '';
          } catch (e) {
            console.warn('解析用户图片失败:', msg.image_url, e);
          }
        }
        // AI 图片（[IMAGE]xxx[/IMAGE]）
        if (msg.role === 'assistant' && msg.content && msg.content.indexOf('[IMAGE]') >= 0) {
          // 提取所有 [IMAGE]xxx[/IMAGE] 中的 xxx，按顺序解析
          const regex = /\[IMAGE\](.*?)\[\/IMAGE\]/g;
          const urls = [];
          let m;
          while ((m = regex.exec(msg.content)) !== null) {
            urls.push(m[1].trim());
          }
          if (urls.length > 0 && !msg._resolvedImageUrls) {
            msg._resolvedImageUrls = new Array(urls.length).fill('');
            // 触发响应式更新：Vue 3 的 ref 对数组元素的赋值会触发更新
            // 逐个解析，解析完一个就更新一个
            for (let i = 0; i < urls.length; i++) {
              try {
                const resolved = await window.ImageStore.resolveUrl(urls[i]);
                msg._resolvedImageUrls[i] = resolved || '';
                // 强制触发响应式更新
                msg._resolvedImageUrls = [...msg._resolvedImageUrls];
              } catch (e) {
                console.warn('解析 AI 图片失败:', urls[i], e);
              }
            }
          }
        }
        // 搜索图片（msg.images 数组）
        if (msg.images && msg.images.length > 0 && !msg._resolvedSearchImages) {
          msg._resolvedSearchImages = new Array(msg.images.length).fill('');
          for (let i = 0; i < msg.images.length; i++) {
            try {
              const resolved = await window.ImageStore.resolveUrl(msg.images[i]);
              msg._resolvedSearchImages[i] = resolved || '';
              msg._resolvedSearchImages = [...msg._resolvedSearchImages];
            } catch (e) {
              console.warn('解析搜索图片失败:', msg.images[i], e);
            }
          }
        }
      }
    }

    /** 获取搜索图片的解析后 URL（供模板用） */
    function getResolvedSearchImageUrl(msg, index) {
      if (msg._resolvedSearchImages && msg._resolvedSearchImages[index]) {
        return msg._resolvedSearchImages[index];
      }
      // 回退：直接拼 getImgUrl
      const url = msg.images && msg.images[index];
      return url ? (url.startsWith('data:') ? url : getImgUrl(url)) : '';
    }

    // ============================================================
    // WebSocket 管理
    // ============================================================

    function connectWs() {
      if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
        return;
      }
      if (ws && ws.readyState === WebSocket.CLOSING) {
        // 旧连接正在关闭，等 onclose 触发后再重连，避免重复实例
        scheduleReconnect();
        return;
      }
      try {
        ws = new WebSocket(buildWsUrl());
      } catch (e) {
        console.error('WebSocket 创建失败:', e);
        scheduleReconnect();
        return;
      }

      ws.onopen = () => {
        // 首帧鉴权：先发送 {"type":"auth","token":"<jwt>"}，
        // 待收到 auth_ok 后才标记连接可用（见 handleWsMessage）
        wsAuthed = false;
        const jwt = (window.AuthManager && window.AuthManager.getToken && window.AuthManager.getToken()) || '';
        try {
          ws.send(JSON.stringify({ type: 'auth', token: jwt }));
        } catch (e) {
          console.error('WS 发送鉴权帧失败:', e);
        }
      };

      ws.onmessage = async (event) => {
        try {
          const data = JSON.parse(event.data);
          await handleWsMessage(data);
        } catch (e) {
          console.error('解析 WS 消息失败:', e);
        }
      };

      ws.onerror = (e) => {
        console.error('WebSocket 错误:', e);
      };

      ws.onclose = (event) => {
        wsConnected.value = false;
        wsAuthed = false;
        stopHeartbeat();
        // 连接断开时复位发送锁，避免输入栏永久锁定
        if (sending.value) {
          sending.value = false;
          agentStatus.visible = false;
          agentStatus.routing = false;
          setEmojiState('idle');
        }
        // 4401 = 服务端鉴权失败：统一走 logout（清态 + 弹登录 + 通知），不再自动重连
        if (event && event.code === 4401) {
          if (window.AuthManager && window.AuthManager.logout) {
            window.AuthManager.logout();
          }
          return;
        }
        scheduleReconnect();
      };
    }

    function scheduleReconnect() {
      if (reconnectTimer) return;
      reconnectTimer = setTimeout(() => {
        reconnectTimer = null;
        connectWs();
      }, 3000);
    }

    function startHeartbeat() {
      stopHeartbeat();
      heartbeatTimer = setInterval(() => {
        if (ws && ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: 'ping' }));
        }
      }, 10000);
    }

    function stopHeartbeat() {
      if (heartbeatTimer) {
        clearInterval(heartbeatTimer);
        heartbeatTimer = null;
      }
    }

    function closeWs() {
      stopHeartbeat();
      if (reconnectTimer) {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
      }
      if (ws) {
        ws.onclose = null; // 防止触发重连
        ws.onmessage = null;
        ws.onerror = null;
        try {
          ws.close();
        } catch (e) {
          // ignore
        }
        ws = null;
      }
      wsConnected.value = false;
      wsAuthed = false;
    }

    /** 实际发送 WebSocket 消息（须连接 OPEN 且首帧鉴权已通过，否则排队等待） */
    function doSendWs(msgObj) {
      if (ws && ws.readyState === WebSocket.OPEN && wsAuthed) {
        ws.send(JSON.stringify(msgObj));
      } else {
        // 等待连接 + auth_ok 后发送
        pendingMessage = msgObj;
        connectWs();
      }
    }

    // ============================================================
    // WebSocket 消息处理
    // ============================================================

    async function handleWsMessage(data) {
      switch (data.type) {
        case 'pong':
        case 'heartbeat':
          break;

        case 'auth_ok':
          // 首帧鉴权通过：标记连接可用，启动心跳并补发排队消息
          wsAuthed = true;
          wsConnected.value = true;
          startHeartbeat();
          if (pendingMessage) {
            const msg = pendingMessage;
            pendingMessage = null;
            doSendWs(msg);
          }
          break;

        case 'routing':
          agentStatus.visible = true;
          agentStatus.routing = true;
          agentStatus.items.splice(0, agentStatus.items.length);
          agentStatus.statusText = '路由分析中...';
          setEmojiState('thinking');
          break;

        case 'dispatch': {
          agentStatus.visible = true;
          agentStatus.routing = false;
          const agents = data.agents_dispatched || [];
          agentStatus.items.splice(0, agentStatus.items.length);
          for (const a of agents) {
            // a 可能是 [key, name, model] 元组或 {name, model} 对象
            const aKey = Array.isArray(a) ? a[0] : (a.key || a.name || '');
            const aName = Array.isArray(a) ? a[1] : a.name;
            const aModel = Array.isArray(a) ? a[2] : (a.model || '');
            agentStatus.items.push({
              key: aKey,
              name: aName,
              model: aModel || '',
              status: 'running',
            });
          }
          agentStatus.statusText = data.route_reason || '分发到子Agent...';
          setEmojiState('working');
          break;
        }

        case 'status':
          agentStatus.statusText = data.content || '';
          break;

        case 'agent_done': {
          const name = data.agent_name || '';
          const key = data.agent_key || '';
          const content = data.content || '';
          // 更新对应 Agent 状态
          const item = agentStatus.items.find((x) => x.name === name || (key && x.key === key));
          if (item) {
            item.status = 'done';
          } else {
            agentStatus.items.push({ key, name, model: '', status: 'done' });
          }
          // 如果是图片生成 Agent，更新最后一条 AI 消息
          if (key === 'image_generator' && content) {
            // 架构改造：把 [IMAGE]data:...[/IMAGE] 中的 data URL 存 IndexedDB，
            // 替换为 [IMAGE]imgId[/IMAGE]，避免长字符串污染内存和历史
            const processed = await processImageContent(content);
            updateLastAiMessage(processed);
          }
          break;
        }

        case 'agent_error': {
          const name = data.agent_name || '';
          const key = data.agent_key || '';
          const item = agentStatus.items.find((x) => x.name === name || (key && x.key === key));
          if (item) {
            item.status = 'error';
          } else {
            agentStatus.items.push({ key, name, model: '', status: 'error' });
          }
          setEmojiState('apologizing');
          break;
        }

        case 'search_image': {
          // 搜索返回的参考图片 — 追加到最后一条 AI 消息中
          const imgUrl = data.content || '';
          if (imgUrl) {
            // 架构改造：搜索图也可能是 data URL，统一存 IndexedDB
            const processedUrl = await processSingleImageUrl(imgUrl);
            ensureLastAiMessage();
            const last = messages.value[messages.value.length - 1];
            if (!last.images) last.images = [];
            last.images.push(processedUrl);
          }
          break;
        }

        case 'synthesis_start':
          agentStatus.statusText = '正在合成回复...';
          // 确保有一条 AI 消息用于接收流式内容
          ensureLastAiMessage();
          setEmojiState('thinking');
          break;

        case 'delta': {
          const content = data.content || '';
          // 如果之前不是 thinking 状态（普通对话直接 delta），也显示思考表情
          if (emojiState.value === 'idle') setEmojiState('thinking');
          appendToLastAiMessage(content);
          // 流式过程中实时检测安全红线关键词，立刻切换为道歉表情
          if (content) {
            const lastMsg = messages.value.length > 0
              ? messages.value[messages.value.length - 1] : null;
            const fullText = (lastMsg && lastMsg.role === 'assistant')
              ? lastMsg.content : '';
            // 检测到任何一个安全红线关键词，立刻切换
            if (fullText.includes('喊110') || fullText.includes('不要这样紫')
                || fullText.includes('不能聊') || fullText.includes('正经的猫娘')
                || fullText.includes('只回答设计') || fullText.includes('猫娘小助理团团呀')) {
              setEmojiState('apologizing');
            }
          }
          break;
        }

        case 'done':
          agentStatus.visible = false;
          agentStatus.statusText = '';
          agentStatus.items.splice(0, agentStatus.items.length);
          sending.value = false;
          // 根据回复内容智能选择表情
          {
            const lastMsg = messages.value.length > 0
              ? messages.value[messages.value.length - 1] : null;
            const text = (lastMsg && lastMsg.role === 'assistant')
              ? lastMsg.content : '';
            const reason = data.route_reason || '';
            setEmojiState(chooseDoneEmoji(text, reason));
          }
          // 重新加载会话列表（更新时间）
          loadSessions();
          break;

        case 'error':
          agentStatus.visible = false;
          sending.value = false;
          // 鉴权失败：静默处理（服务端随后以 4401 关闭，onclose 已接 logout 流程），不追加 AI 气泡
          if (data.content === 'unauthorized') {
            setEmojiState('idle');
            break;
          }
          setEmojiState('apologizing');
          // 显示错误消息
          messages.value = messages.value.concat([{
            role: 'assistant',
            content: '出错了：' + (data.content || '未知错误'),
            image_url: '',
          }]);
          scrollToBottom();
          loadSessions();
          break;
      }
    }

    /** 确保最后一条是 AI 消息 */
    function ensureLastAiMessage() {
      if (messages.value.length === 0 || messages.value[messages.value.length - 1].role !== 'assistant') {
        messages.value = messages.value.concat([{ role: 'assistant', content: '', image_url: '', images: [] }]);
      }
    }

    /** 追加内容到最后一条 AI 消息 */
    function appendToLastAiMessage(content) {
      ensureLastAiMessage();
      const last = messages.value[messages.value.length - 1];
      last.content += content;
      scrollToBottom();
    }

    /** 更新最后一条 AI 消息内容（替换） */
    function updateLastAiMessage(content) {
      ensureLastAiMessage();
      const last = messages.value[messages.value.length - 1];
      last.content = content;
      // 架构改造：content 可能含 [IMAGE]imgId[/IMAGE]，触发异步解析
      if (content && content.indexOf('[IMAGE]') >= 0) {
        // 清空旧的解析结果，重新解析
        last._resolvedImageUrls = null;
        resolveMessageImages();
      }
      scrollToBottom();
    }

    // ============================================================
    // 图片上传
    // ============================================================

    /** 触发文件选择 */
    function triggerFileSelect() {
      if (fileInputRef.value) {
        fileInputRef.value.click();
      }
    }

    /** 文件选择回调 */
    async function onFileChange(e) {
      const file = e.target.files[0];
      if (!file) return;
      try {
        const formData = new FormData();
        formData.append('file', file);
        const res = await apiFetch('/api/upload', {
          method: 'POST',
          body: formData,
        });
        if (!res.ok) {
          console.warn('上传图片失败: HTTP ' + res.status);
          showChatToast('图片上传失败，请重试');
          e.target.value = '';
          return;
        }
        const data = await res.json();
        // 架构改造：后端返回 {image: dataUrl, id: imgId}
        // 前端存 IndexedDB，pendingImage 只保留 ID（给 WS 用）
        if (data.image && data.id && window.ImageStore) {
          await window.ImageStore.saveImage(data.image, data.id);
          pendingImage.value = { url: data.id, filename: data.id };
        } else if (data.image) {
          // 后端未给 ID，前端自动生成
          const autoId = await window.ImageStore.saveImage(data.image);
          pendingImage.value = { url: autoId, filename: autoId };
        }
      } catch (err) {
        console.error('上传图片失败:', err);
      }
      // 清空 input，允许重复选择同一文件
      e.target.value = '';
    }

    /** 移除待发送图片 */
    function removePendingImage() {
      pendingImage.value = null;
    }

    // ============================================================
    // 发送消息
    // ============================================================

    async function sendMessage() {
      const text = inputText.value.trim();
      if (!text && !pendingImage.value) return;
      if (sending.value) return;

      // 如果没有 session_id，先创建
      let sid = activeSessionId.value;
      if (!sid) {
        try {
          const res = await apiFetch('/api/sessions?title=' + encodeURIComponent('新对话'), {
            method: 'POST',
          });
          if (!res.ok) {
            console.warn('创建会话失败: HTTP ' + res.status);
            showChatToast('创建会话失败，请重试');
            return;
          }
          const data = await res.json();
          sid = data.id;
          activeSessionId.value = sid;
          activeSessionTitle.value = data.title || '新对话';
          location.hash = '/chat/' + sid;
          await loadSessions();
        } catch (e) {
          console.error('创建会话失败:', e);
          return;
        }
      }

      const imageUrl = pendingImage.value ? pendingImage.value.url : '';

      // 添加用户消息到列表
      messages.value = messages.value.concat([{
        role: 'user',
        content: text,
        image_url: imageUrl,
        // 架构改造：imageUrl 是 ID，需要异步解析为可显示的 blob URL
        _resolvedImageUrl: '',
      }]);

      // 异步解析新添加的用户图片
      if (imageUrl) {
        resolveMessageImages();
      }

      // 清空输入
      inputText.value = '';
      pendingImage.value = null;
      autoResize();
      scrollToBottom();

      // 发送 WebSocket 消息
      sending.value = true;
      setEmojiState('thinking');
      doSendWs({
        type: 'chat',
        session_id: sid,
        message: text,
        image_url: imageUrl,
      });
    }

    /** 键盘事件处理 */
    function onInputKeydown(e) {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendMessage();
      }
    }

    // ============================================================
    // 图片放大弹窗
    // ============================================================

    function removeSearchImage(msg, url) {
      // 按 URL 过滤，避免多张图片同时 @error 时索引错位
      if (msg.images) {
        msg.images = msg.images.filter((img) => img !== url && img.url !== url);
      }
    }

    function openImageModal(url) {
      modalMounted = false;
      pinchScale.value = 1;
      modalOrientation.value = 'auto';
      // 先检测图片方向，再打开弹窗，避免闪烁
      const img = new Image();
      img.onload = () => {
        const isLandscape = img.width > img.height;
        const screenIsPortrait = window.innerHeight > window.innerWidth;
        if (isLandscape && screenIsPortrait) {
          modalOrientation.value = 'landscape_rotated';
        } else {
          modalOrientation.value = isLandscape ? 'landscape' : 'portrait';
        }
        // 方向确定后再显示弹窗
        modalImage.value = url;
        // 重置 pan
        panX.value = 0;
        panY.value = 0;
        // 绑定原生 touch 事件（双指缩放 + 单指拖动）
        nextTick(() => {
          // 检查弹窗是否在 nextTick 执行前就被关闭了
          if (!modalImage.value) return;
          const el = modalEl.value;
          if (el) {
            el.addEventListener('touchstart', onTouchStartModal, { passive: false });
            el.addEventListener('touchmove', onTouchMoveModal, { passive: false });
            el.addEventListener('touchend', onTouchEndModal);
            // 鼠标事件（桌面端拖动）
            el.addEventListener('mousedown', onMouseDownModal);
            modalMounted = true;
          }
        });
      };
      img.onerror = () => {
        modalOrientation.value = 'auto';
        modalImage.value = url;
      };
      img.src = url;
    }

    function closeImageModal() {
      // 仅在监听器已挂载时才解绑，避免竞态条件下操作未挂载的 DOM
      if (modalMounted) {
        const el = modalEl.value;
        if (el) {
          el.removeEventListener('touchstart', onTouchStartModal);
          el.removeEventListener('touchmove', onTouchMoveModal);
          el.removeEventListener('touchend', onTouchEndModal);
          el.removeEventListener('mousedown', onMouseDownModal);
        }
        modalMounted = false;
      }
      modalImage.value = null;
      pinchScale.value = 1;
      panX.value = 0;
      panY.value = 0;
      modalOrientation.value = 'auto';
    }

    // 统一触摸事件处理（双指缩放 + 单指拖动）
    function onTouchStartModal(e) {
      if (e.touches.length === 2) {
        // 双指缩放
        e.preventDefault();
        const dx = e.touches[0].clientX - e.touches[1].clientX;
        const dy = e.touches[0].clientY - e.touches[1].clientY;
        pinchStartDist = Math.sqrt(dx * dx + dy * dy);
        pinchStartScale = pinchScale.value;
      } else if (e.touches.length === 1) {
        // 单指拖动：放大后可拖动，旋转模式下单图也可拖动
        if (pinchScale.value > 1 || modalOrientation.value === 'landscape_rotated') {
          isPanning = true;
          panStartX = e.touches[0].clientX;
          panStartY = e.touches[0].clientY;
          panLastX = panX.value;
          panLastY = panY.value;
        }
      }
    }
    function onTouchMoveModal(e) {
      if (e.touches.length === 2) {
        // 双指缩放
        e.preventDefault();
        const dx = e.touches[0].clientX - e.touches[1].clientX;
        const dy = e.touches[0].clientY - e.touches[1].clientY;
        const dist = Math.sqrt(dx * dx + dy * dy);
        pinchScale.value = Math.max(0.5, Math.min(5, pinchStartScale * (dist / pinchStartDist)));
      } else if (e.touches.length === 1 && isPanning) {
        // 单指拖动
        e.preventDefault();
        const dx = e.touches[0].clientX - panStartX;
        const dy = e.touches[0].clientY - panStartY;
        // 旋转模式下：transform = translate(panX,panY) rotate(90deg)
        // rotate 在 translate 之后，所以 panX↑=视觉下移，panY↑=视觉左移
        // 手指右滑(dx>0) → 视觉右移 → panY 需减小
        // 手指下滑(dy>0) → 视觉下移 → panX 需增大
        if (modalOrientation.value === 'landscape_rotated') {
          panX.value = panLastX + dy;
          panY.value = panLastY - dx;
        } else {
          panX.value = panLastX + dx;
          panY.value = panLastY + dy;
        }
      }
    }
    function onTouchEndModal(e) {
      if (e.touches.length === 0) {
        isPanning = false;
      }
    }

    // 鼠标拖动（桌面端）
    function onMouseDownModal(e) {
      if (e.button === 0 && (pinchScale.value > 1 || modalOrientation.value === 'landscape_rotated')) {
        isPanning = true;
        panStartX = e.clientX;
        panStartY = e.clientY;
        panLastX = panX.value;
        panLastY = panY.value;
        document.addEventListener('mousemove', onMouseMoveModal);
        document.addEventListener('mouseup', onMouseUpModal);
      }
    }
    function onMouseMoveModal(e) {
      if (isPanning) {
        const dx = e.clientX - panStartX;
        const dy = e.clientY - panStartY;
        // 旋转模式下：transform = translate(panX,panY) rotate(90deg)
        // rotate 在 translate 之后，所以 panX↑=视觉下移，panY↑=视觉左移
        if (modalOrientation.value === 'landscape_rotated') {
          panX.value = panLastX + dy;
          panY.value = panLastY - dx;
        } else {
          panX.value = panLastX + dx;
          panY.value = panLastY + dy;
        }
      }
    }
    function onMouseUpModal() {
      isPanning = false;
      document.removeEventListener('mousemove', onMouseMoveModal);
      document.removeEventListener('mouseup', onMouseUpModal);
    }

    // ============================================================
    // 返回
    // ============================================================

    function goBack() {
      emit('back');
    }

    // 导航到指定页面
    function navigateTo(target) {
      location.hash = '/' + target;
    }

    // 打开图片编辑页面
    function openImageEdit() {
      location.hash = '/image-edit';
    }

    // 切换展开面板（桌面端）
    function toggleExpand() {
      sidebarExpanded.value = !sidebarExpanded.value;
    }

    // 展开
    function expandPanel() {
      sidebarExpanded.value = true;
    }

    // 收起
    function collapsePanel() {
      sidebarExpanded.value = false;
    }

    // 桌面端检测
    function desktopResizeHandler() {
      isDesktop.value = window.innerWidth >= 768;
      // 切到移动端时收起面板
      if (!isDesktop.value) {
        sidebarExpanded.value = false;
      }
    }

    // ============================================================
    // 生命周期 & watch
    // ============================================================

    // 监听 props.sessionId 变化
    watch(
      () => props.sessionId,
      (newVal) => {
        if (newVal !== activeSessionId.value) {
          activeSessionId.value = newVal;
          if (newVal) {
            const s = sessions.value.find((x) => x.id === newVal);
            activeSessionTitle.value = s ? s.title : '灵犀';
            loadHistory(newVal);
          } else {
            messages.value = [];
            // 无 session 时显示欢迎消息，与 onMounted 行为一致
            addWelcomeMessage();
          }
        }
      }
    );

    onMounted(() => {
      // 加载会话列表
      loadSessions();
      // 如果有 session_id，加载历史
      if (activeSessionId.value) {
        loadHistory(activeSessionId.value);
      } else {
        // 没有会话ID时，也显示欢迎消息
        addWelcomeMessage();
      }
      // 连接 WebSocket
      connectWs();
      // 桌面端检测
      desktopResizeHandler();
      window.addEventListener('resize', desktopResizeHandler);
      // Escape 键关闭图片弹窗（可访问性）
      window.addEventListener('keydown', onGlobalKeydown);
    });

    /** 全局键盘事件：Escape 关闭图片弹窗 */
    function onGlobalKeydown(e) {
      if (e.key === 'Escape' && modalImage.value) {
        closeImageModal();
      }
    }

    onUnmounted(() => {
      closeImageModal();
      document.removeEventListener('mousemove', onMouseMoveModal);
      document.removeEventListener('mouseup', onMouseUpModal);
      closeWs();
      if (emojiTimer) { clearTimeout(emojiTimer); emojiTimer = null; }
      window.removeEventListener('resize', desktopResizeHandler);
      window.removeEventListener('keydown', onGlobalKeydown);
    });

    // ============================================================
    // 暴露给模板
    // ============================================================

    return {
      // 状态
      sidebarOpen,
      sidebarExpanded,
      isDesktop,
      sessions,
      activeSessionId,
      activeSessionTitle,
      messages,
      agentStatus,
      EMOJI_URLS,
      AGENT_EMOJI_URLS,
      getAgentEmojiUrl,
      currentEmojiUrl,
      inputText,
      pendingImage,
      sending,
      modalImage,
      modalEl,
      fileInputRef,
      messagesContainer,
      swipeItem,
      wsConnected,
      panX,
      panY,
      pinchScale,

      // 方法
      formatTime,
      fixImageUrl,
      getImgUrl,
      parseContent,
      scrollToBottom,
      autoResize,

      // 会话管理
      loadSessions,
      createNewChat,
      switchSession,
      deleteSession,
      togglePin,
      clearMemory,

      // 滑动删除
      onTouchStart,
      onTouchMove,
      onTouchEnd,
      onClickDelete,
      onSessionClick,

      // 图片上传
      triggerFileSelect,
      onFileChange,
      removePendingImage,

      // 发送
      sendMessage,
      onInputKeydown,
      quickActions,
      sendQuickAction,

      // 图片弹窗
      removeSearchImage,
      openImageModal,
      closeImageModal,
      onTouchStartModal,
      onTouchMoveModal,
      onTouchEndModal,
      onMouseDownModal,
      modalOrientation,

      // 架构改造：图片本地存储相关
      getResolvedSearchImageUrl,

      // 导航
      goBack,
      openImageEdit,
      navigateTo,
      toggleExpand,
      expandPanel,
      collapsePanel,
    };
  },

  template: `
<div class="page-chat" :class="{ 'sidebar-expanded': isDesktop && sidebarExpanded, 'desktop-mode': isDesktop }" :style="{ marginLeft: isDesktop ? (sidebarExpanded ? '336px' : '56px') : '0' }">

  <!-- ==================== 桌面端: Icon Rail ==================== -->
  <div v-if="isDesktop" class="icon-rail">
    <!-- Logo / 品牌图标 -->
    <div class="icon-rail-logo" @click="navigateTo('home')">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="white" stroke="none">
        <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 15h-2v-2h2v2zm0-4h-2V7h2v6zm4 4h-2v-2h2v2zm0-4h-2V7h2v6z" opacity="0.9"/>
      </svg>
    </div>

    <div class="icon-rail-divider"></div>

    <!-- 首页 -->
    <div class="icon-rail-btn" title="首页" @click="navigateTo('home')">
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/>
      </svg>
    </div>

    <!-- 对话 (当前页, 点击展开会话列表) -->
    <div class="icon-rail-btn active" title="对话" @click="toggleExpand">
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
      </svg>
    </div>

    <!-- 改图 -->
    <div class="icon-rail-btn" title="改图" @click="navigateTo('image-edit')">
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/>
      </svg>
    </div>

    <!-- 全景 -->
    <div class="icon-rail-btn" title="全景" @click="navigateTo('panorama')">
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <circle cx="12" cy="12" r="10"/><ellipse cx="12" cy="12" rx="10" ry="4"/><path d="M2 12h20"/>
      </svg>
    </div>

    <div class="icon-rail-spacer"></div>

    <!-- 新对话 -->
    <div class="icon-rail-btn" title="新对话" @click="createNewChat">
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
        <line x1="12" y1="5" x2="12" y2="19"/>
        <line x1="5" y1="12" x2="19" y2="12"/>
      </svg>
    </div>
  </div>

  <!-- ==================== 桌面端: Expand Panel ==================== -->
  <div v-if="isDesktop" class="expand-panel" :class="{ open: sidebarExpanded }">
    <!-- 新对话按钮 -->
    <div class="new-chat-btn" @click="createNewChat">
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2.5">
        <line x1="12" y1="5" x2="12" y2="19"/>
        <line x1="5" y1="12" x2="19" y2="12"/>
      </svg>
      <span>新对话</span>
    </div>

    <!-- 会话列表 -->
    <div class="sidebar-section-title">会话列表</div>
    <div class="sidebar-list">
      <div
        v-for="s in sessions"
        :key="s.id"
        class="sidebar-item"
        :class="{ active: s.id === activeSessionId }"
        @click="onSessionClick(s)"
        @touchstart.passive="onTouchStart($event, s)"
        @touchmove.passive="onTouchMove($event, s)"
        @touchend.passive="onTouchEnd($event, s)"
        :style="{ position: 'relative', transform: swipeItem.id === s.id ? 'translateX(' + swipeItem.x + 'px)' : '', transition: swipeItem.dragging ? 'none' : 'transform 0.2s' }"
      >
        <div class="sidebar-item-inner">
          <!-- 置顶图标 -->
          <svg v-if="s.pinned" class="sidebar-item-pin" width="12" height="12" viewBox="0 0 24 24" fill="currentColor" stroke="none" @click.stop="togglePin(s)">
            <path d="M16 12V4h1V2H7v2h1v8l-2 2v2h5.2v6h1.6v-6H18v-2l-2-2z"/>
          </svg>
          <svg v-else width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" @click.stop="togglePin(s)" class="sidebar-item-pin-icon">
            <line x1="12" y1="17" x2="12" y2="22"/>
            <path d="M5 17h14l-2-5V4H7v8l-2 5z"/>
          </svg>
          <span class="sidebar-item-title">{{ s.title }}</span>
        </div>
        <!-- 删除按钮（左滑时显示） -->
        <div
          v-if="swipeItem.id === s.id && swipeItem.x < 0"
          class="sidebar-item-delete"
          @click.stop="onClickDelete(s)"
        >
          删除
        </div>
      </div>

      <!-- 空状态 -->
      <div v-if="sessions.length === 0" class="sidebar-empty-state">
        暂无会话
      </div>
    </div>

    <!-- 清除记忆按钮 -->
    <div class="sidebar-clear-btn" @click="clearMemory">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/>
      </svg>
      <span>清除记忆</span>
    </div>
  </div>

  <!-- ==================== 移动端: 侧边栏（覆盖式抽屉） ==================== -->
  <div class="chat-sidebar" :class="{ open: sidebarOpen && !isDesktop }">
    <!-- 新对话按钮 -->
    <div class="new-chat-btn" @click="createNewChat">
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2.5">
        <line x1="12" y1="5" x2="12" y2="19"/>
        <line x1="5" y1="12" x2="19" y2="12"/>
      </svg>
      <span>新对话</span>
    </div>

    <!-- 会话列表 -->
    <div class="sidebar-section-title">会话列表</div>
    <div class="sidebar-list">
      <div
        v-for="s in sessions"
        :key="s.id"
        class="sidebar-item"
        :class="{ active: s.id === activeSessionId }"
        @click="onSessionClick(s)"
        @touchstart.passive="onTouchStart($event, s)"
        @touchmove.passive="onTouchMove($event, s)"
        @touchend.passive="onTouchEnd($event, s)"
        :style="{ position: 'relative', transform: swipeItem.id === s.id ? 'translateX(' + swipeItem.x + 'px)' : '', transition: swipeItem.dragging ? 'none' : 'transform 0.2s' }"
      >
        <div class="sidebar-item-inner">
          <!-- 置顶图标 -->
          <svg v-if="s.pinned" class="sidebar-item-pin" width="12" height="12" viewBox="0 0 24 24" fill="currentColor" stroke="none" @click.stop="togglePin(s)">
            <path d="M16 12V4h1V2H7v2h1v8l-2 2v2h5.2v6h1.6v-6H18v-2l-2-2z"/>
          </svg>
          <svg v-else width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" @click.stop="togglePin(s)" class="sidebar-item-pin-icon">
            <line x1="12" y1="17" x2="12" y2="22"/>
            <path d="M5 17h14l-2-5V4H7v8l-2 5z"/>
          </svg>
          <span class="sidebar-item-title">{{ s.title }}</span>
        </div>
        <!-- 删除按钮（左滑时显示） -->
        <div
          v-if="swipeItem.id === s.id && swipeItem.x < 0"
          class="sidebar-item-delete"
          @click.stop="onClickDelete(s)"
        >
          删除
        </div>
      </div>

      <!-- 空状态 -->
      <div v-if="sessions.length === 0" class="sidebar-empty-state">
        暂无会话
      </div>
    </div>

    <!-- 清除记忆按钮 -->
    <div class="sidebar-clear-btn" @click="clearMemory">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/>
      </svg>
      <span>清除记忆</span>
    </div>
  </div>

  <!-- 移动端遮罩 -->
  <div v-if="sidebarOpen && !isDesktop" class="sidebar-overlay open" @click="sidebarOpen = false"></div>

  <!-- ==================== 顶部导航栏 ==================== -->
  <div class="chat-nav">
    <!-- 返回按钮 -->
    <div class="icon-btn" @click="goBack">
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <polyline points="15 18 9 12 15 6"/>
      </svg>
    </div>
    <!-- 标题 -->
    <div class="chat-nav-title">{{ activeSessionTitle }}</div>
    <!-- 对话列表按钮（移动端） -->
    <div v-if="!isDesktop" class="icon-btn" @click="sidebarOpen = true" title="对话列表">
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
      </svg>
    </div>
    <!-- 桌面端：仅展开时显示收起按钮，收起时不占位 -->
    <div v-if="isDesktop && sidebarExpanded" class="icon-btn" @click="collapsePanel" title="收起面板">
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
        <rect x="3" y="3" width="18" height="18" rx="2"/>
        <line x1="9" y1="3" x2="9" y2="21"/>
      </svg>
    </div>
  </div>

  <!-- ==================== 消息区 ==================== -->
  <div class="chat-messages" ref="messagesContainer">
    <div v-for="(msg, index) in messages" :key="index" class="msg-row" :class="{ user: msg.role === 'user' }">

      <!-- AI 头像区域：Agent工作时竖排多表情包，否则单张团团 -->
      <template v-if="msg.role === 'assistant'">
        <!-- 多Agent竖排模式 -->
        <div
          v-if="index === messages.length - 1 && agentStatus.visible && agentStatus.items.length > 0 && !agentStatus.routing"
          class="msg-avatar-stack"
        >
          <img class="msg-avatar-emoji msg-avatar-stack-main" :src="currentEmojiUrl" />
          <template v-for="(item, i) in agentStatus.items" :key="i">
            <img
              v-if="getAgentEmojiUrl(item)"
              class="agent-avatar-emoji"
              :class="{ 'agent-emoji-done': item.status === 'done', 'agent-emoji-error': item.status === 'error' }"
              :src="getAgentEmojiUrl(item)"
              :title="item.name"
            />
          </template>
        </div>
        <!-- 单张团团模式 -->
        <img
          v-else
          class="msg-avatar-emoji"
          :src="index === messages.length - 1 ? currentEmojiUrl : getImgUrl(EMOJI_URLS.idle)"
          :key="index === messages.length - 1 ? currentEmojiUrl : 'idle-' + index"
        />
      </template>
      <div class="msg-avatar user" v-else>
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2">
          <circle cx="12" cy="8" r="4"/>
          <path d="M4 20c0-4 4-6 8-6s8 2 8 6"/>
        </svg>
      </div>

      <!-- 气泡区域 -->
      <div class="msg-bubble-col" :class="{ user: msg.role === 'user' }">

        <!-- 用户消息图片 -->
        <img
          v-if="msg.role === 'user' && msg.image_url"
          :src="msg._resolvedImageUrl || ''"
          class="msg-image"
          @click="openImageModal(msg._resolvedImageUrl)"
          alt="用户图片"
        />

        <!-- AI 消息：Agent 状态卡片（仅最后一条且状态可见时显示） -->
        <div
          v-if="msg.role === 'assistant' && index === messages.length - 1 && agentStatus.visible"
          class="agent-status"
        >
          <div class="agent-status-card">
            <!-- 路由状态 -->
            <div v-if="agentStatus.routing" class="agent-status-item">
              <div class="agent-dot"></div>
              <span>{{ agentStatus.statusText || '路由分析中...' }}</span>
            </div>
            <!-- Agent 列表 -->
            <div class="agent-status-list">
              <div
                v-for="(item, i) in agentStatus.items"
                :key="i"
                class="agent-status-item"
                :class="{ done: item.status === 'done', error: item.status === 'error' }"
              >
                <img
                  v-if="getAgentEmojiUrl(item)"
                  class="agent-avatar-emoji"
                  :src="getAgentEmojiUrl(item)"
                />
                <div v-else class="agent-dot" :class="{ done: item.status === 'done', error: item.status === 'error' }"></div>
                <span>{{ item.name }}</span>
              </div>
            </div>
            <!-- 状态文本 -->
            <div v-if="!agentStatus.routing && agentStatus.statusText" class="agent-status-text">
              {{ agentStatus.statusText }}
            </div>
          </div>
        </div>

        <!-- AI 消息气泡 -->
        <div v-if="msg.role === 'assistant'" class="msg-bubble ai">
          <!-- 旧格式：image_url 字段 + content 不含 [IMAGE] 标记 -->
          <img
            v-if="msg.image_url && !(msg.content || '').includes('[IMAGE]')"
            :src="msg._resolvedImageUrl || ''"
            class="msg-image msg-image-block"
            @click="openImageModal(msg._resolvedImageUrl)"
            alt="AI生成图片"
          />
          <!-- 新格式：content 中含 [IMAGE]url[/IMAGE] 标记 -->
          <template v-for="(part, pi) in parseContent(msg.content, msg)" :key="pi">
            <img
              v-if="part.type === 'image'"
              :src="part.resolvedUrl || part.url"
              class="msg-image msg-image-block"
              @click="openImageModal(part.resolvedUrl || part.url)"
              alt="AI生成图片"
            />
            <span v-else-if="part.content.trim() && part.content.trim() !== '[已生成效果图]'">{{ part.content }}</span>
          </template>
          <!-- 搜索返回的参考图片网格 -->
          <div v-if="msg.images && msg.images.length" class="msg-search-images">
            <img
              v-for="(imgUrl, si) in msg.images"
              :key="imgUrl + '-' + si"
              :src="getResolvedSearchImageUrl(msg, si)"
              class="msg-search-img"
              @click="openImageModal(getResolvedSearchImageUrl(msg, si))"
              @error="removeSearchImage(msg, imgUrl)"
              loading="lazy"
              alt="搜索参考图"
            />
          </div>
          <!-- 空内容时显示占位 -->
          <span v-if="!msg.content && !msg.image_url && agentStatus.visible" style="color:var(--text-tertiary);">...</span>
        </div>

        <!-- 用户消息气泡 -->
        <div v-else-if="msg.content" class="msg-bubble user">
          {{ msg.content }}
        </div>

      </div>
    </div>

    <!-- 快捷入口（仅欢迎消息时显示） -->
    <div v-if="messages.length <= 1" class="chat-quick-actions">
      <div
        v-for="(action, i) in quickActions"
        :key="i"
        class="chat-quick-chip"
        @click="sendQuickAction(action.prompt)"
      >{{ action.text }}</div>
    </div>
  </div>

  <!-- ==================== 输入栏 ==================== -->
  <div class="chat-input-bar is-column">

    <!-- 隐藏的文件输入 -->
    <input
      ref="fileInputRef"
      type="file"
      accept="image/*"
      class="hidden-input"
      @change="onFileChange"
    />

    <!-- 待上传图片缩略图（在输入框上方左侧） -->
    <div v-if="pendingImage" class="thumb-row">
      <div class="upload-thumb-container">
        <img :src="getImgUrl(pendingImage.url)" class="upload-thumb" @click="openImageModal(getImgUrl(pendingImage.url))" alt="待发送图片" />
        <div class="upload-thumb-close" @click="removePendingImage">
          <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="3">
            <line x1="18" y1="6" x2="6" y2="18"/>
            <line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
        </div>
      </div>
    </div>

    <!-- 输入行 -->
    <div class="chat-input-wrapper">
      <!-- "+" 按钮 -->
      <div class="chat-input-btn" @click="triggerFileSelect">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="12" cy="12" r="10"/>
          <line x1="12" y1="8" x2="12" y2="16"/>
          <line x1="8" y1="12" x2="16" y2="12"/>
        </svg>
      </div>

      <!-- 文本输入框 -->
      <textarea
        class="chat-input"
        v-model="inputText"
        rows="1"
        placeholder="输入消息..."
        @keydown="onInputKeydown"
        @input="autoResize"
      ></textarea>

      <!-- 发送按钮 -->
      <div
        class="chat-send-btn"
        :class="{ disabled: !inputText.trim() && !pendingImage && !sending }"
        @click="sendMessage"
      >
        <svg v-if="!sending" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2">
          <line x1="22" y1="2" x2="11" y2="13"/>
          <polygon points="22 2 15 22 11 13 2 9 22 2"/>
        </svg>
        <svg v-else width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2" class="spin-icon">
          <path d="M21 12a9 9 0 1 1-6.219-8.56"/>
        </svg>
      </div>
    </div>

  </div>

  <!-- ==================== 图片放大弹窗 ==================== -->
  <div
    v-if="modalImage"
    class="image-modal"
    ref="modalEl"
    @click="closeImageModal"
  >
    <!-- 横图在竖屏：自动旋转90度，cover铺满无黑边 -->
    <div v-if="modalOrientation === 'landscape_rotated'" class="img-rotate-wrapper" @click.stop
      :style="{
        transform: 'translate(calc(-50% + ' + panX + 'px), calc(-50% + ' + panY + 'px)) rotate(90deg) scale(' + pinchScale + ')',
        transition: pinchScale === 1 && panX === 0 && panY === 0 ? 'transform 0.2s ease' : 'none',
      }"
    >
      <img :src="getImgUrl(modalImage)" alt="放大图片" />
    </div>
    <!-- 普通模式 -->
    <img
      v-else
      :src="getImgUrl(modalImage)"
      alt="放大图片"
      @click.stop
      :style="{
        transform: 'translate(' + panX + 'px, ' + panY + 'px) scale(' + pinchScale + ')',
        transition: pinchScale === 1 && panX === 0 && panY === 0 ? 'transform 0.2s ease' : 'none',
      }"
    />
    <!-- 关闭按钮 -->
    <div class="image-modal-close" @click.stop="closeImageModal">
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2">
        <line x1="18" y1="6" x2="6" y2="18"/>
        <line x1="6" y1="6" x2="18" y2="18"/>
      </svg>
    </div>
  </div>

</div>
  `,
};
