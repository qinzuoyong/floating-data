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

  // ---------- 用例 3b：状态（电量）请求与应答中继 ----------
  console.log('[3b] 状态请求与应答中继');
  const status = { battery: 62, ts: Date.now() };
  joiner.send({ type: 'stat-req', to: 'u-owner' });
  const statReq = await owner.wait((m) => m.type === 'stat-req' && m.from === 'u-join');
  ok('stat-req 转发到目标成员', !!statReq);
  owner.send({ type: 'stat-res', to: 'u-join', payload: status });
  const statRes = await joiner.wait((m) => m.type === 'stat-res' && m.from === 'u-owner');
  ok('stat-res 转发到请求方', !!statRes);
  ok('stat-res 电量保真', !!statRes && statRes.payload.battery === 62,
    statRes ? JSON.stringify(statRes.payload) : 'no payload');

  // ---------- 用例 3c：畸形状态载荷被服务端拦下 ----------
  console.log('[3c] 畸形状态载荷拦截');
  owner.send({ type: 'stat-res', to: 'u-join', payload: { battery: 999, ts: Date.now() } });
  await sleep(300);
  ok('越界电量未被转发', !joiner.msgs.find((m) => m.type === 'stat-res' && m.payload && m.payload.battery === 999));
  owner.send({ type: 'stat-res', to: 'u-join', payload: { battery: -5, ts: Date.now() } });
  await sleep(300);
  ok('负电量未被转发', !joiner.msgs.find((m) => m.type === 'stat-res' && m.payload && m.payload.battery === -5));
  owner.send({ type: 'stat-res', to: 'u-join', payload: { battery: 'abc' } });
  await sleep(300);
  ok('非数值电量未被转发',
    !joiner.msgs.find((m) => m.type === 'stat-res' && m.payload && typeof m.payload.battery !== 'number'));

  // ---------- 用例 3d：未注册连接的状态请求被拒 ----------
  console.log('[3d] 未注册状态请求被拒');
  const anonStat = connect(); await anonStat.open();
  anonStat.send({ type: 'stat-req', to: 'u-owner' });
  const anonStatErr = await anonStat.wait((m) => m.type === 'error' && m.code === 'not_registered');
  ok('未注册 stat-req 返回 not_registered', !!anonStatErr);
  anonStat.close();

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

  // ---------- 用例 5b：状态请求用独立限流桶（位置桶耗尽后仍可请求） ----------
  console.log('[5b] 状态请求独立限流桶');
  joiner.send({ type: 'stat-req', to: 'u-owner' });
  const statAfterFlood = await owner.wait((m) => m.type === 'stat-req' && m.from === 'u-join', 1200);
  ok('位置桶耗尽后 stat-req 仍被转发（独立桶）', !!statAfterFlood);

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

  // ---------- 用例 8：恶意报文不得让服务进程退出（P0） ----------
  // 远端报文完全不可信：用对象冒充 room/uid/to/name 与 payload 数值字段时，
  // 旧实现里的 String()/Number() 会抛 TypeError，沿 ws 的 socket 数据路径冒泡成
  // 未捕获异常 → 整个 Node 进程退出（所有家庭同时掉线），且无需认证即可触发。
  // 首条 room-check 在旧实现上即可复现，故本用例"改回去就变红"。
  console.log('[8] 恶意报文不使服务退出');
  const evil = connect();
  await evil.open();
  const hostile = [
    { type: 'room-check', room: { toString: null } },
    { type: 'room-check', room: { valueOf: null, toString: null } },
    { type: 'register', room: 'EVIL01', uid: { toString: null }, name: 'x' },
    { type: 'register', room: { toString: null }, uid: 'u-evil', name: { toString: null } },
    { type: 'register', room: 'EVIL01', uid: 'u-evil', name: { valueOf: null, toString: null } },
    { type: 'loc-req', to: { toString: null } },
    { type: 'loc-res', to: { toString: null }, payload: { lat: { valueOf: null, toString: null }, lng: 1, ts: 1, accuracy: 1 } },
    { type: 'stat-req', to: { toString: null } },
    { type: 'stat-res', to: 'u-owner', payload: { battery: { valueOf: null, toString: null }, ts: 1 } },
    { type: 'loc-res', to: 'u-owner', payload: [1, 2, 3] },
    { type: 'stat-res', to: 'u-owner', payload: 'not-an-object' }
  ];
  for (const m of hostile) { evil.send(m); await sleep(40); }
  await sleep(500);
  ok('服务进程仍存活（未因恶意报文退出）', child.exitCode === null,
    'exitCode=' + child.exitCode);
  // 对象冒充 room/uid 必须被拒为 bad_register（两条用例各一次），不得建出畸形房间
  ok('对象型 room/uid 被拒为 bad_register',
    evil.msgs.filter((m) => m.type === 'error' && m.code === 'bad_register').length >= 2,
    JSON.stringify(evil.msgs));
  // 第三条 register 的 room/uid 合法，只有 name 是对象：应被规范化为 uid 兜底而非崩溃或落畸形名
  await sleep(900); // saveRooms 有 500ms 去抖
  let saved2 = null;
  try { saved2 = JSON.parse(fs.readFileSync(STATE, 'utf8')); } catch (e) { saved2 = null; }
  ok('对象型 name 被规范化为 uid 兜底（未把畸形名写进名册）',
    !!saved2 && !!saved2.EVIL01 && saved2.EVIL01.approved['u-evil'] === 'u-evil',
    saved2 ? JSON.stringify(saved2.EVIL01) : 'no file');
  // 崩溃后再连也拿不到回执，故"同连接仍被应答 + 新连接仍能注册"两条一起锁住可用性
  evil.send({ type: 'room-check', room: 'TEST01' });
  const aliveRes = await evil.wait((m) => m.type === 'room-check-res');
  ok('恶意报文之后同连接仍被应答', !!aliveRes);
  const afterEvil = connect();
  await afterEvil.open();
  afterEvil.send({ type: 'register', room: 'TEST01', uid: 'u-after', name: '崩溃后' });
  const afterReg = await afterEvil.wait((m) => m.type === 'registered');
  ok('恶意报文之后新成员仍能注册进房', !!afterReg,
    afterReg ? '' : '只收到: ' + JSON.stringify(afterEvil.msgs));
  evil.close();
  afterEvil.close();

  owner.close(); joiner.close(); q.close();
  child.kill();
  await sleep(200);

  console.log('\n== 结果: ' + pass + ' 通过 / ' + fail + ' 失败 ==');
  try { fs.unlinkSync(STATE); } catch {}
  process.exit(fail === 0 ? 0 : 1);
}

main().catch((e) => { console.error('测试异常:', e); process.exit(1); });
