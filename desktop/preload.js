const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('mochyAPI', {
  getAppInfo: () => ipcRenderer.invoke('app:info'),
  searchMusic: (query) => ipcRenderer.invoke('ytm:search', query),
  getTrendingMusic: (category) => ipcRenderer.invoke('ytm:trending', category),
  getLyrics: (track) => ipcRenderer.invoke('lyrics:get', track),
  playYouTube: (videoId) => ipcRenderer.invoke('player:load-yt', videoId),
  playerControl: (action, value) => ipcRenderer.invoke('player:control', action, value),
  setEqualizer: (eqState) => ipcRenderer.invoke('player:set-eq', eqState),
  openYoutubeMusicWeb: () => ipcRenderer.invoke('ytm:open-web-window'),
  pickLocalFiles: () => ipcRenderer.invoke('local:pick-files'),
  checkUpdate: (force) => ipcRenderer.invoke('ota:check', force),
  downloadAndInstallUpdate: (releaseInfo) => ipcRenderer.invoke('ota:install', releaseInfo),
  openExternal: (url) => ipcRenderer.invoke('app:open-external', url),
  windowControl: (cmd) => ipcRenderer.invoke('window:control', cmd),
  onPlayerState: (cb) => ipcRenderer.on('player:state', (_e, state) => cb(state)),
  onOtaProgress: (cb) => ipcRenderer.on('ota:progress', (_e, data) => cb(data)),
  onMediaKey: (cb) => ipcRenderer.on('media:key', (_e, key) => cb(key))
});