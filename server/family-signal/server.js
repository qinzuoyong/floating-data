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

const PORT = 8088;
const STATE_FILE = '/opt/family-signal/rooms.json';
const wss = new WebSocket.Server({ port: PORT, host: '0.0.0.0' });

/** room -> { owner: uid, members: Map(uid->{ws,name,isAlive}), pending: Map(uid->{ws,name}), approved: Map(uid->name) } */
const rooms = new Map();

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
    try { fs.writeFileSync(STATE_FILE, JSON.stringify(data)); } catch (e) { /* ignore */ }
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

// 家庭关系（owner/approved）持久保留：成员全部离线不清除房间，重连自动恢复
function cleanupRoom(room) {
  /* no-op：房间仅在持久化状态中保留 */
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

wss.on('connection', (ws) => {
  ws.isAlive = true;
  ws.room = null;
  ws.uid = null;
  ws.name = null;

  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', (data) => {
    let msg;
    try { msg = JSON.parse(data.toString('utf8')); } catch { return; }
    if (!msg || typeof msg.type !== 'string') return;

    switch (msg.type) {
      case 'room-check': {
        const room = String(msg.room || '').trim();
        const rs = rooms.get(room);
        const ownerName = rs && rs.members.get(rs.owner) ? rs.members.get(rs.owner).name : '';
        send(ws, { type: 'room-check-res', room, exists: !!rs, ownerName });
        break;
      }

      case 'register': {
        const room = String(msg.room || '').trim();
        const uid = String(msg.uid || '').trim();
        const name = String(msg.name || '').trim() || uid;
        if (!room || !uid || room.length > 16 || uid.length > 64) {
          send(ws, { type: 'error', code: 'bad_register', message: 'room/uid 非法' });
          return;
        }
        if (ws.room && ws.room !== room) leave(ws);
        let rs = rooms.get(room);
        if (!rs) {
          // 房间不存在：首个注册者成为创建人，直接进房
          rs = { owner: uid, members: new Map(), pending: new Map(), approved: new Map() };
          rooms.set(room, rs);
          saveRooms();
        }
        const old = rs.members.get(uid);
        if (old && old.ws !== ws) { old.ws.terminate(); }
        // 创建人、当前在线成员、或已被批准过的成员（断线重连免审核）直接进房
        if (rs.owner === uid || rs.members.has(uid) || rs.approved.has(uid)) {
          // 创建人或已批准成员：进房；名字刷新进名册（创建人也入名册）
          ws.room = room;
          ws.uid = uid;
          ws.name = name;
          rs.members.set(uid, { ws, name, isAlive: true });
          rs.approved.set(uid, name);
          saveRooms();
          send(ws, { type: 'registered', uid, room, peers: memberPeers(rs, uid), roster: rosterOf(rs, uid) });
          broadcast(room, { type: 'presence', uid, name, online: true }, uid);
        } else {
          // 新成员：进 pending，等待创建人审核
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
        if (!to) break;
        const rs = ws.room ? rooms.get(ws.room) : undefined;
        const entry = rs ? rs.members.get(to) : undefined;
        if (entry && entry.ws.readyState === WebSocket.OPEN) {
          send(entry.ws, { type: 'loc-res', from: ws.uid, name: ws.name, to, payload: msg.payload || {} });
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

wss.on('listening', () => {
  console.log('family-signal listening on 0.0.0.0:' + PORT);
});
