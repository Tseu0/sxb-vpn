@echo off
title SXB VPN Platform - Control Plane & Simulateur Android
cd /d "%~dp0"
echo Demarrage de la plateforme SXB VPN sur http://localhost:3847 ...
start http://localhost:3847
node server.mjs
pause
