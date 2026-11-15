import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { DatabaseSync } from 'node:sqlite';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const PORT = process.env.PORT || 3847;
const DB_PATH = path.join(__dirname, 'sxb.db');

// Clé maîtresse serveur pour le chiffrement AES-256-GCM des profils envoyés aux clients mobiles
const MASTER_SECRET = process.env.SXB_MASTER_SECRET || 'sxb-ultra-secret-master-key-2026-aes256gcm';

const db = new DatabaseSync(DB_PATH);

// Initialisation du schéma SQLite (compatible PostgreSQL en production)
db.exec(`
  PRAGMA journal_mode = WAL;

  CREATE TABLE IF NOT EXISTS plans (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    quota_mb INTEGER NOT NULL,
    duration_days INTEGER NOT NULL,
    max_devices INTEGER NOT NULL DEFAULT 1,
    price_cfa INTEGER NOT NULL DEFAULT 1000,
    created_at TEXT NOT NULL
  );

  CREATE TABLE IF NOT EXISTS resellers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    contact TEXT NOT NULL,
    credits INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL
  );

  CREATE TABLE IF NOT EXISTS coupons (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code TEXT NOT NULL UNIQUE,
    plan_id INTEGER NOT NULL,
    reseller_id INTEGER,
    status TEXT NOT NULL DEFAULT 'available', -- available, active, depleted, expired, revoked
    used_up_bytes INTEGER NOT NULL DEFAULT 0,
    used_down_bytes INTEGER NOT NULL DEFAULT 0,
    quota_bytes INTEGER NOT NULL,
    duration_days INTEGER NOT NULL,
    max_devices INTEGER NOT NULL DEFAULT 1,
    activated_at TEXT,
    expires_at TEXT,
    created_at TEXT NOT NULL,
    FOREIGN KEY (plan_id) REFERENCES plans(id)
  );

  CREATE TABLE IF NOT EXISTS devices (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    coupon_id INTEGER NOT NULL,
    hwid TEXT NOT NULL,
    device_model TEXT NOT NULL,
    android_version TEXT NOT NULL,
    last_ip TEXT,
    last_seen_at TEXT NOT NULL,
    UNIQUE(coupon_id, hwid),
    FOREIGN KEY (coupon_id) REFERENCES coupons(id)
  );

  CREATE TABLE IF NOT EXISTS profiles (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    protocol TEXT NOT NULL, -- vless-reality, vless-ws, hysteria2, wireguard, ssh-dnstt, trojan, shadowsocks
    server_host TEXT NOT NULL,
    server_port INTEGER NOT NULL,
    sni_host TEXT,
    raw_config TEXT NOT NULL,
    priority INTEGER NOT NULL DEFAULT 1,
    is_active INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL
  );

  CREATE TABLE IF NOT EXISTS audit_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_type TEXT NOT NULL,
    actor TEXT NOT NULL,
    details TEXT NOT NULL,
    ip_address TEXT,
    created_at TEXT NOT NULL
  );
`);

function logAudit(eventType, actor, details, ip = '127.0.0.1') {
  const stmt = db.prepare(`
    INSERT INTO audit_logs (event_type, actor, details, ip_address, created_at)
    VALUES (?, ?, ?, ?, ?)
  `);
  stmt.run(eventType, actor, details, ip, new Date().toISOString());
}

// Données initiales de démonstration si la base est vide
const planCount = db.prepare('SELECT COUNT(*) as c FROM plans').get().c;
if (planCount === 0) {
  const now = new Date().toISOString();
  const insertPlan = db.prepare(`
    INSERT INTO plans (name, quota_mb, duration_days, max_devices, price_cfa, created_at)
    VALUES (?, ?, ?, ?, ?, ?)
  `);
  insertPlan.run('Pass Journalier 1 Go', 1024, 1, 1, 200, now);
  insertPlan.run('Pass Semaine 10 Go', 10240, 7, 1, 1000, now);
  insertPlan.run('Pass Mensuel Pro 50 Go', 51200, 30, 2, 3000, now);
  insertPlan.run('Pass Illimité VIP (200 Go)', 204800, 30, 3, 5000, now);

  const insertReseller = db.prepare(`
    INSERT INTO resellers (name, contact, credits, created_at)
    VALUES (?, ?, ?, ?)
  `);
  insertReseller.run('Tonny Telecom (Officiel)', '@TonnyTelecom', 50000, now);
  insertReseller.run('Kiosk Express Douala', '+237 690 00 00 00', 15000, now);

  const insertProfile = db.prepare(`
    INSERT INTO profiles (name, protocol, server_host, server_port, sni_host, raw_config, priority, is_active, created_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)
  `);
  insertProfile.run(
    'FR-Paris #1 (VLESS + REALITY Furtif)',
    'vless-reality',
    '185.212.44.10',
    443,
    'www.microsoft.com',
    'vless://9a7b3301-88aa-4c51-b9e2-001122334455@185.212.44.10:443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.microsoft.com&fp=chrome&pbk=mK9xZ8pL2qW1vB4nM7jH5gF3dS0aQ6wE8rT1yU3iO5p#FR-Paris-REALITY',
    1,
    now
  );
  insertProfile.run(
    'DE-Francfort #2 (VLESS + WebSocket + CDN)',
    'vless-ws',
    'cdn-edge.afrihall.example',
    443,
    'zero-rate.operator.example',
    'vless://9a7b3301-88aa-4c51-b9e2-001122334455@104.21.55.12:443?type=ws&security=tls&host=cdn-edge.afrihall.example&sni=zero-rate.operator.example&path=%2Fws-tunnel&fp=chrome#DE-Frankfurt-WS',
    2,
    now
  );
  insertProfile.run(
    'NL-Amsterdam #3 (Hysteria 2 QUIC Turbo)',
    'hysteria2',
    'nl-quic.sxb-node.example',
    8443,
    'www.bing.com',
    'hy2://sxb_pass_2026@nl-quic.sxb-node.example:8443?insecure=0&sni=www.bing.com&obfs=salamander&obfs-password=sxb_obfs_key#NL-Hysteria2',
    3,
    now
  );
  insertProfile.run(
    'Secours Ultime (SlowDNS / DNSTT UDP 53)',
    'ssh-dnstt',
    'ns1.tunnel.sxb-node.example',
    53,
    'ns1.tunnel.sxb-node.example',
    JSON.stringify({
      type: 'dnstt',
      ns_domain: 't.sxb-node.example',
      Resolver: '1.1.1.1:53',
      pubkey: '7f8e9d0c1b2a3f4e5d6c7b8a9f0e1d2c3b4a5f6e7d8c9b0a1f2e3d4c5b6a7f8e',
      ssh_user: 'sxb_tunnel',
      local_port: 1080
    }),
    4,
    now
  );

  // Génération de 3 coupons de démonstration prêts à tester
  const insertCoupon = db.prepare(`
    INSERT INTO coupons (code, plan_id, reseller_id, status, quota_bytes, duration_days, max_devices, created_at)
    VALUES (?, ?, ?, 'available', ?, ?, ?, ?)
  `);
  insertCoupon.run('SXB-DEMO-2026', 2, 1, 10240 * 1024 * 1024, 7, 1, now);
  insertCoupon.run('SXB-TEST-1G00', 1, 1, 1024 * 1024 * 1024, 1, 1, now);
  insertCoupon.run('SXB-VIP9-9999', 3, 1, 51200 * 1024 * 1024, 30, 2, now);

  logAudit('INIT_SYSTEM', 'SYSTEM', 'Base de données TSEU VPN initialisée avec les profils multi-protocoles et coupons de démo.');
}

// Chiffrement AES-256-GCM des profils pour l'application Android (Boîte noire)
function encryptProfilesPayload(payloadObj, hwid) {
  const key = crypto.scryptSync(MASTER_SECRET, `sxb-salt-${hwid}`, 32);
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
  const plaintext = JSON.stringify(payloadObj);
  const encrypted = Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final()]);
  const authTag = cipher.getAuthTag();
  return {
    algorithm: 'AES-256-GCM',
    keystore_alias: 'sxb_keystore_master_v1',
    iv_base64: iv.toString('base64'),
    tag_base64: authTag.toString('base64'),
    ciphertext_base64: encrypted.toString('base64'),
    // Pour le simulateur intégré au Dashboard, on renvoie aussi un aperçu décodé côté démo
    _simulator_preview: payloadObj
  };
}

// Parseur intelligent d'URI V2Ray / Xray / Hysteria2 / WireGuard / DNSTT
function parseVpnUri(rawInput) {
  const text = rawInput.trim();
  try {
    if (text.startsWith('vless://') || text.startsWith('trojan://') || text.startsWith('hy2://') || text.startsWith('hysteria2://') || text.startsWith('tuic://') || text.startsWith('ss://')) {
      const url = new URL(text);
      const protoScheme = url.protocol.replace(':', '');
      const security = url.searchParams.get('security') || '';
      const type = url.searchParams.get('type') || '';
      let protocol = protoScheme;
      if (protoScheme === 'vless' && security === 'reality') protocol = 'vless-reality';
      else if (protoScheme === 'vless' && type === 'ws') protocol = 'vless-ws';
      else if (protoScheme === 'hy2') protocol = 'hysteria2';
      const sni = url.searchParams.get('sni') || url.searchParams.get('host') || url.hostname;
      const name = decodeURIComponent(url.hash.replace('#', '')) || `${protocol.toUpperCase()} (${url.hostname})`;
      return {
        name,
        protocol,
        server_host: url.hostname,
        server_port: Number(url.port) || 443,
        sni_host: sni,
        raw_config: text
      };
    }
    if (text.startsWith('vmess://')) {
      const b64 = text.slice(8);
      const decoded = JSON.parse(Buffer.from(b64, 'base64').toString('utf8'));
      return {
        name: decoded.ps || `VMess (${decoded.add})`,
        protocol: 'vmess-ws',
        server_host: decoded.add || 'unknown',
        server_port: Number(decoded.port) || 443,
        sni_host: decoded.sni || decoded.host || decoded.add,
        raw_config: text
      };
    }
    if (text.includes('[Interface]') && text.includes('[Peer]')) {
      const endpointMatch = text.match(/Endpoint\s*=\s*([^\s:]+):(\d+)/i);
      return {
        name: 'WireGuard / AmneziaWG Tunnel',
        protocol: 'wireguard',
        server_host: endpointMatch ? endpointMatch[1] : 'wg.sxb-node.example',
        server_port: endpointMatch ? Number(endpointMatch[2]) : 51820,
        sni_host: 'WireGuard-UDP',
        raw_config: text
      };
    }
  } catch {
    // Fallback générique si l'URI est personnalisée
  }
  return null;
}

function generateCouponCode() {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  const seg = () => Array.from({ length: 4 }, () => chars[crypto.randomInt(0, chars.length)]).join('');
  return `TSEU-${seg()}-${seg()}`;
}

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', chunk => {
      body += chunk;
      if (body.length > 1e6) req.destroy();
    });
    req.on('end', () => {
      if (!body) return resolve({});
      try {
        resolve(JSON.parse(body));
      } catch (e) {
        reject(e);
      }
    });
    req.on('error', reject);
  });
}

function sendJson(res, statusCode, data) {
  res.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type'
  });
  res.end(JSON.stringify(data));
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type'
    });
    return res.end();
  }

  const parsedUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const pathname = parsedUrl.pathname;
  const clientIp = req.socket.remoteAddress || '127.0.0.1';

  try {
    // 1. STATISTIQUES & ÉTAT GLOBAL DU DASHBOARD
    if (req.method === 'GET' && pathname === '/api/admin/overview') {
      const plans = db.prepare('SELECT * FROM plans ORDER BY quota_mb ASC').all();
      const resellers = db.prepare('SELECT * FROM resellers ORDER BY id DESC').all();
      const profiles = db.prepare('SELECT * FROM profiles ORDER BY priority ASC, id DESC').all();
      const coupons = db.prepare(`
        SELECT c.*, p.name as plan_name, r.name as reseller_name,
               (SELECT COUNT(*) FROM devices d WHERE d.coupon_id = c.id) as bound_devices
        FROM coupons c
        LEFT JOIN plans p ON c.plan_id = p.id
        LEFT JOIN resellers r ON c.reseller_id = r.id
        ORDER BY c.id DESC
      `).all();
      const devices = db.prepare(`
        SELECT d.*, c.code as coupon_code
        FROM devices d
        JOIN coupons c ON d.coupon_id = c.id
        ORDER BY d.last_seen_at DESC
      `).all();
      const logs = db.prepare('SELECT * FROM audit_logs ORDER BY id DESC LIMIT 40').all();

      const totalTrafficBytes = coupons.reduce((acc, c) => acc + c.used_up_bytes + c.used_down_bytes, 0);
      const activeCoupons = coupons.filter(c => c.status === 'active').length;

      return sendJson(res, 200, {
        stats: {
          total_coupons: coupons.length,
          active_coupons: activeCoupons,
          active_profiles: profiles.filter(p => p.is_active).length,
          bound_devices: devices.length,
          total_traffic_bytes: totalTrafficBytes
        },
        plans,
        resellers,
        profiles,
        coupons,
        devices,
        logs
      });
    }

    // 2. CRÉATION D'UN FORFAIT (PLAN)
    if (req.method === 'POST' && pathname === '/api/admin/plans') {
      const body = await readJsonBody(req);
      const name = (body.name || 'Nouveau Forfait').trim();
      const quotaMb = Math.max(10, Number(body.quota_mb) || 1024);
      const durationDays = Math.max(1, Number(body.duration_days) || 7);
      const maxDevices = Math.max(1, Number(body.max_devices) || 1);
      const priceCfa = Math.max(0, Number(body.price_cfa) || 500);

      const stmt = db.prepare(`
        INSERT INTO plans (name, quota_mb, duration_days, max_devices, price_cfa, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
      `);
      stmt.run(name, quotaMb, durationDays, maxDevices, priceCfa, new Date().toISOString());
      logAudit('CREATE_PLAN', 'ADMIN', `Forfait créé : ${name} (${quotaMb} Mo / ${durationDays}j)`, clientIp);
      return sendJson(res, 201, { ok: true });
    }

    // 3. GÉNÉRATION DE COUPONS EN LOT OU À L'UNITÉ
    if (req.method === 'POST' && pathname === '/api/admin/coupons/generate') {
      const body = await readJsonBody(req);
      const planId = Number(body.plan_id);
      const count = Math.min(50, Math.max(1, Number(body.count) || 1));
      const resellerId = body.reseller_id ? Number(body.reseller_id) : null;

      const plan = db.prepare('SELECT * FROM plans WHERE id = ?').get(planId);
      if (!plan) return sendJson(res, 404, { error: 'Forfait introuvable' });

      const quotaBytes = plan.quota_mb * 1024 * 1024;
      const now = new Date().toISOString();
      const insert = db.prepare(`
        INSERT INTO coupons (code, plan_id, reseller_id, status, quota_bytes, duration_days, max_devices, created_at)
        VALUES (?, ?, ?, 'available', ?, ?, ?, ?)
      `);

      const generated = [];
      for (let i = 0; i < count; i++) {
        const code = generateCouponCode();
        insert.run(code, plan.id, resellerId, quotaBytes, plan.duration_days, plan.max_devices, now);
        generated.push(code);
      }

      logAudit('GENERATE_COUPONS', 'ADMIN', `${count} coupon(s) généré(s) pour "${plan.name}" : ${generated.join(', ')}`, clientIp);
      return sendJson(res, 201, { ok: true, codes: generated });
    }

    // 4. RÉVOCATION / RÉINITIALISATION D'UN COUPON
    if (req.method === 'POST' && pathname === '/api/admin/coupons/action') {
      const body = await readJsonBody(req);
      const couponId = Number(body.coupon_id);
      const action = body.action;
      const coupon = db.prepare('SELECT * FROM coupons WHERE id = ?').get(couponId);
      if (!coupon) return sendJson(res, 404, { error: 'Coupon introuvable' });

      if (action === 'revoke') {
        db.prepare(`UPDATE coupons SET status = 'revoked' WHERE id = ?`).run(couponId);
        logAudit('KILL_SWITCH_MANUAL', 'ADMIN', `Révocation immédiate du coupon ${coupon.code}`, clientIp);
      } else if (action === 'reset') {
        db.prepare(`
          UPDATE coupons
          SET status = 'available', used_up_bytes = 0, used_down_bytes = 0, activated_at = NULL, expires_at = NULL
          WHERE id = ?
        `).run(couponId);
        db.prepare(`DELETE FROM devices WHERE coupon_id = ?`).run(couponId);
        logAudit('RESET_COUPON', 'ADMIN', `Remise à zéro du coupon ${coupon.code} et suppression des verrous HWID`, clientIp);
      } else if (action === 'delete') {
        db.prepare(`DELETE FROM devices WHERE coupon_id = ?`).run(couponId);
        db.prepare(`DELETE FROM coupons WHERE id = ?`).run(couponId);
        logAudit('DELETE_COUPON', 'ADMIN', `Suppression du coupon ${coupon.code}`, clientIp);
      }
      return sendJson(res, 200, { ok: true });
    }

    // 5. IMPORTATION ATOMIQUE D'UNE CONFIGURATION VPN (URI V2Ray / Xray / Hysteria2 / WireGuard / Manuel)
    if (req.method === 'POST' && pathname === '/api/admin/profiles') {
      const body = await readJsonBody(req);
      const now = new Date().toISOString();

      let profileData = null;
      if (body.uri_import && body.uri_import.trim()) {
        profileData = parseVpnUri(body.uri_import);
        if (!profileData) {
          return sendJson(res, 400, {
            error: 'Format URI non reconnu. Collez une URI vless://, vmess://, trojan://, ss://, hy2://, tuic:// ou une configuration [Interface]/[Peer] WireGuard.'
          });
        }
      } else {
        profileData = {
          name: (body.name || 'Noeud VPN').trim(),
          protocol: body.protocol || 'vless-reality',
          server_host: (body.server_host || '127.0.0.1').trim(),
          server_port: Number(body.server_port) || 443,
          sni_host: (body.sni_host || '').trim(),
          raw_config: (body.raw_config || '').trim() || `vless://uuid@${body.server_host}:443?security=reality&sni=${body.sni_host}#${encodeURIComponent(body.name || 'Node')}`
        };
      }

      const stmt = db.prepare(`
        INSERT INTO profiles (name, protocol, server_host, server_port, sni_host, raw_config, priority, is_active, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)
      `);
      stmt.run(
        profileData.name,
        profileData.protocol,
        profileData.server_host,
        profileData.server_port,
        profileData.sni_host,
        profileData.raw_config,
        Number(body.priority) || 1,
        now
      );
      logAudit('IMPORT_PROFILE', 'ADMIN', `Profil ajouté : ${profileData.name} [${profileData.protocol}] -> ${profileData.server_host}:${profileData.server_port}`, clientIp);
      return sendJson(res, 201, { ok: true, profile: profileData });
    }

    if (req.method === 'DELETE' && pathname.startsWith('/api/admin/profiles/')) {
      const id = Number(pathname.split('/').pop());
      db.prepare('DELETE FROM profiles WHERE id = ?').run(id);
      logAudit('DELETE_PROFILE', 'ADMIN', `Profil #${id} supprimé`, clientIp);
      return sendJson(res, 200, { ok: true });
    }

    // 6. API CLIENT MOBILE : ACTIVATION PAR COUPON + VERROUILLAGE HWID + CHIFFREMENT AES-256-GCM
    if (req.method === 'POST' && pathname === '/api/v1/mobile/activate') {
      const body = await readJsonBody(req);
      const code = (body.code || '').trim().toUpperCase();
      const hwid = (body.hwid || 'ANDROID-HWID-DEFAULT').trim();
      const deviceModel = (body.device_model || 'Android Device').trim();
      const androidVersion = (body.android_version || 'Android 14').trim();

      const coupon = db.prepare(`
        SELECT c.*, p.name as plan_name
        FROM coupons c
        LEFT JOIN plans p ON c.plan_id = p.id
        WHERE c.code = ?
      `).get(code);

      if (!coupon) {
        logAudit('AUTH_FAILED', hwid, `Code d'accès invalide : ${code}`, clientIp);
        return sendJson(res, 404, { error: 'Code d\'accès introuvable ou invalide.' });
      }

      if (coupon.status === 'revoked') {
        return sendJson(res, 403, { error: 'Ce code d\'accès a été révoqué par l\'administrateur.' });
      }
      if (coupon.status === 'depleted' || (coupon.used_up_bytes + coupon.used_down_bytes) >= coupon.quota_bytes) {
        return sendJson(res, 403, { error: 'Quota de données épuisé (100 % utilisé).' });
      }

      // Vérification du verrouillage matériel (HWID Binding)
      const existingDevices = db.prepare('SELECT * FROM devices WHERE coupon_id = ?').all(coupon.id);
      const isKnownDevice = existingDevices.some(d => d.hwid === hwid);

      if (!isKnownDevice && existingDevices.length >= coupon.max_devices) {
        logAudit('HWID_REJECTED', hwid, `Dépassement du nombre d'appareils (${existingDevices.length}/${coupon.max_devices}) sur le code ${code}`, clientIp);
        return sendJson(res, 403, {
          error: `Ce code est déjà verrouillé sur le nombre maximal d'appareils autorisés (${coupon.max_devices}).`
        });
      }

      const now = new Date();
      let activatedAt = coupon.activated_at;
      let expiresAt = coupon.expires_at;

      if (coupon.status === 'available' || !activatedAt) {
        activatedAt = now.toISOString();
        const exp = new Date(now.getTime() + coupon.duration_days * 86400 * 1000);
        expiresAt = exp.toISOString();
        db.prepare(`
          UPDATE coupons
          SET status = 'active', activated_at = ?, expires_at = ?
          WHERE id = ?
        `).run(activatedAt, expiresAt, coupon.id);
      } else if (new Date(expiresAt) <= now) {
        db.prepare(`UPDATE coupons SET status = 'expired' WHERE id = ?`).run(coupon.id);
        return sendJson(res, 403, { error: 'Ce forfait a expiré.' });
      }

      // Enregistrement ou mise à jour de l'appareil
      db.prepare(`
        INSERT INTO devices (coupon_id, hwid, device_model, android_version, last_ip, last_seen_at)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(coupon_id, hwid) DO UPDATE SET
          device_model = excluded.device_model,
          last_ip = excluded.last_ip,
          last_seen_at = excluded.last_seen_at
      `).run(coupon.id, hwid, deviceModel, androidVersion, clientIp, now.toISOString());

      // Récupération des profils actifs (jusqu'à 20 configurations comme TSEU VPN)
      const activeProfiles = db.prepare(`
        SELECT id, name, protocol, server_host, server_port, sni_host, raw_config, priority
        FROM profiles
        WHERE is_active = 1
        ORDER BY priority ASC
        LIMIT 20
      `).all();

      const encryptedBundle = encryptProfilesPayload({
        issued_at: now.toISOString(),
        bound_hwid: hwid,
        coupon_code: coupon.code,
        profiles: activeProfiles
      }, hwid);

      logAudit('COUPON_ACTIVATED', hwid, `Activation réussie du code ${code} sur ${deviceModel} (${activeProfiles.length} profils chiffrés en AES-256-GCM)`, clientIp);

      return sendJson(res, 200, {
        ok: true,
        session: {
          code: coupon.code,
          plan_name: coupon.plan_name,
          status: 'active',
          quota_bytes: coupon.quota_bytes,
          used_bytes: coupon.used_up_bytes + coupon.used_down_bytes,
          remaining_bytes: Math.max(0, coupon.quota_bytes - (coupon.used_up_bytes + coupon.used_down_bytes)),
          activated_at: activatedAt,
          expires_at: expiresAt,
          bound_hwid: hwid,
          max_devices: coupon.max_devices
        },
        encrypted_profiles_bundle: encryptedBundle
      });
    }

    // 7. API CLIENT MOBILE : SYNCHRONISATION DES QUOTAS EN TEMPS RÉEL & KILL-SWITCH
    if (req.method === 'POST' && pathname === '/api/v1/mobile/sync-traffic') {
      const body = await readJsonBody(req);
      const code = (body.code || '').trim().toUpperCase();
      const hwid = (body.hwid || 'ANDROID-HWID-DEFAULT').trim();
      const deltaUp = Math.max(0, Number(body.delta_up_bytes) || 0);
      const deltaDown = Math.max(0, Number(body.delta_down_bytes) || 0);

      const coupon = db.prepare(`
        SELECT c.*, p.name as plan_name
        FROM coupons c
        LEFT JOIN plans p ON c.plan_id = p.id
        WHERE c.code = ?
      `).get(code);

      if (!coupon) return sendJson(res, 404, { error: 'Coupon introuvable', kill_switch: true });

      if (coupon.status !== 'active') {
        return sendJson(res, 200, {
          ok: false,
          kill_switch: true,
          reason: `Session interrompue (statut : ${coupon.status})`,
          status: coupon.status
        });
      }

      const newUp = coupon.used_up_bytes + deltaUp;
      const newDown = coupon.used_down_bytes + deltaDown;
      const totalUsed = newUp + newDown;
      const now = new Date();
      const isExpired = coupon.expires_at && new Date(coupon.expires_at) <= now;
      const isDepleted = totalUsed >= coupon.quota_bytes;

      let newStatus = 'active';
      let killSwitch = false;
      let reason = null;

      if (isDepleted) {
        newStatus = 'depleted';
        killSwitch = true;
        reason = 'QUOTA_DEPLETED : 100 % du volume de données a été consommé. Tunnel coupé automatiquement.';
        logAudit('KILL_SWITCH_QUOTA', hwid, `Quota épuisé sur ${code} (${(totalUsed / (1024 * 1024)).toFixed(1)} Mo consommés) -> Révocation immédiate du tunnel.`, clientIp);
      } else if (isExpired) {
        newStatus = 'expired';
        killSwitch = true;
        reason = 'PLAN_EXPIRED : La date d\'expiration du forfait est atteinte.';
        logAudit('KILL_SWITCH_EXPIRY', hwid, `Expiration atteinte sur ${code} -> Révocation immédiate du tunnel.`, clientIp);
      }

      db.prepare(`
        UPDATE coupons
        SET used_up_bytes = ?, used_down_bytes = ?, status = ?
        WHERE id = ?
      `).run(newUp, newDown, newStatus, coupon.id);

      db.prepare(`
        UPDATE devices SET last_seen_at = ?, last_ip = ?
        WHERE coupon_id = ? AND hwid = ?
      `).run(now.toISOString(), clientIp, coupon.id, hwid);

      return sendJson(res, 200, {
        ok: !killSwitch,
        kill_switch: killSwitch,
        reason,
        session: {
          code: coupon.code,
          plan_name: coupon.plan_name,
          status: newStatus,
          quota_bytes: coupon.quota_bytes,
          used_bytes: totalUsed,
          remaining_bytes: Math.max(0, coupon.quota_bytes - totalUsed),
          expires_at: coupon.expires_at
        }
      });
    }

    // 8. PAGES RÉGLEMENTAIRES GOOGLE PLAY (Privacy Policy & Data Deletion)
    if (req.method === 'GET' && pathname === '/api/public/privacy') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      return res.end(`<!doctype html><html lang="fr"><head><meta charset="utf-8"><title>Politique de Confidentialité — TSEU VPN</title>
      <style>body{font-family:system-ui,sans-serif;max-width:740px;margin:2rem auto;padding:0 1rem;line-height:1.6;background:#0f172a;color:#f1f5f9}section{background:#1e293b;padding:1.25rem;border-radius:8px;margin-bottom:1rem;border:1px solid #334155}h1,h2{color:#06b6d4}</style></head>
      <body><h1>Politique de Confidentialité (Conforme Google Play VpnService)</h1>
      <section><h2>1. Service VPN et données collectées</h2><p>L'application utilise exclusivement l'API <code>android.net.VpnService</code> pour établir un tunnel chiffré vers le serveur choisi. Seuls le code d'activation, un identifiant matériel pseudonymisé (HWID) et les compteurs de volume montant/descendant sont traités pour gérer votre forfait.</p></section>
      <section><h2>2. Chiffrement local</h2><p>Les configurations importées sont chiffrées localement sur votre appareil via <strong>AES-256-GCM</strong> avec une clé protégée par l'Android Keystore.</p></section>
      </body></html>`);
    }

    // 9. SERVEUR DE FICHIERS STATIQUES (Dashboard Web)
    if (req.method === 'GET' && (pathname === '/' || pathname === '/index.html')) {
      const htmlPath = path.join(__dirname, 'public', 'index.html');
      const content = fs.readFileSync(htmlPath, 'utf8');
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      return res.end(content);
    }

    sendJson(res, 404, { error: 'Route introuvable' });
  } catch (err) {
    sendJson(res, 500, { error: err.message || 'Erreur interne du serveur' });
  }
});

server.listen(PORT, () => {
  console.log(`\n=============================================================`);
  console.log(`🚀 TSEU VPN PLATFORM (CONTROL PLANE + SIMULATEUR) EN LIGNE !`);
  console.log(`👉 Dashboard Admin & Simulateur : http://localhost:${PORT}`);
  console.log(`👉 Politique Confidentialité    : http://localhost:${PORT}/api/public/privacy`);
  console.log(`=============================================================\n`);
});

