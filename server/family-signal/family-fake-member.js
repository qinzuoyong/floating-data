'use strict';
/**
 * 常驻测试成员「微软服务器」
 *
 * 用途：让家里始终有一个在线的对端，便于随时验证「请求家人位置」链路。
 *
 * 为什么同时连主、备两个节点：
 * 信令是**有状态中继**——同房成员必须落在同一台服务器上（见 server.js 顶部说明）。
 * 只挂一台的话，手机切到另一台时就看不到这个成员了。两台都挂，
 * 手机在哪台它就出现在哪台，切换过程中也不会掉线。
 *
 * 环境变量：
 *   FAMILY_FAKE_ENDPOINTS  逗号分隔端点（默认 ws://127.0.0.1:8088）
 *   FAMILY_FAKE_ROOM       家庭码（必填）
 *   FAMILY_FAKE_UID        成员标识（固定值，重连后仍算同一成员）
 *   FAMILY_FAKE_NAME       显示名
 *   FAMILY_FAKE_LAT/LNG    应答的固定坐标（GCJ-02，家人地图可直接渲染）
 */
const WebSocket = require('ws');

const ROOM = (process.env.FAMILY_FAKE_ROOM || '').trim();
const UID = (process.env.FAMILY_FAKE_UID || 'ms-azure-fake').trim();
const NAME = (process.env.FAMILY_FAKE_NAME || '微软服务器').trim();
const LAT = Number(process.env.FAMILY_FAKE_LAT || 39.9087);
const LNG = Number(process.env.FAMILY_FAKE_LNG || 116.3975);
const ENDPOINTS = (process.env.FAMILY_FAKE_ENDPOINTS || 'ws://127.0.0.1:8088')
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean);

if (!ROOM) {
  console.error('[fake] 未配置 FAMILY_FAKE_ROOM，退出');
  process.exit(1);
}

/** 每个端点一条独立连接，各自退避重连，互不影响 */
function runEndpoint(url) {
  const tag = '[' + url.replace(/\/\/[^/]*/, '//…') + ']';
  let ws = null;
  let backoff = 2000;
  let timer = null;

  const send = (obj) => {
    if (ws && ws.readyState === WebSocket.OPEN) {
      try { ws.send(JSON.stringify(obj)); return true; } catch (e) { return false; }
    }
    return false;
  };

  function scheduleReconnect() {
    if (timer) return;
    const delay = backoff;
    backoff = Math.min(backoff * 2, 30000);
    timer = setTimeout(() => { timer = null; connect(); }, delay);
  }

  function connect() {
    ws = new WebSocket(url);

    ws.on('open', () => {
      backoff = 2000;
      console.log(tag + ' 已连接，注册中…');
      send({ type: 'register', room: ROOM, uid: UID, name: NAME });
    });

    ws.on('message', (data) => {
      let msg;
      try { msg = JSON.parse(data.toString('utf8')); } catch (e) { return; }
      switch (msg.type) {
        case 'registered':
          console.log(tag + ' 已进房：名册 ' + ((msg.roster || []).length) +
            ' 人，同房在线 ' + ((msg.peers || []).length) + ' 人');
          break;
        case 'presence':
          console.log(tag + ' 成员上下线：online=' + msg.online);
          break;
        case 'loc-req':
          // 立即回固定坐标，便于对方在地图上看到落点
          if (send({ type: 'loc-res', to: msg.from, payload: { lat: LAT, lng: LNG, ts: Date.now(), accuracy: 10 } })) {
            console.log(tag + ' 收到位置请求，已应答固定坐标');
          }
          break;
        case 'ping':
          send({ type: 'pong' });
          break;
        default:
          break;
      }
    });

    ws.on('close', (code) => {
      console.log(tag + ' 连接断开 code=' + code + '，稍后重连');
      scheduleReconnect();
    });

    ws.on('error', (err) => {
      console.log(tag + ' 连接错误：' + err.message);
      // 由 close 统一退避重连
    });
  }

  connect();
}

console.log('[fake] 测试成员启动：名=' + NAME + ' 房间长度=' + ROOM.length +
  ' 端点=' + ENDPOINTS.length + ' 个 固定坐标=' + LAT + ',' + LNG);
ENDPOINTS.forEach(runEndpoint);
