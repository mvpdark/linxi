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
            <img :src="getHistoryThumb(item)" class="pano-history-thumb" />
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
               :src="getCameraPhotoUrl(facePositions[cameraStep].key)" class="pano-camera-preview" />
          
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
          <a :href="getResolvedPanoUrl()" download class="pano-download-btn">下载</a>
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
    // MediaStream 用普通变量持有：避免 Vue 对原生流对象做深度响应式代理
    let cameraStream = null;
    const panoViewer = ref(null);

    let pannellumViewer = null;
    let abortTimeoutId = null;
    let generateController = null; // doGenerate 的 AbortController（组件卸载时统一取消）
    
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
        if (!res.ok) {
          console.warn('上传图片失败: HTTP ' + res.status);
          showPanoToast('图片上传失败，请重试');
          e.target.value = '';
          return;
        }
        const data = await res.json();
        // 架构改造：后端返回 {image: dataUrl, id: imgId}，存 IndexedDB
        if (data.image && window.ImageStore) {
          const imgId = data.id || await window.ImageStore.saveImage(data.image);
          if (!data.id) await window.ImageStore.saveImage(data.image, imgId);
          uploadFaces[slotKey] = imgId;
        } else if (data.url) {
          // 兼容旧格式
          uploadFaces[slotKey] = data.url;
        }
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
          // 架构改造：从 IndexedDB 读 data URL，转 blob
          let blob;
          const imgRef = uploadFaces[key];
          if (window.ImageStore && window.ImageStore.isImageId(imgRef)) {
            const dataUrl = await window.ImageStore.getImageDataUrl(imgRef);
            if (!dataUrl) throw new Error('读取面图失败（IndexedDB 无数据）');
            const resp = await fetch(dataUrl);
            blob = await resp.blob();
          } else {
            // 兼容旧 URL
            const res = await apiFetch(getImgUrl(imgRef));
            if (!res.ok) {
              console.warn('读取面图失败: HTTP ' + res.status);
              throw new Error('读取面图失败(HTTP ' + res.status + ')');
            }
            blob = await res.blob();
          }
          formData.append('files', blob, `${key}.jpg`);
        }
        const res = await apiFetch('/api/panorama/stitch', { method: 'POST', body: formData });
        if (!res.ok) {
          console.warn('拼接请求失败: HTTP ' + res.status);
          showPanoToast('拼接失败(HTTP ' + res.status + ')，请重试');
          stitching.value = false;
          return;
        }
        const data = await res.json();
        if (data.error) {
          showPanoToast('拼接失败: ' + data.error);
        } else {
          // 架构改造：后端返回 {image: dataUrl, id: panoId}，存 IndexedDB
          let panoDisplayUrl;
          if (data.image && window.ImageStore) {
            const panoId = data.id || await window.ImageStore.saveImage(data.image);
            if (!data.id) await window.ImageStore.saveImage(data.image, panoId);
            panoramaUrl.value = panoId;
            panoDisplayUrl = await window.ImageStore.getImageUrl(panoId);
          } else {
            // 兼容旧格式
            panoramaUrl.value = data.url;
            panoDisplayUrl = data.url;
          }
          // AI 修正接缝
          stitching.value = false;
          correcting.value = true;
          try {
            const correctRes = await apiFetch('/api/panorama/ai-correct', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ panorama_url: panoDisplayUrl })
            });
            const correctData = await correctRes.json();
            // 架构改造：ai-correct 也返回 {image: dataUrl}
            if (correctData.image && window.ImageStore) {
              const correctedId = await window.ImageStore.saveImage(correctData.image);
              panoramaUrl.value = correctedId;
              panoDisplayUrl = await window.ImageStore.getImageUrl(correctedId);
            } else {
              panoramaUrl.value = correctData.url || panoramaUrl.value;
              panoDisplayUrl = correctData.url || panoDisplayUrl;
            }
          } catch (e) {
            // 修正失败，保留原图
          }
          correcting.value = false;
          await nextTick();
          initPannellum(panoDisplayUrl);
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
        if (!res.ok) {
          console.warn('上传平面图失败: HTTP ' + res.status);
          showPanoToast('平面图上传失败，请重试');
          e.target.value = '';
          return;
        }
        const data = await res.json();
        // 架构改造：存 IndexedDB
        if (data.image && window.ImageStore) {
          const imgId = data.id || await window.ImageStore.saveImage(data.image);
          if (!data.id) await window.ImageStore.saveImage(data.image, imgId);
          genFloorPlan.value = imgId;
        } else {
          genFloorPlan.value = data.url;
        }
      } catch (err) {
        showPanoToast('上传失败: ' + err.message);
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
        if (!res.ok) {
          console.warn('上传风格参考图失败: HTTP ' + res.status);
          showPanoToast('风格参考图上传失败，请重试');
          e.target.value = '';
          return;
        }
        const data = await res.json();
        // 架构改造：存 IndexedDB
        if (data.image && window.ImageStore) {
          const imgId = data.id || await window.ImageStore.saveImage(data.image);
          if (!data.id) await window.ImageStore.saveImage(data.image, imgId);
          genStyleRef.value = imgId;
        } else {
          genStyleRef.value = data.url;
        }
      } catch (err) {
        showPanoToast('上传失败: ' + err.message);
      }
      e.target.value = '';
    }
    
    async function doGenerate() {
      generating.value = true;
      try {
        // 架构改造：从 IndexedDB 读平面图 blob
        let planBlob;
        if (window.ImageStore && window.ImageStore.isImageId(genFloorPlan.value)) {
          const dataUrl = await window.ImageStore.getImageDataUrl(genFloorPlan.value);
          if (!dataUrl) {
            showPanoToast('读取平面图失败，请重新上传');
            generating.value = false;
            return;
          }
          const resp = await fetch(dataUrl);
          planBlob = await resp.blob();
        } else {
          const planRes = await apiFetch(getImgUrl(genFloorPlan.value));
          if (!planRes.ok) {
            console.warn('读取平面图失败: HTTP ' + planRes.status);
            showPanoToast('读取平面图失败，请重新上传');
            generating.value = false;
            return;
          }
          planBlob = await planRes.blob();
        }

        const formData = new FormData();
        formData.append('floor_plan', planBlob, 'floorplan.jpg');
        formData.append('style_desc', genStyleDesc.value);

        // 方案A：直接生成 equirectangular 全景图
        // controller 提升到外层变量，组件卸载时（onUnmounted）统一 abort
        generateController = new AbortController();
        abortTimeoutId = setTimeout(() => generateController.abort(), 600000); // 10分钟超时
        try {
          const res = await apiFetch('/api/panorama/ai-generate', {
            method: 'POST',
            body: formData,
            signal: generateController.signal,
          });
          clearTimeout(abortTimeoutId);
          abortTimeoutId = null;
          generateController = null;
          if (!res.ok) {
            console.warn('AI 生成失败: HTTP ' + res.status);
            showPanoToast('生成失败(HTTP ' + res.status + ')，请重试');
            generating.value = false;
            return;
          }
          const data = await res.json();
          if (data.error) {
            showPanoToast('生成失败: ' + data.error);
          } else if (data.image && window.ImageStore) {
            // 架构改造：后端返回 {image: dataUrl, id: panoId}
            const panoId = data.id || await window.ImageStore.saveImage(data.image);
            if (!data.id) await window.ImageStore.saveImage(data.image, panoId);
            panoramaUrl.value = panoId;
            const displayUrl = await window.ImageStore.getImageUrl(panoId);
            await nextTick();
            initPannellum(displayUrl);
          } else if (data.url) {
            // 兼容旧格式
            panoramaUrl.value = data.url;
            await nextTick();
            initPannellum(data.url);
          }
        } catch (fetchErr) {
          clearTimeout(abortTimeoutId);
          abortTimeoutId = null;
          generateController = null;
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
        cameraStream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: 'environment', width: { ideal: 1024 }, height: { ideal: 1024 } }
        });
        if (cameraVideo.value) {
          cameraVideo.value.srcObject = cameraStream;
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
          // 架构改造：存 IndexedDB
          if (data.image && window.ImageStore) {
            const imgId = data.id || await window.ImageStore.saveImage(data.image);
            if (!data.id) await window.ImageStore.saveImage(data.image, imgId);
            cameraPhotos[key] = imgId;
          } else {
            cameraPhotos[key] = data.url;
          }
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
          // 架构改造：从 IndexedDB 读
          let blob;
          const imgRef = cameraPhotos[key];
          if (window.ImageStore && window.ImageStore.isImageId(imgRef)) {
            const dataUrl = await window.ImageStore.getImageDataUrl(imgRef);
            if (!dataUrl) throw new Error('读取拍照面图失败');
            const resp = await fetch(dataUrl);
            blob = await resp.blob();
          } else {
            const res = await apiFetch(getImgUrl(imgRef));
            blob = await res.blob();
          }
          formData.append('files', blob, `${key}.jpg`);
        }
        const res = await apiFetch('/api/panorama/stitch', { method: 'POST', body: formData });
        if (!res.ok) {
          console.warn('拼接请求失败: HTTP ' + res.status);
          showPanoToast('拼接失败(HTTP ' + res.status + ')，请重试');
          stitching.value = false;
          return;
        }
        const data = await res.json();
        if (data.error) {
          showPanoToast('拼接失败: ' + data.error);
        } else {
          // 架构改造：后端返回 {image, id}，存 IndexedDB
          let panoDisplayUrl;
          if (data.image && window.ImageStore) {
            const panoId = data.id || await window.ImageStore.saveImage(data.image);
            if (!data.id) await window.ImageStore.saveImage(data.image, panoId);
            panoramaUrl.value = panoId;
            panoDisplayUrl = await window.ImageStore.getImageUrl(panoId);
          } else {
            panoramaUrl.value = data.url;
            panoDisplayUrl = data.url;
          }
          // AI 修正接缝
          stitching.value = false;
          correcting.value = true;
          try {
            const correctRes = await apiFetch('/api/panorama/ai-correct', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ panorama_url: panoDisplayUrl })
            });
            const correctData = await correctRes.json();
            if (correctData.image && window.ImageStore) {
              const correctedId = await window.ImageStore.saveImage(correctData.image);
              panoramaUrl.value = correctedId;
              panoDisplayUrl = await window.ImageStore.getImageUrl(correctedId);
            } else {
              panoramaUrl.value = correctData.url || panoramaUrl.value;
              panoDisplayUrl = correctData.url || panoDisplayUrl;
            }
          } catch (e) {
            // 保留原图
          }
          correcting.value = false;
          await nextTick();
          initPannellum(panoDisplayUrl);
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
      // 架构改造：url 已是解析后的 blob/data URL（由调用方传入），
      // 不再二次走 getImgUrl；仅当是旧 URL 格式时才拼接 apiBase
      const panoramaUrl = (url && (url.startsWith('data:') || url.startsWith('blob:') || url.startsWith('http'))) ? url : getImgUrl(url);
      pannellumViewer = pannellum.viewer(el, {
        type: 'equirectangular',
        panorama: panoramaUrl,
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
        // 延迟到用户交互后再请求摄像头，避免页面一加载就弹权限
        // 实际在点击"拍照模式"卡片时已经有过用户手势，可以请求
        nextTick(() => requestCamera());
      } else {
        if (cameraStream) {
          cameraStream.getTracks().forEach(t => t.stop());
          cameraStream = null;
        }
      }
    }

    /** 先检查权限再请求摄像头，避免直接弹 toast */
    async function requestCamera() {
      try {
        // 检查权限状态（Android WebView 可能不支持 permissions API，需 catch）
        let perm = { state: 'prompt' };
        try {
          if (navigator.permissions && navigator.permissions.query) {
            perm = await navigator.permissions.query({ name: 'camera' });
          }
        } catch (e) {
          // 不支持 permissions API，直接请求
        }
        if (perm.state === 'denied') {
          showPanoToast('摄像头权限被拒绝，请在系统设置中开启');
          activeMode.value = null;
          return;
        }
        await startCamera();
      } catch (err) {
        showPanoToast('无法访问摄像头: ' + err.message);
        activeMode.value = null;
      }
    }
    
    // 用 watch 替代
    watch(activeMode, onModeChange);
    
    // === 历史全景 ===
    async function loadHistory() {
      try {
        const res = await apiFetch('/api/panorama/history');
        if (!res.ok) {
          console.warn('加载历史失败: HTTP ' + res.status);
          showPanoToast('加载历史记录失败');
          return;
        }
        const data = await res.json();
        historyList.value = data.history || [];
        // 架构改造：异步解析每张历史全景图的缩略图 URL
        resolveHistoryThumbnails();
      } catch (e) {
        console.error('加载历史失败:', e);
      }
    }

    /** 异步把历史列表里每项的 url（ID）解析为 blob URL，缓存到 _resolvedUrl */
    async function resolveHistoryThumbnails() {
      if (!window.ImageStore) return;
      for (const item of historyList.value) {
        if (item.url && !item._resolvedUrl) {
          if (window.ImageStore.isImageId(item.url)) {
            try {
              item._resolvedUrl = await window.ImageStore.getImageUrl(item.url);
            } catch (e) {
              console.warn('解析历史缩略图失败:', item.url, e);
              item._resolvedUrl = '';
            }
          } else {
            // 兼容旧 URL
            item._resolvedUrl = getImgUrl(item.url);
          }
        }
      }
    }

    /** 供模板用的拍照预览图 URL（cameraPhotos 存的是 ID，需解析） */
    async function getCameraPhotoUrl(key) {
      const imgId = cameraPhotos[key];
      if (!imgId) return '';
      if (window.ImageStore && window.ImageStore.isImageId(imgId)) {
        return await window.ImageStore.getImageUrl(imgId) || '';
      }
      return getImgUrl(imgId);
    }

    /** 供模板用的历史缩略图 URL 获取函数 */
    function getHistoryThumb(item) {
      if (item._resolvedUrl) return item._resolvedUrl;
      // 兜底：旧 URL 直接拼
      return item.url ? getImgUrl(item.url) : '';
    }

    /** 供模板用的当前全景图下载 URL（panoramaUrl 可能是 ID，需解析） */
    async function getResolvedPanoUrl() {
      const v = panoramaUrl.value;
      if (!v) return '';
      if (v.startsWith('data:') || v.startsWith('blob:') || v.startsWith('http')) return v;
      if (window.ImageStore && window.ImageStore.isImageId(v)) {
        return await window.ImageStore.getImageUrl(v) || '';
      }
      return getImgUrl(v);
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
      // 架构改造：item.url 可能是 pano ID，异步解析为 blob URL
      let displayUrl = item.url;
      if (window.ImageStore && window.ImageStore.isImageId(item.url)) {
        displayUrl = await window.ImageStore.getImageUrl(item.url);
        if (!displayUrl) {
          showPanoToast('该全景图本地数据已丢失');
          return;
        }
      }
      panoramaUrl.value = item.url;
      activeMode.value = null;
      await nextTick();
      await nextTick();
      initPannellum(displayUrl);
    }
    
    onMounted(() => {
      loadHistory();
    });
    
    onUnmounted(() => {
      if (cameraStream) {
        cameraStream.getTracks().forEach(t => t.stop());
        cameraStream = null;
      }
      if (pannellumViewer) {
        pannellumViewer.destroy();
      }
      if (panoToastTimer) clearTimeout(panoToastTimer);
      if (abortTimeoutId) {
        clearTimeout(abortTimeoutId);
        abortTimeoutId = null;
      }
      // 取消可能仍在进行的 AI 生成请求，避免卸载后回调写状态
      if (generateController) {
        generateController.abort();
        generateController = null;
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
      getCameraPhotoUrl, getHistoryThumb, getResolvedPanoUrl,
    };
  },
};
