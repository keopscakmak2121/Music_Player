@echo off
cd /d "C:\Projects\Music_Player"
echo Git gonderme islemi basliyor...

REM Tum degisiklikleri ekle
git add -A

REM Commit yap
set /p message="Commit mesaji (bos birakabilirsin): "
if "%message%"=="" set message=Otomatik commit

git commit -m "%message%"

REM Push et
echo Push yapiliyor: origin/main
git push origin main
if errorlevel 1 (
    echo Push basarisiz, once pull deneniyor...
    git pull --rebase origin main
    git push origin main
)

echo Islem tamamlandi!
pause
