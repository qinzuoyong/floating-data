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

console.log('\n== 结果: ' + pass + ' 通过 / ' + fail + ' 失败 ==');
process.exit(fail === 0 ? 0 : 1);
