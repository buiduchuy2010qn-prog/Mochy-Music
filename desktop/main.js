const { app, BrowserWindow, ipcMain, session, shell, dialog, Tray, Menu, globalShortcut, nativeImage } = require('electron');
const path = require('path');
const fs = require('fs');
const https = require('https');
const { spawn } = require('child_process');

const pkg = require('./package.json');

const OTA_OWNER = 'buiduchuy2010qn-prog';
const OTA_REPO = 'Mochy-Music';
const RELEASES_API = `https://api.github.com/repos/${OTA_OWNER}/${OTA_REPO}/releases?per_page=15`;

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
    width: 1260,
    height: 820,
    minWidth: 960,
    minHeight: 640,
    frame: false,
    backgroundColor: '#090d0c',
    icon: getIconPath(),
    title: 'Mochy Music — Bản Việt Hóa',
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

ipcMain.handle('lyrics:get', async (_e, track) => {
  if (!track || !track.title) return { synced: [], plain: '' };
  try {
    const cleanTitle = track.title
      .replace(/\(.*?\)|\[.*?\]/g, '')
      .replace(/official\s*(music\s*)?video|mv|lyrics?|audio|hd|4k/gi, '')
      .trim();
    const q = encodeURIComponent(`${cleanTitle} ${track.artist || ''}`.trim());
    const list = await httpsRequestJson(`https://lrclib.net/api/search?q=${q}`);
    if (!Array.isArray(list) || list.length === 0) return { synced: [], plain: '' };
    const best = list.find(item => item.syncedLyrics) || list[0];
    const synced = [];
    if (best.syncedLyrics) {
      const lines = best.syncedLyrics.split('\n');
      for (const line of lines) {
        const m = /^\[(\d+):(\d+(?:\.\d+)?)\](.*)/.exec(line.trim());
        if (m) {
          const time = parseInt(m[1], 10) * 60 + parseFloat(m[2]);
          const text = m[3].trim();
          if (text) synced.push({ time, text });
        }
      }
    }
    return {
      synced,
      plain: best.plainLyrics || '',
      trackName: best.trackName,
      artistName: best.artistName
    };
  } catch (_) {
    return { synced: [], plain: '' };
  }
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

ipcMain.handle('ota:check', async () => {
  try {
    const releases = await httpsRequestJson(RELEASES_API);
    if (!Array.isArray(releases) || releases.length === 0) {
      return { status: 'upToDate' };
    }
    const installedVer = parseFullVersion(`${pkg.version} build#${pkg.buildNumber || '1231'}`);
    let newest = null;
    let newestVer = installedVer;

    for (const rel of releases) {
      if (rel.draft) continue;
      const v = parseFullVersion(`${rel.name || ''} ${rel.tag_name || ''}`);
      if (compareVersions(v, newestVer) > 0) {
        newestVer = v;
        newest = rel;
      }
    }

    if (!newest) {
      return {
        status: 'upToDate',
        currentVersion: `${pkg.version} (build#${pkg.buildNumber || '1231'})`
      };
    }

    const assets = Array.isArray(newest.assets) ? newest.assets : [];
    let matchedAsset = null;
    if (process.platform === 'win32') {
      matchedAsset =
        assets.find(a => /setup.*\.exe$/i.test(a.name)) ||
        assets.find(a => /\.exe$/i.test(a.name));
    } else if (process.platform === 'linux') {
      matchedAsset =
        assets.find(a => /\.AppImage$/i.test(a.name)) ||
        assets.find(a => /\.deb$/i.test(a.name));
    }

    return {
      status: 'available',
      currentVersion: `${pkg.version} (build#${pkg.buildNumber || '1231'})`,
      latestVersion: `${newestVer.major}.${newestVer.minor}.${newestVer.patch} (build#${newestVer.buildNumber})`,
      title: newest.name || newest.tag_name,
      tag: newest.tag_name,
      body: newest.body || '',
      htmlUrl: newest.html_url,
      publishedAt: newest.published_at,
      downloadUrl: matchedAsset ? matchedAsset.browser_download_url : null,
      assetName: matchedAsset ? matchedAsset.name : null,
      assetSize: matchedAsset ? matchedAsset.size : 0
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

  return new Promise((resolve) => {
    function download(url) {
      https.get(url, { headers: { 'User-Agent': 'MochyMusic-Desktop-OTA' } }, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          return download(res.headers.location);
        }
        const total = parseInt(res.headers['content-length'] || '0', 10) || releaseInfo.assetSize || 0;
        let downloaded = 0;
        const fileStream = fs.createWriteStream(destFile);

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

        res.pipe(fileStream);
        fileStream.on('finish', () => {
          fileStream.close(() => {
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
      }).on('error', (err) => {
        resolve({ ok: false, message: err.message });
      });
    }
    download(releaseInfo.downloadUrl);
  });
});