(function() {
  const api = window.mochyAPI;
  const localAudio = document.getElementById('local-audio');

  let homeTracks = [];
  let searchTracks = [];
  let favorites = JSON.parse(localStorage.getItem('mochy_favorites') || '[]');
  let playlists = JSON.parse(localStorage.getItem('mochy_playlists') || '[]');
  let localTracks = [];

  let queue = [];
  let currentIndex = -1;
  let currentTrack = null;
  let isPlaying = false;
  let isShuffle = false;
  let repeatMode = 0; // 0: off, 1: all, 2: one
  let isSeeking = false;

  let syncedLyrics = [];
  let eqState = { enabled: true, gains: [0, 0, 0, 0, 0], bassBoost: 0 };
  let latestOtaRelease = null;

  // Local Audio WebAudio Equalizer
  let localAudioCtx = null;
  let localFilters = [];
  let localBassFilter = null;

  function initLocalAudioEq() {
    if (localAudioCtx) return;
    try {
      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      localAudioCtx = new AudioCtx();
      const src = localAudioCtx.createMediaElementSource(localAudio);
      localBassFilter = localAudioCtx.createBiquadFilter();
      localBassFilter.type = 'lowshelf';
      localBassFilter.frequency.value = 110;

      const freqs = [60, 230, 910, 3600, 14000];
      localFilters = freqs.map((f) => {
        const flt = localAudioCtx.createBiquadFilter();
        flt.type = 'peaking';
        flt.frequency.value = f;
        flt.Q.value = 1.1;
        return flt;
      });

      src.connect(localBassFilter);
      let prev = localBassFilter;
      localFilters.forEach((flt) => { prev.connect(flt); prev = flt; });
      prev.connect(localAudioCtx.destination);
      applyLocalEq();
    } catch (_) {}
  }

  function applyLocalEq() {
    if (!localAudioCtx) return;
    const on = eqState.enabled;
    localBassFilter.gain.value = on ? (Number(eqState.bassBoost) || 0) : 0;
    localFilters.forEach((flt, idx) => {
      flt.gain.value = on ? (Number(eqState.gains[idx]) || 0) : 0;
    });
  }

  function formatTime(sec) {
    if (!isFinite(sec) || sec < 0) return '0:00';
    const m = Math.floor(sec / 60);
    const s = Math.floor(sec % 60);
    return `${m}:${s < 10 ? '0' : ''}${s}`;
  }

  function saveFavorites() {
    localStorage.setItem('mochy_favorites', JSON.stringify(favorites));
    document.getElementById('fav-count').textContent = favorites.length;
    renderFavorites();
    updateFavButtonState();
  }

  function savePlaylists() {
    localStorage.setItem('mochy_playlists', JSON.stringify(playlists));
    renderPlaylists();
  }

  function isFav(track) {
    return track && favorites.some((t) => t.id === track.id);
  }

  function updateFavButtonState() {
    const btn = document.getElementById('btn-fav-current');
    if (currentTrack && isFav(currentTrack)) {
      btn.textContent = '❤️';
      btn.classList.add('active');
    } else {
      btn.textContent = '♡';
      btn.classList.remove('active');
    }
  }

  function switchTab(tabName) {
    document.querySelectorAll('.nav-item').forEach((b) => {
      b.classList.toggle('active', b.dataset.tab === tabName);
    });
    document.querySelectorAll('.tab-pane').forEach((p) => {
      p.classList.toggle('active', p.id === `tab-${tabName}`);
    });
  }

  function renderTracksGrid(containerId, list) {
    const el = document.getElementById(containerId);
    if (!list || !list.length) {
      el.innerHTML = '<div class="muted-text">Đang tải hoặc không có bài hát nào...</div>';
      return;
    }
    el.innerHTML = '';
    list.forEach((track, idx) => {
      const card = document.createElement('div');
      card.className = 'track-card';
      const thumb = track.thumbnail || 'https://i.ytimg.com/vi/jfKfPfyJRdk/hqdefault.jpg';
      card.innerHTML = `
        <img src="${thumb}" alt="" loading="lazy" />
        <div class="track-card-title">${track.title}</div>
        <div class="track-card-sub">
          <span>${track.artist || 'Nghệ sĩ'}</span>
          <span>${track.durationText || ''}</span>
        </div>
      `;
      card.addEventListener('click', () => playFromList(list, idx));
      el.appendChild(card);
    });
  }

  function renderTracksList(containerId, list) {
    const el = document.getElementById(containerId);
    if (!list || !list.length) {
      el.innerHTML = '<div class="muted-text">Chưa có bài hát nào trong mục này.</div>';
      return;
    }
    el.innerHTML = '';
    list.forEach((track, idx) => {
      const row = document.createElement('div');
      row.className = 'track-row';
      const thumb = track.thumbnail || 'https://i.ytimg.com/vi/jfKfPfyJRdk/hqdefault.jpg';
      row.innerHTML = `
        <img src="${thumb}" alt="" loading="lazy" />
        <div class="track-row-info">
          <div class="track-row-title">${track.title}</div>
          <div class="track-row-artist">${track.artist || 'Nghệ sĩ'}</div>
        </div>
        <span class="time-label">${track.durationText || ''}</span>
      `;
      row.addEventListener('click', () => playFromList(list, idx));
      el.appendChild(row);
    });
  }

  function renderFavorites() {
    renderTracksList('favorites-list', favorites);
  }

  function renderPlaylists() {
    const box = document.getElementById('playlists-container');
    if (!playlists.length) {
      box.innerHTML = '<div class="muted-text">Bạn chưa tạo danh sách phát nào. Hãy nhập tên ở trên rồi nhấn "+ Tạo mới" (bài hát đang phát sẽ được thêm dễ dàng).</div>';
      return;
    }
    box.innerHTML = '';
    playlists.forEach((pl, pIdx) => {
      const card = document.createElement('div');
      card.className = 'card-panel';
      card.style.marginBottom = '12px';
      card.innerHTML = `
        <div class="section-header">
          <h3>💿 ${pl.name} (${pl.tracks.length} bài)</h3>
          <div class="row-actions">
            <button class="btn-primary-sm btn-add-current">+ Thêm bài đang phát</button>
            <button class="btn-secondary-sm btn-play-pl">▶ Phát</button>
            <button class="btn-secondary-sm btn-del-pl">🗑 Xóa</button>
          </div>
        </div>
      `;
      card.querySelector('.btn-add-current').addEventListener('click', () => {
        if (!currentTrack) return;
        pl.tracks.push(currentTrack);
        savePlaylists();
      });
      card.querySelector('.btn-play-pl').addEventListener('click', () => {
        if (pl.tracks.length) playFromList(pl.tracks, 0);
      });
      card.querySelector('.btn-del-pl').addEventListener('click', () => {
        playlists.splice(pIdx, 1);
        savePlaylists();
      });
      box.appendChild(card);
    });
  }

  async function playFromList(list, index) {
    queue = list.slice();
    currentIndex = index;
    await loadAndPlayCurrent();
  }

  async function loadAndPlayCurrent() {
    if (currentIndex < 0 || currentIndex >= queue.length) return;
    currentTrack = queue[currentIndex];
    isPlaying = true;

    const thumb = currentTrack.thumbnail || 'https://i.ytimg.com/vi/jfKfPfyJRdk/hqdefault.jpg';
    document.getElementById('player-art').src = thumb;
    document.getElementById('player-title').textContent = currentTrack.title;
    document.getElementById('player-artist').textContent = currentTrack.artist || 'Mochy Music';
    document.getElementById('bedside-art').src = thumb;
    document.getElementById('bedside-title').textContent = currentTrack.title;
    document.getElementById('bedside-artist').textContent = currentTrack.artist || 'Mochy Music';
    document.getElementById('btn-play-pause').textContent = '⏸';
    updateFavButtonState();

    // Load Lyrics from LRCLIB
    fetchLyricsForTrack(currentTrack);

    if (currentTrack.source === 'local') {
      await api.playerControl('stop');
      initLocalAudioEq();
      localAudio.src = currentTrack.fileUrl;
      localAudio.volume = Number(document.getElementById('volume-slider').value) || 0.85;
      localAudio.play().catch(() => {});
    } else {
      localAudio.pause();
      localAudio.src = '';
      await api.playYouTube(currentTrack.videoId);
      await api.playerControl('volume', Number(document.getElementById('volume-slider').value) || 0.85);
    }
  }

  async function fetchLyricsForTrack(track) {
    document.getElementById('lyrics-song-title').textContent = track.title;
    document.getElementById('lyrics-song-artist').textContent = track.artist || '';
    const container = document.getElementById('lyrics-container');
    container.innerHTML = '<div class="muted-text">⏳ Đang tìm lời bài hát đồng bộ trên LRCLIB...</div>';
    syncedLyrics = [];

    const res = await api.getLyrics(track);
    if (res && res.synced && res.synced.length > 0) {
      syncedLyrics = res.synced;
      container.innerHTML = '';
      syncedLyrics.forEach((line, idx) => {
        const div = document.createElement('div');
        div.className = 'lyric-line';
        div.id = `lyric-line-${idx}`;
        div.textContent = line.text;
        div.addEventListener('click', () => seekTo(line.time));
        container.appendChild(div);
      });
    } else if (res && res.plain) {
      container.innerHTML = '';
      res.plain.split('\n').forEach((txt) => {
        const div = document.createElement('div');
        div.className = 'lyric-line';
        div.textContent = txt;
        container.appendChild(div);
      });
    } else {
      container.innerHTML = '<div class="muted-text">🎵 Chưa tìm thấy lời bài hát cho bản nhạc này trên LRCLIB.</div>';
    }
  }

  function updateSyncedLyricsHighlight(currentTime) {
    if (!syncedLyrics.length) return;
    let activeIdx = -1;
    for (let i = 0; i < syncedLyrics.length; i++) {
      if (currentTime >= syncedLyrics[i].time) activeIdx = i;
      else break;
    }
    if (activeIdx >= 0) {
      document.querySelectorAll('.lyric-line.active').forEach((el) => el.classList.remove('active'));
      const activeEl = document.getElementById(`lyric-line-${activeIdx}`);
      if (activeEl) {
        activeEl.classList.add('active');
        activeEl.scrollIntoView({ behavior: 'smooth', block: 'center' });
      }
      document.getElementById('bedside-lyric-line').textContent = `♪ ${syncedLyrics[activeIdx].text} ♪`;
    }
  }

  function updateProgressUI(currentTime, duration) {
    document.getElementById('time-current').textContent = formatTime(currentTime);
    document.getElementById('time-total').textContent = formatTime(duration);
    if (!isSeeking && duration > 0) {
      document.getElementById('seek-slider').value = ((currentTime / duration) * 100).toFixed(1);
    }
    updateSyncedLyricsHighlight(currentTime);
  }

  async function togglePlayPause() {
    if (!currentTrack) {
      if (homeTracks.length) await playFromList(homeTracks, 0);
      return;
    }
    if (currentTrack.source === 'local') {
      if (localAudio.paused) { localAudio.play(); isPlaying = true; }
      else { localAudio.pause(); isPlaying = false; }
    } else {
      if (isPlaying) { await api.playerControl('pause'); isPlaying = false; }
      else { await api.playerControl('play'); isPlaying = true; }
    }
    document.getElementById('btn-play-pause').textContent = isPlaying ? '⏸' : '▶';
  }

  async function playNext() {
    if (!queue.length) return;
    if (repeatMode === 2) {
      await loadAndPlayCurrent();
      return;
    }
    if (isShuffle) {
      currentIndex = Math.floor(Math.random() * queue.length);
    } else if (currentIndex + 1 < queue.length) {
      currentIndex++;
    } else if (repeatMode === 1) {
      currentIndex = 0;
    } else {
      return;
    }
    await loadAndPlayCurrent();
  }

  async function playPrev() {
    if (!queue.length) return;
    currentIndex = currentIndex > 0 ? currentIndex - 1 : queue.length - 1;
    await loadAndPlayCurrent();
  }

  async function seekTo(seconds) {
    if (!currentTrack) return;
    if (currentTrack.source === 'local') {
      localAudio.currentTime = seconds;
    } else {
      await api.playerControl('seek', seconds);
    }
  }

  // Local Audio Events
  localAudio.addEventListener('timeupdate', () => {
    if (currentTrack && currentTrack.source === 'local') {
      updateProgressUI(localAudio.currentTime, localAudio.duration || 0);
    }
  });
  localAudio.addEventListener('ended', () => {
    if (currentTrack && currentTrack.source === 'local') playNext();
  });

  // Remote YouTube Audio State Events
  let lastDuration = 0;
  let endedLock = false;
  api.onPlayerState((st) => {
    if (!currentTrack || currentTrack.source === 'local') return;
    lastDuration = st.duration || 0;
    isPlaying = !st.paused;
    document.getElementById('btn-play-pause').textContent = isPlaying ? '⏸' : '▶';
    updateProgressUI(st.currentTime, st.duration);
    if (st.ended && st.duration > 5 && !endedLock) {
      endedLock = true;
      setTimeout(() => { endedLock = false; }, 2000);
      playNext();
    }
  });

  api.onMediaKey((key) => {
    if (key === 'toggle') togglePlayPause();
    else if (key === 'next') playNext();
    else if (key === 'prev') playPrev();
  });

  // OTA Progress listener
  api.onOtaProgress((p) => {
    const wrap = document.getElementById('ota-progress-wrap');
    wrap.classList.remove('hidden');
    document.getElementById('ota-progress-fill').style.width = `${p.percent}%`;
    document.getElementById('ota-progress-label').textContent = `${p.percent}%`;
    const mWrap = document.getElementById('ota-modal-progress-wrap');
    if (mWrap) {
      mWrap.classList.remove('hidden');
      document.getElementById('ota-modal-progress-fill').style.width = `${p.percent}%`;
      document.getElementById('ota-modal-progress-label').textContent = `${p.percent}%`;
    }
  });

  function showOtaPopupModal(res) {
    const modal = document.getElementById('ota-popup-modal');
    if (!modal) return;
    document.getElementById('ota-modal-title').textContent = res.title || `Mochy Music ${res.latestVersion}`;
    document.getElementById('ota-modal-sub').textContent = `Phiên bản mới ${res.latestVersion} đã sẵn sàng để cập nhật tự động (OTA)`;
    const cleanNotes = (res.body || 'Bản cập nhật cải thiện hiệu năng, đồng bộ giao diện & Việt hóa 100%.')
      .replace(/^#{1,6}\s*/gm, '• ')
      .replace(/\*\*(.*?)\*\*/g, '$1')
      .replace(/`(.*?)`/g, '$1')
      .trim();
    document.getElementById('ota-modal-notes').textContent = cleanNotes;
    document.getElementById('ota-modal-file').textContent = res.assetName
      ? `📦 Tệp cài đặt: ${res.assetName}`
      : '📦 Sẵn sàng tải về từ GitHub Releases';
    modal.classList.remove('hidden');
  }

  async function checkOtaUpdate(silent = false) {
    const stateText = document.getElementById('ota-state-text');
    stateText.textContent = '⏳ Đang kiểm tra bản phát hành mới nhất trên GitHub...';
    const res = await api.checkUpdate();
    if (res.status === 'available') {
      latestOtaRelease = res;
      stateText.innerHTML = `<b style="color:#2ee59d">🎉 Đã có bản mới: ${res.title} (${res.latestVersion})!</b>`;
      document.getElementById('btn-install-ota').classList.remove('hidden');
      const clBox = document.getElementById('ota-changelog-box');
      clBox.textContent = res.body || 'Bản cập nhật cải thiện hiệu năng & Việt hóa.';
      clBox.classList.remove('hidden');
      document.getElementById('btn-ota-pill').textContent = `⚡ Có bản mới ${res.latestVersion}`;
      showOtaPopupModal(res);
      if (!silent) switchTab('ota');
    } else if (res.status === 'upToDate') {
      stateText.textContent = '✅ Bạn đang sử dụng phiên bản mới nhất!';
      document.getElementById('btn-install-ota').classList.add('hidden');
    } else {
      stateText.textContent = `⚠️ Không thể kiểm tra lúc này: ${res.message || ''}`;
    }
  }

  async function loadTrendingCategory(cat) {
    document.getElementById('home-tracks-grid').innerHTML = '<div class="muted-text">⏳ Đang tải danh sách nhạc thịnh hành từ YouTube Music...</div>';
    homeTracks = await api.getTrendingMusic(cat);
    renderTracksGrid('home-tracks-grid', homeTracks);
  }

  async function performSearch() {
    const q = document.getElementById('global-search-input').value.trim();
    if (!q) return;
    switchTab('search');
    document.getElementById('search-title').textContent = `🔍 Kết quả cho "${q}"`;
    document.getElementById('search-results-list').innerHTML = '<div class="muted-text">⏳ Đang tìm kiếm trên YouTube Music...</div>';
    searchTracks = await api.searchMusic(q);
    renderTracksList('search-results-list', searchTracks);
  }

  // Wire up UI Events
  document.querySelectorAll('.nav-item').forEach((btn) => {
    btn.addEventListener('click', () => switchTab(btn.dataset.tab));
  });

  document.querySelectorAll('.category-chips .chip').forEach((chip) => {
    chip.addEventListener('click', () => {
      document.querySelectorAll('.category-chips .chip').forEach((c) => c.classList.remove('active'));
      chip.classList.add('active');
      loadTrendingCategory(chip.dataset.cat);
    });
  });

  document.getElementById('global-search-btn').addEventListener('click', performSearch);
  document.getElementById('global-search-input').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') performSearch();
  });

  document.getElementById('btn-play-all-home').addEventListener('click', () => {
    if (homeTracks.length) playFromList(homeTracks, 0);
  });
  document.getElementById('btn-play-all-search').addEventListener('click', () => {
    if (searchTracks.length) playFromList(searchTracks, 0);
  });
  document.getElementById('btn-play-all-fav').addEventListener('click', () => {
    if (favorites.length) playFromList(favorites, 0);
  });

  document.getElementById('btn-fav-current').addEventListener('click', () => {
    if (!currentTrack) return;
    const idx = favorites.findIndex((t) => t.id === currentTrack.id);
    if (idx >= 0) favorites.splice(idx, 1);
    else favorites.unshift(currentTrack);
    saveFavorites();
  });

  document.getElementById('btn-create-playlist').addEventListener('click', () => {
    const inp = document.getElementById('new-playlist-name');
    const name = inp.value.trim();
    if (!name) return;
    playlists.push({ name, tracks: currentTrack ? [currentTrack] : [] });
    inp.value = '';
    savePlaylists();
  });

  document.getElementById('btn-pick-local').addEventListener('click', async () => {
    const picked = await api.pickLocalFiles();
    if (picked && picked.length) {
      localTracks = localTracks.concat(picked);
      renderTracksList('local-tracks-list', localTracks);
      playFromList(localTracks, 0);
    }
  });

  document.getElementById('btn-play-pause').addEventListener('click', togglePlayPause);
  document.getElementById('btn-next').addEventListener('click', playNext);
  document.getElementById('btn-prev').addEventListener('click', playPrev);
  document.getElementById('btn-shuffle').addEventListener('click', (e) => {
    isShuffle = !isShuffle;
    e.currentTarget.classList.toggle('active', isShuffle);
  });
  document.getElementById('btn-repeat').addEventListener('click', (e) => {
    repeatMode = (repeatMode + 1) % 3;
    e.currentTarget.textContent = repeatMode === 2 ? '🔂' : '🔁';
    e.currentTarget.classList.toggle('active', repeatMode > 0);
  });

  const seekSlider = document.getElementById('seek-slider');
  seekSlider.addEventListener('mousedown', () => { isSeeking = true; });
  seekSlider.addEventListener('change', async () => {
    const pct = Number(seekSlider.value) / 100;
    const dur = (currentTrack && currentTrack.source === 'local') ? (localAudio.duration || 0) : lastDuration;
    if (dur > 0) await seekTo(pct * dur);
    isSeeking = false;
  });

  document.getElementById('volume-slider').addEventListener('input', (e) => {
    const v = Number(e.target.value);
    localAudio.volume = v;
    api.playerControl('volume', v);
  });

  document.getElementById('btn-quick-lyrics').addEventListener('click', () => switchTab('lyrics'));
  document.getElementById('btn-quick-eq').addEventListener('click', () => switchTab('equalizer'));
  document.getElementById('btn-open-ytm-web').addEventListener('click', () => api.openYoutubeMusicWeb());
  document.getElementById('btn-ota-pill').addEventListener('click', () => {
    switchTab('ota');
    checkOtaUpdate(false);
  });

  // Equalizer Controls
  function syncEq() {
    applyLocalEq();
    api.setEqualizer(eqState);
  }
  document.getElementById('eq-enable-toggle').addEventListener('change', (e) => {
    eqState.enabled = e.target.checked;
    syncEq();
  });
  document.querySelectorAll('.eq-slider').forEach((sl) => {
    sl.addEventListener('input', () => {
      const idx = Number(sl.dataset.band);
      eqState.gains[idx] = Number(sl.value);
      document.getElementById(`eq-val-${idx}`).textContent = `${sl.value} dB`;
      syncEq();
    });
  });
  document.getElementById('eq-bass-slider').addEventListener('input', (e) => {
    eqState.bassBoost = Number(e.target.value);
    document.getElementById('eq-val-bass').textContent = `${e.target.value} dB`;
    syncEq();
  });
  document.querySelectorAll('.eq-preset').forEach((btn) => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.eq-preset').forEach((b) => b.classList.remove('active'));
      btn.classList.add('active');
      const vals = btn.dataset.preset.split(',').map(Number);
      for (let i = 0; i < 5; i++) {
        eqState.gains[i] = vals[i] || 0;
        const sl = document.querySelector(`.eq-slider[data-band="${i}"]`);
        if (sl) sl.value = eqState.gains[i];
        document.getElementById(`eq-val-${i}`).textContent = `${eqState.gains[i]} dB`;
      }
      eqState.bassBoost = vals[5] || 0;
      document.getElementById('eq-bass-slider').value = eqState.bassBoost;
      document.getElementById('eq-val-bass').textContent = `${eqState.bassBoost} dB`;
      syncEq();
    });
  });

  // OTA Buttons & Floating Modal Buttons
  document.getElementById('btn-check-ota').addEventListener('click', () => checkOtaUpdate(false));
  document.getElementById('btn-install-ota').addEventListener('click', async () => {
    if (!latestOtaRelease) return;
    document.getElementById('ota-state-text').textContent = '⬇️ Đang tải bản cập nhật về máy để cài đè...';
    await api.downloadAndInstallUpdate(latestOtaRelease);
  });
  const btnModalLater = document.getElementById('btn-modal-later');
  if (btnModalLater) {
    btnModalLater.addEventListener('click', () => {
      document.getElementById('ota-popup-modal').classList.add('hidden');
    });
  }
  const btnModalUpdateNow = document.getElementById('btn-modal-update-now');
  if (btnModalUpdateNow) {
    btnModalUpdateNow.addEventListener('click', async () => {
      if (!latestOtaRelease) return;
      btnModalUpdateNow.textContent = '⏳ Đang tải bản cập nhật...';
      btnModalUpdateNow.disabled = true;
      await api.downloadAndInstallUpdate(latestOtaRelease);
    });
  }
  document.getElementById('btn-open-releases').addEventListener('click', () => {
    api.openExternal('https://github.com/buiduchuy2010qn-prog/Mochy-Music/releases');
  });

  document.querySelectorAll('.link-btn').forEach((btn) => {
    btn.addEventListener('click', () => api.openExternal(btn.dataset.url));
  });

  // Window controls
  document.getElementById('win-min').addEventListener('click', () => api.windowControl('minimize'));
  document.getElementById('win-max').addEventListener('click', () => api.windowControl('maximize'));
  document.getElementById('win-close').addEventListener('click', () => api.windowControl('close'));

  // Bedside clock mode
  function updateBedsideClock() {
    const now = new Date();
    const hh = String(now.getHours()).padStart(2, '0');
    const mm = String(now.getMinutes()).padStart(2, '0');
    document.getElementById('bedside-clock').textContent = `${hh}:${mm}`;
    const days = ['Chủ Nhật', 'Thứ Hai', 'Thứ Ba', 'Thứ Tư', 'Thứ Năm', 'Thứ Sáu', 'Thứ Bảy'];
    document.getElementById('bedside-date').textContent =
      `${days[now.getDay()]}, ${String(now.getDate()).padStart(2, '0')}/${String(now.getMonth() + 1).padStart(2, '0')}/${now.getFullYear()}`;
  }
  setInterval(updateBedsideClock, 1000);
  updateBedsideClock();

  document.getElementById('btn-bedside-mode').addEventListener('click', () => {
    document.getElementById('bedside-overlay').classList.remove('hidden');
  });
  document.getElementById('btn-close-bedside').addEventListener('click', () => {
    document.getElementById('bedside-overlay').classList.add('hidden');
  });

  // Initialize App Info, Favorites, Playlists, Trending & OTA check
  (async function init() {
    const info = await api.getAppInfo();
    if (info) {
      document.getElementById('header-version-badge').textContent = `v${info.version} • Tiếng Việt`;
      document.getElementById('ota-current-version').textContent = `${info.version} (build#${info.buildNumber})`;
    }
    saveFavorites();
    renderPlaylists();
    await loadTrendingCategory('vpop');
    setTimeout(() => checkOtaUpdate(true), 2500);
  })();
})();