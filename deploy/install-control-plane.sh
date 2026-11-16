#!/usr/bin/env bash
# ==============================================================================
# TSEU VPN — DÉPLOIEMENT DU SERVEUR DE CONTRÔLE (CONTROL PLANE) SUR VPS UBUNTU/DEBIAN
# Usage : sudo bash install-control-plane.sh [DOMAINE_OPTIONNEL]
# ==============================================================================
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "Exécutez ce script en root : sudo bash install-control-plane.sh"
  exit 1
fi

DOMAIN="${1:-}"
APP_DIR="/opt/tseu-vpn"
DATA_DIR="/var/lib/tseu-vpn"

echo "1. Mise à jour et installation de Node.js 22 LTS + git..."
apt-get update -y
apt-get install -y curl git ca-certificates ufw
if ! command -v node >/dev/null 2>&1 || [ "$(node -v | sed 's/v\([0-9]*\).*/\1/')" -lt 22 ]; then
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
  apt-get install -y nodejs
fi

echo "2. Clonage / mise à jour du dépôt..."
if [ -d "$APP_DIR/.git" ]; then
  git -C "$APP_DIR" pull --ff-only
else
  git clone https://github.com/Tseu0/sxb-vpn.git "$APP_DIR"
fi

mkdir -p "$DATA_DIR"

echo "3. Création du service systemd tseu-vpn..."
cat <<EOF >/etc/systemd/system/tseu-vpn.service
[Unit]
Description=TSEU VPN Control Plane
After=network.target

[Service]
Type=simple
WorkingDirectory=$APP_DIR
ExecStart=/usr/bin/node $APP_DIR/server.mjs
Restart=always
RestartSec=3
Environment=PORT=3847
Environment=SXB_DB_PATH=$DATA_DIR/sxb.db

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now tseu-vpn

if [ -n "$DOMAIN" ]; then
  echo "4. Configuration HTTPS automatique avec Caddy pour $DOMAIN..."
  apt-get install -y debian-keyring debian-archive-keyring apt-transport-https
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | tee /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -y && apt-get install -y caddy
  cat <<EOF >/etc/caddy/Caddyfile
$DOMAIN {
    reverse_proxy 127.0.0.1:3847
}
EOF
  systemctl reload caddy
  ufw allow 80/tcp || true
  ufw allow 443/tcp || true
  echo "✅ Control Plane en ligne sur : https://$DOMAIN"
else
  ufw allow 3847/tcp || true
  SERVER_IP=$(curl -s4 ifconfig.me || hostname -I | awk '{print $1}')
  echo "✅ Control Plane en ligne sur : http://$SERVER_IP:3847"
fi
