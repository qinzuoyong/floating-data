'use strict';
/**
 * BatteryFloating 家人位置共享 - 信令中继服务（含家庭创建与加入审核）
 *
 * 协议（JSON，房间为 6 位家庭码）：
 *   -> room-check     { type:'room-check', room }                查询家庭码是否被占用
 *   <- room-check-res { type:'room-check-res', room, exists, ownerName }
 *   -> register       { type:'register', room, uid, name }       注册（首个注册者=创建人直接进房；其他新成员进 pending 待审核）
 *   <- join-pending   { type:'join-pending', room }              加入者：等待创建人审核
 *   <- join-request   { type:'join-request', uid, name }         通知创建人：有新的加入申请
 *   -> join-approve   { type:'join-approve', uid }               创建人批准加入
 *   -> join-reject    { type:'join-reject', uid }                创建人拒绝加入
 *   <- join-rejected  { type:'join-rejected', room }             加入者：申请被拒绝
 *   <- registered     { type:'registered', uid, room, peers[], roster[] }  注册回执（peers=同房在线成员；roster=全量名册含离线成员；创建人直接收到；加入者批准后收到）
 *   => presence       { type:'presence', uid, name, online }     上下线广播（全房间）
 *   -> loc-req        { type:'loc-req', to }                     请求位置（转发）
 *   -> loc-res        { type:'loc-res', to, payload }            位置应答（转发）
 *   -> ping / <- pong                                            心跳
 *   <- error          { type:'error', code, message }
 */
const WebSocket = require('ws');
const fs = require('fs');

// 监听端口与状态文件路径允许经环境变量覆盖（仅供本机测试注入，默认值即生产值）
const PORT = Number(process.env.FAMILY_SIGNAL_PORT || 8088);
const STATE_FILE = process.env.FAMILY_SIGNAL_STATE_FILE || '/opt/family-signal/rooms.json';
/** 房间码结构约束（4-16 位字母数字，兼容历史房间；不通过即拒绝注册/查询） */
const ROOM_PATTERN = /^[A-Za-z0-9_-]{4,16}$/;
/** 新建房间数量上限：防止外部批量注册把内存与状态文件刷爆 */
const MAX_ROOMS = 5000;
/** 成员显示名长度上限（客户端 16 字限制可被绕过，服务端必须独立约束） */
const MAX_NAME_LEN = 32;
const wss = new WebSocket.Server({ port: PORT, host: '0.0.0.0' });

/** room -> { owner: uid, members: Map(uid->{ws,name,isAlive}), pending: Map(uid->{ws,name}), approved: Map(uid->name) } */
const rooms = new Map();

/**
 * 简易内存限流：key → { count, resetAt }。
 * 用于阻断家庭码枚举（room-check）、批量注册（register）与高频位置请求（loc-req）：
 * 这三者分别对应"猜房间"、"刷房间"、"刷定位"三种滥用路径。
 */
const rateBuckets = new Map();
function rateLimited(key, limit, windowMs) {
  const now = Date.now();
  const bucket = rateBuckets.get(key);
  if (!bucket || now >= bucket.resetAt) {
    rateBuckets.set(key, { count: 1, resetAt: now + windowMs });
    if (rateBuckets.size > 8192) {
      for (const [k, v] of rateBuckets) { if (now >= v.resetAt) rateBuckets.delete(k); }
    }
    return false;
  }
  bucket.count++;
  return bucket.count > limit;
}

/**
 * 位置载荷白名单校验（服务端侧第二道防线）：
 * 客户端已做校验，但中继前的规范化可避免畸形 payload 被转发给其他成员的 App。
 */
function sanitizeLocationPayload(payload) {
  if (!payload || typeof payload !== 'object') return null;
  const lat = Number(payload.lat);
  const lng = Number(payload.lng);
  const ts = Number(payload.ts);
  const accuracy = Number(payload.accuracy);
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return null;
  if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return null;
  if (lat === 0 && lng === 0) return null;
  return {
    lat,
    lng,
    ts: Number.isFinite(ts) ? ts : Date.now(),
    accuracy: Number.isFinite(accuracy) && accuracy >= 0 ? accuracy : 0
  };
}

/** 清理显示名：去除控制字符并截断，防止超长/畸形文本进入广播与持久化 */
function sanitizeName(name, fallbackUid) {
  const cleaned = String(name || '')
    .replace(/[\u0000-\u001f\u007f]/g, '')
    .trim()
    .slice(0, MAX_NAME_LEN);
  return cleaned || fallbackUid;
}

// 房间关系（owner/approved 名册）持久化：服务器重启不丢失家庭
let saveTimer = null;
function saveRooms() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    const data = {};
    for (const [room, rs] of rooms) {
      data[room] = { owner: rs.owner, approved: Object.fromEntries(rs.approved) };
    }
    try {
      // 原子写：先写临时文件再 rename，避免写入过程中崩溃留下半截 JSON
      // （loadRooms 捕获异常后会把全部家庭关系静默丢弃，属不可接受的退化路径）
      const tmpFile = STATE_FILE + '.tmp';
      fs.writeFileSync(tmpFile, JSON.stringify(data));
      fs.renameSync(tmpFile, STATE_FILE);
    } catch (e) { /* ignore */ }
  }, 500);
}

function loadRooms() {
  try {
    const data = JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'));
    for (const [room, s] of Object.entries(data)) {
      // 兼容旧格式（approved 为 uid 数组，名字置空）
      const approved = new Map();
      if (Array.isArray(s.approved)) {
        for (const u of s.approved) approved.set(u, '');
      } else if (s.approved) {
        for (const [u, n] of Object.entries(s.approved)) approved.set(u, n);
      }
      rooms.set(room, {
        owner: s.owner,
        members: new Map(),
        pending: new Map(),
        approved
      });
    }
    console.log('family-signal loaded rooms: ' + Object.keys(data).length);
  } catch (e) { /* 首次启动无状态文件 */ }
}
loadRooms();

function send(ws, obj) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    try { ws.send(JSON.stringify(obj)); } catch (e) { /* ignore */ }
  }
}

function broadcast(room, obj, exceptUid) {
  const rs = rooms.get(room);
  if (!rs) return;
  for (const [uid, c] of rs.members) {
    if (uid !== exceptUid) send(c.ws, obj);
  }
}

// 家庭关系（owner/approved）持久保留：成员全部离线不清除房间，重连自动恢复。
// 但"只有 owner、从未有第二个 approved 成员、且长期无连接"的空房间会过期回收，
// 避免批量注册刷出大量僵尸房间把内存与状态文件撑爆。
const ROOM_IDLE_TTL_MS = 7 * 24 * 3600 * 1000; // 7 天无任何连接即回收
const roomLastSeen = new Map(); // room -> 最后活跃时间戳

function touchRoom(room) {
  roomLastSeen.set(room, Date.now());
  if (roomLastSeen.size > MAX_ROOMS * 2) {
    const cutoff = Date.now() - ROOM_IDLE_TTL_MS;
    for (const [r, t] of roomLastSeen) { if (t < cutoff) roomLastSeen.delete(r); }
  }
}

function cleanupRoom(room) {
  /* no-op：房间仅在持久化状态中保留；过期回收见 reapIdleRooms */
}

/** 回收长期无连接、且无第二个成员名册的空房间 */
function reapIdleRooms() {
  const cutoff = Date.now() - ROOM_IDLE_TTL_MS;
  let removed = 0;
  for (const [room, rs] of rooms) {
    if (rs.members.size > 0 || rs.pending.size > 0) continue;
    const last = roomLastSeen.get(room) || 0;
    if (last >= cutoff) continue;
    // 仅回收"只有创建人"的空房间，有多个 approved 成员的真实家庭永不回收
    if (rs.approved.size <= 1) {
      rooms.delete(room);
      roomLastSeen.delete(room);
      removed++;
    }
  }
  if (removed > 0) {
    console.log('family-signal reaped ' + removed + ' idle rooms');
    saveRooms();
  }
}

function leave(ws) {
  if (!ws.room || !ws.uid) return;
  const rs = rooms.get(ws.room);
  if (rs) {
    const m = rs.members.get(ws.uid);
    if (m && m.ws === ws) {
      rs.members.delete(ws.uid);
      broadcast(ws.room, { type: 'presence', uid: ws.uid, online: false });
    } else {
      const p = rs.pending.get(ws.uid);
      if (p && p.ws === ws) rs.pending.delete(ws.uid);
    }
    cleanupRoom(ws.room);
  }
  ws.room = null;
  ws.uid = null;
  ws.name = null;
}

function memberPeers(rs, exceptUid) {
  const peers = [];
  for (const [u, c] of rs.members) {
    if (u !== exceptUid) peers.push({ uid: u, name: c.name, online: true });
  }
  return peers;
}

/** 全量名册：approved 中全部成员（除自己），含离线成员；online 取运行时连接状态 */
function rosterOf(rs, exceptUid) {
  const list = [];
  for (const [u, name] of rs.approved) {
    if (u === exceptUid) continue;
    const m = rs.members.get(u);
    list.push({ uid: u, name: m ? m.name : name, online: !!m });
  }
  return list;
}

wss.on('connection', (ws, req) => {
  ws.isAlive = true;
  ws.room = null;
  ws.uid = null;
  ws.name = null;
  ws.remoteIp = (req && req.socket && req.socket.remoteAddress) || 'unknown';

  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', (data) => {
    let msg;
    try { msg = JSON.parse(data.toString('utf8')); } catch { return; }
    if (!msg || typeof msg.type !== 'string') return;

    switch (msg.type) {
      case 'room-check': {
        // 限流：家庭码空间有限，不限制即可被逐个枚举出全部在用房间
        if (rateLimited('rc:' + ws.remoteIp, 30, 60000)) {
          send(ws, { type: 'error', code: 'rate_limited', message: '查询过于频繁' });
          break;
        }
        const room = String(msg.room || '').trim();
        if (!ROOM_PATTERN.test(room)) {
          send(ws, { type: 'room-check-res', room, exists: false, ownerName: '' });
          break;
        }
        const rs = rooms.get(room);
        const ownerName = rs && rs.members.get(rs.owner) ? rs.members.get(rs.owner).name : '';
        send(ws, { type: 'room-check-res', room, exists: !!rs, ownerName });
        break;
      }

      case 'register': {
        // 限流阈值放宽到 60/分钟：家庭成员共用同一出口 IP，升级/重装后的正常重连
        // 不应被误判为批量注册（房间创建另受 MAX_ROOMS 约束）
        if (rateLimited('reg:' + ws.remoteIp, 60, 60000)) {
          send(ws, { type: 'error', code: 'rate_limited', message: '注册过于频繁' });
          return;
        }
        const room = String(msg.room || '').trim();
        const uid = String(msg.uid || '').trim();
        if (!ROOM_PATTERN.test(room) || !uid || uid.length > 64) {
          send(ws, { type: 'error', code: 'bad_register', message: 'room/uid 非法' });
          return;
        }
        const name = sanitizeName(msg.name, uid);
        if (ws.room && ws.room !== room) leave(ws);
        let rs = rooms.get(room);
        if (!rs) {
          if (rooms.size >= MAX_ROOMS) {
            send(ws, { type: 'error', code: 'server_full', message: '服务器房间数已达上限' });
            return;
          }
          // 房间不存在：首个注册者成为创建人，直接进房
          rs = { owner: uid, members: new Map(), pending: new Map(), approved: new Map() };
          rooms.set(room, rs);
          saveRooms();
        }
        touchRoom(room);
        const old = rs.members.get(uid);
        if (old && old.ws !== ws) { old.ws.terminate(); }
        // 加入审核停用（2026-09）：新成员直接进房，无需创建人批准；
        // 客户端审核 UI 仅在收到 join-pending/join-request 时显示，服务器不再下发即自动隐藏。
        // 恢复审核：删除下面条件中的 APPROVAL_DISABLED || 并取消 else 分支注释
        const APPROVAL_DISABLED = true;
        if (APPROVAL_DISABLED || rs.owner === uid || rs.members.has(uid) || rs.approved.has(uid)) {
          // 创建人或已批准成员：进房；名字刷新进名册（创建人也入名册）
          ws.room = room;
          ws.uid = uid;
          ws.name = name;
          rs.members.set(uid, { ws, name, isAlive: true });
          rs.approved.set(uid, name);
          saveRooms();
          send(ws, { type: 'registered', uid, room, peers: memberPeers(rs, uid), roster: rosterOf(rs, uid) });
          broadcast(room, { type: 'presence', uid, name, online: true }, uid);
          if (rs.owner === uid) {
            // 创建人上线：补发离线期间积压的加入申请（申请仅在到达时推送一次，离线即丢失）
            for (const [pendingUid, p] of rs.pending) {
              send(ws, { type: 'join-request', uid: pendingUid, name: p.name });
            }
          }
        } else {
          // 新成员：进 pending，等待创建人审核；审核通过前不接收 presence、不能请求位置
          ws.room = room;
          ws.uid = uid;
          ws.name = name;
          rs.pending.set(uid, { ws, name });
          send(ws, { type: 'join-pending', room });
          const ownerEntry = rs.members.get(rs.owner);
          if (ownerEntry) send(ownerEntry.ws, { type: 'join-request', uid, name });
        }
        break;
      }

      case 'join-approve': {
        const uid = String(msg.uid || '').trim();
        const rs = ws.room ? rooms.get(ws.room) : undefined;
        if (!rs || rs.owner !== ws.uid) { send(ws, { type: 'error', code: 'not_owner' }); break; }
        const p = rs.pending.get(uid);
        if (!p) break;
        rs.pending.delete(uid);
        const name = p.name;
        // 记录已批准成员：断线重连直接进房，无需重新审核；名字入名册
        rs.approved.set(uid, name);
        saveRooms();
        rs.members.set(uid, { ws: p.ws, name, isAlive: true });
        send(p.ws, { type: 'registered', uid, room: ws.room, peers: memberPeers(rs, uid), roster: rosterOf(rs, uid) });
        broadcast(ws.room, { type: 'presence', uid, name, online: true }, uid);
        break;
      }

      case 'join-reject': {
        const uid = String(msg.uid || '').trim();
        const rs = ws.room ? rooms.get(ws.room) : undefined;
        if (!rs || rs.owner !== ws.uid) { send(ws, { type: 'error', code: 'not_owner' }); break; }
        const p = rs.pending.get(uid);
        if (!p) break;
        rs.pending.delete(uid);
        send(p.ws, { type: 'join-rejected', room: ws.room });
        break;
      }

      case 'signal':
      case 'loc-req': {
        const to = String(msg.to || '').trim();
        if (!ws.uid || !ws.room) { send(ws, { type: 'error', code: 'not_registered' }); break; }
        // 限流：单成员高频请求会让对端 GNSS 持续采集（耗电），也属可被滥用的探测手段
        if (rateLimited('loc:' + ws.room + ':' + ws.uid, 10, 60000)) {
          send(ws, { type: 'error', code: 'rate_limited', message: '位置请求过于频繁' });
          break;
        }
        console.log('[' + new Date().toISOString() + '] ' + msg.type + ' from=' + ws.uid + ' room=' + ws.room + ' to=' + to);
        if (!to) { send(ws, { type: 'error', code: 'no_target' }); break; }
        const rs = ws.room ? rooms.get(ws.room) : undefined;
        const entry = rs ? rs.members.get(to) : undefined;
        if (!entry || entry.ws.readyState !== WebSocket.OPEN) {
          console.log('[' + new Date().toISOString() + ']   -> OFFLINE members=' + (rs ? Array.from(rs.members.keys()).join(',') : 'none'));
          send(ws, { type: 'error', code: 'offline', message: '目标离线' });
          break;
        }
        console.log('[' + new Date().toISOString() + ']   -> forwarded to ' + to);
        send(entry.ws, { type: msg.type, from: ws.uid, name: ws.name, to, payload: msg.payload || {} });
        break;
      }

      case 'loc-res': {
        const to = String(msg.to || '').trim();
        if (!ws.uid || !ws.room || !to) break;
        // 中继前规范化载荷：畸形/越界数据在服务端即被拦下，不再转发给其他成员的 App
        const payload = sanitizeLocationPayload(msg.payload);
        if (!payload) {
          console.log('[' + new Date().toISOString() + '] loc-res dropped (invalid payload) from=' + ws.uid);
          break;
        }
        const rs = ws.room ? rooms.get(ws.room) : undefined;
        const entry = rs ? rs.members.get(to) : undefined;
        if (entry && entry.ws.readyState === WebSocket.OPEN) {
          send(entry.ws, { type: 'loc-res', from: ws.uid, name: ws.name, to, payload });
        }
        break;
      }

      case 'ping': {
        send(ws, { type: 'pong' });
        break;
      }

      default:
        send(ws, { type: 'error', code: 'unknown_type', message: msg.type });
    }
  });

  ws.on('close', () => leave(ws));
  ws.on('error', () => { /* ignore */ });
});

// 心跳：30s ping，60s 内无 pong 判死（members 与 pending 都清理）
const heartbeat = setInterval(() => {
  for (const [room, rs] of rooms) {
    for (const [uid, c] of Array.from(rs.members.entries())) {
      if (!c.ws.isAlive) {
        c.ws.terminate();
        rs.members.delete(uid);
        broadcast(room, { type: 'presence', uid, online: false });
      } else {
        c.ws.isAlive = false;
        try { c.ws.ping(); } catch { /* ignore */ }
      }
    }
    for (const [uid, c] of Array.from(rs.pending.entries())) {
      if (!c.ws.isAlive) {
        c.ws.terminate();
        rs.pending.delete(uid);
      } else {
        c.ws.isAlive = false;
        try { c.ws.ping(); } catch { /* ignore */ }
      }
    }
  }
}, 30000);
heartbeat.unref?.();

// 房间过期回收：每小时一次
const reaper = setInterval(reapIdleRooms, 3600 * 1000);
reaper.unref?.();

wss.on('listening', () => {
  console.log('family-signal listening on 0.0.0.0:' + PORT);
});
