// Run with: npm run build:helper
// Compiles helper/WinHelper.cs → assets/bin/winhelper.exe with the C# compiler built into Windows.
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const NET = 'C:\\Windows\\Microsoft.NET\\Framework64\\v4.0.30319';
const root = path.join(__dirname, '..');
const out = path.join(root, 'assets', 'bin', 'winhelper.exe');
fs.mkdirSync(path.dirname(out), { recursive: true });

execFileSync(path.join(NET, 'csc.exe'), [
  '/nologo', '/optimize+', '/target:exe', '/platform:x64',
  `/out:${out}`,
  `/reference:${path.join(NET, 'WPF', 'UIAutomationClient.dll')}`,
  `/reference:${path.join(NET, 'WPF', 'UIAutomationTypes.dll')}`,
  `/reference:${path.join(NET, 'WPF', 'WindowsBase.dll')}`,
  path.join(root, 'helper', 'WinHelper.cs'),
], { stdio: 'inherit' });
console.log('Built', out);
