'use strict';
/**
 * family-signal 本机协议回归测试（零依赖，Node >= 21 内置 WebSocket）
 *
 * 用途：在本机拉起真实 server.js（独立端口 + 临时状态文件），用真实 WebSocket 客户端
 * 验证信令协议行为，不需要真机、不需要动线上服务器。
 *
 * 运行：node server/family-signal/test/protocol.test.js
 * 退出码：0=全部通过；1=有断言失败
 */
const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const SERVER = path.join(__dirname, '..', 'server.js');
const PORT = 18234;
const STATE = path.join(os.tmpdir(), 'family-signal-test-' + Date.now() + '.json');

let pass = 0, fail = 0;
function ok(name, cond, extra) {
  if (cond) { pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (extra ? '  -> ' + extra : '')); }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 测试用信令客户端：记录全部下行报文，支持按条件等待 */
function connect() {
  const ws = new WebSocket('ws://127.0.0.1:' + PORT);
  const msgs = [];
  const waiters = [];
  ws.addEventListener('message', (e) => {
    let m;
    try { m = JSON.parse(typeof e.data === 'string' ? e.data : String(e.data)); } catch { return; }
    msgs.push(m);
    for (let i = waiters.length - 1; i >= 0; i--) {
      if (waiters[i].pred(m)) { waiters[i].resolve(m); waiters.splice(i, 1); }
    }
  });
  const client = {
    ws, msgs,
    open: () => new Promise((res, rej) => {
      ws.addEventListener('open', res, { once: true });
      ws.addEventListener('error', () => rej(new Error('connect failed')), { once: true });
    }),
    send: (o) => ws.send(JSON.stringify(o)),
    /** 等待满足条件的报文；超时返回 null（用于"不应发生"断言） */
    wait: (pred, ms = 1200) => new Promise((resolve) => {
      const hit = msgs.find(pred);
      if (hit) return resolve(hit);
      const w = { pred, resolve };
      waiters.push(w);
      setTimeout(() => {
        const i = waiters.indexOf(w);
        if (i >= 0) { waiters.splice(i, 1); resolve(null); }
      }, ms);
    }),
    close: () => { try { ws.close(); } catch {} }
  };
  return client;
}

async function waitForPort() {
  for (let i = 0; i < 40; i++) {
    try {
      const c = connect();
      await c.open();
      c.close();
      return true;
    } catch { await sleep(100); }
  }
  return false;
}

async function main() {
  const child = spawn(process.execPath, [SERVER], {
    env: Object.assign({}, process.env, {
      FAMILY_SIGNAL_PORT: String(PORT),
      FAMILY_SIGNAL_STATE_FILE: STATE
    }),
    stdio: ['ignore', 'pipe', 'pipe']
  });
  child.stdout.on('data', () => {});
  child.stderr.on('data', (d) => process.stderr.write('[server] ' + d));

  if (!(await waitForPort())) { console.error('服务器未能在 4s 内监听端口'); child.kill(); process.exit(1); }
  console.log('\n== family-signal 协议回归 ==\n');

  // ---------- 用例 1：新成员免审核直接进房（P0 修复核心） ----------
  console.log('[1] 新成员免审核直接进房');
  const owner = connect(); await owner.open();
  owner.send({ type: 'register', room: 'TEST01', uid: 'u-owner', name: '创建人' });
  const ownerReg = await owner.wait((m) => m.type === 'registered');
  ok('创建人收到 registered', !!ownerReg);

  const joiner = connect(); await joiner.open();
  joiner.send({ type: 'register', room: 'TEST01', uid: 'u-join', name: '新成员' });
  const joinReg = await joiner.wait((m) => m.type === 'registered');
  const joinPending = joiner.msgs.find((m) => m.type === 'join-pending');
  ok('新成员直接 registered（未被拦为待审）', !!joinReg, joinReg ? '' : '只收到: ' + JSON.stringify(joiner.msgs));
  ok('新成员未收到 join-pending', !joinPending);
  ok('创建人未收到 join-request', !owner.msgs.find((m) => m.type === 'join-request'));
  ok('新成员名册含创建人', !!joinReg && joinReg.roster.some((p) => p.uid === 'u-owner'));

  const presence = await owner.wait((m) => m.type === 'presence' && m.uid === 'u-join' && m.online === true);
  ok('创建人收到新成员上线 presence', !!presence);

  // ---------- 用例 2：位置应答中继保真（P0 载荷修复） ----------
  console.log('[2] 位置请求与应答中继');
  const payload = { lat: 39.9073, lng: 116.3912, ts: Date.now(), accuracy: 12.5 };
  joiner.send({ type: 'loc-req', to: 'u-owner' });
  const req = await owner.wait((m) => m.type === 'loc-req' && m.from === 'u-join');
  ok('loc-req 转发到目标成员', !!req);

  owner.send({ type: 'loc-res', to: 'u-join', payload });
  const res = await joiner.wait((m) => m.type === 'loc-res' && m.from === 'u-owner');
  ok('loc-res 转发到请求方', !!res);
  ok('loc-res 经纬度保真', !!res && res.payload.lat === payload.lat && res.payload.lng === payload.lng,
    res ? JSON.stringify(res.payload) : 'no payload');
  ok('loc-res 精度字段保真', !!res && res.payload.accuracy === payload.accuracy);

  // ---------- 用例 3：畸形位置载荷被服务端拦下 ----------
  console.log('[3] 畸形位置载荷拦截');
  owner.send({ type: 'loc-res', to: 'u-join', payload: { lat: 999, lng: 116 } });
  await sleep(300);
  ok('越界纬度未被转发', !joiner.msgs.find((m) => m.type === 'loc-res' && m.payload && m.payload.lat === 999));
  owner.send({ type: 'loc-res', to: 'u-join', payload: { lat: 0, lng: 0 } });
  await sleep(300);
  ok('零值坐标未被转发', !joiner.msgs.find((m) => m.type === 'loc-res' && m.payload && m.payload.lat === 0 && m.payload.lng === 0));

  // ---------- 用例 4：未注册连接不能中继 ----------
  console.log('[4] 未注册连接中继被拒');
  const anon = connect(); await anon.open();
  anon.send({ type: 'loc-req', to: 'u-owner' });
  const anonErr = await anon.wait((m) => m.type === 'error' && m.code === 'not_registered');
  ok('未注册 loc-req 返回 not_registered', !!anonErr);
  ok('未注册请求未被转发给目标', !owner.msgs.find((m) => m.type === 'loc-req' && m.from === undefined));
  anon.close();

  // ---------- 用例 5：位置请求限流（10/分钟/成员） ----------
  console.log('[5] 位置请求限流');
  for (let i = 0; i < 11; i++) joiner.send({ type: 'loc-req', to: 'u-owner' });
  const limited = await joiner.wait((m) => m.type === 'error' && m.code === 'rate_limited', 2000);
  ok('高频 loc-req 触发 rate_limited', !!limited);

  // ---------- 用例 6：房间码校验与占用查询 ----------
  console.log('[6] 房间码校验与占用查询');
  const q = connect(); await q.open();
  q.send({ type: 'room-check', room: 'TEST01' });
  const qRes = await q.wait((m) => m.type === 'room-check-res');
  ok('room-check 返回 exists=true', !!qRes && qRes.exists === true, qRes ? JSON.stringify(qRes) : '');
  q.send({ type: 'register', room: 'ab', uid: 'u-x', name: 'x' });
  const badRoom = await q.wait((m) => m.type === 'error' && m.code === 'bad_register');
  ok('非法房间码被拒', !!badRoom);

  // ---------- 用例 7：状态持久化（owner/approved 落盘） ----------
  console.log('[7] 家庭关系持久化');
  await sleep(900); // saveRooms 有 500ms 去抖
  let saved = null;
  try { saved = JSON.parse(fs.readFileSync(STATE, 'utf8')); } catch (e) { saved = null; }
  ok('rooms.json 已写入 TEST01', !!saved && !!saved.TEST01, saved ? JSON.stringify(saved) : 'no file');
  ok('创建人记录为 owner', !!saved && saved.TEST01 && saved.TEST01.owner === 'u-owner');
  ok('新成员已入 approved 名册', !!saved && saved.TEST01 && !!saved.TEST01.approved['u-join']);

  owner.close(); joiner.close(); q.close();
  child.kill();
  await sleep(200);

  console.log('\n== 结果: ' + pass + ' 通过 / ' + fail + ' 失败 ==');
  try { fs.unlinkSync(STATE); } catch {}
  process.exit(fail === 0 ? 0 : 1);
}

main().catch((e) => { console.error('测试异常:', e); process.exit(1); });
