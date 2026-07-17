/**
 * 灵犀 — 全景页面
 * 三种模式：上传成品 / AI生成 / 拍照
 * 最终用 Pannellum 展示 360° 全景
 */
window.PanoramaView = {
  emits: ['back', 'navigate'],
  props: {
    isDesktop: Boolean,
  },
  template: `
    <div class="page-panorama" :class="{ 'desktop-mode': isDesktop }">
      <!-- 桌面端: Icon Rail -->
      <div v-if="isDesktop" class="icon-rail">
        <div class="icon-rail-logo" @click="$emit('navigate', 'home')">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="white" stroke="none">
            <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 15h-2v-2h2v2zm0-4h-2V7h2v6zm4 4h-2v-2h2v2zm0-4h-2V7h2v6z" opacity="0.9"/>
          </svg>
        </div>
        <div class="icon-rail-divider"></div>
        <div class="icon-rail-btn" title="首页" @click="$emit('navigate', 'home')">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/>
          </svg>
        </div>
        <div class="icon-rail-btn" title="对话" @click="$emit('navigate', 'chat')">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
          </svg>
        </div>
        <div class="icon-rail-btn" title="改图" @click="$emit('navigate', 'image-edit')">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/>
          </svg>
        </div>
        <div class="icon-rail-btn active" title="全景">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="10"/><ellipse cx="12" cy="12" rx="10" ry="4"/><path d="M2 12h20"/>
          </svg>
        </div>
      </div>

      <!-- 顶部导航 -->
      <div class="pano-nav">
        <div v-if="!isDesktop" class="pano-back" @click="$emit('back')">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 18 9 12 15 6"/></svg>
        </div>
        <h2 class="pano-title">全景</h2>
        <div class="nav-spacer"></div>
      </div>

      <!-- Toast 通知 -->
      <div v-if="panoToast" class="pano-toast">{{ panoToast }}</div>

      <!-- 模式选择 (仅在未开始时显示) -->
      <div v-if="!activeMode" class="pano-mode-select">
        <div class="pano-mode-card" @click="activeMode = 'upload'">
          <div class="pano-mode-icon upload">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>
          </div>
          <h3>上传成品</h3>
          <p>上传6个位置的渲染图<br/>AI自动调整拼接</p>
        </div>

        <div class="pano-mode-card" @click="activeMode = 'generate'">
          <div class="pano-mode-icon generate">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>
          </div>
          <h3>AI生成</h3>
          <p>上传平面图和风格参考<br/>AI自动生成6面图</p>
        </div>

        <div class="pano-mode-card" @click="activeMode = 'camera'">
          <div class="pano-mode-icon camera">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2"><path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/></svg>
          </div>
          <h3>拍照模式</h3>
          <p>对准6个方向逐个拍摄<br/>AI修正后拼接</p>
        </div>
      </div>

      <!-- 历史全景 (仅在模式选择页显示) -->
      <div v-if="!activeMode && historyList.length > 0" class="pano-history-section">
        <div class="pano-history-header">
          <svg class="pano-history-icon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#9E9EA4" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
          <span>历史全景</span>
        </div>
        <div class="pano-history-list">
          <div v-for="item in historyList" :key="item.url"
               class="pano-history-item" @click="viewHistory(item)">
            <img :src="getImgUrl(item.url)" class="pano-history-thumb" />
            <div class="pano-history-info">
              <span class="pano-history-name">{{ item.filename }}</span>
              <span class="pano-history-time">{{ formatPanoTime(item.created_at) }}</span>
            </div>
            <svg class="pano-history-arrow pano-history-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 18 15 12 9 6"/></svg>
          </div>
        </div>
      </div>

      <!-- 上传成品模式 -->
      <div v-if="activeMode === 'upload'" class="pano-content">
        <div class="pano-step-header">
          <span class="pano-back-btn" @click="activeMode = null">← 返回</span>
          <h3>上传6个位置的图 + 平面图</h3>
        </div>
        <div class="pano-upload-grid">
          <div v-for="pos in facePositions" :key="pos.key" class="pano-upload-slot"
               :class="{ filled: uploadFaces[pos.key] }"
               @click="triggerUpload(pos.key)">
            <img v-if="uploadFaces[pos.key]" :src="getImgUrl(uploadFaces[pos.key])" class="pano-slot-img" />
            <div v-else class="pano-slot-placeholder">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
              <span>{{ pos.label }}</span>
              <small>{{ pos.hint }}</small>
            </div>
          </div>
        </div>
        <!-- 平面图上传 -->
        <div class="pano-floorplan-upload" @click="triggerUpload('floorplan')">
          <img v-if="uploadFaces.floorplan" :src="getImgUrl(uploadFaces.floorplan)" class="pano-slot-img" />
          <div v-else class="pano-slot-placeholder">
            <span>📐 上传平面图（可选）</span>
          </div>
        </div>
        <button class="pano-action-btn" :disabled="!canStitch || stitching || correcting" @click="doStitch">
          {{ stitching ? '拼接中...' : (correcting ? 'AI修正中...' : '拼接全景') }}
        </button>
        <div v-if="correcting" class="pano-correcting-hint">
          <div class="pano-correcting-spinner"></div>
          <p>AI正在修正接缝和光照...</p>
        </div>
      </div>

      <!-- AI生成模式 -->
      <div v-if="activeMode === 'generate'" class="pano-content">
        <div class="pano-step-header">
          <span class="pano-back-btn" @click="activeMode = null">← 返回</span>
          <h3>AI生成6面图</h3>
        </div>
        <div class="pano-gen-section">
          <div class="pano-gen-upload" @click="triggerFloorplanUpload()">
            <img v-if="genFloorPlan" :src="getImgUrl(genFloorPlan)" class="pano-slot-img" />
            <div v-else class="pano-slot-placeholder">
              <span>📐 上传平面图</span>
            </div>
          </div>
          <input ref="floorplanInput" type="file" accept="image/*" @change="onFloorPlanUpload" class="hidden-input" />
          
          <div class="pano-gen-style">
            <label>风格描述</label>
            <textarea v-model="genStyleDesc" placeholder="如：现代北欧风格，浅色调，木质家具..." rows="2"></textarea>
          </div>
          
          <div class="pano-gen-upload" @click="triggerStyleRefUpload()">
            <img v-if="genStyleRef" :src="getImgUrl(genStyleRef)" class="pano-slot-img" />
            <div v-else class="pano-slot-placeholder">
              <span>🎨 风格参考图（可选）</span>
            </div>
          </div>
          <input ref="styleRefInput" type="file" accept="image/*" @change="onStyleRefUpload" class="hidden-input" />
        </div>
        <button class="pano-action-btn" :disabled="!genFloorPlan || generating" @click="doGenerate">
          {{ generating ? '生成中（约2-3分钟）...' : 'AI生成全景' }}
        </button>
        <!-- 生成进度 -->
        <div v-if="generating" class="pano-progress">
          <div class="pano-progress-item">
            <span>AI 正在生成全景图</span>
          </div>
        </div>
      </div>

      <!-- 拍照模式 -->
      <div v-if="activeMode === 'camera'" class="pano-content">
        <div class="pano-step-header">
          <span class="pano-back-btn" @click="activeMode = null">← 返回</span>
          <h3>拍照模式 — 第 {{ cameraStep + 1 }}/{{ facePositions.length }} 步</h3>
        </div>
        <div class="pano-camera-container">
          <video v-show="!cameraPhotos[facePositions[cameraStep].key]" 
                 ref="cameraVideo" class="pano-camera-video" autoplay playsinline></video>
          <img v-if="cameraPhotos[facePositions[cameraStep].key]"
               :src="getImgUrl(cameraPhotos[facePositions[cameraStep].key])" class="pano-camera-preview" />
          
          <!-- 引导框 -->
          <div v-if="!cameraPhotos[facePositions[cameraStep].key]" class="pano-guide-overlay">
            <div class="pano-guide-frame"></div>
            <div class="pano-guide-text">
              <p class="pano-guide-direction">{{ facePositions[cameraStep].label }}</p>
              <p class="pano-guide-hint">{{ facePositions[cameraStep].hint }}</p>
            </div>
          </div>
        </div>
        
        <div v-if="correcting" class="pano-correcting-hint">
          <div class="pano-correcting-spinner"></div>
          <p>AI正在修正接缝和光照...</p>
        </div>
        
        <div class="pano-camera-controls">
          <button v-if="!cameraPhotos[facePositions[cameraStep].key]" 
                  class="pano-action-btn" @click="capturePhoto">拍摄</button>
          <div v-else class="pano-camera-review">
            <button class="pano-action-btn secondary" @click="retakePhoto">重拍</button>
            <button v-if="cameraStep < facePositions.length - 1" 
                    class="pano-action-btn" @click="nextCameraStep">下一步</button>
            <button v-else class="pano-action-btn" @click="doCameraStitch">拼接全景</button>
          </div>
        </div>
        
        <!-- 拍摄进度点 -->
        <div class="pano-camera-dots">
          <div v-for="(pos, i) in facePositions" :key="pos.key" 
               class="pano-dot" :class="{ 
                 done: cameraPhotos[pos.key], 
                 current: i === cameraStep 
               }"></div>
        </div>
      </div>

      <!-- 全景查看器 -->
      <div v-if="panoramaUrl" class="pano-viewer-container">
        <div class="pano-viewer-header">
          <span class="pano-back-btn" @click="closeViewer">← 重新制作</span>
          <h3>360° 全景</h3>
          <a :href="getImgUrl(panoramaUrl)" download class="pano-download-btn">下载</a>
        </div>
        <div ref="panoViewer" class="pano-viewer"></div>
        <p class="pano-viewer-hint">👆 拖动画面查看 360° 视角</p>
      </div>

      <!-- 隐藏文件输入 -->
      <input ref="fileInput" type="file" accept="image/*" class="hidden-input" @change="onFileSelected" />
    </div>
  `,

  setup(props) {
    const { ref, reactive, nextTick, onMounted, onUnmounted, watch } = Vue;

    const activeMode = ref(null);
    const stitching = ref(false);
    const generating = ref(false);
    const panoramaUrl = ref('');
    const historyList = ref([]);
    
    // Toast 通知
    const panoToast = ref('');
    let panoToastTimer = null;
    function showPanoToast(msg) {
      panoToast.value = msg;
      if (panoToastTimer) clearTimeout(panoToastTimer);
      panoToastTimer = setTimeout(() => { panoToast.value = ''; }, 3000);
    }
    
    // 当前上传槽位
    const currentUploadSlot = ref('');
    const fileInput = ref(null);
    const floorplanInput = ref(null);
    const styleRefInput = ref(null);
    const correcting = ref(false);
    
    // 上传模式数据
    const uploadFaces = reactive({});
    const facePositions = [
      { key: 'front', label: '前', hint: '正前方墙面' },
      { key: 'right', label: '右', hint: '右侧墙面' },
      { key: 'back', label: '后', hint: '后方墙面' },
      { key: 'left', label: '左', hint: '左侧墙面' },
      { key: 'top', label: '顶', hint: '天花板' },
      { key: 'bottom', label: '底', hint: '地板' },
    ];
    
    const canStitch = ref(false);
    
    function checkCanStitch() {
      canStitch.value = facePositions.every(p => uploadFaces[p.key]);
    }
    
    // AI生成模式
    const genFloorPlan = ref('');
    const genStyleRef = ref('');
    const genStyleDesc = ref('现代北欧风格，浅色调，木质家具，温暖自然光');
    
    // 拍照模式
    const cameraStep = ref(0);
    const cameraPhotos = reactive({});
    const cameraVideo = ref(null);
    const cameraStream = ref(null);
    const panoViewer = ref(null);
    
    let pannellumViewer = null;
    let abortTimeoutId = null;
    
    // === 上传模式 ===
    function triggerUpload(slotKey) {
      currentUploadSlot.value = slotKey;
      if (fileInput.value) {
        fileInput.value.dataset.slot = slotKey;
        fileInput.value.click();
      }
    }
    
    async function onFileSelected(e) {
      const file = e.target.files[0];
      if (!file) return;
      const slotKey = e.target.dataset.slot || currentUploadSlot.value;
      
      // 上传到服务器（自动压缩）
      const formData = new FormData();
      formData.append('file', file);
      try {
        const res = await apiFetch('/api/upload', { method: 'POST', body: formData });
        const data = await res.json();
        uploadFaces[slotKey] = data.url;
        checkCanStitch();
      } catch (err) {
        showPanoToast('上传失败: ' + err.message);
      }
      e.target.value = '';
    }
    
    async function doStitch() {
      stitching.value = true;
      try {
        const formData = new FormData();
        const order = ['front', 'right', 'back', 'left', 'top', 'bottom'];
        for (const key of order) {
          // 从 URL 获取图片 blob
          const res = await apiFetch(getImgUrl(uploadFaces[key]));
          const blob = await res.blob();
          formData.append('files', blob, `${key}.jpg`);
        }
        const res = await apiFetch('/api/panorama/stitch', { method: 'POST', body: formData });
        const data = await res.json();
        if (data.error) {
          showPanoToast('拼接失败: ' + data.error);
        } else {
          // AI 修正接缝
          stitching.value = false;
          correcting.value = true;
          try {
            const correctRes = await apiFetch('/api/panorama/ai-correct', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ panorama_url: data.url })
            });
            const correctData = await correctRes.json();
            panoramaUrl.value = correctData.url || data.url;
          } catch (e) {
            panoramaUrl.value = data.url;
          }
          correcting.value = false;
          await nextTick();
          initPannellum(panoramaUrl.value);
        }
      } catch (err) {
        showPanoToast('拼接失败: ' + err.message);
      }
      stitching.value = false;
    }
    
    // === AI生成模式 ===
    function triggerFloorplanUpload() {
      if (floorplanInput.value) {
        floorplanInput.value.click();
      }
    }

    function triggerStyleRefUpload() {
      if (styleRefInput.value) {
        styleRefInput.value.click();
      }
    }

    async function onFloorPlanUpload(e) {
      const file = e.target.files[0];
      if (!file) return;
      const formData = new FormData();
      formData.append('file', file);
      try {
        const res = await apiFetch('/api/upload', { method: 'POST', body: formData });
        const data = await res.json();
        genFloorPlan.value = data.url;
      } catch (err) {
        alert('上传失败: ' + err.message);
      }
      e.target.value = '';
    }
    
    async function onStyleRefUpload(e) {
      const file = e.target.files[0];
      if (!file) return;
      const formData = new FormData();
      formData.append('file', file);
      try {
        const res = await apiFetch('/api/upload', { method: 'POST', body: formData });
        const data = await res.json();
        genStyleRef.value = data.url;
      } catch (err) {
        alert('上传失败: ' + err.message);
      }
      e.target.value = '';
    }
    
    async function doGenerate() {
      generating.value = true;
      try {
        // 获取平面图 blob
        const planRes = await apiFetch(getImgUrl(genFloorPlan.value));
        const planBlob = await planRes.blob();
        
        const formData = new FormData();
        formData.append('floor_plan', planBlob, 'floorplan.jpg');
        formData.append('style_desc', genStyleDesc.value);
        
        // 方案A：直接生成 equirectangular 全景图
        const controller = new AbortController();
        abortTimeoutId = setTimeout(() => controller.abort(), 600000); // 10分钟超时
        try {
          const res = await apiFetch('/api/panorama/ai-generate', {
            method: 'POST',
            body: formData,
            signal: controller.signal,
          });
          clearTimeout(abortTimeoutId);
          abortTimeoutId = null;
          const data = await res.json();
          if (data.error) {
            showPanoToast('生成失败: ' + data.error);
          } else if (data.url) {
            panoramaUrl.value = data.url;
            await nextTick();
            initPannellum(data.url);
          }
        } catch (fetchErr) {
          clearTimeout(abortTimeoutId);
          abortTimeoutId = null;
          if (fetchErr.name === 'AbortError') {
            showPanoToast('生成超时，请重试');
          } else {
            throw fetchErr;
          }
        }
      } catch (err) {
        showPanoToast('生成失败: ' + err.message);
      }
      generating.value = false;
    }
    
    // === 拍照模式 ===
    async function startCamera() {
      try {
        cameraStream.value = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: 'environment', width: { ideal: 1024 }, height: { ideal: 1024 } }
        });
        if (cameraVideo.value) {
          cameraVideo.value.srcObject = cameraStream.value;
        }
      } catch (err) {
        showPanoToast('无法访问摄像头: ' + err.message);
        activeMode.value = null;
      }
    }
    
    function capturePhoto() {
      const video = cameraVideo.value;
      if (!video) return;
      // 检查视频流是否就绪，避免捕获空白图片
      if (!video.videoWidth || !video.videoHeight) {
        showPanoToast('摄像头未就绪，请稍后再试');
        return;
      }
      const canvas = document.createElement('canvas');
      // 取正方形画面
      const size = Math.min(video.videoWidth, video.videoHeight);
      canvas.width = 1024;
      canvas.height = 1024;
      const ctx = canvas.getContext('2d');
      const sx = (video.videoWidth - size) / 2;
      const sy = (video.videoHeight - size) / 2;
      ctx.drawImage(video, sx, sy, size, size, 0, 0, 1024, 1024);
      // 捕获当前 key，避免异步回调中 cameraStep.value 已变化导致闭包问题
      const key = facePositions[cameraStep.value].key;
      canvas.toBlob(async (blob) => {
        try {
          // 上传
          const formData = new FormData();
          formData.append('file', blob, `${key}.jpg`);
          const res = await apiFetch('/api/upload', { method: 'POST', body: formData });
          const data = await res.json();
          cameraPhotos[key] = data.url;
        } catch (err) {
          showPanoToast('上传失败: ' + err.message);
        }
      }, 'image/jpeg', 0.9);
    }
    
    function retakePhoto() {
      delete cameraPhotos[facePositions[cameraStep.value].key];
    }
    
    function nextCameraStep() {
      if (cameraStep.value < facePositions.length - 1) {
        cameraStep.value++;
      }
    }
    
    async function doCameraStitch() {
      stitching.value = true;
      try {
        const formData = new FormData();
        const order = ['front', 'right', 'back', 'left', 'top', 'bottom'];
        for (const key of order) {
          const res = await apiFetch(getImgUrl(cameraPhotos[key]));
          const blob = await res.blob();
          formData.append('files', blob, `${key}.jpg`);
        }
        const res = await apiFetch('/api/panorama/stitch', { method: 'POST', body: formData });
        const data = await res.json();
        if (data.error) {
          showPanoToast('拼接失败: ' + data.error);
        } else if (data.url) {
          // AI 修正接缝
          stitching.value = false;
          correcting.value = true;
          try {
            const correctRes = await apiFetch('/api/panorama/ai-correct', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ panorama_url: data.url })
            });
            const correctData = await correctRes.json();
            panoramaUrl.value = correctData.url || data.url;
          } catch (e) {
            panoramaUrl.value = data.url;
          }
          correcting.value = false;
          await nextTick();
          initPannellum(panoramaUrl.value);
        }
      } catch (err) {
        showPanoToast('拼接失败: ' + err.message);
      }
      stitching.value = false;
      correcting.value = false;
    }
    
    // === Pannellum 查看器 ===
    function initPannellum(url) {
      if (typeof pannellum === 'undefined') {
        showPanoToast('全景查看器加载失败，请刷新页面重试');
        return;
      }
      if (pannellumViewer) {
        pannellumViewer.destroy();
      }
      // panoViewer ref 可能在 v-if 刚渲染时还未绑定，用 querySelector 兜底
      const el = panoViewer.value || document.querySelector('.pano-viewer');
      if (!el) return;
      pannellumViewer = pannellum.viewer(el, {
        type: 'equirectangular',
        panorama: getImgUrl(url),
        autoLoad: true,
        showZoomCtrl: false,
        showFullscreenCtrl: true,
        compass: false,
        minHfov: 50,
        maxHfov: 120,
      });
    }
    
    function closeViewer() {
      if (pannellumViewer) {
        pannellumViewer.destroy();
        pannellumViewer = null;
      }
      panoramaUrl.value = '';
      activeMode.value = null;
      // 清空数据
      canStitch.value = false;
      Object.keys(uploadFaces).forEach(k => delete uploadFaces[k]);
      Object.keys(cameraPhotos).forEach(k => delete cameraPhotos[k]);
      cameraStep.value = 0;
      // 清空 AI 生成模式数据，防止残留
      genFloorPlan.value = '';
      genStyleRef.value = '';
      genStyleDesc.value = '现代北欧风格，浅色调，木质家具，温暖自然光';
    }
    
    // 监听 activeMode 变化，启动/停止摄像头
    function onModeChange() {
      if (activeMode.value === 'camera') {
        nextTick(() => startCamera());
      } else {
        if (cameraStream.value) {
          cameraStream.value.getTracks().forEach(t => t.stop());
          cameraStream.value = null;
        }
      }
    }
    
    // 用 watch 替代
    watch(activeMode, onModeChange);
    
    // === 历史全景 ===
    async function loadHistory() {
      try {
        const res = await apiFetch('/api/panorama/history');
        const data = await res.json();
        historyList.value = data.history || [];
      } catch (e) {
        console.error('加载历史失败:', e);
      }
    }
    
    function formatPanoTime(ts) {
      if (!ts) return '';
      const now = Date.now() / 1000;
      const diff = now - ts;
      if (diff < 60) return '刚刚';
      if (diff < 3600) return Math.floor(diff / 60) + '分钟前';
      if (diff < 86400) return Math.floor(diff / 3600) + '小时前';
      const d = new Date(ts * 1000);
      return (d.getMonth() + 1) + '月' + d.getDate() + '日';
    }
    
    async function viewHistory(item) {
      panoramaUrl.value = item.url;
      activeMode.value = null;
      await nextTick();
      await nextTick();
      initPannellum(item.url);
    }
    
    onMounted(() => {
      loadHistory();
    });
    
    onUnmounted(() => {
      if (cameraStream.value) {
        cameraStream.value.getTracks().forEach(t => t.stop());
      }
      if (pannellumViewer) {
        pannellumViewer.destroy();
      }
      if (panoToastTimer) clearTimeout(panoToastTimer);
      if (abortTimeoutId) {
        clearTimeout(abortTimeoutId);
        abortTimeoutId = null;
      }
    });
    
    return {
      activeMode, facePositions, uploadFaces, canStitch,
      stitching, generating, panoramaUrl, correcting, historyList,
      genFloorPlan, genStyleRef, genStyleDesc,
      cameraStep, cameraPhotos, cameraVideo, panoViewer, fileInput,
      floorplanInput, styleRefInput,
      panoToast, showPanoToast,
      getImgUrl,
      triggerUpload, onFileSelected, doStitch,
      triggerFloorplanUpload, triggerStyleRefUpload,
      onFloorPlanUpload, onStyleRefUpload, doGenerate,
      capturePhoto, retakePhoto, nextCameraStep, doCameraStitch,
      closeViewer, loadHistory, viewHistory, formatPanoTime,
    };
  },
};
