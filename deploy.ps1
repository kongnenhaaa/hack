# ============================================================
# deploy.ps1 - Build & Deploy APK qua ADB (Tailscale / USB)
# ============================================================
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$ADB      = "C:\Users\congn\Downloads\platform-tools-latest-windows\platform-tools\adb.exe"
$PHONE_IP = "100.114.202.84:5555"
$APK_PATH = "C:\Users\congn\Pictures\hack\app\build\outputs\apk\debug\app-debug.apk"
$PKG_NAME = "com.example.hack"

Write-Host "`n==============================" -ForegroundColor Cyan
Write-Host " BUILD & DEPLOY Xposed Module" -ForegroundColor Cyan
Write-Host "==============================`n" -ForegroundColor Cyan

# ---- Bước 1: Build APK ----
Write-Host "[1/4] Building APK..." -ForegroundColor Yellow
& "$PSScriptRoot\gradlew.bat" assembleDebug
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Build thất bại!" -ForegroundColor Red
    exit 1
}
Write-Host "✅ Build thành công!" -ForegroundColor Green

# ---- Bước 2: Kết nối ADB ----
Write-Host "`n[2/4] Kết nối ADB tới $PHONE_IP ..." -ForegroundColor Yellow
& $ADB connect $PHONE_IP
Start-Sleep -Seconds 2

$devices = (& $ADB devices) -join "`n"
if ($devices -notmatch [regex]::Escape($PHONE_IP)) {
    Write-Host "[!] Khong ket noi duoc toi $PHONE_IP" -ForegroundColor Red
    Write-Host "    Kiem tra Tailscale dang bat tren ca 2 thiet bi." -ForegroundColor DarkYellow
    exit 1
}
Write-Host "[OK] Da ket noi!" -ForegroundColor Green

# ---- Bước 3: Cài APK ----
Write-Host "`n[3/4] Cài APK lên điện thoại..." -ForegroundColor Yellow
& $ADB -s $PHONE_IP install -r $APK_PATH
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Cài APK thất bại!" -ForegroundColor Red
    exit 1
}
Write-Host "✅ Cài APK thành công!" -ForegroundColor Green

# ---- Bước 4: Reboot app (nếu dùng LSPosed thì cần reboot Zalo) ----
Write-Host "`n[4/4] Khởi động lại Zalo..." -ForegroundColor Yellow
& $ADB -s $PHONE_IP shell "am force-stop com.zing.zalo"
Start-Sleep -Seconds 1
& $ADB -s $PHONE_IP shell "monkey -p com.zing.zalo -c android.intent.category.LAUNCHER 1" 2>$null
Write-Host "✅ Zalo đã restart!" -ForegroundColor Green

Write-Host "`n🚀 DEPLOY XONG! Module đã active trên điện thoại.`n" -ForegroundColor Cyan
