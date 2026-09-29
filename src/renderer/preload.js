// Safe bridge between the web pages (pill, preview, dashboard) and the main process.
const { contextBridge, ipcRenderer } = require('electron');

const on = (channel) => (fn) => ipcRenderer.on(channel, (_e, data) => fn(data));

contextBridge.exposeInMainWorld('mywisper', {
  // pill
  onRecordStart: on('record:start'),
  onRecordStop: on('record:stop'),
  onRecordCancel: on('record:cancel'),
  onPillState: on('pill:state'),
  onPillLayout: on('pill:layout'),
  onPillMenu: on('pill:menu'),
  sendAudio: (payload) => ipcRenderer.send('record:audio', payload),
  setPillSize: (size) => ipcRenderer.send('pill:size', size),

  // preview window
  onPreview: on('preview:data'),
  setPreviewHeight: (h) => ipcRenderer.send('preview:height', h),

  // dashboard
  listHistory: () => ipcRenderer.invoke('history:list'),
  deleteHistory: (id) => ipcRenderer.invoke('history:delete', id),
  clearHistory: () => ipcRenderer.invoke('history:clear'),
  onHistoryChanged: on('history:changed'),
  onTab: on('dashboard:tab'),
  getDictionary: () => ipcRenderer.invoke('dict:get'),
  saveDictionary: (d) => ipcRenderer.invoke('dict:save', d),
  getConfig: () => ipcRenderer.invoke('config:get'),
  saveConfig: (c) => ipcRenderer.invoke('config:save', c),
  testApi: (key) => ipcRenderer.invoke('api:test', key),
  copyText: (text) => ipcRenderer.invoke('clipboard:copy', text),
  listPrompts: () => ipcRenderer.invoke('prompts:list'),
  savePrompt: (p) => ipcRenderer.invoke('prompts:save', p),
  pinPrompt: (id, pinned) => ipcRenderer.invoke('prompts:pin', id, pinned),
  removePrompt: (id) => ipcRenderer.invoke('prompts:remove', id),
  resetPrompt: (id) => ipcRenderer.invoke('prompts:reset', id),
  createTodayEod: () => ipcRenderer.invoke('eod:today'),
});
