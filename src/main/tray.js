// System tray icon (near the clock) and its right-click menu.
const { Tray, Menu, nativeImage, app } = require('electron');
const path = require('path');

const icon = (name) => nativeImage.createFromPath(path.join(__dirname, '..', '..', 'assets', name));

let tray = null;
let idleIcon = null;
let recIcon = null;

function createTray({ openDashboard, createTodayEod }) {
  idleIcon = icon('tray-idle.png');
  recIcon = icon('tray-recording.png');
  tray = new Tray(idleIcon);
  tray.setToolTip('MyWisper - hold Win + Ctrl to talk');
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: 'MyWisper - hold Win + Ctrl to talk', enabled: false },
    { type: 'separator' },
    { label: "Create today's EOD", click: () => createTodayEod() },
    { type: 'separator' },
    { label: 'Open Dashboard', click: () => openDashboard('overview') },
    { label: 'History', click: () => openDashboard('history') },
    { label: 'Prompts', click: () => openDashboard('prompts') },
    { label: 'Dictionary', click: () => openDashboard('dictionary') },
    { label: 'Settings', click: () => openDashboard('settings') },
    { type: 'separator' },
    { label: 'Quit MyWisper', click: () => app.quit() },
  ]));
  tray.on('double-click', () => openDashboard('overview'));
  tray.on('click', () => openDashboard('overview'));
  return tray;
}

function setRecording(on) {
  if (!tray) return;
  tray.setImage(on ? recIcon : idleIcon);
  tray.setToolTip(on ? 'MyWisper - listening…' : 'MyWisper - hold Win + Ctrl to talk');
}

module.exports = { createTray, setRecording };
