# Generates the app + tray icons (a white microphone on a coloured circle/rounded square).
# Run: powershell -ExecutionPolicy Bypass -File scripts\make-icons.ps1
Add-Type -AssemblyName System.Drawing
$out = Join-Path $PSScriptRoot '..\assets'
New-Item -ItemType Directory -Force $out | Out-Null

function New-RoundRect([float]$x, [float]$y, [float]$w, [float]$h, [float]$r) {
  $p = New-Object System.Drawing.Drawing2D.GraphicsPath
  $d = [Math]::Min($r * 2, [Math]::Min($w, $h))
  $p.AddArc($x, $y, $d, $d, 180, 90)
  $p.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
  $p.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
  $p.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
  $p.CloseFigure()
  return $p
}

function New-Icon([int]$size, [string]$c1, [string]$c2, [string]$file, [bool]$circle) {
  $bmp = New-Object System.Drawing.Bitmap $size, $size
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode = 'AntiAlias'
  $g.Clear([System.Drawing.Color]::Transparent)
  $rect = New-Object System.Drawing.RectangleF 0, 0, $size, $size
  $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, ([System.Drawing.ColorTranslator]::FromHtml($c1)), ([System.Drawing.ColorTranslator]::FromHtml($c2)), 45
  if ($circle) { $g.FillEllipse($brush, 0, 0, $size - 1, $size - 1) }
  else { $g.FillPath($brush, (New-RoundRect 0 0 $size $size ($size * 0.22))) }

  # microphone
  $s = $size / 32.0
  $white = [System.Drawing.Brushes]::White
  $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::White), ([float](2.2 * $s))
  $pen.StartCap = 'Round'; $pen.EndCap = 'Round'
  $g.FillPath($white, (New-RoundRect (12.5 * $s) (6 * $s) (7 * $s) (13 * $s) (3.5 * $s)))
  $g.DrawArc($pen, (9 * $s), (9.5 * $s), (14 * $s), (13 * $s), 0, 180)
  $g.DrawLine($pen, (16 * $s), (22.5 * $s), (16 * $s), (26 * $s))
  $g.DrawLine($pen, (12.5 * $s), (26 * $s), (19.5 * $s), (26 * $s))

  $bmp.Save((Join-Path $out $file), [System.Drawing.Imaging.ImageFormat]::Png)
  $g.Dispose(); $bmp.Dispose()
}

New-Icon 256 '#6366f1' '#8b5cf6' 'icon.png' $false
New-Icon 32 '#6366f1' '#8b5cf6' 'tray-idle.png' $true
New-Icon 32 '#ef4444' '#dc2626' 'tray-recording.png' $true
Write-Output "Icons written to $out"
