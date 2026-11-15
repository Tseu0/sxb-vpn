@echo off
chcp 65001 >nul
title Envoi Automatique sur GitHub - SXB VPN Platform
cd /d "%~dp0"
echo ==================================================================
echo 🚀 ENVOI DE VOTRE PROJET SXB VPN SUR GITHUB POUR COMPILER L'APK
echo ==================================================================
echo.
set /p REPO_URL="Collez ici le lien de votre depot GitHub (ex: https://github.com/pseudo/sxb-vpn.git) : "
if "%REPO_URL%"=="" (
    echo Aucun lien saisi. Operation annulee.
    pause
    exit /b 1
)
git remote remove origin 2>nul
git remote add origin %REPO_URL%
git push -u origin main
echo.
echo ✅ Termine ! Allez dans l'onglet "Actions" de votre depot GitHub pour telecharger votre APK.
pause
