@echo off
cd /d "C:\Projects\Music_Player"
echo Git cekme islemi basliyor...

REM Eger git repo degilse once clone yap
if not exist ".git" (
    echo Repo bulunamadi, clone yapiliyor...
    git clone https://github.com/keopscakmak2121/Music_Player.git .
    echo Clone tamamlandi!
    pause
    exit /b
)

REM Zaten repo varsa guncelle
git fetch origin
git reset --hard origin/main
git clean -fd

echo Islem tamamlandi!
pause
