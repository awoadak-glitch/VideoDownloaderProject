Add-Type -AssemblyName System.Drawing
$bmp=[Drawing.Bitmap]::new(128,128)
$g=[Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode=[Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.Clear([Drawing.Color]::FromArgb(16,20,30))
$brush=[Drawing.SolidBrush]::new([Drawing.Color]::FromArgb(66,214,164))
$points=[Drawing.Point[]]@([Drawing.Point]::new(52,22),[Drawing.Point]::new(76,22),[Drawing.Point]::new(76,62),[Drawing.Point]::new(98,62),[Drawing.Point]::new(64,96),[Drawing.Point]::new(30,62),[Drawing.Point]::new(52,62))
$g.FillPolygon($brush,$points)
$g.FillRectangle($brush,30,103,68,7)
$stream=[IO.File]::Create("$PSScriptRoot/awr.ico")
$icon=[Drawing.Icon]::FromHandle($bmp.GetHicon())
$icon.Save($stream)
$stream.Close();$icon.Dispose();$g.Dispose();$brush.Dispose();$bmp.Dispose()
