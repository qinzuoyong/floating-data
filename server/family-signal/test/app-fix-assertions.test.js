'use strict';
/**
 * App 侧修复的源码级回归断言（零依赖）。
 *
 * 这两处缺陷都是"行为静默错误"，Kotlin 编译期无法发现，故用源码断言锁定修复形态，
 * 防止后续改动把错误逻辑改回来。
 *
 * 运行：node server/family-signal/test/app-fix-assertions.test.js
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..', '..');
const AUTO_GRANT = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/adb/AdbAutoGrant.kt');
const PRIV_BASELINE = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/adb/PrivBaseline.kt');
const HOME = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/ui/HomeScreen.kt');
const ADB_KEY = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/adb/AdbKey.kt');

let pass = 0, fail = 0;
function ok(name, cond, extra) {
  if (cond) { pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (extra ? '  -> ' + extra : '')); }
}
const read = (p) => fs.readFileSync(p, 'utf8');

console.log('\n== App 侧修复源码断言 ==\n');

// ---- 修复 3：撤销必须读回实际状态，失败项不得被清掉 ----
console.log('[撤销透明化 AdbAutoGrant.kt]');
const ag = read(AUTO_GRANT);
ok('撤销返回失败条目列表（不再只回 Boolean）',
  /suspend fun revokeAutoGranted\(ctx: Context\): List<AutoGrant>/.test(ag));
ok('逐项读回复核实际状态', /if \(isGranted\(ctx, kind\)\)/.test(ag));
ok('失败项计入 failed 列表', /failed\.add\(kind\)/.test(ag));
ok('仅对已生效撤销项移除记录', /removeGrantLog\(ctx, kind\)/.test(ag));
ok('不再整体清空记录（clearGrantLog 已移除）', !/clearGrantLog/.test(ag));
ok('无障碍撤销保留异步宽限期', /ACCESSIBILITY_REVOKE_GRACE_MS/.test(ag) && /delay\(ACCESSIBILITY_REVOKE_GRACE_MS\)/.test(ag));
ok('已引入 delay 导入', /import kotlinx\.coroutines\.delay/.test(ag));

console.log('[撤销结果上报 HomeScreen.kt]');
const hs = read(HOME);
ok('撤销结果回传用户（Toast 提示）', /已撤销全部自动授权/.test(hs) && /撤销未生效/.test(hs));
ok('撤销异常被收敛（不冒泡崩溃）', /撤销自动授权失败/.test(hs));
ok('已引入 Log 导入', /import android\.util\.Log/.test(hs));

// ---- 修复 2：密钥未就绪时不得置"已尝试"标记 ----
console.log('[trust-key 时序 PrivBaseline.kt]');
const pb = read(PRIV_BASELINE);
const idxKeyNull = pb.indexOf('ADB 密钥尚未就绪');
const idxMarkTried = pb.indexOf('putBoolean(PrefsKeys.PRIV_BASELINE_KEY_TRIED, true)');
const idxElse = pb.indexOf('} else {', idxKeyNull);
ok('key == null 分支存在（留待重试）', idxKeyNull > 0 && /key == null/.test(pb));
ok('置"已尝试"标记位于 else 分支内（仅密钥就绪后才置位）',
  idxMarkTried > idxElse && idxElse > idxKeyNull,
  'markTried=' + idxMarkTried + ' else=' + idxElse + ' keyNull=' + idxKeyNull);
ok('peekKey 先于标记写入', pb.indexOf('peekKey()') < idxMarkTried);

// ---- 2026-09-18 审查修复回归锁定 ----
console.log('[家人页自动启动门控 FamilyScreen.kt]');
const FAMILY_SCREEN = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/ui/family/FamilyScreen.kt');
const fsc = read(FAMILY_SCREEN);
ok('家人页自动启动受 FAMILY_WAS_RUNNING 门控（尊重用户主动停止）',
  /!isServiceRunning\(\) && prefs\.getBoolean\(PrefsKeys\.FAMILY_WAS_RUNNING, false\)/.test(fsc),
  '自动启动分支未发现 FAMILY_WAS_RUNNING 门控');

console.log('[信令空端点守卫 SignalClient.kt]');
const SIGNAL_CLIENT = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/p2p/SignalClient.kt');
const sc = read(SIGNAL_CLIENT);
const idxCheckRoom = sc.indexOf('fun checkRoom(');
const idxEmptyGuard = sc.indexOf('endpoints.isEmpty()');
ok('checkRoom 存在空端点守卫（未配置 SIGNAL_URL 时不再 coerceIn(0,-1) 抛异常）',
  idxCheckRoom > 0 && idxEmptyGuard > idxCheckRoom,
  'endpoints.isEmpty() 未出现在 checkRoom 内');

console.log('[无障碍通道恢复悬浮窗兜底 KeepAliveAccessibilityService.kt]');
const KA_SERVICE = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/service/KeepAliveAccessibilityService.kt');
const kas = read(KA_SERVICE);
ok('恢复悬浮窗服务有异常兜底（onServiceConnected 不得冒泡异常）',
  /try \{\s*\n\s*FloatingWindowService\.start\(this\)/.test(kas) &&
  /恢复悬浮窗服务失败/.test(kas),
  'FloatingWindowService.start 未被 try 包裹');

// ---- 2026-09-24 审查修复回归锁定 ----
// AdbKey.sign() 的 PKCS#1 v1.5 填充前缀长度受模数硬约束：update(PADDING) 与
// doFinal(token) 两段之和必须恰好等于模数字节数，否则 JCE 抛 IllegalBlockSizeException，
// 经典 A_AUTH 签名路径整体失效（编译期无法发现，且失败被上层 catch 静默降级）。
console.log('[ADB 签名填充长度 AdbKey.kt]');
const ak = read(ADB_KEY);
const PAD_TAIL = '0x04, 0x14';
const idxPad = ak.indexOf('PADDING = byteArrayOf(');
const idxTail = ak.indexOf(PAD_TAIL + ')', idxPad);
const padBody = ak.slice(ak.indexOf('(', idxPad) + 1, idxTail + PAD_TAIL.length);
const padTokens = padBody.split(',').map((t) => t.trim()).filter((t) => t.length > 0);
const padFf = padTokens.filter((t) => t === '-1').length;
const MODULUS_BYTES = 2048 / 8;
const TOKEN_BYTES = 20; // ADB_AUTH_TOKEN 长度
ok('PADDING 数组可解析', padTokens.length > 0, 'PADDING 未找到或为空');
ok('填充前缀 + token 恰好等于模数字节数 256',
  padTokens.length + TOKEN_BYTES === MODULUS_BYTES,
  'PADDING=' + padTokens.length + ' + token=' + TOKEN_BYTES + ' = ' + (padTokens.length + TOKEN_BYTES) + '，应为 ' + MODULUS_BYTES);
ok('PS 0xff 段长度为 218',
  padFf === MODULUS_BYTES - 3 - 15 - TOKEN_BYTES,
  '0xff 个数=' + padFf + '，应为 ' + (MODULUS_BYTES - 3 - 15 - TOKEN_BYTES));
ok('填充头为 0x00 0x01', padTokens[0] === '0x00' && padTokens[1] === '0x01');
ok('DigestInfo(SHA-1) 前缀完整',
  padBody.includes('0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00') &&
  padTokens.slice(-15).join(',') === '0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14');

console.log('\n== 结果: ' + pass + ' 通过 / ' + fail + ' 失败 ==');
process.exit(fail === 0 ? 0 : 1);
