@echo off
chcp 65001 >nul
echo Ouverture du port TCP 3847 dans le pare-feu Windows pour TSEU VPN...
netsh advfirewall firewall delete rule name="TSEU VPN 3847" >nul 2>&1
netsh advfirewall firewall add rule name="TSEU VPN 3847" dir=in action=allow protocol=TCP localport=3847
if %errorlevel%==0 (
    echo ✅ Port 3847 ouvert avec succes ! Votre telephone peut maintenant se connecter en Wi-Fi sur http://192.168.43.196:3847
) else (
    echo ❌ Erreur : faites un CLIC DROIT sur ce fichier puis "Executer en tant qu'administrateur".
)
pause
