const { app, BrowserWindow, ipcMain, session, shell, dialog, Tray, Menu, globalShortcut, nativeImage } = require('electron');
const path = require('path');
const fs = require('fs');
const https = require('https');
const { spawn } = require('child_process');

const pkg = require('./package.json');

const OTA_OWNER = 'buiduchuy2010qn-prog';
const OTA_REPO = 'Mochy-Music';
const RELEASES_API = `https://api.github.com/repos/${OTA_OWNER}/${OTA_REPO}/releases?per_page=15`;
const RELEASES_ATOM = `https://github.com/${OTA_OWNER}/${OTA_REPO}/releases.atom`;

let mainWindow = null;
let audioWindow = null;
let ytmWebWindow = null;
let tray = null;
let isQuitting = false;
let currentEqState = { enabled: true, gains: [0, 0, 0, 0, 0], bassBoost: 0 };

// Configure Chromium flags for smooth background audio playback
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');
app.commandLine.appendSwitch('disable-renderer-backgrounding');
app.commandLine.appendSwitch('disable-background-timer-throttling');

function httpsRequestJson(url, options = {}, postBody = null) {
  return new Promise((resolve, reject) => {
    const req = https.request(url, {
      method: options.method || (postBody ? 'POST' : 'GET'),
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
        'Accept': 'application/json, text/plain, */*',
        'Accept-Language': 'vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7',
        ...(options.headers || {})
      },
      timeout: 15000
    }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        return httpsRequestJson(res.headers.location, options, postBody).then(resolve).catch(reject);
      }
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        try {
          resolve(JSON.parse(text));
        } catch (err) {
          reject(new Error(`Invalid JSON (${res.statusCode}): ${text.slice(0, 180)}`));
        }
      });
    });
    req.on('error', reject);
    req.on('timeout', () => { req.destroy(new Error('Request timeout')); });
    if (postBody) req.write(typeof postBody === 'string' ? postBody : JSON.stringify(postBody));
    req.end();
  });
}

function setupAdBlocker() {
  const adPatterns = [
    '*://*.doubleclick.net/*',
    '*://*.googlesyndication.com/*',
    '*://*.googleadservices.com/*',
    '*://*.adservice.google.com/*',
    '*://*.youtube.com/api/stats/ads*',
    '*://*.youtube.com/pagead/*',
    '*://*.youtube.com/ptracking*',
    '*://music.youtube.com/api/stats/ads*',
    '*://music.youtube.com/pagead/*'
  ];
  session.defaultSession.webRequest.onBeforeRequest({ urls: adPatterns }, (_details, callback) => {
    callback({ cancel: true });
  });
}

function getIconPath() {
  const iconCandidate = path.join(__dirname, 'build', 'icon.png');
  return fs.existsSync(iconCandidate) ? iconCandidate : undefined;
}

function createAudioWindow() {
  audioWindow = new BrowserWindow({
    width: 480,
    height: 320,
    show: false,
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      backgroundThrottling: false,
      autoplayPolicy: 'no-user-gesture-required'
    }
  });
  audioWindow.webContents.setUserAgent(
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'
  );
  audioWindow.on('closed', () => { audioWindow = null; });
}

function injectAudioBridge() {
  if (!audioWindow || audioWindow.isDestroyed()) return;
  const eqJson = JSON.stringify(currentEqState);
  const script = `
    (function() {
      if (window.__mochyAudioInit) return;
      window.__mochyAudioInit = true;
      window.__mochyEqState = ${eqJson};

      function skipAds() {
        const adShowing = document.querySelector('.ad-showing, .ad-interrupting');
        const v = document.querySelector('video');
        if (adShowing && v) {
          v.muted = true;
          if (isFinite(v.duration) && v.duration > 0) {
            v.currentTime = v.duration;
          }
        }
        const skipBtns = document.querySelectorAll('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
        skipBtns.forEach(b => { try { b.click(); } catch(_){} });
        const confirmBtn = document.querySelector('ytmusic-you-there-renderer button, tp-yt-paper-dialog yt-button-renderer button');
        if (confirmBtn) { try { confirmBtn.click(); } catch(_){} }
      }

      function attachEq(v) {
        if (!v || v.__mochyEqAttached) return;
        try {
          const AudioCtx = window.AudioContext || window.webkitAudioContext;
          const ctx = new AudioCtx();
          const source = ctx.createMediaElementSource(v);
          const bassFilter = ctx.createBiquadFilter();
          bassFilter.type = 'lowshelf';
          bassFilter.frequency.value = 110;

          const freqs = [60, 230, 910, 3600, 14000];
          const filters = freqs.map((f) => {
            const flt = ctx.createBiquadFilter();
            flt.type = 'peaking';
            flt.frequency.value = f;
            flt.Q.value = 1.1;
            return flt;
          });

          source.connect(bassFilter);
          let prev = bassFilter;
          filters.forEach(flt => { prev.connect(flt); prev = flt; });
          prev.connect(ctx.destination);

          v.__mochyEqAttached = true;
          window.__mochyApplyEq = function(st) {
            window.__mochyEqState = st;
            if (ctx.state === 'suspended') ctx.resume();
            const on = st && st.enabled;
            bassFilter.gain.value = on ? (Number(st.bassBoost) || 0) : 0;
            filters.forEach((flt, i) => {
              flt.gain.value = on && st.gains ? (Number(st.gains[i]) || 0) : 0;
            });
          };
          window.__mochyApplyEq(window.__mochyEqState);
        } catch(e) {}
      }

      setInterval(() => {
        skipAds();
        const v = document.querySelector('video');
        if (v) {
          attachEq(v);
          if (!document.querySelector('.ad-showing') && v.muted && !window.__mochyUserMuted) {
            v.muted = false;
          }
        }
      }, 300);
    })();
  `;
  audioWindow.webContents.executeJavaScript(script).catch(() => {});
}

function startAudioTelemetryLoop() {
  setInterval(async () => {
    if (!audioWindow || audioWindow.isDestroyed() || !mainWindow || mainWindow.isDestroyed()) return;
    try {
      const state = await audioWindow.webContents.executeJavaScript(`
        (function() {
          const v = document.querySelector('video');
          const adShowing = !!document.querySelector('.ad-showing, .ad-interrupting');
          if (!v || adShowing) return null;
          return {
            currentTime: v.currentTime || 0,
            duration: isFinite(v.duration) ? v.duration : 0,
            paused: v.paused,
            ended: v.ended
          };
        })();
      `);
      if (state) {
        mainWindow.webContents.send('player:state', state);
      }
    } catch (_) {}
  }, 350);
}

function createMainWindow() {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 840,
    minWidth: 960,
    minHeight: 660,
    frame: false,
    backgroundColor: '#000000',
    icon: getIconPath(),
    title: 'Mochy Music',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });

  mainWindow.loadFile(path.join(__dirname, 'renderer', 'index.html'));

  mainWindow.on('close', (e) => {
    if (!isQuitting && tray) {
      e.preventDefault();
      mainWindow.hide();
    }
  });
}

function createSystemTray() {
  const iconPath = getIconPath();
  if (!iconPath) return;
  try {
    const img = nativeImage.createFromPath(iconPath).resize({ width: 18, height: 18 });
    tray = new Tray(img);
    tray.setToolTip('Mochy Music (Bản Việt Hóa)');
    const menu = Menu.buildFromTemplate([
      { label: '🎵 Mở Mochy Music', click: () => { if (mainWindow) { mainWindow.show(); mainWindow.focus(); } } },
      { type: 'separator' },
      { label: '⏯ Phát / Tạm dừng', click: () => mainWindow?.webContents.send('media:key', 'toggle') },
      { label: '⏭ Bài tiếp theo', click: () => mainWindow?.webContents.send('media:key', 'next') },
      { label: '⏮ Bài trước đó', click: () => mainWindow?.webContents.send('media:key', 'prev') },
      { type: 'separator' },
      { label: '❌ Thoát hẳn ứng dụng', click: () => { isQuitting = true; app.quit(); } }
    ]);
    tray.setContextMenu(menu);
    tray.on('double-click', () => {
      if (mainWindow) { mainWindow.show(); mainWindow.focus(); }
    });
  } catch (_) {}
}

// Parse FullVersion matching Android GithubUpdates.kt
function parseFullVersion(text) {
  const semverMatch = /(\d+)\.(\d+)(?:\.(\d+))?/.exec(text || '');
  const buildMatch = /(?:build#|\(#|#|\()(\d+)\)?/i.exec(text || '');
  return {
    major: semverMatch ? parseInt(semverMatch[1], 10) : 0,
    minor: semverMatch ? parseInt(semverMatch[2], 10) : 0,
    patch: semverMatch && semverMatch[3] ? parseInt(semverMatch[3], 10) : 0,
    buildNumber: buildMatch ? parseInt(buildMatch[1], 10) : 0
  };
}

function compareVersions(a, b) {
  if (a.major !== b.major) return a.major - b.major;
  if (a.minor !== b.minor) return a.minor - b.minor;
  if (a.patch !== b.patch) return a.patch - b.patch;
  return a.buildNumber - b.buildNumber;
}

// Extract YouTube search results via InnerTube API
async function searchYouTubeInnerTube(query) {
  const body = {
    context: {
      client: {
        clientName: 'WEB',
        clientVersion: '2.20250101.00.00',
        hl: 'vi',
        gl: 'VN'
      }
    },
    query: query
  };
  const data = await httpsRequestJson(
    'https://www.youtube.com/youtubei/v1/search?prettyPrint=false',
    { headers: { 'Content-Type': 'application/json' } },
    body
  );

  const results = [];
  function walk(node) {
    if (!node || typeof node !== 'object') return;
    if (node.videoRenderer) {
      const vr = node.videoRenderer;
      const videoId = vr.videoId;
      const title = vr.title?.runs?.map(r => r.text).join('') || vr.title?.simpleText || '';
      const author = vr.ownerText?.runs?.[0]?.text || vr.longBylineText?.runs?.[0]?.text || 'Nghệ sĩ';
      const durationText = vr.lengthText?.simpleText || '';
      if (videoId && title && durationText) {
        results.push({
          id: videoId,
          videoId: videoId,
          title: title,
          artist: author,
          durationText: durationText,
          thumbnail: `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
          source: 'youtube'
        });
      }
    }
    for (const k of Object.keys(node)) {
      if (results.length < 30) walk(node[k]);
    }
  }
  walk(data);
  return results;
}

app.whenReady().then(() => {
  setupAdBlocker();
  createAudioWindow();
  createMainWindow();
  createSystemTray();
  startAudioTelemetryLoop();

  try {
    globalShortcut.register('MediaPlayPause', () => mainWindow?.webContents.send('media:key', 'toggle'));
    globalShortcut.register('MediaNextTrack', () => mainWindow?.webContents.send('media:key', 'next'));
    globalShortcut.register('MediaPreviousTrack', () => mainWindow?.webContents.send('media:key', 'prev'));
  } catch (_) {}
});

app.on('before-quit', () => { isQuitting = true; });
app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

// IPC Handlers
ipcMain.handle('app:info', () => ({
  version: pkg.version,
  buildNumber: pkg.buildNumber || '1231',
  fullVersionText: `${pkg.version} build#${pkg.buildNumber || '1231'}`,
  platform: process.platform,
  arch: process.arch,
  otaRepoUrl: `https://github.com/${OTA_OWNER}/${OTA_REPO}`,
  originalRepoUrl: 'https://github.com/realidkroo/Mochy-Music'
}));

ipcMain.handle('window:control', (_e, cmd) => {
  if (!mainWindow) return;
  if (cmd === 'minimize') mainWindow.minimize();
  else if (cmd === 'maximize') {
    if (mainWindow.isMaximized()) mainWindow.unmaximize();
    else mainWindow.maximize();
  } else if (cmd === 'close') {
    mainWindow.close();
  }
});

ipcMain.handle('app:open-external', (_e, url) => {
  if (url && /^https?:\/\//i.test(url)) shell.openExternal(url);
});

ipcMain.handle('ytm:search', async (_e, query) => {
  if (!query || !query.trim()) return [];
  try {
    return await searchYouTubeInnerTube(query.trim());
  } catch (err) {
    return [];
  }
});

ipcMain.handle('ytm:trending', async (_e, category) => {
  const queries = {
    vpop: 'Nhạc Trẻ Việt Nam Thịnh Hành Official MV',
    lofi: 'Nhạc Lofi Chill Tiếng Việt Nghe Nhẹ Nhàng',
    remix: 'Nhạc Remix Việt Nam Hot TikTok',
    usuk: 'Top Hits Pop Songs Official Audio',
    bolero: 'Nhạc Trữ Tình Bolero Tuyển Chọn Hay Nhất'
  };
  const q = queries[category] || queries.vpop;
  try {
    return await searchYouTubeInnerTube(q);
  } catch (_) {
    return [];
  }
});

ipcMain.handle('player:load-yt', async (_e, videoId) => {
  if (!videoId) return { ok: false };
  if (!audioWindow || audioWindow.isDestroyed()) createAudioWindow();
  const targetUrl = `https://music.youtube.com/watch?v=${encodeURIComponent(videoId)}`;
  await audioWindow.loadURL(targetUrl);
  injectAudioBridge();
  setTimeout(() => injectAudioBridge(), 1200);
  return { ok: true };
});

ipcMain.handle('player:control', async (_e, action, value) => {
  if (!audioWindow || audioWindow.isDestroyed()) return;
  if (action === 'play') {
    await audioWindow.webContents.executeJavaScript(`document.querySelector('video')?.play()`).catch(() => {});
  } else if (action === 'pause') {
    await audioWindow.webContents.executeJavaScript(`document.querySelector('video')?.pause()`).catch(() => {});
  } else if (action === 'stop') {
    await audioWindow.loadURL('about:blank').catch(() => {});
  } else if (action === 'seek') {
    const sec = Number(value) || 0;
    await audioWindow.webContents.executeJavaScript(`
      (function(){ const v = document.querySelector('video'); if (v) v.currentTime = ${sec}; })();
    `).catch(() => {});
  } else if (action === 'volume') {
    const vol = Math.max(0, Math.min(1, Number(value)));
    await audioWindow.webContents.executeJavaScript(`
      (function(){ const v = document.querySelector('video'); if (v) v.volume = ${vol}; })();
    `).catch(() => {});
  } else if (action === 'speed') {
    const rate = Math.max(0.25, Math.min(2.5, Number(value) || 1));
    await audioWindow.webContents.executeJavaScript(`
      (function(){ const v = document.querySelector('video'); if (v) v.playbackRate = ${rate}; })();
    `).catch(() => {});
  }
});

ipcMain.handle('app:set-icon', (_e, iconFileName) => {
  if (!iconFileName) return { ok: false };
  const safeName = path.basename(String(iconFileName));
  const candidate = path.join(__dirname, 'renderer', 'assets', 'icons', safeName);
  if (!fs.existsSync(candidate)) return { ok: false };
  try {
    const img = nativeImage.createFromPath(candidate);
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.setIcon(img);
    }
    if (tray) {
      tray.setImage(img.resize({ width: 18, height: 18 }));
    }
    return { ok: true };
  } catch (_) {
    return { ok: false };
  }
});

ipcMain.handle('player:set-eq', async (_e, eqState) => {
  currentEqState = eqState || currentEqState;
  if (!audioWindow || audioWindow.isDestroyed()) return;
  const payload = JSON.stringify(currentEqState);
  await audioWindow.webContents.executeJavaScript(`
    if (window.__mochyApplyEq) window.__mochyApplyEq(${payload});
  `).catch(() => {});
});

function parseLrcText(rawLrc) {
  if (!rawLrc || typeof rawLrc !== 'string') return [];
  const synced = [];
  const lines = rawLrc.split(/\r?\n/);
  for (const line of lines) {
    const trimmed = line.trim();
    const m = /^\[(\d+):(\d+(?:[.:]\d+)?)\](.*)/.exec(trimmed);
    if (m) {
      const mins = parseInt(m[1], 10);
      const secs = parseFloat(m[2].replace(':', '.'));
      const text = m[3]
        .replace(/<[^>]+>/g, '')
        .replace(/^\{[^}]+\}/, '')
        .trim();
      if (text && !Number.isNaN(mins) && !Number.isNaN(secs)) {
        synced.push({ time: mins * 60 + secs, text });
      }
    }
  }
  return synced.sort((a, b) => a.time - b.time);
}

function parseSmartTrackQueries(track) {
  const rawTitle = String(track?.title || '').trim();
  const rawArtist = String(track?.artist || '').trim();

  // Strip pipe suffixes, brackets, MV tags
  let t = rawTitle
    .replace(/\|.*$/g, '')
    .replace(/\/\/.*$/g, '')
    .replace(/【.*?】/g, '')
    .replace(/\[.*?\]/g, '')
    .replace(/\((?:[^)]*(?:official|music|video|mv|lyrics?|audio|visualizer|live|remaster|version|edit|hd|4k|prod|ost|cover|karaoke|ver)[^)]*)\)/gi, '')
    .replace(/\b(?:official\s*(?:music\s*)?video|official\s*mv|official\s*audio|lyric\s*video|visualizer|mv|hd|4k)\b/gi, '')
    .replace(/\s+/g, ' ')
    .replace(/^[\s\-|•–—]+|[\s\-|•–—]+$/g, '');

  const cleanChannelArtist = rawArtist
    .split(/[/|•,]|\b(?:official|channel|music|vevo|entertainment)\b/i)[0]
    .replace(/\s+/g, ' ')
    .trim();

  let titleArtist = '';
  let songPart = t;
  const dashParts = t.split(/\s+[-–—]\s+/);
  if (dashParts.length >= 2) {
    titleArtist = dashParts[0].trim();
    songPart = dashParts.slice(1).join(' - ').trim();
  } else {
    titleArtist = cleanChannelArtist;
  }

  // Extract feat / ft artist
  let featArtist = '';
  const featMatch = /(?:^|\s|\()(?:ft\.?|feat\.?|featuring)\s+([^()[\]]+)/i.exec(songPart);
  if (featMatch) {
    featArtist = featMatch[1].replace(/[()]/g, '').trim();
  }

  const songClean = songPart
    .replace(/\s*\(?(?:ft\.?|feat\.?|featuring)\s+.*$/i, '')
    .replace(/\s*\(.*?\)\s*/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();

  const reverseSong = dashParts.length >= 2
    ? dashParts[0].replace(/\s*\(?(?:ft\.?|feat\.?|featuring)\s+.*$/i, '').trim()
    : '';
  const reverseArtist = dashParts.length >= 2 ? dashParts[1].trim() : '';

  const rawCandidates = [
    `${songClean} ${titleArtist} ${featArtist}`,
    `${songClean} ${featArtist}`,
    `${songClean} ${titleArtist}`,
    `${songClean} ${cleanChannelArtist}`,
    reverseSong && reverseArtist ? `${reverseSong} ${reverseArtist}` : '',
    `${t} ${cleanChannelArtist}`,
    songClean,
    t
  ];

  const seen = new Set();
  const candidates = [];
  for (const c of rawCandidates) {
    const norm = c.replace(/\s+/g, ' ').trim();
    if (norm && norm.length >= 2 && !seen.has(norm.toLowerCase())) {
      seen.add(norm.toLowerCase());
      candidates.push(norm);
    }
  }

  return {
    songClean: songClean || t,
    primaryArtist: titleArtist || cleanChannelArtist || rawArtist,
    featArtist,
    candidates
  };
}

ipcMain.handle('lyrics:get', async (_e, track) => {
  if (!track || !track.title) return { synced: [], plain: '', provider: '' };
  const videoId = track.videoId || (track.source === 'youtube' ? track.id : '');
  const parsed = parseSmartTrackQueries(track);
  let plainFallback = null;

  // 1. Try SimpMusic Lyrics API by YouTube videoId (same as Android APK SimpMusicLyrics.kt)
  if (videoId && !String(videoId).startsWith('local:')) {
    for (const base of ['https://api-lyrics.simpmusic.org/v1/', 'https://musicfy-yt-music-server.onrender.com/v1/']) {
      try {
        const simp = await httpsRequestJson(`${base}${encodeURIComponent(videoId)}`, {
          headers: { 'User-Agent': 'SimpMusicLyrics/1.0' }
        });
        const items = simp && simp.success && Array.isArray(simp.data) ? simp.data : [];
        if (items.length > 0) {
          const best = items.find(x => x.syncedLyrics || x.richSyncLyrics) || items[0];
          const synced = parseLrcText(best.syncedLyrics || best.richSyncLyrics || '');
          if (synced.length > 0) {
            return {
              synced,
              plain: best.plainLyrics || synced.map(s => s.text).join('\n'),
              provider: 'SimpMusic',
              trackName: best.songTitle || parsed.songClean,
              artistName: best.artistName || parsed.primaryArtist
            };
          }
          if (!plainFallback && best.plainLyrics) {
            plainFallback = {
              synced: [],
              plain: best.plainLyrics,
              provider: 'SimpMusic',
              trackName: best.songTitle || parsed.songClean,
              artistName: best.artistName || parsed.primaryArtist
            };
          }
        }
      } catch (_) {}
    }
  }

  // 2. Try LRCLIB with multi-step smart Vietnamese/International query candidates
  for (const query of parsed.candidates) {
    try {
      const q = encodeURIComponent(query);
      const list = await httpsRequestJson(`https://lrclib.net/api/search?q=${q}`);
      if (!Array.isArray(list) || list.length === 0) continue;

      const withSynced = list.find(item => item && item.syncedLyrics && parseLrcText(item.syncedLyrics).length > 0);
      if (withSynced) {
        const synced = parseLrcText(withSynced.syncedLyrics);
        return {
          synced,
          plain: withSynced.plainLyrics || synced.map(s => s.text).join('\n'),
          provider: 'LRCLIB',
          trackName: withSynced.trackName || parsed.songClean,
          artistName: withSynced.artistName || parsed.primaryArtist
        };
      }

      if (!plainFallback) {
        const withPlain = list.find(item => item && item.plainLyrics);
        if (withPlain) {
          plainFallback = {
            synced: [],
            plain: withPlain.plainLyrics,
            provider: 'LRCLIB',
            trackName: withPlain.trackName || parsed.songClean,
            artistName: withPlain.artistName || parsed.primaryArtist
          };
        }
      }
    } catch (_) {}
  }

  // 3. Try Paxsenix Apple Music Lyrics (same as Android APK Paxsenix.kt)
  try {
    const q = encodeURIComponent(`${parsed.songClean} ${parsed.primaryArtist}`.trim());
    const searchRes = await httpsRequestJson(`https://lyrics.paxsenix.org/apple-music/search?q=${q}`, {
      headers: { 'User-Agent': `Mochy/${pkg.version || '8.9.0'}` }
    });
    if (Array.isArray(searchRes) && searchRes.length > 0 && searchRes[0].id) {
      const lrcData = await httpsRequestJson(`https://lyrics.paxsenix.org/apple-music/lyrics?id=${encodeURIComponent(searchRes[0].id)}`, {
        headers: { 'User-Agent': `Mochy/${pkg.version || '8.9.0'}` }
      });
      if (lrcData && Array.isArray(lrcData.content) && lrcData.content.length > 0) {
        const synced = [];
        for (const line of lrcData.content) {
          const text = Array.isArray(line.text) ? line.text.map(w => w.text).join(' ').replace(/\s+/g, ' ').trim() : '';
          if (text && typeof line.timestamp === 'number') {
            synced.push({ time: line.timestamp / 1000, text });
          }
        }
        if (synced.length > 0) {
          return {
            synced,
            plain: synced.map(s => s.text).join('\n'),
            provider: 'Apple Music',
            trackName: searchRes[0].songName || parsed.songClean,
            artistName: searchRes[0].artistName || parsed.primaryArtist
          };
        }
      }
    }
  } catch (_) {}

  // 4. Try YouTube Closed Captions from active audioWindow (same as YouTubeCaptionsLyricsProvider.kt)
  if (audioWindow && !audioWindow.isDestroyed()) {
    try {
      const ytCapUrl = await audioWindow.webContents.executeJavaScript(`
        (function() {
          try {
            const tracks = window.ytInitialPlayerResponse?.captions?.playerCaptionsTracklistRenderer?.captionTracks;
            if (!Array.isArray(tracks) || !tracks.length) return null;
            const vi = tracks.find(t => t.languageCode === 'vi') || tracks[0];
            return vi && vi.baseUrl ? (vi.baseUrl + '&fmt=json3') : null;
          } catch (_) { return null; }
        })();
      `);
      if (ytCapUrl) {
        const capJson = await httpsRequestJson(ytCapUrl);
        if (capJson && Array.isArray(capJson.events)) {
          const synced = [];
          for (const ev of capJson.events) {
            if (!Array.isArray(ev.segs)) continue;
            const text = ev.segs.map(s => s.utf8 || '').join('').replace(/\s+/g, ' ').trim();
            if (text && typeof ev.tStartMs === 'number') {
              synced.push({ time: ev.tStartMs / 1000, text });
            }
          }
          if (synced.length >= 3) {
            return {
              synced,
              plain: synced.map(s => s.text).join('\n'),
              provider: 'YouTube CC',
              trackName: parsed.songClean,
              artistName: parsed.primaryArtist
            };
          }
        }
      }
    } catch (_) {}
  }

  return plainFallback || { synced: [], plain: '', provider: '' };
});

// Translate lyrics lines into Vietnamese (matching GoogleTranslateEngine.kt in Android APK)
ipcMain.handle('lyrics:translate', async (_e, lines) => {
  if (!Array.isArray(lines) || !lines.length) return [];
  try {
    const joined = lines.slice(0, 120).join('\n');
    const url = `https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=vi&dt=t&q=${encodeURIComponent(joined)}`;
    const data = await httpsRequestJson(url);
    if (!Array.isArray(data) || !Array.isArray(data[0])) return [];
    const fullTranslated = data[0].map(seg => (Array.isArray(seg) ? seg[0] : '')).join('');
    return fullTranslated.split('\n').map(s => s.trim());
  } catch (_) {
    return [];
  }
});

// Fetch animated cover video (Canvas) via Monochrome / Tidal API (matching MonochromeApiCanvas.kt)
ipcMain.handle('canvas:get', async (_e, track) => {
  if (!track || !track.title) return { videoUrl: null };
  const parsed = parseSmartTrackQueries(track);
  const q = encodeURIComponent(`${parsed.primaryArtist} - ${parsed.songClean}`.trim());
  const instances = [
    'https://api.monochrome.tf/',
    'https://eu-central.monochrome.tf/',
    'https://us-west.monochrome.tf/'
  ];
  for (const base of instances) {
    try {
      const root = await httpsRequestJson(`${base}search/?s=${q}`);
      const items = root?.tracks?.items || root?.data?.tracks?.items || root?.items || [];
      if (!Array.isArray(items)) continue;
      for (const item of items) {
        const vc = item?.album?.videoCover;
        if (vc && typeof vc === 'string') {
          const parts = vc.split('-');
          if (parts.length === 5) {
            return {
              videoUrl: `https://resources.tidal.com/videos/${parts[0]}/${parts[1]}/${parts[2]}/${parts[3]}/${parts[4]}/1280x1280.mp4`
            };
          }
        }
      }
    } catch (_) {}
  }
  return { videoUrl: null };
});

ipcMain.handle('ytm:open-web-window', () => {
  if (ytmWebWindow && !ytmWebWindow.isDestroyed()) {
    ytmWebWindow.show();
    ytmWebWindow.focus();
    return;
  }
  ytmWebWindow = new BrowserWindow({
    width: 1180,
    height: 780,
    title: 'Mochy Music — YouTube Music Web (Đã chặn quảng cáo)',
    icon: getIconPath(),
    backgroundColor: '#090d0c',
    autoHideMenuBar: true,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false
    }
  });
  ytmWebWindow.loadURL('https://music.youtube.com/?hl=vi');
  ytmWebWindow.on('closed', () => { ytmWebWindow = null; });
});

ipcMain.handle('local:pick-files', async () => {
  const res = await dialog.showOpenDialog(mainWindow, {
    title: 'Chọn tệp nhạc trong máy tính',
    properties: ['openFile', 'multiSelections'],
    filters: [
      { name: 'Tệp Âm Thanh (MP3, FLAC, WAV, M4A, OGG)', extensions: ['mp3', 'flac', 'wav', 'm4a', 'ogg', 'opus', 'aac'] }
    ]
  });
  if (res.canceled || !res.filePaths.length) return [];
  return res.filePaths.map((fp) => {
    const base = path.basename(fp, path.extname(fp));
    const parts = base.split(' - ');
    return {
      id: `local:${fp}`,
      filePath: fp,
      fileUrl: `file://${fp.replace(/\\/g, '/')}`,
      title: parts.length > 1 ? parts.slice(1).join(' - ').trim() : base,
      artist: parts.length > 1 ? parts[0].trim() : 'Nhạc trong máy',
      durationText: 'Offline',
      thumbnail: '',
      source: 'local'
    };
  });
});

// api.github.com allows only 60 unauthenticated calls an hour per IP, shared by every device on the
// same Wi-Fi. When it refuses, the public releases.atom feed on github.com (not rate-limited) answers.
function httpsGetText(url, timeoutMs = 15000, hops = 0) {
  return new Promise((resolve, reject) => {
    const req = https.get(url, {
      headers: { 'User-Agent': 'MochyMusic-Desktop-OTA', 'Accept': '*/*' },
      timeout: timeoutMs
    }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && hops < 6) {
        res.resume();
        return httpsGetText(new URL(res.headers.location, url).toString(), timeoutMs, hops + 1).then(resolve, reject);
      }
      if (res.statusCode < 200 || res.statusCode >= 300) {
        res.resume();
        return reject(new Error(`HTTP ${res.statusCode}`));
      }
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
      res.on('error', reject);
    });
    req.on('error', reject);
    req.on('timeout', () => { req.destroy(new Error('Request timeout')); });
  });
}

function unescapeXmlHtml(input) {
  // the feed escapes the release html once more, so this runs twice
  let out = input || '';
  for (let i = 0; i < 2; i++) {
    out = out
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .replace(/&quot;/g, '"')
      .replace(/&#39;|&apos;/g, "'")
      .replace(/&amp;/g, '&');
  }
  return out;
}

function atomHtmlToMarkdown(raw) {
  return unescapeXmlHtml(raw)
    .replace(/<h3[^>]*>([\s\S]*?)<\/h3>/g, '### $1')
    .replace(/<h4[^>]*>([\s\S]*?)<\/h4>/g, '#### $1')
    .replace(/<strong[^>]*>([\s\S]*?)<\/strong>/g, '**$1**')
    .replace(/<b[^>]*>([\s\S]*?)<\/b>/g, '**$1**')
    .replace(/<code[^>]*>([\s\S]*?)<\/code>/g, '`$1`')
    .replace(/<a[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/g, '[$2]($1)')
    .replace(/<li[^>]*>([\s\S]*?)<\/li>/g, '- $1')
    .replace(/<hr\s*\/?>/g, '---')
    .replace(/<\/?(ul|ol|p|div|span|br)[^>]*>/g, '')
    .replace(/<[^>]+>/g, '')
    .trim();
}

// Shapes the feed like the API's release list, so ota:check reads either the same way.
async function fetchReleasesViaAtom() {
  const xml = await httpsGetText(RELEASES_ATOM);
  const entries = [...xml.matchAll(/<entry>([\s\S]*?)<\/entry>/g)].map((m) => m[1]);
  const releases = [];
  for (let i = 0; i < entries.length; i++) {
    const e = entries[i];
    const tag = (e.match(/releases\/tag\/([^"&<>\s]+)/) || [])[1];
    if (!tag) continue;
    const title = unescapeXmlHtml((e.match(/<title[^>]*>([\s\S]*?)<\/title>/) || [])[1] || tag);
    const body = atomHtmlToMarkdown((e.match(/<content[^>]*>([\s\S]*?)<\/content>/) || [])[1] || '');
    const publishedAt = ((e.match(/<updated[^>]*>([\s\S]*?)<\/updated>/) || [])[1] || '').trim();
    // the release workflow names every installer in its notes
    let names = [...new Set([...body.matchAll(/Mochy-Music-[^\s*`"<>()]+?\.(?:exe|AppImage|deb|apk)/gi)].map((m) => m[0]))];
    // notes without them: ask the release's asset list, only for the newest few
    if (!names.some((n) => /\.(exe|AppImage|deb)$/i.test(n)) && i < 3) {
      try {
        const html = await httpsGetText(`https://github.com/${OTA_OWNER}/${OTA_REPO}/releases/expanded_assets/${tag}`, 8000);
        names = [...new Set([...html.matchAll(/releases\/download\/[^/"]+\/([^"<>\s/]+?\.(?:exe|AppImage|deb|apk))"/gi)]
          .map((m) => decodeURIComponent(m[1])))];
      } catch (_) {}
    }
    releases.push({
      draft: false,
      prerelease: false,
      name: title,
      tag_name: tag,
      body,
      html_url: `https://github.com/${OTA_OWNER}/${OTA_REPO}/releases/tag/${tag}`,
      published_at: publishedAt,
      assets: names.map((n) => ({
        name: n,
        size: 0,
        browser_download_url: `https://github.com/${OTA_OWNER}/${OTA_REPO}/releases/download/${tag}/${n}`
      }))
    });
  }
  return releases;
}

ipcMain.handle('ota:check', async () => {
  try {
    let releases = null;
    try {
      releases = await httpsRequestJson(RELEASES_API);
    } catch (_) {
      releases = null;
    }
    // a rate-limited API answers {"message":"API rate limit exceeded ..."} instead of a list
    if (!Array.isArray(releases) || releases.length === 0) {
      releases = await fetchReleasesViaAtom();
    }
    if (!Array.isArray(releases) || releases.length === 0) {
      return { status: 'error', message: 'Không đọc được danh sách bản phát hành trên GitHub' };
    }
    const rawInstalled = parseFullVersion(pkg.version || '9.1.0');
    const installedVer = {
      major: rawInstalled.major,
      minor: rawInstalled.minor,
      patch: rawInstalled.patch,
      buildNumber: 0
    };
    let newest = null;
    let newestVer = installedVer;
    let matchedAsset = null;

    for (const rel of releases) {
      if (rel.draft || rel.prerelease) continue;
      const assets = Array.isArray(rel.assets) ? rel.assets : [];
      let candidateAsset = null;
      if (process.platform === 'win32') {
        candidateAsset =
          assets.find(a => /setup.*\.exe$/i.test(a.name)) ||
          assets.find(a => /\.exe$/i.test(a.name));
      } else if (process.platform === 'linux') {
        candidateAsset =
          assets.find(a => /\.AppImage$/i.test(a.name)) ||
          assets.find(a => /\.deb$/i.test(a.name));
      }
      if (!candidateAsset) continue;

      // Parse version directly from the PC installer filename (e.g. Mochy-Music-v9.1.0-Windows-x64-Setup.exe)
      // so Android-only releases that carry forward the existing PC installer NEVER trigger a PC update prompt.
      const assetVer = parseFullVersion(candidateAsset.name || '');
      const tagVer = parseFullVersion(`${rel.name || ''} ${rel.tag_name || ''}`);
      const effectiveVer = {
        major: assetVer.major > 0 ? assetVer.major : tagVer.major,
        minor: assetVer.major > 0 ? assetVer.minor : tagVer.minor,
        patch: assetVer.major > 0 ? assetVer.patch : tagVer.patch,
        buildNumber: 0
      };

      if (compareVersions(effectiveVer, newestVer) > 0) {
        newestVer = effectiveVer;
        newest = rel;
        matchedAsset = candidateAsset;
      }
    }

    if (!newest || !matchedAsset) {
      return {
        status: 'upToDate',
        currentVersion: `${pkg.version} (build#${pkg.buildNumber || '1246'})`
      };
    }

    return {
      status: 'available',
      currentVersion: `${pkg.version} (build#${pkg.buildNumber || '1246'})`,
      latestVersion: `${newestVer.major}.${newestVer.minor}.${newestVer.patch}`,
      title: newest.name || newest.tag_name,
      tag: newest.tag_name,
      body: newest.body || '',
      htmlUrl: newest.html_url,
      publishedAt: newest.published_at,
      downloadUrl: matchedAsset.browser_download_url,
      assetName: matchedAsset.name,
      assetSize: matchedAsset.size || 0
    };
  } catch (err) {
    return { status: 'error', message: err.message };
  }
});

ipcMain.handle('ota:install', async (_e, releaseInfo) => {
  if (!releaseInfo || !releaseInfo.downloadUrl) {
    if (releaseInfo?.htmlUrl) shell.openExternal(releaseInfo.htmlUrl);
    return { ok: false, message: 'Không tìm thấy tệp cài đặt cho hệ điều hành này, đã mở trang Releases.' };
  }
  const destDir = app.getPath('temp');
  const destFile = path.join(destDir, releaseInfo.assetName || (process.platform === 'win32' ? 'Mochy-Update-Setup.exe' : 'Mochy-Update.AppImage'));
  // written here first and only moved into place once complete, so a cut-off download is never run
  const partFile = destFile + '.part';

  return new Promise((resolve) => {
    let settled = false;
    function fail(message) {
      if (settled) return;
      settled = true;
      try { fs.rmSync(partFile, { force: true }); } catch (_) {}
      resolve({ ok: false, message });
    }
    function download(url, hops = 0) {
      https.get(url, { headers: { 'User-Agent': 'MochyMusic-Desktop-OTA' } }, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          res.resume();
          if (hops >= 6) return fail('Quá nhiều lần chuyển hướng khi tải bản cập nhật');
          return download(new URL(res.headers.location, url).toString(), hops + 1);
        }
        if (res.statusCode !== 200) {
          res.resume();
          return fail(`Máy chủ trả về mã lỗi HTTP ${res.statusCode}`);
        }
        const total = parseInt(res.headers['content-length'] || '0', 10) || releaseInfo.assetSize || 0;
        let downloaded = 0;
        const fileStream = fs.createWriteStream(partFile);

        res.on('data', (chunk) => {
          downloaded += chunk.length;
          if (total > 0 && mainWindow && !mainWindow.isDestroyed()) {
            mainWindow.webContents.send('ota:progress', {
              percent: Math.min(100, Math.round((downloaded / total) * 100)),
              downloaded,
              total
            });
          }
        });
        res.on('error', (err) => { fileStream.destroy(); fail(err.message); });
        fileStream.on('error', (err) => fail(err.message));

        res.pipe(fileStream);
        fileStream.on('finish', () => {
          fileStream.close(() => {
            if (total > 0 && downloaded !== total) {
              return fail(`Tệp tải về bị thiếu (${downloaded}/${total} bytes), hãy thử lại`);
            }
            try {
              fs.rmSync(destFile, { force: true });
              fs.renameSync(partFile, destFile);
            } catch (err) {
              return fail(err.message);
            }
            settled = true;
            if (process.platform === 'win32') {
              spawn(destFile, [], { detached: true, stdio: 'ignore' }).unref();
              isQuitting = true;
              setTimeout(() => app.quit(), 600);
              resolve({ ok: true });
            } else {
              try { fs.chmodSync(destFile, 0o755); } catch (_) {}
              shell.showItemInFolder(destFile);
              resolve({ ok: true, filePath: destFile });
            }
          });
        });
      }).on('error', (err) => fail(err.message));
    }
    download(releaseInfo.downloadUrl);
  });
});
