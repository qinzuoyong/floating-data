'use strict';
/**
 * 常驻测试成员（family-fake-member.js）的协议回归（零依赖，Node >= 21 内置 WebSocket）
 *
 * 目的：这个桩是家人链路唯一的"永远在线的对端"，它支持的应答类型必须与 server.js
 * 的载荷白名单、与 App 侧的准入校验对齐。最易漏的是 stat-req（电量）：桩不支持时
 * 只回 loc-res，界面表现为"电量一直未知"且不报错——本用例把它锁死。
 *
 * 运行：node server/family-signal/test/fake-member.test.js
 */
const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const SERVER = path.join(__dirname, '..', 'server.js');
const FAKE = path.join(__dirname, '..', 'family-fake-member.js');
const PORT = 18236;
const ROOM = 'FAKE01';
const STATE = path.join(os.tmpdir(), 'family-fake-member-test-' + Date.now() + '.json');

const FAKE_UID = 'ms-azure-fake';
const FAKE_LAT = 39.9087;
const FAKE_LNG = 116.3975;
const FAKE_BATTERY = 42;

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
    wait: (pred, ms = 3000) => new Promise((resolve) => {
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
  const server = spawn(process.execPath, [SERVER], {
    env: Object.assign({}, process.env, {
      FAMILY_SIGNAL_PORT: String(PORT),
      FAMILY_SIGNAL_STATE_FILE: STATE
    }),
    stdio: ['ignore', 'pipe', 'pipe']
  });
  server.stdout.on('data', () => {});
  server.stderr.on('data', (d) => process.stderr.write('[server] ' + d));
  if (!(await waitForPort())) {
    console.error('服务器未能在 4s 内监听端口');
    server.kill();
    process.exit(1);
  }

  // 桩的 stdout 全量留档：用例断言"桩确实收到并应答了请求"（日志即证据）
  let fakeOut = '';
  const fake = spawn(process.execPath, [FAKE], {
    env: Object.assign({}, process.env, {
      FAMILY_FAKE_ROOM: ROOM,
      FAMILY_FAKE_UID: FAKE_UID,
      FAMILY_FAKE_NAME: '测试桩',
      FAMILY_FAKE_LAT: String(FAKE_LAT),
      FAMILY_FAKE_LNG: String(FAKE_LNG),
      FAMILY_FAKE_BATTERY: String(FAKE_BATTERY),
      FAMILY_FAKE_ENDPOINTS: 'ws://127.0.0.1:' + PORT
    }),
    stdio: ['ignore', 'pipe', 'pipe']
  });
  fake.stdout.on('data', (d) => { fakeOut += d.toString('utf8'); });
  fake.stderr.on('data', (d) => process.stderr.write('[fake] ' + d));

  console.log('\n== 常驻测试成员（假家人桩）协议回归 ==\n');

  const phone = connect();
  await phone.open();
  phone.send({ type: 'register', room: ROOM, uid: 'u-phone', name: '手机' });
  const phoneReg = await phone.wait((m) => m.type === 'registered');
  ok('手机注册成功（成为创建人）', !!phoneReg);

  const online = await phone.wait((m) => m.type === 'presence' && m.uid === FAKE_UID && m.online === true, 6000);
  ok('桩上线（同房成员可见）', !!online, fakeOut || '桩无输出');
  ok('桩在名册内（uid 与配置一致）',
    !!online && online.name === '测试桩', online ? JSON.stringify(online) : '');

  // ---------- 位置应答 ----------
  console.log('[位置应答 loc-req → loc-res]');
  phone.send({ type: 'loc-req', to: FAKE_UID });
  const locRes = await phone.wait((m) => m.type === 'loc-res' && m.from === FAKE_UID, 3000);
  // 断言必须在"应答已到达"之后再读桩日志：原写法 `|| (await phone.wait(() => true, 50)) !== null`
  // 是恒真兜底（wait 内部 msgs.find 会立刻命中此前任何一条已收报文），桩没收到请求也会 PASS
  ok('桩收到位置请求（桩日志出现该行）', /收到位置请求/.test(fakeOut), fakeOut);
  ok('桩回传 loc-res', !!locRes, fakeOut);
  ok('坐标与配置一致',
    !!locRes && locRes.payload.lat === FAKE_LAT && locRes.payload.lng === FAKE_LNG,
    locRes ? JSON.stringify(locRes.payload) : '');
  ok('loc-res 带时间戳与精度（App 侧准入校验需要）',
    !!locRes && typeof locRes.payload.ts === 'number' && locRes.payload.ts > 0 &&
    typeof locRes.payload.accuracy === 'number' && locRes.payload.accuracy > 0);

  // ---------- 状态（电量）应答 ----------
  console.log('[状态应答 stat-req → stat-res（2026-09 新增能力）]');
  phone.send({ type: 'stat-req', to: FAKE_UID });
  const statRes = await phone.wait((m) => m.type === 'stat-res' && m.from === FAKE_UID, 3000);
  ok('桩回传 stat-res（旧版桩只回 loc-res，会在此失败）', !!statRes, fakeOut);
  ok('电量与配置一致且在服务端白名单内（0-100）',
    !!statRes && statRes.payload.battery === FAKE_BATTERY &&
    statRes.payload.battery >= 0 && statRes.payload.battery <= 100,
    statRes ? JSON.stringify(statRes.payload) : '');
  ok('stat-res 带时间戳（App 侧 isPlausibleStatus 需要 ts > 0）',
    !!statRes && typeof statRes.payload.ts === 'number' && statRes.payload.ts > 0);
  ok('桩日志留痕（排障时能在服务器 journal 里看到应答）',
    /收到状态请求，已应答电量 42%/.test(fakeOut), fakeOut);

  phone.close();
  fake.kill();
  server.kill();
  await sleep(300);
  console.log('\n== 结果: ' + pass + ' 通过 / ' + fail + ' 失败 ==');
  try { fs.unlinkSync(STATE); } catch {}
  process.exit(fail === 0 ? 0 : 1);
}

main().catch((e) => { console.error('测试异常:', e); process.exit(1); });
