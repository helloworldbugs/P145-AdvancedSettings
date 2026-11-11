# 构建「高级设置」APK（不依赖 gradle）
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = $PSScriptRoot
$sdk  = "D:\ARM\Android\Sdk"
$bt   = Join-Path $sdk "build-tools\36.0.0"
$plat = Join-Path $sdk "platforms\android-34\android.jar"
$out  = Join-Path $root "build"
$pkg  = "com.p145.advancedsettings"

Write-Host "== 1. 清理 ==" -ForegroundColor Cyan
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out, "$out\classes", "$out\dex", "$out\res" | Out-Null

Write-Host "== 2. aapt2 compile ==" -ForegroundColor Cyan
& "$bt\aapt2.exe" compile --dir "$root\res" -o "$out\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile 失败" }

Write-Host "== 3. aapt2 link ==" -ForegroundColor Cyan
& "$bt\aapt2.exe" link -o "$out\base.apk" `
    -I $plat `
    --manifest "$root\AndroidManifest.xml" `
    -R "$out\res.zip" `
    -A "$root\assets" `
    --java "$out\gen" `
    --min-sdk-version 28 --target-sdk-version 25 `
    --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link 失败" }

Write-Host "== 4. javac ==" -ForegroundColor Cyan
$srcs = @()
$srcs += (Get-ChildItem "$root\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$srcs += (Get-ChildItem "$out\gen" -Recurse -Filter *.java -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
& javac -encoding UTF-8 -source 8 -target 8 -nowarn -bootclasspath $plat -d "$out\classes" $srcs 2>&1 | Out-String | Write-Host
if ($LASTEXITCODE -ne 0) { throw "javac 失败" }

Write-Host "== 5. d8 ==" -ForegroundColor Cyan
Push-Location "$out\classes"
& jar cf "$out\classes.jar" .
Pop-Location
& cmd /c "`"$bt\d8.bat`" --min-api 28 --lib `"$plat`" --output `"$out\dex`" `"$out\classes.jar`""
if ($LASTEXITCODE -ne 0) { throw "d8 失败" }

Write-Host "== 6. 打包 classes.dex ==" -ForegroundColor Cyan
Copy-Item "$out\base.apk" "$out\unaligned.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$out\unaligned.apk", 'Update')
$exist = $zip.Entries | Where-Object { $_.FullName -eq 'classes.dex' }
if ($exist) { $exist.Delete() }
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", 'classes.dex', 'Optimal') | Out-Null
$zip.Dispose()

Write-Host "== 7. zipalign ==" -ForegroundColor Cyan
& "$bt\zipalign.exe" -f -p 4 "$out\unaligned.apk" "$out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign 失败" }

Write-Host "== 8. 平台签名 ==" -ForegroundColor Cyan
& cmd /c "`"$bt\apksigner.bat`" sign --key `"$root\keys\platform.pk8`" --cert `"$root\keys\platform.x509.pem`" --v4-signing-enabled false --min-sdk-version 28 --out `"$out\advanced-settings.apk`" `"$out\aligned.apk`""
if ($LASTEXITCODE -ne 0) { throw "apksigner 失败" }

Write-Host "== 9. 校验 ==" -ForegroundColor Cyan
& cmd /c "`"$bt\apksigner.bat`" verify --print-certs `"$out\advanced-settings.apk`""
& "$bt\aapt2.exe" dump badging "$out\advanced-settings.apk" | Select-String -Pattern "package:|sdkVersion|targetSdkVersion|application-label|launchable-activity"

$f = Get-Item "$out\advanced-settings.apk"
Write-Host ("`n构建完成: {0}  ({1:N0} KB)" -f $f.FullName, ($f.Length / 1KB)) -ForegroundColor Green
