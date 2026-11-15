@echo off
echo ============================================================
echo   CONFIGURATION RESEAU & PARE-FEU POUR TSEU VPN
echo ============================================================
echo.
powershell -Command "Set-NetConnectionProfile -InterfaceAlias 'Wi-Fi' -NetworkCategory Private -ErrorAction SilentlyContinue"
netsh advfirewall firewall delete rule name="Node.js JavaScript Runtime" >nul 2>&1
netsh advfirewall firewall delete rule name="TSEU VPN 3847" >nul 2>&1
netsh advfirewall firewall add rule name="TSEU VPN 3847" dir=in action=allow protocol=TCP localport=3847 profile=any
netsh advfirewall firewall add rule name="TSEU VPN Node" dir=in action=allow program="C:\Program Files\nodejs\node.exe" profile=any
if %errorlevel%==0 (
    echo.
    echo [OK] SUCCES ! Reseau passe en Prive + Port 3847 + Node.js autorises.
    echo Testez maintenant http://192.168.43.196:3847 sur votre telephone !
) else (
    echo.
    echo [ERREUR] Faites un CLIC DROIT sur OUVRIR-PARE-FEU.bat puis
    echo cliquez sur "Executer en tant qu'administrateur".
)
echo.
pause
