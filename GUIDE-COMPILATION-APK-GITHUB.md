# Guide : Générer votre fichier `.APK` gratuitement avec GitHub Actions (Sans installer Android Studio)

Votre dossier `C:\Users\odette\Desktop\free\sxb-platform` contient désormais tout le nécessaire pour que les serveurs gratuits de **GitHub** compilent votre application Android **SXB VPN** en moins de 2 minutes.

---

## Étape 1 : Mettre l'adresse de votre serveur dans l'application (Optionnel au début)

Dans le fichier `android-client/app/src/main/java/com/sxbvpn/mobile/MainActivity.kt` (ligne 27) :
```kotlin
apiClient = SxbApiClient(baseUrl = "http://10.0.2.2:3847")
```
* Si vous testez sur le même réseau Wi-Fi que votre PC Windows, remplacez `10.0.2.2` par l'adresse IP Wi-Fi de votre PC (ex. `http://192.168.1.15:3847`).
* Dès que vous aurez votre VPS en ligne, remplacez par l'adresse de votre serveur (ex. `https://vpn.votredomaine.com`).

---

## Étape 2 : Envoyer le projet sur un dépôt GitHub (Gratuit)

1. Créez un compte gratuit sur [https://github.com](https://github.com) et cliquez sur **New repository** (ex. `sxb-vpn-platform`, cochez **Private** pour garder votre code privé).
2. Ouvrez PowerShell dans le dossier `C:\Users\odette\Desktop\free\sxb-platform` et lancez :
   ```powershell
   git init
   git add .
   git commit -m "Initialisation SXB VPN Platform"
   git branch -M main
   git remote add origin https://github.com/VOTRE_PSEUDO/sxb-vpn-platform.git
   git push -u origin main
   ```

---

## Étape 3 : Télécharger votre fichier `.APK`

1. Sur la page de votre dépôt GitHub, cliquez sur l'onglet **Actions** en haut.
2. Vous verrez le job **"Compiler APK Android SXB VPN (Cloud Gratuit)"** tourner (environ 1 minute 30).
3. Une fois terminé (coche verte ✅), cliquez dessus et descendez à la section **Artifacts**.
4. Cliquez sur **SXB-VPN-Android-APK** : vous téléchargez un fichier `.zip` contenant **`app-debug.apk`**, prêt à être installé sur n'importe quel téléphone Android !
