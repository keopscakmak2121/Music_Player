@echo off
cd /d "C:\Projects\Music_Player"
chcp 65001 >nul

echo ================================================
echo   GIT GUNCELLEME ARACI
echo ================================================
echo.
echo [1] En son versiyona gec (main)
echo [2] Gecmis versiyonlardan sec
echo.
set /p SECIM="Seciminiz (1 veya 2): "

if "%SECIM%"=="1" goto SON_VERSIYON
if "%SECIM%"=="2" goto GECMIS_VERSIYON
echo Gecersiz secim!
pause
exit /b

:SON_VERSIYON
echo.
echo En son versiyon aliniyor...
git fetch origin
git reset --hard origin/main
git clean -fd
echo.
echo ================================================
echo GUNCELLENEN VERSIYON:
git log --oneline -1
echo ================================================
echo Islem tamamlandi! En son versiyondasiniz.
pause
exit /b

:GECMIS_VERSIYON
echo.
echo Son 15 commit:
echo ------------------------------------------------
git log --oneline -15
echo ------------------------------------------------
echo.
set /p HASH="Gitmek istediginiz commit hash'ini girin (ilk 7 karakter): "

if "%HASH%"=="" (
    echo Hash girmediniz, iptal edildi.
    pause
    exit /b
)

echo.
echo Onceki versiyon:
git log --oneline -1

echo.
echo "%HASH%" versiyonuna geciliyor...
git fetch --all
git reset --hard %HASH%
git clean -fd

echo.
echo ================================================
echo GECILEN VERSIYON:
git log --oneline -1
echo ================================================
echo.
echo Islem tamamlandi!
pause