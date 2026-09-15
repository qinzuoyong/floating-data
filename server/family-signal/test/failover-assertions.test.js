'use strict';
/**
 * 主/备信令端点自动切换的源码级回归断言（零依赖）。
 *
 * 切换逻辑的正确性是"行为静默错误"的高发区：退化成"连不上就来回抖"或
 * "一断线就切走"都不会编译报错，却会让家人位置共享无声失效或把同一个家庭
 * 拆到两台服务器上。故用源码断言锁定关键形态。
 *
 * 运行：node server/family-signal/test/failover-assertions.test.js
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..', '..');
const CLIENT = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/p2p/SignalClient.kt');
const SERVICE = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/service/FamilyLocationService.kt');
const ADD_FAMILY = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/ui/family/AddFamilyScreen.kt');
const GRADLE = path.join(ROOT, 'app/build.gradle.kts');

let pass = 0, fail = 0;
function ok(name, cond, extra) {
  if (cond) { pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (extra ? '  -> ' + extra : '')); }
}
const read = (p) => fs.readFileSync(p, 'utf8');

console.log('\n== 主/备端点切换源码断言 ==\n');

const sc = read(CLIENT);

console.log('[端点参数与兼容性 SignalClient.kt]');
ok('构造函数新增备用地址参数且带默认值（旧调用点不受影响）',
  /class SignalClient\([\s\S]*?private val backupUrl: String = ""/.test(sc));
ok('候选端点过滤空串并去重', /listOf\(url, backupUrl\)[\s\S]*?\.filter \{ it\.isNotEmpty\(\) \}[\s\S]*?\.distinct\(\)/.test(sc));
ok('单端点时不做任何切换（直接返回）',
  /private fun noteEndpointFailure[\s\S]*?if \(endpoints\.size < 2 \|\| activeIndex != 0\) return/.test(sc));

console.log('[切换触发条件]');
ok('仅"未完成握手"才计端点失败（服务器可达但被限流不算）',
  /if \(!handshakeDone\) noteEndpointFailure\(this\)/.test(sc));
ok('握手成功即清零失败计数', /handshakeDone = true\s*\n\s*consecutiveFails = 0/.test(sc));
ok('同一连接实例的失败只计一次',
  /if \(ws != null && failureCountedFor === ws\) return/.test(sc));
ok('连续失败阈值常量存在', /const val FAILS_BEFORE_SWITCH = \d+/.test(sc));
ok('达阈值才切到备用端点', /if \(consecutiveFails >= FAILS_BEFORE_SWITCH\) \{[\s\S]*?activeIndex = 1/.test(sc));
ok('切换后重置退避（立即重连备用，不等 30 秒）', /activeIndex = 1\s*\n\s*backoffMs = 2_000L/.test(sc));

console.log('[切回主端点：主动回探]');
ok('回探间隔常量存在', /const val PRIMARY_PROBE_INTERVAL_MS = \d+/.test(sc));
ok('备用端点期间才回探（主端点上不停探测）', /if \(stopped \|\| activeIndex == 0\) continue/.test(sc));
ok('回探探通用临时连接，不做注册（不产生房间状态）',
  /private suspend fun probeEndpoint[\s\S]*?\}/.test(sc) &&
  !/probeEndpoint[\s\S]{0,900}?SignalTypes\.REGISTER/.test(sc));
ok('回探不阻塞主线程（非阻塞 connect + 超时等待）',
  /withTimeoutOrNull\(PROBE_TIMEOUT_MS\) \{ result\.await\(\) \}/.test(sc));
ok('探通后切回主端点并主动断开备用连接触发重连',
  /activeIndex = 0[\s\S]{0,200}?closeConnection\(CloseFrame\.NORMAL, "switch to primary"\)/.test(sc));
ok('回探失败保持备用端点（不反复切换）', /主端点回探失败，继续使用备用端点/.test(sc));

console.log('[生效端点进程内共享]');
ok('生效端点下标为进程内共享（服务与 UI 用同一台服务器）',
  /@Volatile\s*\n\s*var activeIndex = 0/.test(sc));
ok('家庭码查询使用当前生效端点（不再固定主地址）',
  /object : WebSocketClient\(URI\.create\(currentEndpoint\(\)\)\)/.test(sc));
ok('下标越界回落主端点', /private fun currentEndpoint\(\): String = endpoints\[activeIndex\.coerceIn\(0, endpoints\.size - 1\)\]/.test(sc));
ok('断开时取消回探任务（不留后台任务）', /primaryProbeJob\?\.cancel\(\)/.test(sc));

console.log('[调用点接入]');
const svc = read(SERVICE);
ok('家人服务传入备用地址', /SignalClient\(signalUrl, backupUrl\)/.test(svc));
ok('备用地址做 ws\/wss 校验（非法按未配置处理）',
  /BuildConfig\.SIGNAL_URL_BACKUP\s*\n?\s*\.takeIf \{ it\.startsWith\("ws:\/\/"\) \|\| it\.startsWith\("wss:\/\/"\) \} \?: ""/.test(svc));
ok('主地址校验逻辑保持不变（主地址非法即不连接）',
  /if \(!signalUrl\.startsWith\("ws:\/\/"\) && !signalUrl\.startsWith\("wss:\/\/"\)\) \{/.test(svc));

const af = read(ADD_FAMILY);
ok('添加家庭页查询传入备用地址',
  /SignalClient\(BuildConfig\.SIGNAL_URL, BuildConfig\.SIGNAL_URL_BACKUP\)\.checkRoom/.test(af));

const gr = read(GRADLE);
ok('构建注入 SIGNAL_URL_BACKUP（值取自 local.properties 的 signalUrlBackup）',
  /buildConfigField\("String", "SIGNAL_URL_BACKUP", "[\s\S]{0,12}signalUrlBackup/.test(gr));
ok('备用地址非法时给出构建告警',
  /SIGNAL_URL_BACKUP 非法（须以 ws:\/\/ 或 wss:\/\/ 开头）/.test(gr));
ok('备用地址缺省为空（不配置也能构建）',
  /lp\.getProperty\("SIGNAL_URL_BACKUP", ""\)/.test(gr));

console.log('\n== 结果: ' + pass + ' 通过 / ' + fail + ' 失败 ==\n');
process.exit(fail === 0 ? 0 : 1);
