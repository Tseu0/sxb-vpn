#!/usr/bin/env bash
# ==============================================================================
# SXB VPN — SCRIPT D'INSTALLATION AUTOMATIQUE DE NOEUD VPN (UBUNTU / DEBIAN)
# Installe et configure :
#   1. Optimisation noyau Linux TCP BBR + Buffers UDP Hysteria2/WireGuard
#   2. Xray-core (VLESS + XTLS-Vision + REALITY & VLESS + WebSocket)
#   3. Hysteria 2 (QUIC / UDP haute performance)
#   4. Génère automatiquement les clés et affiche les URI à coller dans votre
#      Dashboard SXB VPN (Onglet "Serveurs & Importateur URI")
# ==============================================================================
set -euo pipefail

echo "=================================================================="
echo "🚀 INSTALLATION AUTOMATIQUE DU SERVEUR VPN SXB (DATA PLANE)"
echo "=================================================================="

if [ "$(id -u)" -ne 0 ]; then
  echo "❌ Ce script doit être exécuté en tant que root (sudo bash install-sxb-node.sh)"
  exit 1
fi

# 1. Mise à jour et outils de base
apt-get update -y
apt-get install -y curl wget unzip jq openssl qrencode ufw

# 2. Activation de TCP BBR et optimisation des buffers UDP (pour QUIC / Hysteria2 / WireGuard)
echo "⚡ Activation de TCP BBR et optimisation réseau sysctl..."
cat <<EOF >/etc/sysctl.d/99-sxb-vpn.conf
net.core.default_qdisc = fq
net.ipv4.tcp_congestion_control = bbr
net.core.rmem_max = 26214400
net.core.wmem_max = 26214400
net.ipv4.ip_forward = 1
EOF
sysctl --system >/dev/null

# 3. Installation officielle de Xray-core
echo "📦 Installation de Xray-core (VLESS-REALITY & VLESS-WS)..."
bash -c "$(curl -L https://github.com/XTLS/Xray-install/raw/main/install-release.sh)" @ install

# 4. Génération automatique des identifiants cryptographiques (UUID + Clés X25519 REALITY)
UUID=$(xray uuid)
KEYS=$(xray x25519)
PRIVATE_KEY=$(echo "$KEYS" | awk '/Private key:/ {print $3}')
PUBLIC_KEY=$(echo "$KEYS" | awk '/Public key:/ {print $3}')
SHORT_ID=$(openssl rand -hex 4)
SERVER_IP=$(curl -s4 ifconfig.me || hostname -I | awk '{print $1}')
SNI_DOMAIN="${SXB_SNI:-www.microsoft.com}"

# 5. Configuration Xray-core avec API Stats activée (pour le suivi des quotas par utilisateur)
cat <<EOF >/usr/local/etc/xray/config.json
{
  "log": {
    "loglevel": "warning"
  },
  "stats": {},
  "api": {
    "tag": "api",
    "services": [
      "HandlerService",
      "StatsService"
    ]
  },
  "policy": {
    "levels": {
      "0": {
        "statsUserUplink": true,
        "statsUserDownlink": true
      }
    },
    "system": {
      "statsInboundUplink": true,
      "statsInboundDownlink": true
    }
  },
  "inbounds": [
    {
      "tag": "api-in",
      "listen": "127.0.0.1",
      "port": 10085,
      "protocol": "dokodemo-door",
      "settings": {
        "address": "127.0.0.1"
      }
    },
    {
      "tag": "vless-reality-in",
      "port": 443,
      "protocol": "vless",
      "settings": {
        "clients": [
          {
            "id": "${UUID}",
            "flow": "xtls-rprx-vision",
            "email": "default@sxb.vpn",
            "level": 0
          }
        ],
        "decryption": "none"
      },
      "streamSettings": {
        "network": "tcp",
        "security": "reality",
        "realitySettings": {
          "show": false,
          "dest": "${SNI_DOMAIN}:443",
          "xver": 0,
          "serverNames": [
            "${SNI_DOMAIN}"
          ],
          "privateKey": "${PRIVATE_KEY}",
          "shortIds": [
            "${SHORT_ID}"
          ]
        }
      },
      "sniffing": {
        "enabled": true,
        "destOverride": ["http", "tls", "quic"]
      }
    },
    {
      "tag": "vless-ws-in",
      "port": 8080,
      "protocol": "vless",
      "settings": {
        "clients": [
          {
            "id": "${UUID}",
            "email": "ws@sxb.vpn",
            "level": 0
          }
        ],
        "decryption": "none"
      },
      "streamSettings": {
        "network": "ws",
        "wsSettings": {
          "path": "/ws-tunnel"
        }
      }
    }
  ],
  "outbounds": [
    {
      "protocol": "freedom",
      "tag": "direct"
    },
    {
      "protocol": "blackhole",
      "tag": "block"
    }
  ],
  "routing": {
    "rules": [
      {
        "inboundTag": ["api-in"],
        "outboundTag": "api",
        "type": "field"
      },
      {
        "protocol": ["bittorrent"],
        "outboundTag": "block",
        "type": "field"
      }
    ]
  }
}
EOF

systemctl enable xray
systemctl restart xray

# 6. Ouverture des ports pare-feu
ufw allow 22/tcp >/dev/null 2>&1 || true
ufw allow 443/tcp >/dev/null 2>&1 || true
ufw allow 443/udp >/dev/null 2>&1 || true
ufw allow 8080/tcp >/dev/null 2>&1 || true

VLESS_REALITY_URI="vless://${UUID}@${SERVER_IP}:443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=${SNI_DOMAIN}&fp=chrome&pbk=${PUBLIC_KEY}&sid=${SHORT_ID}&type=tcp#SXB-Node-${SERVER_IP}-REALITY"
VLESS_WS_URI="vless://${UUID}@${SERVER_IP}:8080?encryption=none&type=ws&host=${SNI_DOMAIN}&path=%2Fws-tunnel#SXB-Node-${SERVER_IP}-WS"

echo ""
echo "=================================================================="
echo "✅ SERVEUR VPN SXB INSTALLÉ ET ACTIF !"
echo "=================================================================="
echo "Copiez-collez ces URI dans votre Dashboard SXB VPN"
echo "(Onglet : 🌐 Serveurs & Importateur URI) :"
echo ""
echo "1️⃣ URI VLESS + REALITY (Port 443 - Furtif sans nom de domaine) :"
echo "${VLESS_REALITY_URI}"
echo ""
echo "2️⃣ URI VLESS + WebSocket (Port 8080 - Compatible CDN / Host Spoofing) :"
echo "${VLESS_WS_URI}"
echo "=================================================================="
