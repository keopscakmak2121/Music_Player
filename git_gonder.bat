@echo off
cd /d "C:\Projects\Music_Player"
chcp 65001 >nul
echo Git gonderme islemi basliyor...

echo.
echo ================================================
echo GONDERMEDEN ONCEKI VERSIYON:
git log --oneline -1
echo ================================================

REM Tum degisiklikleri ekle
git add -A

REM Commit yap
set /p message="Commit mesaji (bos birakabilirsin): "
if "%message%"=="" set message=Otomatik commit

git commit -m "%message%"

echo.
echo ================================================
echo YENI VERSIYON OLUSTURULDU:
git log --oneline -1
echo ================================================

REM Push et
echo.
echo Push yapiliyor: origin/main
git push origin main
if errorlevel 1 (
    echo Push basarisiz, once pull deneniyor...
    git pull --rebase origin main
    git push origin main
)

echo.
echo ================================================
echo UZAK SUNUCUDAKI VERSIYON:
git ls-remote origin main | findstr /r "[0-9a-f]"
echo ================================================
echo.
echo Islem tamamlandi!
pause