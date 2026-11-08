(function () {
  const api = window.mochyAPI;
  const localAudio = document.getElementById('local-audio-player');

  // ==================== PERSISTENT STATE ====================
  const storage = {
    get(key, fallback) {
      try {
        const raw = localStorage.getItem(key);
        return raw ? JSON.parse(raw) : fallback;
      } catch (_) {
        return fallback;
      }
    },
    set(key, val) {
      try {
        localStorage.setItem(key, JSON.stringify(val));
      } catch (_) {}
    }
  };

  const LOGO_MOI_ICONS = [
    { id: 'default', name: 'Mochy Mặc định', file: 'mochy_avatar_default.png' },
    { id: 'happy', name: 'Mochy Vui vẻ', file: 'mochy_avatar_happy.png' },
    { id: 'music', name: 'Mochy Nghe nhạc', file: 'mochy_avatar_music.png' },
    { id: 'sleep', name: 'Mochy Ngủ gật', file: 'mochy_avatar_sleep.png' },
    { id: 'play_dark', name: 'Nút Play Tối', file: 'mochy_play_dark.png' },
    { id: 'play_gradient', name: 'Nút Play Chuyển sắc', file: 'mochy_play_gradient.png' },
    { id: 'play_glass', name: 'Nút Play Kính mờ', file: 'mochy_play_glass.png' },
    { id: 'play_galaxy', name: 'Nút Play Ngân hà', file: 'mochy_play_galaxy.png' }
  ];

  const PROFILE_AVATARS = [
    'assets/icons/ic_launcher_anime.webp',
    'assets/icons/mochy_avatar_default.png',
    'assets/icons/mochy_avatar_happy.png',
    'assets/icons/mochy_avatar_music.png',
    'assets/icons/mochy_avatar_sleep.png',
    'assets/icons/dev_keycii.jpg',
    'assets/icons/mochy_discord.png'
  ];

  const MOOD_CATEGORIES = [
    { title: 'Nhạc Trẻ Thịnh Hành', query: 'Nhạc Trẻ Việt Nam Thịnh Hành Official MV', bg: 'linear-gradient(135deg, #be123c 0%, #4c0519 100%)', c1: 'https://i.ytimg.com/vi/abPmZCZZrFA/hqdefault.jpg', c2: 'https://i.ytimg.com/vi/FN7ALfpGxiI/hqdefault.jpg' },
    { title: 'Lofi Chill Tiếng Việt', query: 'Nhạc Lofi Chill Tiếng Việt Nghe Nhẹ Nhàng', bg: 'linear-gradient(135deg, #4338ca 0%, #1e1b4b 100%)', c1: 'https://i.ytimg.com/vi/jfKfPfyJRdk/hqdefault.jpg', c2: 'https://i.ytimg.com/vi/5qap5aO4i9A/hqdefault.jpg' },
    { title: 'Hip-Hop & Rap Việt', query: 'Rap Việt Mới Nhất Thịnh Hành Official', bg: 'linear-gradient(135deg, #b45309 0%, #451a03 100%)', c1: 'https://i.ytimg.com/vi/ddaEtFOsFeM/hqdefault.jpg', c2: 'https://i.ytimg.com/vi/2Vv-BfVoq4g/hqdefault.jpg' },
    { title: 'Remix Hot TikTok', query: 'Nhạc Remix Việt Nam Hot TikTok', bg: 'linear-gradient(135deg, #7e22ce 0%, #2e1065 100%)', c1: 'https://i.ytimg.com/vi/kJQP7kiw5Fk/hqdefault.jpg', c2: 'https://i.ytimg.com/vi/RgKAFK5djSk/hqdefault.jpg' },
    { title: 'Trữ Tình & Bolero', query: 'Nhạc Trữ Tình Bolero Tuyển Chọn Hay Nhất', bg: 'linear-gradient(135deg, #0f766e 0%, #042f2e 100%)', c1: 'https://i.ytimg.com/vi/JGwWNGJdvx8/hqdefault.jpg', c2: 'https://i.ytimg.com/vi/09R8_2nJtjg/hqdefault.jpg' },
    { title: 'Indie Việt Tâm Trạng', query: 'Nhạc Indie Việt Nam Hay Nhất Chill', bg: 'linear-gradient(135deg, #1d4ed8 0%, #172554 100%)', c1: 'https://i.ytimg.com/vi/abPmZCZZrFA/hqdefault.jpg', c2: 'https://i.ytimg.com/vi/ddaEtFOsFeM/hqdefault.jpg' },
    { title: 'US-UK Top Hits', query: 'Top Hits Pop Songs Official Audio', bg: 'linear-gradient(135deg, #a21caf 0%, #4a044e 100%)', c1: 'https://i.ytimg.com/vi/kJQP7kiw5Fk/hqdefault.jpg', c2: 'https://i.ytimg.com/vi/JGwWNGJdvx8/hqdefault.jpg' },
    { title: 'K-Pop Thịnh Hành', query: 'Kpop Top Hits Official MV', bg: 'linear-gradient(135deg, #0369a1 0%, #082f49 100%)', c1: 'https://i.ytimg.com/vi/FN7ALfpGxiI/hqdefault.jpg', c2: 'https://i.ytimg.com/vi/09R8_2nJtjg/hqdefault.jpg' }
  ];

  const state = {
    appInfo: { version: '8.8.0', buildNumber: '1243', otaRepoUrl: 'https://github.com/buiduchuy2010qn-prog/Mochy-Music' },
    activeTab: 'home',
    queue: [],
    currentIndex: -1,
    currentTrack: null,
    menuTargetTrack: null,
    isPlaying: false,
    shuffle: false,
    repeat: false,
    playbackSpeed: 1.0,
    sleepTimerMinutes: 0,
    sleepTimerTimeout: null,
    currentTime: 0,
    duration: 0,
    lyrics: { synced: [], plain: '' },
    likedTracks: storage.get('mochy_liked_v2', []),
    libraryTracks: storage.get('mochy_library_v2', []),
    localTracks: storage.get('mochy_local_v2', []),
    recentTracks: storage.get('mochy_recent_v2', []),
    playlists: storage.get('mochy_playlists_v2', []),
    listeningSeconds: storage.get('mochy_listen_sec_v2', 3960), // default 1h 6m like screenshot
    profile: storage.get('mochy_profile_v2', {
      username: 'duchuy2010',
      userId: '20928256479',
      joined: 'tham gia ngày 8 Th11, 2026',
      avatar: 'assets/icons/ic_launcher_anime.webp',
      appIconId: 'default'
    }),
    blur: storage.get('mochy_blur_v2', {
      enabled: true,
      progressive: true,
      quality: 'high',
      strength: 25,
      componentBlur: true
    }),
    playerCover: storage.get('mochy_player_cover_v2', {
      animatedCanvas: false, // Default OFF: chỉ hiện logo bài hát tĩnh, không tự chạy video
      coverStyle: 'squared'  // 'squared' | 'disc' | 'full'
    }),
    lyricsOffsetSec: 0,
    lyricsTranslated: false,
    translatedLines: [],
    eq: storage.get('mochy_eq_v2', {
      enabled: true,
      gains: [0, 0, 0, 0, 0],
      bassBoost: 0
    }),
    libraryFilter: 'all',
    libraryBentoMode: 'songs',
    latestOtaRelease: null
  };

  // ==================== HELPERS ====================
  function showToast(msg) {
    const t = document.getElementById('mochy-toast');
    if (!t) return;
    t.textContent = msg;
    t.classList.add('show');
    clearTimeout(t.__timer);
    t.__timer = setTimeout(() => t.classList.remove('show'), 2500);
  }

  function formatTime(sec) {
    if (!isFinite(sec) || sec < 0) return '0:00';
    const m = Math.floor(sec / 60);
    const s = Math.floor(sec % 60);
    return `${m}:${s < 10 ? '0' : ''}${s}`;
  }

  function escapeHtml(str) {
    return String(str || '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function isLiked(trackId) {
    return state.likedTracks.some(t => t.id === trackId);
  }

  function addTrackToLibrary(track) {
    if (!track || !track.id) return;
    if (!state.libraryTracks.some(t => t.id === track.id)) {
      state.libraryTracks.unshift(track);
      storage.set('mochy_library_v2', state.libraryTracks);
      renderLibraryAll();
    }
  }

  function addTrackToRecent(track) {
    if (!track || !track.id) return;
    state.recentTracks = [track, ...state.recentTracks.filter(t => t.id !== track.id)].slice(0, 30);
    storage.set('mochy_recent_v2', state.recentTracks);
    if (!state.libraryTracks.some(t => t.id === track.id)) {
      state.libraryTracks.unshift(track);
      storage.set('mochy_library_v2', state.libraryTracks.slice(0, 120));
    }
    renderRecentSection();
    renderLibraryAll();
  }

  // ==================== WINDOW CONTROLS ====================
  document.getElementById('btn-win-min')?.addEventListener('click', () => api.windowControl('minimize'));
  document.getElementById('btn-win-max')?.addEventListener('click', () => api.windowControl('maximize'));
  document.getElementById('btn-win-close')?.addEventListener('click', () => api.windowControl('close'));

  // ==================== 4-TAB NAVIGATION ====================
  const tabTitles = {
    home: 'Trang chủ',
    search: 'Tìm kiếm',
    library: 'Thư viện',
    profile: 'Hồ sơ'
  };

  function switchTab(tabId) {
    state.activeTab = tabId;
    document.querySelectorAll('.tab-screen').forEach(el => {
      el.classList.toggle('active', el.id === `screen-${tabId}`);
    });
    document.querySelectorAll('.nav-pill-item').forEach(btn => {
      btn.classList.toggle('active', btn.dataset.tab === tabId);
    });

    const headerRow = document.getElementById('apk-header-row');
    const headerMark = document.getElementById('apk-header-mark');
    const pageTitle = document.getElementById('apk-page-title');
    const viewport = document.getElementById('mochy-viewport');

    if (tabId === 'profile') {
      headerRow.style.display = 'none';
      viewport.classList.add('profile-mode');
    } else {
      headerRow.style.display = 'flex';
      viewport.classList.remove('profile-mode');
      pageTitle.textContent = tabTitles[tabId] || 'Trang chủ';
      headerMark.style.display = tabId === 'home' ? 'block' : 'none';
    }

    if (tabId === 'library') {
      renderLibraryAll();
    }
  }

  document.querySelectorAll('.nav-pill-item').forEach(btn => {
    btn.addEventListener('click', () => switchTab(btn.dataset.tab));
  });

  document.getElementById('btn-header-avatar')?.addEventListener('click', () => {
    switchTab('profile');
  });

  // ==================== RENDER SONG ROWS & CARDS ====================
  function createQuickPickRow(track, listContext) {
    const row = document.createElement('div');
    row.className = 'quick-pick-row';
    const thumb = track.thumbnail || 'assets/icons/mochy_play_dark.png';
    row.innerHTML = `
      <div class="quick-pick-left">
        <img class="quick-pick-thumb" src="${escapeHtml(thumb)}" alt="" loading="lazy" />
        <div class="quick-pick-meta">
          <div class="quick-pick-title">${escapeHtml(track.title)}</div>
          <div class="quick-pick-artist">${escapeHtml(track.artist || 'Nghệ sĩ')}</div>
        </div>
      </div>
      <button class="row-more-btn" title="Tùy chọn & Chia sẻ bài hát (⋯)">
        <svg viewBox="0 0 24 24"><use href="#svg-more-horiz"></use></svg>
      </button>
    `;
    row.addEventListener('click', (e) => {
      if (e.target.closest('.row-more-btn')) {
        e.stopPropagation();
        openPlayerMenuSheet(track);
        return;
      }
      playTrackFromList(track, listContext);
    });
    return row;
  }

  function createMediaCard(track, listContext) {
    const card = document.createElement('div');
    card.className = 'mochy-media-card';
    const thumb = track.thumbnail || 'assets/icons/mochy_play_dark.png';
    card.innerHTML = `
      <div class="media-card-art-wrap">
        <img src="${escapeHtml(thumb)}" alt="" loading="lazy" />
        <div class="media-card-play-badge">
          <svg viewBox="0 0 24 24"><use href="#svg-play"></use></svg>
        </div>
      </div>
      <div class="media-card-bottom">
        <div style="min-width:0;flex:1;">
          <div class="media-card-title">${escapeHtml(track.title)}</div>
          <div class="media-card-sub">${escapeHtml(track.artist || 'Nghệ sĩ')}</div>
        </div>
        <button class="row-more-btn" title="Tùy chọn & Chia sẻ bài hát (⋯)">
          <svg viewBox="0 0 24 24"><use href="#svg-more-horiz"></use></svg>
        </button>
      </div>
    `;
    card.addEventListener('click', (e) => {
      if (e.target.closest('.row-more-btn')) {
        e.stopPropagation();
        openPlayerMenuSheet(track);
        return;
      }
      playTrackFromList(track, listContext);
    });
    return card;
  }

  function createTrackListRow(track, listContext) {
    const row = document.createElement('div');
    row.className = 'apk-track-row';
    const thumb = track.thumbnail || 'assets/icons/mochy_play_dark.png';
    const liked = isLiked(track.id);
    row.innerHTML = `
      <div class="track-row-left">
        <img class="track-row-thumb" src="${escapeHtml(thumb)}" alt="" loading="lazy" />
        <div class="track-row-meta">
          <div class="track-row-title">${escapeHtml(track.title)}</div>
          <div class="track-row-artist">${escapeHtml(track.artist || 'Nghệ sĩ')}</div>
        </div>
      </div>
      <div class="track-row-right">
        <span class="track-row-dur">${escapeHtml(track.durationText || '')}</span>
        <button class="row-more-btn btn-row-like" title="Yêu thích">
          <svg viewBox="0 0 24 24"><use href="${liked ? '#svg-heart-filled' : '#svg-heart-outline'}"></use></svg>
        </button>
        <button class="row-more-btn btn-row-more" title="Menu & Chia sẻ bài hát (⋯)">
          <svg viewBox="0 0 24 24"><use href="#svg-more-horiz"></use></svg>
        </button>
      </div>
    `;
    row.addEventListener('click', (e) => {
      if (e.target.closest('.btn-row-like')) {
        e.stopPropagation();
        toggleLikeTrack(track);
        renderLibraryAll();
        const useEl = row.querySelector('.btn-row-like use');
        if (useEl) useEl.setAttribute('href', isLiked(track.id) ? '#svg-heart-filled' : '#svg-heart-outline');
        return;
      }
      if (e.target.closest('.btn-row-more')) {
        e.stopPropagation();
        openPlayerMenuSheet(track);
        return;
      }
      playTrackFromList(track, listContext);
    });
    return row;
  }

  // ==================== HOME TAB DATA ====================
  async function loadHomeGenre(genre) {
    const quickBox = document.getElementById('home-quick-picks');
    const trendBox = document.getElementById('home-trending-grid');
    quickBox.innerHTML = '<div class="version-card-sub" style="padding:12px 4px;">Đang tải bài hát...</div>';
    trendBox.innerHTML = '';

    const list = await api.getTrendingMusic(genre);
    if (!Array.isArray(list) || !list.length) {
      quickBox.innerHTML = '<div class="version-card-sub">Không thể tải danh sách nhạc.</div>';
      return;
    }

    // Seed initial library if empty so Library Bento Grid looks great right away
    if (state.libraryTracks.length === 0) {
      state.libraryTracks = list.slice(0, 4);
      storage.set('mochy_library_v2', state.libraryTracks);
      renderLibraryAll();
    }

    quickBox.innerHTML = '';
    list.slice(0, 6).forEach(track => {
      quickBox.appendChild(createQuickPickRow(track, list));
    });

    trendBox.innerHTML = '';
    list.slice(6, 18).forEach(track => {
      trendBox.appendChild(createMediaCard(track, list));
    });

    document.getElementById('btn-play-all-home').onclick = () => {
      if (list.length) playTrackFromList(list[0], list);
    };
  }

  function renderRecentSection() {
    const head = document.getElementById('home-recent-head');
    const grid = document.getElementById('home-recent-grid');
    if (!state.recentTracks.length) {
      head.style.display = 'none';
      grid.innerHTML = '';
      return;
    }
    head.style.display = 'flex';
    grid.innerHTML = '';
    state.recentTracks.slice(0, 6).forEach(t => {
      grid.appendChild(createQuickPickRow(t, state.recentTracks));
    });
  }

  document.querySelectorAll('#home-genre-bar .apk-pill').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('#home-genre-bar .apk-pill').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      loadHomeGenre(btn.dataset.genre);
    });
  });

  // ==================== SEARCH TAB ====================
  function renderMoodGrid() {
    const grid = document.getElementById('mood-bento-grid');
    if (!grid) return;
    grid.innerHTML = '';
    MOOD_CATEGORIES.forEach(m => {
      const tile = document.createElement('div');
      tile.className = 'mood-tile';
      tile.style.background = m.bg;
      tile.innerHTML = `
        <div class="mood-tile-title">${escapeHtml(m.title)}</div>
        <div class="mood-tile-covers">
          <img class="back" src="${escapeHtml(m.c1)}" alt="" loading="lazy" />
          <img class="front" src="${escapeHtml(m.c2)}" alt="" loading="lazy" />
        </div>
      `;
      tile.addEventListener('click', () => {
        const input = document.getElementById('search-input');
        input.value = m.title;
        runSearch(m.query, m.title);
      });
      grid.appendChild(tile);
    });
  }

  async function runSearch(query, displayLabel) {
    const q = (query || '').trim();
    const resSec = document.getElementById('search-results-section');
    const resList = document.getElementById('search-results-list');
    const resTitle = document.getElementById('search-results-title');
    const clearBtn = document.getElementById('btn-search-clear');

    if (!q) {
      resSec.style.display = 'none';
      clearBtn.style.display = 'none';
      return;
    }
    clearBtn.style.display = 'inline-block';
    resSec.style.display = 'block';
    resTitle.textContent = `Đang tìm "${displayLabel || q}"...`;
    resList.innerHTML = '';

    const items = await api.searchMusic(q);
    resTitle.textContent = `Kết quả cho "${displayLabel || q}" (${items.length})`;
    if (!items.length) {
      resList.innerHTML = '<div class="version-card-sub" style="padding:14px 4px;">Không tìm thấy bài hát phù hợp.</div>';
      return;
    }
    items.forEach(t => resList.appendChild(createTrackListRow(t, items)));
  }

  const searchInput = document.getElementById('search-input');
  let searchDebounce = null;
  searchInput?.addEventListener('input', () => {
    clearTimeout(searchDebounce);
    const val = searchInput.value;
    if (!val.trim()) {
      document.getElementById('search-results-section').style.display = 'none';
      document.getElementById('btn-search-clear').style.display = 'none';
      return;
    }
    searchDebounce = setTimeout(() => runSearch(val), 420);
  });
  searchInput?.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      clearTimeout(searchDebounce);
      runSearch(searchInput.value);
    }
  });
  document.getElementById('btn-search-clear')?.addEventListener('click', () => {
    searchInput.value = '';
    document.getElementById('search-results-section').style.display = 'none';
    document.getElementById('btn-search-clear').style.display = 'none';
  });

  // ==================== LIBRARY TAB (1:1 BENTO GRID) ====================
  function renderFanCovers(containerId, tracks) {
    const el = document.getElementById(containerId);
    if (!el) return;
    const covers = tracks.map(t => t.thumbnail).filter(Boolean).slice(0, 2);
    el.innerHTML = covers.map(src => `<img src="${escapeHtml(src)}" alt="" />`).join('');
  }

  function renderLibraryAll() {
    const allSongs = [...state.localTracks, ...state.libraryTracks];
    const uniqueMap = new Map();
    allSongs.forEach(t => { if (t && t.id && !uniqueMap.has(t.id)) uniqueMap.set(t.id, t); });
    const mergedSongs = Array.from(uniqueMap.values());

    const uniqueArtists = [];
    const seenArtist = new Set();
    mergedSongs.forEach(t => {
      const a = (t.artist || 'Nghệ sĩ').trim();
      if (!seenArtist.has(a)) {
        seenArtist.add(a);
        uniqueArtists.push({ name: a, thumb: t.thumbnail || 'assets/icons/ic_launcher_anime.webp' });
      }
    });

    document.getElementById('bento-count-songs').textContent = mergedSongs.length;
    document.getElementById('bento-count-playlists').textContent = state.playlists.length;
    document.getElementById('bento-count-albums').textContent = Math.max(0, Math.floor(mergedSongs.length / 3));
    document.getElementById('bento-count-artists').textContent = uniqueArtists.length;
    document.getElementById('bento-count-recent').textContent = state.recentTracks.length || mergedSongs.length;
    document.getElementById('bento-count-liked').textContent = state.likedTracks.length;

    renderFanCovers('bento-fan-songs', mergedSongs);
    renderFanCovers('bento-fan-recent', state.recentTracks.length ? state.recentTracks : mergedSongs);
    renderFanCovers('bento-fan-liked', state.likedTracks);

    const artistAvatarsEl = document.getElementById('bento-avatars-artists');
    if (artistAvatarsEl) {
      artistAvatarsEl.innerHTML = uniqueArtists
        .slice(0, 2)
        .map(a => `<img src="${escapeHtml(a.thumb)}" alt="${escapeHtml(a.name)}" />`)
        .join('');
    }

    renderLibraryTracksList(mergedSongs);
  }

  function renderLibraryTracksList(mergedSongs) {
    const listEl = document.getElementById('library-tracks-list');
    const headingEl = document.getElementById('library-tracks-heading');
    const query = (document.getElementById('library-search-input')?.value || '').trim().toLowerCase();

    let sourceList = mergedSongs;
    if (state.libraryFilter === 'local') {
      sourceList = state.localTracks;
      headingEl.textContent = `Nhạc trên máy (${sourceList.length})`;
    } else if (state.libraryBentoMode === 'liked') {
      sourceList = state.likedTracks;
      headingEl.textContent = `Bài hát yêu thích (${sourceList.length})`;
    } else if (state.libraryBentoMode === 'recent') {
      sourceList = state.recentTracks.length ? state.recentTracks : mergedSongs;
      headingEl.textContent = `Mới thêm gần đây (${sourceList.length})`;
    } else {
      headingEl.textContent = `Bài hát trong thư viện (${sourceList.length})`;
    }

    if (query) {
      sourceList = sourceList.filter(t =>
        (t.title || '').toLowerCase().includes(query) ||
        (t.artist || '').toLowerCase().includes(query)
      );
    }

    listEl.innerHTML = '';
    if (!sourceList.length) {
      listEl.innerHTML = '<div class="version-card-sub" style="padding:16px 4px;">Chưa có bài hát nào trong mục này. Nhấn "+ Thêm nhạc trong máy" hoặc phát nhạc bất kỳ để tự động lưu vào thư viện.</div>';
      return;
    }
    sourceList.forEach(t => listEl.appendChild(createTrackListRow(t, sourceList)));
  }

  document.querySelectorAll('#library-filter-bar .apk-seg-pill').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('#library-filter-bar .apk-seg-pill').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      state.libraryFilter = btn.dataset.libfilter;
      state.libraryBentoMode = 'songs';
      renderLibraryAll();
    });
  });

  document.querySelectorAll('[data-bento]').forEach(card => {
    card.addEventListener('click', () => {
      document.querySelectorAll('[data-bento]').forEach(c => c.classList.remove('selected'));
      card.classList.add('selected');
      state.libraryBentoMode = card.dataset.bento;
      state.libraryFilter = 'all';
      renderLibraryAll();
      document.getElementById('library-tracks-panel')?.scrollIntoView({ behavior: 'smooth' });
    });
  });

  document.getElementById('library-search-input')?.addEventListener('input', () => renderLibraryAll());

  async function importLocalMusicFiles() {
    const picked = await api.pickLocalFiles();
    if (Array.isArray(picked) && picked.length) {
      picked.forEach(f => {
        if (!state.localTracks.some(x => x.id === f.id)) state.localTracks.unshift(f);
      });
      storage.set('mochy_local_v2', state.localTracks);
      state.libraryFilter = 'local';
      renderLibraryAll();
      showToast(`Đã nhập ${picked.length} bài hát từ máy tính`);
    }
  }

  document.getElementById('btn-library-import-card')?.addEventListener('click', importLocalMusicFiles);
  document.getElementById('btn-pick-local-files')?.addEventListener('click', importLocalMusicFiles);

  document.getElementById('btn-create-playlist')?.addEventListener('click', () => {
    const name = prompt('Nhập tên danh sách phát mới:', `Danh sách phát #${state.playlists.length + 1}`);
    if (name && name.trim()) {
      state.playlists.push({ id: `pl_${Date.now()}`, name: name.trim(), tracks: [] });
      storage.set('mochy_playlists_v2', state.playlists);
      renderLibraryAll();
      showToast(`Đã tạo danh sách phát "${name.trim()}"`);
    }
  });

  // ==================== PROFILE & 3D ID CARD ====================
  function renderProfileUI() {
    const p = state.profile;
    document.getElementById('profile-greeting-text').textContent = `Xin chào, ${p.username}!`;
    document.getElementById('profile-usr-tag').textContent = `PROFILE USR#${p.userId}`;
    document.getElementById('profile-card-username').textContent = p.username;
    document.getElementById('profile-card-joined').textContent = p.joined;
    document.getElementById('profile-strip-text').textContent = `${p.username}'s CARD  •  ${p.username}`;
    document.getElementById('profile-card-avatar').src = p.avatar;
    document.getElementById('header-avatar-img').src = p.avatar;

    const hrs = Math.floor(state.listeningSeconds / 3600);
    const mins = Math.floor((state.listeningSeconds % 3600) / 60);
    document.getElementById('listen-hours').textContent = hrs;
    document.getElementById('listen-mins').textContent = mins;
  }

  // Interactive 3D tilt on Profile ID Card
  const idCard = document.getElementById('profile-id-card');
  idCard?.addEventListener('mousemove', (e) => {
    const rect = idCard.getBoundingClientRect();
    const x = (e.clientX - rect.left) / rect.width - 0.5;
    const y = (e.clientY - rect.top) / rect.height - 0.5;
    idCard.style.transform = `rotateY(${x * 14}deg) rotateX(${-y * 14}deg)`;
  });
  idCard?.addEventListener('mouseleave', () => {
    idCard.style.transform = 'rotateY(0deg) rotateX(0deg)';
  });

  // Collapse / Expand ID Card ("vuốt xuống tại đây!!")
  document.getElementById('btn-toggle-id-card')?.addEventListener('click', () => {
    const wrap = document.getElementById('profile-id-card-wrap');
    const chev = document.getElementById('profile-pull-chevron');
    const collapsed = wrap.classList.toggle('collapsed');
    chev.classList.toggle('rotated', !collapsed);
  });

  // Change Username
  document.getElementById('btn-edit-username')?.addEventListener('click', () => {
    const next = prompt('Đổi tên hiển thị trên thẻ ID Mochy:', state.profile.username);
    if (next && next.trim()) {
      state.profile.username = next.trim();
      storage.set('mochy_profile_v2', state.profile);
      renderProfileUI();
      showToast('Đã cập nhật tên hồ sơ');
    }
  });

  // Cycle / Change Avatar
  document.getElementById('btn-change-avatar')?.addEventListener('click', () => {
    const idx = PROFILE_AVATARS.indexOf(state.profile.avatar);
    const nextAvatar = PROFILE_AVATARS[(idx + 1) % PROFILE_AVATARS.length];
    state.profile.avatar = nextAvatar;
    storage.set('mochy_profile_v2', state.profile);
    renderProfileUI();
    showToast('Đã đổi ảnh đại diện Mochy');
  });

  // Profile Action Cards
  document.getElementById('btn-open-about-sheet')?.addEventListener('click', () => {
    document.getElementById('sheet-about-app').style.display = 'flex';
  });
  document.getElementById('btn-open-modder-repo')?.addEventListener('click', () => {
    api.openExternal('https://github.com/buiduchuy2010qn-prog/Mochy-Music');
  });
  document.getElementById('btn-open-contributors')?.addEventListener('click', () => {
    document.getElementById('sheet-about-app').style.display = 'flex';
  });

  // Open Settings & Customisation Sub-Screen
  document.getElementById('btn-open-settings-view')?.addEventListener('click', () => {
    document.getElementById('profile-main-view').style.display = 'none';
    document.getElementById('profile-settings-view').style.display = 'block';
  });
  document.getElementById('btn-back-to-profile')?.addEventListener('click', () => {
    document.getElementById('profile-settings-view').style.display = 'none';
    document.getElementById('profile-main-view').style.display = 'block';
  });

  // Settings Sub-tabs
  document.querySelectorAll('#settings-subtab-bar .apk-seg-pill').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('#settings-subtab-bar .apk-seg-pill').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      const target = btn.dataset.subtab;
      ['customisation', 'audiolab', 'ota'].forEach(id => {
        document.getElementById(`subpane-${id}`).style.display = id === target ? 'block' : 'none';
      });
    });
  });

  // ==================== CUSTOMISATION: "LOGO MỚI" & LIVE BLUR ====================
  function renderLogoMoiGrid() {
    const grid = document.getElementById('logo-moi-grid');
    if (!grid) return;
    grid.innerHTML = '';
    LOGO_MOI_ICONS.forEach(item => {
      const div = document.createElement('div');
      div.className = `logo-moi-item ${state.profile.appIconId === item.id ? 'active' : ''}`;
      div.innerHTML = `
        <img src="assets/icons/${item.file}" alt="${escapeHtml(item.name)}" />
        <span>${escapeHtml(item.name)}</span>
      `;
      div.addEventListener('click', () => {
        state.profile.appIconId = item.id;
        storage.set('mochy_profile_v2', state.profile);
        document.getElementById('current-app-icon-preview').src = `assets/icons/${item.file}`;
        document.getElementById('current-app-icon-title').textContent = item.name;
        api.setAppIcon(item.file);
        renderLogoMoiGrid();
        showToast(`Đã đổi biểu tượng ứng dụng: ${item.name}`);
      });
      grid.appendChild(div);
    });

    const cur = LOGO_MOI_ICONS.find(x => x.id === state.profile.appIconId) || LOGO_MOI_ICONS[0];
    document.getElementById('current-app-icon-preview').src = `assets/icons/${cur.file}`;
    document.getElementById('current-app-icon-title').textContent = cur.name;
  }

  function applyBlurState() {
    const b = state.blur;
    document.body.classList.toggle('blur-enabled', !!b.enabled);
    document.body.classList.toggle('progressive-blur-enabled', !!(b.enabled && b.progressive));
    document.body.classList.toggle('component-blur-enabled', !!(b.enabled && b.componentBlur));

    const effectivePx = b.enabled ? (b.quality === 'high' ? b.strength : Math.round(b.strength * 0.65)) : 0;
    document.documentElement.style.setProperty('--mochy-blur-px', `${effectivePx}px`);

    const masterSw = document.getElementById('switch-blur-master');
    const progSw = document.getElementById('switch-progressive-blur');
    const compSw = document.getElementById('switch-component-blur');
    const slider = document.getElementById('slider-blur-strength');
    const label = document.getElementById('blur-strength-label');
    const orb = document.getElementById('component-blur-orb');

    masterSw?.classList.toggle('active', !!b.enabled);
    progSw?.classList.toggle('active', !!b.progressive);
    compSw?.classList.toggle('active', !!b.componentBlur);
    if (slider) slider.value = b.strength;
    if (label) label.textContent = `${b.strength}px`;
    if (orb) orb.style.filter = b.componentBlur ? `blur(${Math.max(3, Math.round(b.strength / 4))}px)` : 'none';

    document.querySelectorAll('#blur-quality-segmented .quality-seg-btn').forEach(btn => {
      btn.classList.toggle('active', btn.dataset.quality === b.quality);
    });
  }

  document.getElementById('switch-blur-master')?.addEventListener('click', () => {
    state.blur.enabled = !state.blur.enabled;
    storage.set('mochy_blur_v2', state.blur);
    applyBlurState();
  });
  document.getElementById('switch-progressive-blur')?.addEventListener('click', () => {
    state.blur.progressive = !state.blur.progressive;
    storage.set('mochy_blur_v2', state.blur);
    applyBlurState();
  });
  document.getElementById('switch-component-blur')?.addEventListener('click', () => {
    state.blur.componentBlur = !state.blur.componentBlur;
    storage.set('mochy_blur_v2', state.blur);
    applyBlurState();
  });
  document.querySelectorAll('#blur-quality-segmented .quality-seg-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      state.blur.quality = btn.dataset.quality;
      storage.set('mochy_blur_v2', state.blur);
      applyBlurState();
    });
  });
  document.getElementById('slider-blur-strength')?.addEventListener('input', (e) => {
    state.blur.strength = Number(e.target.value) || 25;
    storage.set('mochy_blur_v2', state.blur);
    applyBlurState();
  });
  document.getElementById('btn-reset-blur')?.addEventListener('click', () => {
    state.blur = { enabled: true, progressive: true, quality: 'high', strength: 25, componentBlur: true };
    storage.set('mochy_blur_v2', state.blur);
    applyBlurState();
    showToast('Đã khôi phục cài đặt Làm mờ mặc định');
  });

  // ==================== EQUALIZER ====================
  const EQ_PRESETS = {
    flat: { gains: [0, 0, 0, 0, 0], bass: 0 },
    bass: { gains: [6, 4, 0, 1, 2], bass: 8 },
    pop: { gains: [-1, 3, 5, 3, -1], bass: 3 },
    rock: { gains: [5, 3, -2, 3, 5], bass: 6 },
    vocal: { gains: [-2, 1, 5, 4, 1], bass: 1 },
    lofi: { gains: [4, 3, 1, -2, -4], bass: 5 }
  };

  function syncEqUI() {
    document.getElementById('switch-eq-enabled')?.classList.toggle('active', !!state.eq.enabled);
    document.querySelectorAll('.eq-slider').forEach(sl => {
      const i = Number(sl.dataset.band);
      const val = state.eq.gains[i] || 0;
      sl.value = val;
      const lbl = document.getElementById(`eq-val-${i}`);
      if (lbl) lbl.textContent = `${val > 0 ? '+' : ''}${val} dB`;
    });
    const bassSl = document.getElementById('eq-bass-slider');
    const bassLbl = document.getElementById('eq-val-bass');
    if (bassSl) bassSl.value = state.eq.bassBoost || 0;
    if (bassLbl) bassLbl.textContent = `+${state.eq.bassBoost || 0} dB`;
    api.setEqualizer(state.eq);
  }

  document.getElementById('switch-eq-enabled')?.addEventListener('click', () => {
    state.eq.enabled = !state.eq.enabled;
    storage.set('mochy_eq_v2', state.eq);
    syncEqUI();
  });
  document.querySelectorAll('.eq-slider').forEach(sl => {
    sl.addEventListener('input', () => {
      const i = Number(sl.dataset.band);
      state.eq.gains[i] = Number(sl.value) || 0;
      storage.set('mochy_eq_v2', state.eq);
      syncEqUI();
    });
  });
  document.getElementById('eq-bass-slider')?.addEventListener('input', (e) => {
    state.eq.bassBoost = Number(e.target.value) || 0;
    storage.set('mochy_eq_v2', state.eq);
    syncEqUI();
  });
  document.querySelectorAll('#eq-presets-bar .apk-pill').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('#eq-presets-bar .apk-pill').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      const p = EQ_PRESETS[btn.dataset.preset] || EQ_PRESETS.flat;
      state.eq.gains = [...p.gains];
      state.eq.bassBoost = p.bass;
      storage.set('mochy_eq_v2', state.eq);
      syncEqUI();
    });
  });

  // ==================== PLAYER COVER STYLE & QUICK CANVAS TOGGLE ====================
  const COVER_STYLE_NAMES = {
    squared: 'Logo vuông (Squared)',
    disc: 'Đĩa nhạc xoay (Disc)',
    full: 'Tràn viền (Edge)'
  };

  function applyPlayerCoverSettings() {
    const pc = state.playerCover;
    const artBox = document.getElementById('player-artwork-box');
    const coverImg = document.getElementById('player-full-cover');
    const canvasVideo = document.getElementById('player-canvas-video');

    if (artBox) {
      artBox.classList.remove('cover-style-squared', 'cover-style-disc', 'cover-style-full', 'canvas-breathing', 'playing');
      artBox.classList.add(`cover-style-${pc.coverStyle || 'squared'}`);
      if (state.isPlaying) artBox.classList.add('playing');
      if (pc.animatedCanvas && state.isPlaying && (!canvasVideo || canvasVideo.style.display === 'none')) {
        artBox.classList.add('canvas-breathing');
      }
    }

    if (canvasVideo) {
      if (pc.animatedCanvas && canvasVideo.dataset.hasVideo === '1') {
        canvasVideo.style.display = 'block';
        if (coverImg) coverImg.style.display = 'none';
        if (state.isPlaying) canvasVideo.play().catch(() => {});
        else canvasVideo.pause();
      } else {
        canvasVideo.pause();
        canvasVideo.style.display = 'none';
        if (coverImg) coverImg.style.display = 'block';
      }
    }

    // Sync switches & labels in 3-dot menu, Settings, and Player top bar
    const menuSw = document.getElementById('switch-menu-canvas');
    const setSw = document.getElementById('switch-settings-canvas');
    const menuSub = document.getElementById('menu-canvas-sub');
    const setSub = document.getElementById('settings-canvas-desc');
    const quickBtn = document.getElementById('btn-player-quick-canvas');
    const sourceSub = document.getElementById('player-source-sub');
    const menuStyleLbl = document.getElementById('menu-cover-style-label');

    menuSw?.classList.toggle('active', !!pc.animatedCanvas);
    setSw?.classList.toggle('active', !!pc.animatedCanvas);

    const statusText = pc.animatedCanvas
      ? 'Đang bật • Chạy hiệu ứng ảnh bìa động (Canvas)'
      : 'Đang tắt • Chỉ hiện logo bài hát tĩnh (Không chạy video)';
    if (menuSub) menuSub.textContent = statusText;
    if (setSub) setSub.textContent = statusText;
    if (quickBtn) {
      quickBtn.textContent = pc.animatedCanvas ? 'Ảnh bìa động: BẬT' : 'Chỉ hiện logo tĩnh';
      quickBtn.style.background = pc.animatedCanvas ? '#FFFFFF' : '#4D4D4D';
      quickBtn.style.color = pc.animatedCanvas ? '#000000' : '#FFFFFF';
    }
    if (sourceSub) {
      sourceSub.textContent = pc.animatedCanvas
        ? 'Ảnh bìa động (Canvas) • Không quảng cáo'
        : 'Chỉ hiện logo bài hát tĩnh • Không quảng cáo';
    }
    if (menuStyleLbl) {
      menuStyleLbl.textContent = `Kiểu logo bài hát: ${COVER_STYLE_NAMES[pc.coverStyle] || COVER_STYLE_NAMES.squared}`;
    }

    document.querySelectorAll('#cover-style-segmented .apk-seg-pill').forEach(btn => {
      btn.classList.toggle('active', btn.dataset.coverstyle === (pc.coverStyle || 'squared'));
    });
  }

  async function refreshCanvasForCurrentTrack(track) {
    const canvasVideo = document.getElementById('player-canvas-video');
    if (!canvasVideo) return;
    canvasVideo.pause();
    canvasVideo.removeAttribute('src');
    canvasVideo.dataset.hasVideo = '0';
    canvasVideo.style.display = 'none';
    document.getElementById('player-full-cover').style.display = 'block';

    if (!state.playerCover.animatedCanvas || !track) {
      applyPlayerCoverSettings();
      return;
    }

    try {
      const res = await api.getCanvasArtwork(track);
      if (res && res.videoUrl && state.currentTrack && state.currentTrack.id === track.id && state.playerCover.animatedCanvas) {
        canvasVideo.src = res.videoUrl;
        canvasVideo.dataset.hasVideo = '1';
      }
    } catch (_) {}
    applyPlayerCoverSettings();
  }

  function toggleAnimatedCanvas() {
    state.playerCover.animatedCanvas = !state.playerCover.animatedCanvas;
    storage.set('mochy_player_cover_v2', state.playerCover);
    if (state.playerCover.animatedCanvas && state.currentTrack) {
      refreshCanvasForCurrentTrack(state.currentTrack);
      showToast('Đã bật Ảnh bìa động (Canvas)');
    } else {
      applyPlayerCoverSettings();
      showToast('Đã tắt chạy video • Chỉ hiện logo bài hát tĩnh');
    }
  }

  document.getElementById('menu-act-toggle-canvas')?.addEventListener('click', toggleAnimatedCanvas);
  document.getElementById('switch-settings-canvas')?.addEventListener('click', toggleAnimatedCanvas);
  document.getElementById('btn-player-quick-canvas')?.addEventListener('click', toggleAnimatedCanvas);

  document.getElementById('menu-act-cover-style')?.addEventListener('click', () => {
    const styles = ['squared', 'disc', 'full'];
    const idx = styles.indexOf(state.playerCover.coverStyle || 'squared');
    state.playerCover.coverStyle = styles[(idx + 1) % styles.length];
    storage.set('mochy_player_cover_v2', state.playerCover);
    applyPlayerCoverSettings();
    showToast(`Kiểu logo bài hát: ${COVER_STYLE_NAMES[state.playerCover.coverStyle]}`);
  });

  document.querySelectorAll('#cover-style-segmented .apk-seg-pill').forEach(btn => {
    btn.addEventListener('click', () => {
      state.playerCover.coverStyle = btn.dataset.coverstyle || 'squared';
      storage.set('mochy_player_cover_v2', state.playerCover);
      applyPlayerCoverSettings();
      showToast(`Kiểu logo bài hát: ${COVER_STYLE_NAMES[state.playerCover.coverStyle]}`);
    });
  });

  // ==================== AUDIO PLAYBACK & MULTI-PROVIDER LYRICS ====================
  async function playTrackFromList(track, list) {
    if (!track) return;
    if (Array.isArray(list) && list.length) {
      state.queue = [...list];
      state.currentIndex = state.queue.findIndex(x => x.id === track.id);
    } else {
      state.queue = [track];
      state.currentIndex = 0;
    }
    state.currentTrack = track;
    state.isPlaying = true;
    state.currentTime = 0;
    state.duration = 0;
    state.lyricsOffsetSec = 0;
    state.translatedLines = [];

    updatePlayerUI();
    renderQueueList();
    addTrackToRecent(track);
    loadLyricsForTrack(track);
    refreshCanvasForCurrentTrack(track);

    if (track.source === 'local' && track.fileUrl) {
      await api.playerControl('stop');
      localAudio.src = track.fileUrl;
      localAudio.playbackRate = state.playbackSpeed;
      localAudio.play().catch(() => {});
    } else {
      localAudio.pause();
      localAudio.src = '';
      await api.playYouTube(track.videoId || track.id);
      if (state.playbackSpeed !== 1.0) {
        setTimeout(() => api.playerControl('speed', state.playbackSpeed), 1200);
      }
    }
  }

  function updatePlayerUI() {
    const t = state.currentTrack;
    const cover = t?.thumbnail || 'assets/icons/mochy_play_dark.png';
    const title = t?.title || 'Chưa phát bài hát';
    const artist = t?.artist || 'Chọn bài hát bất kỳ để thưởng thức';

    document.getElementById('mini-cover-img').src = cover;
    document.getElementById('mini-title').textContent = title;
    document.getElementById('mini-artist').textContent = artist;

    const fullCoverEl = document.getElementById('player-full-cover');
    if (fullCoverEl) {
      fullCoverEl.src = cover;
      fullCoverEl.classList.toggle('yt-thumb-crop', cover.includes('ytimg.com'));
    }
    document.getElementById('player-full-title').textContent = title;
    document.getElementById('player-full-artist').textContent = artist;
    document.getElementById('player-sheet-backdrop').style.backgroundImage = `url("${cover}")`;

    const badgeEl = document.getElementById('player-format-badge');
    if (badgeEl) {
      badgeEl.textContent = t?.source === 'local' ? 'LOSSLESS • LOCAL' : 'FLAC • 384,0 KHz';
    }

    const playHref = state.isPlaying ? '#svg-pause' : '#svg-play';
    document.querySelector('#svg-mini-play-icon use')?.setAttribute('href', playHref);
    document.querySelector('#svg-player-play-icon use')?.setAttribute('href', playHref);

    const liked = t ? isLiked(t.id) : false;
    document.querySelector('#svg-player-like-icon use')?.setAttribute('href', liked ? '#svg-heart-filled' : '#svg-heart-outline');
    applyPlayerCoverSettings();
  }

  async function togglePlayPause() {
    if (!state.currentTrack) return;
    if (state.currentTrack.source === 'local') {
      if (localAudio.paused) {
        localAudio.play();
        state.isPlaying = true;
      } else {
        localAudio.pause();
        state.isPlaying = false;
      }
    } else {
      if (state.isPlaying) {
        await api.playerControl('pause');
        state.isPlaying = false;
      } else {
        await api.playerControl('play');
        state.isPlaying = true;
      }
    }
    updatePlayerUI();
  }

  function playNext() {
    if (!state.queue.length) return;
    let nextIdx = state.currentIndex + 1;
    if (state.shuffle) {
      nextIdx = Math.floor(Math.random() * state.queue.length);
    } else if (nextIdx >= state.queue.length) {
      nextIdx = 0;
    }
    playTrackFromList(state.queue[nextIdx], state.queue);
  }

  function playPrev() {
    if (!state.queue.length) return;
    const prevIdx = (state.currentIndex - 1 + state.queue.length) % state.queue.length;
    playTrackFromList(state.queue[prevIdx], state.queue);
  }

  function renderQueueList() {
    const qBox = document.getElementById('queue-scroll-box');
    if (!qBox) return;
    qBox.innerHTML = '';
    if (!state.queue.length) {
      qBox.innerHTML = '<div class="lyrics-placeholder">Danh sách chờ đang trống.</div>';
      return;
    }
    state.queue.forEach((t, idx) => {
      const row = createTrackListRow(t, state.queue);
      if (idx === state.currentIndex) {
        row.style.border = '1px solid rgba(255,255,255,0.28)';
        row.style.background = '#1C1C1E';
      }
      qBox.appendChild(row);
    });
  }

  // Switch between Lyrics & Queue tabs in Player
  document.getElementById('tab-btn-lyrics')?.addEventListener('click', () => {
    document.getElementById('tab-btn-lyrics').classList.add('active');
    document.getElementById('tab-btn-queue').classList.remove('active');
    document.getElementById('lyrics-scroll-box').style.display = 'flex';
    document.getElementById('queue-scroll-box').style.display = 'none';
  });
  document.getElementById('tab-btn-queue')?.addEventListener('click', () => {
    document.getElementById('tab-btn-queue').classList.add('active');
    document.getElementById('tab-btn-lyrics').classList.remove('active');
    document.getElementById('lyrics-scroll-box').style.display = 'none';
    document.getElementById('queue-scroll-box').style.display = 'flex';
    renderQueueList();
  });

  function renderLyricsLines() {
    const box = document.getElementById('lyrics-scroll-box');
    const ticker = document.getElementById('mini-lyrics-ticker-text');
    const badge = document.getElementById('lyrics-status-badge');
    if (badge) badge.textContent = state.lyrics.provider || 'LRCLIB';

    if (state.lyrics.synced && state.lyrics.synced.length) {
      box.innerHTML = '';
      // Add APK Interlude Dots at top if first line starts after 4 seconds
      if (state.lyrics.synced[0].time > 4) {
        const dots = document.createElement('div');
        dots.className = 'lyric-line interlude-line active';
        dots.dataset.idx = '-1';
        dots.innerHTML = '<span class="lyric-interlude-dots"><i></i><i></i><i></i></span>';
        dots.addEventListener('click', () => seekToSeconds(0));
        box.appendChild(dots);
      }

      state.lyrics.synced.forEach((line, idx) => {
        const div = document.createElement('div');
        div.className = 'lyric-line';
        div.dataset.idx = idx;
        const transText = state.lyricsTranslated && state.translatedLines[idx] ? state.translatedLines[idx] : '';
        div.innerHTML = `
          <span>${escapeHtml(line.text)}</span>
          ${transText ? `<span class="lyric-translation">${escapeHtml(transText)}</span>` : ''}
        `;
        div.addEventListener('click', () => {
          seekToSeconds(Math.max(0, line.time - state.lyricsOffsetSec));
        });
        box.appendChild(div);
      });
      if (ticker) ticker.textContent = state.lyrics.synced[0].text;
    } else if (state.lyrics.plain) {
      box.innerHTML = '';
      const plainLines = state.lyrics.plain.split(/\r?\n/).filter(x => x.trim());
      plainLines.forEach((text, idx) => {
        const div = document.createElement('div');
        div.className = 'lyric-line active';
        div.style.fontSize = '17.5px';
        const transText = state.lyricsTranslated && state.translatedLines[idx] ? state.translatedLines[idx] : '';
        div.innerHTML = `
          <span>${escapeHtml(text)}</span>
          ${transText ? `<span class="lyric-translation">${escapeHtml(transText)}</span>` : ''}
        `;
        box.appendChild(div);
      });
      if (ticker) ticker.textContent = plainLines[0] || 'Đang hiển thị lời bài hát văn bản';
    } else {
      box.innerHTML = '<div class="lyrics-placeholder">Chưa tìm thấy lời bài hát cho bài hát này.</div>';
      if (ticker) ticker.textContent = state.currentTrack ? `${state.currentTrack.title} — ${state.currentTrack.artist}` : 'Chưa phát bài hát';
    }
  }

  async function loadLyricsForTrack(track) {
    const box = document.getElementById('lyrics-scroll-box');
    const ticker = document.getElementById('mini-lyrics-ticker-text');
    box.innerHTML = '<div class="lyrics-placeholder">Đang tìm & đồng bộ lời bài hát (SimpMusic • LRCLIB • Apple Music • YouTube)...</div>';
    if (ticker) ticker.textContent = 'Đang đồng bộ lời bài hát...';
    state.lyrics = { synced: [], plain: '', provider: 'LRCLIB' };

    const res = await api.getLyrics(track);
    if (state.currentTrack && state.currentTrack.id !== track.id) return;
    state.lyrics = res || { synced: [], plain: '', provider: 'LRCLIB' };

    if (state.lyricsTranslated) {
      await translateCurrentLyrics();
    } else {
      renderLyricsLines();
    }
  }

  async function translateCurrentLyrics() {
    const rawLines = state.lyrics.synced?.length
      ? state.lyrics.synced.map(x => x.text)
      : (state.lyrics.plain ? state.lyrics.plain.split(/\r?\n/).filter(x => x.trim()) : []);
    if (!rawLines.length) return;
    state.translatedLines = await api.translateLyrics(rawLines);
    renderLyricsLines();
  }

  document.getElementById('btn-lyrics-translate')?.addEventListener('click', async (e) => {
    state.lyricsTranslated = !state.lyricsTranslated;
    e.currentTarget.classList.toggle('active', state.lyricsTranslated);
    if (state.lyricsTranslated) {
      showToast('Đang dịch lời bài hát sang Tiếng Việt...');
      await translateCurrentLyrics();
    } else {
      renderLyricsLines();
      showToast('Đã tắt dịch lời bài hát');
    }
  });

  document.getElementById('btn-lyrics-offset-minus')?.addEventListener('click', () => {
    state.lyricsOffsetSec = Math.round((state.lyricsOffsetSec - 0.5) * 10) / 10;
    showToast(`Độ trễ lời bài hát: ${state.lyricsOffsetSec > 0 ? '+' : ''}${state.lyricsOffsetSec}s`);
    syncActiveLyric(state.currentTime);
  });
  document.getElementById('btn-lyrics-offset-plus')?.addEventListener('click', () => {
    state.lyricsOffsetSec = Math.round((state.lyricsOffsetSec + 0.5) * 10) / 10;
    showToast(`Độ trễ lời bài hát: ${state.lyricsOffsetSec > 0 ? '+' : ''}${state.lyricsOffsetSec}s`);
    syncActiveLyric(state.currentTime);
  });

  function syncActiveLyric(currentTime) {
    const synced = state.lyrics.synced;
    if (!synced || !synced.length) return;
    const effectiveTime = currentTime + state.lyricsOffsetSec;
    let activeIdx = -1;
    for (let i = 0; i < synced.length; i++) {
      if (effectiveTime >= synced[i].time) activeIdx = i;
      else break;
    }

    const ticker = document.getElementById('mini-lyrics-ticker-text');
    if (ticker) {
      if (activeIdx >= 0 && synced[activeIdx]) {
        ticker.textContent = synced[activeIdx].text;
      } else if (synced[0]) {
        ticker.textContent = `♪ ${synced[0].text}`;
      }
    }

    const lines = document.querySelectorAll('#lyrics-scroll-box .lyric-line');
    lines.forEach((el) => {
      const idx = Number(el.dataset.idx);
      const isAct = idx === activeIdx;
      if (isAct && !el.classList.contains('active')) {
        el.classList.add('active');
        el.scrollIntoView({ behavior: 'smooth', block: 'center' });
      } else if (!isAct) {
        el.classList.remove('active');
      }
    });
  }

  function seekToSeconds(sec) {
    if (!state.currentTrack) return;
    if (state.currentTrack.source === 'local') {
      localAudio.currentTime = sec;
    } else {
      api.playerControl('seek', sec);
    }
  }

  // Telemetry from YouTube Hidden Audio Window
  api.onPlayerState((st) => {
    if (!state.currentTrack || state.currentTrack.source === 'local') return;
    state.currentTime = st.currentTime || 0;
    state.duration = st.duration || 0;
    state.isPlaying = !st.paused;
    updatePlayerUI();
    updateProgressBars();
    syncActiveLyric(state.currentTime);
    if (st.ended) {
      if (state.repeat) seekToSeconds(0);
      else playNext();
    }
  });

  // Telemetry from Local Audio Element
  localAudio.addEventListener('timeupdate', () => {
    if (!state.currentTrack || state.currentTrack.source !== 'local') return;
    state.currentTime = localAudio.currentTime || 0;
    state.duration = isFinite(localAudio.duration) ? localAudio.duration : 0;
    updateProgressBars();
    syncActiveLyric(state.currentTime);
  });
  localAudio.addEventListener('ended', () => {
    if (state.repeat) {
      localAudio.currentTime = 0;
      localAudio.play();
    } else {
      playNext();
    }
  });

  function updateProgressBars() {
    const pct = state.duration > 0 ? Math.min(100, (state.currentTime / state.duration) * 100) : 0;
    document.getElementById('mini-progress-fill').style.width = `${pct}%`;
    const apkFill = document.getElementById('apk-progress-pill-fill');
    if (apkFill) apkFill.style.width = `${pct}%`;

    const seekSlider = document.getElementById('player-seek-slider');
    if (seekSlider && !seekSlider.__dragging) {
      seekSlider.value = Math.round(pct * 10);
    }
    document.getElementById('player-time-cur').textContent = formatTime(state.currentTime);
    document.getElementById('player-time-dur').textContent = formatTime(state.duration);
  }

  // Listening Time Odometer Ticker (every 15 seconds while playing)
  setInterval(() => {
    if (state.isPlaying) {
      state.listeningSeconds += 15;
      storage.set('mochy_listen_sec_v2', state.listeningSeconds);
      renderProfileUI();
    }
  }, 15000);

  // Player Sheet Controls
  document.getElementById('mini-player-expand')?.addEventListener('click', () => {
    document.getElementById('mochy-player-sheet').style.display = 'flex';
  });
  document.getElementById('btn-collapse-player')?.addEventListener('click', () => {
    document.getElementById('mochy-player-sheet').style.display = 'none';
  });
  document.getElementById('btn-mini-play')?.addEventListener('click', togglePlayPause);
  document.getElementById('btn-player-play')?.addEventListener('click', togglePlayPause);
  document.getElementById('btn-mini-next')?.addEventListener('click', playNext);
  document.getElementById('btn-player-next')?.addEventListener('click', playNext);
  document.getElementById('btn-player-prev')?.addEventListener('click', playPrev);

  document.getElementById('btn-player-shuffle')?.addEventListener('click', (e) => {
    state.shuffle = !state.shuffle;
    e.currentTarget.classList.toggle('active', state.shuffle);
    showToast(state.shuffle ? 'Đã bật phát ngẫu nhiên' : 'Đã tắt phát ngẫu nhiên');
  });
  document.getElementById('btn-player-repeat')?.addEventListener('click', (e) => {
    state.repeat = !state.repeat;
    e.currentTarget.classList.toggle('active', state.repeat);
    showToast(state.repeat ? 'Đã bật lặp lại bài hát' : 'Đã tắt lặp lại');
  });

  const seekSlider = document.getElementById('player-seek-slider');
  seekSlider?.addEventListener('mousedown', () => { seekSlider.__dragging = true; });
  seekSlider?.addEventListener('mouseup', () => { seekSlider.__dragging = false; });
  seekSlider?.addEventListener('input', () => {
    const pct = Number(seekSlider.value) / 10;
    const apkFill = document.getElementById('apk-progress-pill-fill');
    if (apkFill) apkFill.style.width = `${pct}%`;
  });
  seekSlider?.addEventListener('change', () => {
    seekSlider.__dragging = false;
    if (state.duration > 0) {
      const targetSec = (Number(seekSlider.value) / 1000) * state.duration;
      seekToSeconds(targetSec);
    }
  });

  function toggleLikeTrack(track) {
    const t = track || state.currentTrack;
    if (!t) return;
    const exists = isLiked(t.id);
    if (exists) {
      state.likedTracks = state.likedTracks.filter(x => x.id !== t.id);
      showToast('Đã xóa khỏi bài hát yêu thích');
    } else {
      state.likedTracks.unshift(t);
      showToast('Đã thêm vào bài hát yêu thích');
    }
    storage.set('mochy_liked_v2', state.likedTracks);
    updatePlayerUI();
    renderLibraryAll();
  }

  document.getElementById('btn-player-like')?.addEventListener('click', () => toggleLikeTrack(state.currentTrack));

  // ==================== 3-DOT (⋯) PLAYER MENU SHEET & SHARE SHEET ====================
  function openPlayerMenuSheet(track) {
    const target = track || state.currentTrack;
    if (!target) {
      showToast('Hãy chọn một bài hát trước');
      return;
    }
    state.menuTargetTrack = target;
    document.getElementById('menu-like-label').textContent = isLiked(target.id)
      ? 'Xóa khỏi bài hát yêu thích'
      : 'Thêm vào bài hát yêu thích';
    applyPlayerCoverSettings();
    document.getElementById('sheet-player-menu').style.display = 'flex';
  }

  function openShareSongSheet(track) {
    const target = track || state.menuTargetTrack || state.currentTrack;
    if (!target) {
      showToast('Hãy chọn một bài hát để chia sẻ');
      return;
    }
    state.menuTargetTrack = target;
    document.getElementById('sheet-player-menu').style.display = 'none';
    document.getElementById('share-preview-cover').src = target.thumbnail || 'assets/icons/mochy_play_dark.png';
    document.getElementById('share-preview-title').textContent = target.title || 'Mochy Music';
    document.getElementById('share-preview-artist').textContent = target.artist || 'Nghệ sĩ';
    document.getElementById('share-lyrics-title').textContent = target.title || 'Lời bài hát';

    const snippet = state.lyrics.synced?.length
      ? state.lyrics.synced.slice(0, 4).map(l => l.text).join('\n')
      : (state.lyrics.plain ? state.lyrics.plain.slice(0, 140) : `🎵 ${target.title} — ${target.artist}`);
    document.getElementById('share-lyrics-snippet').textContent = snippet;

    document.getElementById('sheet-share-song').style.display = 'flex';
  }

  document.getElementById('btn-mini-more')?.addEventListener('click', () => openPlayerMenuSheet(state.currentTrack));
  document.getElementById('btn-player-more')?.addEventListener('click', () => openPlayerMenuSheet(state.currentTrack));
  document.getElementById('btn-player-chevron-menu')?.addEventListener('click', () => openPlayerMenuSheet(state.currentTrack));

  // Close overlays when clicking backdrop
  ['sheet-player-menu', 'sheet-share-song', 'sheet-about-app', 'sheet-ota-update'].forEach(id => {
    const el = document.getElementById(id);
    el?.addEventListener('click', (e) => {
      if (e.target === el) el.style.display = 'none';
    });
  });

  // 3-Dot Menu Actions
  document.getElementById('menu-act-radio')?.addEventListener('click', () => {
    const t = state.menuTargetTrack || state.currentTrack;
    document.getElementById('sheet-player-menu').style.display = 'none';
    if (t) {
      playTrackFromList(t, state.queue);
      showToast(`Đang phát Radio theo "${t.title}"`);
    }
  });
  document.getElementById('menu-act-playlist')?.addEventListener('click', () => {
    const t = state.menuTargetTrack || state.currentTrack;
    document.getElementById('sheet-player-menu').style.display = 'none';
    if (t) {
      addTrackToLibrary(t);
      showToast(`Đã thêm "${t.title}" vào danh sách phát`);
    }
  });
  document.getElementById('menu-act-download')?.addEventListener('click', () => {
    const t = state.menuTargetTrack || state.currentTrack;
    document.getElementById('sheet-player-menu').style.display = 'none';
    if (t) {
      addTrackToLibrary(t);
      showToast(`Đã lưu đệm "${t.title}" vào thư viện`);
    }
  });
  document.getElementById('menu-act-video')?.addEventListener('click', () => {
    const t = state.menuTargetTrack || state.currentTrack;
    document.getElementById('sheet-player-menu').style.display = 'none';
    if (t && (t.videoId || t.id)) {
      api.openExternal(`https://music.youtube.com/watch?v=${encodeURIComponent(t.videoId || t.id)}`);
    }
  });
  document.getElementById('menu-volume-slider')?.addEventListener('input', (e) => {
    const vol = (Number(e.target.value) || 90) / 100;
    localAudio.volume = vol;
    api.playerControl('volume', vol);
  });
  document.getElementById('menu-act-sleeptimer')?.addEventListener('click', () => {
    const options = [0, 15, 30, 45, 60];
    const idx = options.indexOf(state.sleepTimerMinutes);
    state.sleepTimerMinutes = options[(idx + 1) % options.length];
    clearTimeout(state.sleepTimerTimeout);
    const lbl = document.getElementById('menu-sleeptimer-label');
    if (state.sleepTimerMinutes > 0) {
      lbl.textContent = `Hẹn giờ tắt nhạc (${state.sleepTimerMinutes} phút)`;
      state.sleepTimerTimeout = setTimeout(() => {
        if (state.isPlaying) togglePlayPause();
        showToast('Đã tự động dừng phát nhạc theo hẹn giờ');
      }, state.sleepTimerMinutes * 60000);
      showToast(`Đã hẹn giờ tắt nhạc sau ${state.sleepTimerMinutes} phút`);
    } else {
      lbl.textContent = 'Hẹn giờ tắt nhạc';
      showToast('Đã tắt hẹn giờ ngủ');
    }
  });
  document.getElementById('menu-act-speed')?.addEventListener('click', () => {
    const speeds = [0.75, 1.0, 1.25, 1.5];
    const idx = speeds.indexOf(state.playbackSpeed);
    state.playbackSpeed = speeds[(idx + 1) % speeds.length];
    localAudio.playbackRate = state.playbackSpeed;
    api.playerControl('speed', state.playbackSpeed);
    document.getElementById('menu-speed-label').textContent = `Tốc độ phát và cao độ (${state.playbackSpeed}x)`;
    showToast(`Tốc độ phát: ${state.playbackSpeed}x`);
  });
  document.getElementById('menu-act-eq')?.addEventListener('click', () => {
    document.getElementById('sheet-player-menu').style.display = 'none';
    document.getElementById('mochy-player-sheet').style.display = 'none';
    switchTab('profile');
    document.getElementById('profile-main-view').style.display = 'none';
    document.getElementById('profile-settings-view').style.display = 'block';
    document.querySelector('[data-subtab="audiolab"]')?.click();
  });
  document.getElementById('menu-act-like')?.addEventListener('click', () => {
    toggleLikeTrack(state.menuTargetTrack || state.currentTrack);
    document.getElementById('sheet-player-menu').style.display = 'none';
  });
  document.getElementById('menu-act-addlib')?.addEventListener('click', () => {
    const t = state.menuTargetTrack || state.currentTrack;
    if (t) {
      addTrackToLibrary(t);
      showToast(`Đã thêm "${t.title}" vào thư viện`);
    }
    document.getElementById('sheet-player-menu').style.display = 'none';
  });
  document.getElementById('menu-act-share')?.addEventListener('click', () => {
    openShareSongSheet(state.menuTargetTrack || state.currentTrack);
  });

  // Share Sheet Actions (1:1 with media_1794125884417_e74f9ca2.jpg)
  function getTrackUrl(t) {
    if (!t) return 'https://github.com/buiduchuy2010qn-prog/Mochy-Music';
    if (t.source === 'local') return t.filePath || 'Nhạc trên máy';
    return `https://music.youtube.com/watch?v=${encodeURIComponent(t.videoId || t.id)}`;
  }

  document.getElementById('share-opt-card')?.addEventListener('click', () => {
    const t = state.menuTargetTrack || state.currentTrack;
    if (!t) return;
    const text = `🎵 ${t.title} — ${t.artist}\nNghe trên Mochy Music: ${getTrackUrl(t)}`;
    navigator.clipboard.writeText(text);
    document.getElementById('sheet-share-song').style.display = 'none';
    showToast('Đã sao chép thẻ chia sẻ bài hát vào bộ nhớ tạm!');
  });

  document.getElementById('share-opt-lyrics')?.addEventListener('click', () => {
    const t = state.menuTargetTrack || state.currentTrack;
    if (!t) return;
    const snippet = document.getElementById('share-lyrics-snippet')?.textContent || '';
    const text = `🎤 "${snippet}"\n— ${t.title} (${t.artist})\n${getTrackUrl(t)}`;
    navigator.clipboard.writeText(text);
    document.getElementById('sheet-share-song').style.display = 'none';
    showToast('Đã sao chép lời bài hát & liên kết để chia sẻ!');
  });

  document.getElementById('share-opt-link')?.addEventListener('click', () => {
    const t = state.menuTargetTrack || state.currentTrack;
    if (!t) return;
    navigator.clipboard.writeText(getTrackUrl(t));
    document.getElementById('sheet-share-song').style.display = 'none';
    showToast('Đã sao chép liên kết YouTube Music!');
  });

  document.getElementById('btn-close-share-sheet')?.addEventListener('click', () => {
    document.getElementById('sheet-share-song').style.display = 'none';
  });

  // ==================== ABOUT & OTA UPDATE SHEETS ====================
  document.getElementById('btn-close-about-sheet')?.addEventListener('click', () => {
    document.getElementById('sheet-about-app').style.display = 'none';
  });
  document.getElementById('btn-about-open-github')?.addEventListener('click', () => {
    api.openExternal('https://github.com/buiduchuy2010qn-prog/Mochy-Music');
  });
  document.getElementById('btn-settings-open-releases')?.addEventListener('click', () => {
    api.openExternal('https://github.com/buiduchuy2010qn-prog/Mochy-Music/releases');
  });

  async function runOtaCheck(showIfUpToDate) {
    const res = await api.checkUpdate();
    if (res && res.status === 'available') {
      state.latestOtaRelease = res;
      document.getElementById('ota-sheet-title').textContent = res.title || `Mochy Music v${res.latestVersion}`;
      document.getElementById('ota-sheet-sub').textContent = `Phiên bản mới: ${res.latestVersion} • Hiện tại: ${res.currentVersion}`;
      document.getElementById('ota-sheet-notes').textContent = res.body || 'Bản cập nhật mới cải thiện hiệu năng và giao diện.';
      document.getElementById('sheet-ota-update').style.display = 'flex';
    } else if (showIfUpToDate) {
      showToast(`Bạn đang dùng phiên bản mới nhất (${state.appInfo.version})`);
    }
  }

  document.getElementById('btn-about-check-ota')?.addEventListener('click', () => {
    document.getElementById('sheet-about-app').style.display = 'none';
    runOtaCheck(true);
  });
  document.getElementById('btn-settings-check-ota')?.addEventListener('click', () => runOtaCheck(true));
  document.getElementById('btn-ota-later')?.addEventListener('click', () => {
    document.getElementById('sheet-ota-update').style.display = 'none';
  });
  document.getElementById('btn-ota-install-now')?.addEventListener('click', async () => {
    if (!state.latestOtaRelease) return;
    document.getElementById('ota-progress-box').style.display = 'block';
    document.getElementById('btn-ota-install-now').disabled = true;
    await api.downloadAndInstallUpdate(state.latestOtaRelease);
  });

  api.onOtaProgress((data) => {
    const bar = document.getElementById('ota-sheet-progress-bar');
    const txt = document.getElementById('ota-sheet-progress-text');
    if (bar) bar.style.width = `${data.percent || 0}%`;
    if (txt) txt.textContent = `Đang tải bản cập nhật... ${data.percent || 0}%`;
  });

  api.onMediaKey((key) => {
    if (key === 'toggle') togglePlayPause();
    else if (key === 'next') playNext();
    else if (key === 'prev') playPrev();
  });

  // ==================== INITIALIZATION ====================
  async function init() {
    try {
      const info = await api.getAppInfo();
      if (info && info.version) {
        state.appInfo = info;
        document.getElementById('window-version-pill').textContent = `Mochy Music v${info.version}`;
        document.getElementById('profile-version-number').textContent = info.version;
        document.getElementById('settings-ota-ver').textContent = info.version;
        document.getElementById('about-sheet-version').textContent = `Phiên bản v${info.version} (build#${info.buildNumber}) • GPL-3.0`;
      }
    } catch (_) {}

    renderProfileUI();
    renderLogoMoiGrid();
    applyBlurState();
    applyPlayerCoverSettings();
    syncEqUI();
    renderMoodGrid();
    renderRecentSection();
    renderLibraryAll();

    // Restore custom app icon if user picked one previously
    const savedIcon = LOGO_MOI_ICONS.find(x => x.id === state.profile.appIconId);
    if (savedIcon) api.setAppIcon(savedIcon.file);

    await loadHomeGenre('vpop');
    setTimeout(() => runOtaCheck(false), 1800);
  }

  init();
})();
