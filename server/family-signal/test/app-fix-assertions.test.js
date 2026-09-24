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

// ---- 2026-09-24 坐标豁免区域回归锁定 ----
// 港澳台豁免此前用外接矩形：香港矩形（113.82~114.44°E / 22.15~22.57°N）把深圳主城区整片
// 吞进去、澳门矩形（113.52~113.63°E / 22.10~22.24°N）吞掉珠海拱北/横琴，这些大陆坐标因此
// 被跳过 GCJ-02 偏移（实测偏 603~620 m）。矩形在几何上无法与大陆分离（香港最北端
// 22.5591°N 高于深圳福田 22.5448°N），故改用真实边界：香港北部边界线 + 澳门边界多边形。
// 本断言直接解析 CoordTransform.kt 里的边界数据并在 JS 侧复算区域判定，锁定大陆点不被误豁免。
console.log('[坐标豁免区域 CoordTransform.kt]');
const COORD = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/location/CoordTransform.kt');
const ct = read(COORD);
const constOf = (n) => {
  const m = ct.match(new RegExp('const val ' + n + '\\s*=\\s*(-?[0-9.]+)'));
  return m ? parseFloat(m[1]) : NaN;
};
const HK_LNG_MIN = constOf('HK_LNG_MIN'), HK_LNG_MAX = constOf('HK_LNG_MAX');
const HK_LAT_MIN = constOf('HK_LAT_MIN');
const GUISHAN_LNG_MAX = constOf('GUISHAN_LNG_MAX'), GUISHAN_LAT_MAX = constOf('GUISHAN_LAT_MAX');
const flatNumbers = (from, to) =>
  ct.slice(ct.indexOf(from) + from.length, ct.indexOf(to, ct.indexOf(from)))
    .split(/[,\s]+/).filter((t) => /^-?[0-9.]+$/.test(t)).map(parseFloat);
const border = flatNumbers('private val HK_BORDER = doubleArrayOf(', 'private val MACAU_RINGS');
const macauFlat = flatNumbers('private val MACAU_RINGS = arrayOf(', 'private fun transformLat');
// 澳门按 doubleArrayOf(...) 分组还原成环
const macauRings = [];
{
  const seg = ct.slice(ct.indexOf('private val MACAU_RINGS = arrayOf('), ct.indexOf('private fun transformLat'));
  const groups = seg.split('doubleArrayOf(').slice(1);
  for (const g of groups) {
    const nums = g.slice(0, g.indexOf(')')).split(/[,\s]+/).filter((t) => /^-?[0-9.]+$/.test(t)).map(parseFloat);
    macauRings.push(nums);
  }
}
ok('香港边界折线可解析（经度升序、成对出现）',
  border.length >= 40 && border.length % 2 === 0 &&
  border.filter((_, i) => i % 2 === 0).every((v, i, a) => i === 0 || v > a[i - 1]),
  '顶点数=' + border.length / 2);
ok('澳门边界环可解析（每环闭合）',
  macauRings.length >= 2 && macauRings.every((r) => r.length >= 8 && r[0] === r[r.length - 2] && r[1] === r[r.length - 1]),
  '环数=' + macauRings.length + ' 顶点=' + macauRings.map((r) => r.length / 2).join('/'));

/** 香港北部边界线在给定经度上的纬度上限（与 Kotlin 实现同算法：二分 + 线性插值） */
function hkBorderLat(lng) {
  const last = border.length / 2 - 1;
  if (lng <= border[0]) return border[1];
  if (lng >= border[last * 2]) return border[last * 2 + 1];
  let lo = 0, hi = last;
  while (hi - lo > 1) {
    const mid = (lo + hi) >> 1;
    if (border[mid * 2] <= lng) lo = mid; else hi = mid;
  }
  const x1 = border[lo * 2], y1 = border[lo * 2 + 1], x2 = border[hi * 2], y2 = border[hi * 2 + 1];
  return y1 + (lng - x1) / (x2 - x1) * (y2 - y1);
}
function inHongKong(lat, lng) {
  if (lat < HK_LAT_MIN || lng < HK_LNG_MIN || lng > HK_LNG_MAX) return false;
  if (lng <= GUISHAN_LNG_MAX && lat <= GUISHAN_LAT_MAX) return false;
  return lat <= hkBorderLat(lng);
}
function inMacau(lat, lng) {
  let inside = false;
  for (const ring of macauRings) {
    for (let i = 0; i + 3 < ring.length; i += 2) {
      const ax = ring[i], ay = ring[i + 1], bx = ring[i + 2], by = ring[i + 3];
      if ((ay > lat) !== (by > lat) && lng < ax + (lat - ay) * (bx - ax) / (by - ay)) inside = !inside;
    }
  }
  return inside;
}
const exempt = (lat, lng) =>
  inHongKong(lat, lng) || inMacau(lat, lng) || (lng >= 119.90 && lng <= 122.01 && lat >= 21.87 && lat <= 25.35);

// 大陆点（深圳主城区 + 珠海拱北/横琴/湾仔 + 桂山岛）：必须不被豁免，即必须施加 GCJ 偏移
const MAINLAND = {
  深圳市民中心: [22.5448, 114.0545], 深圳福田区府: [22.5410, 114.0550], 深圳罗湖口岸: [22.5310, 114.1178],
  深圳南山: [22.5330, 113.9300], 深圳宝安中心: [22.5550, 113.8830], 深圳盐田: [22.5570, 114.2360],
  深圳沙头角: [22.5480, 114.2400], 深圳前海: [22.5270, 113.8980], 深圳蛇口: [22.4800, 113.9200],
  深圳福田口岸: [22.5333, 114.0666], 深圳皇岗口岸: [22.5210, 114.0700], 深圳大铲岛: [22.4700, 113.8600],
  珠海拱北口岸: [22.2190, 113.5450], 珠海横琴口岸: [22.1120, 113.5300], 珠海湾仔: [22.1930, 113.5320],
  珠海香洲: [22.2700, 113.5500], 珠海桂山岛: [22.1500, 113.8300], 珠海外伶仃岛: [22.0980, 114.0300],
  北京天安门: [39.9073, 116.3912], 上海人民广场: [31.2304, 121.4737], 广州天河: [23.1250, 113.3610],
};
const badMainland = Object.entries(MAINLAND).filter(([, [lat, lng]]) => exempt(lat, lng)).map(([n]) => n);
ok('大陆地标不被误豁免（深圳/珠海等 ' + Object.keys(MAINLAND).length + ' 点）',
  badMainland.length === 0, '误豁免: ' + badMainland.join('、'));

// 香港点（含离岛与最北/最南边界）：必须仍被豁免
const HK = {
  中环: [22.2830, 114.1560], 尖沙咀: [22.2970, 114.1720], 上水: [22.5020, 114.1280], 元朗: [22.4440, 114.0320],
  天水围: [22.4600, 114.0040], 屯门: [22.3920, 113.9760], 东涌: [22.2890, 113.9430], 长洲: [22.2110, 114.0290],
  西贡: [22.3820, 114.2740], 沙田: [22.3830, 114.1910], 将军澳: [22.3120, 114.2600], 赤柱: [22.2160, 114.2200],
  东平洲: [22.5400, 114.4300], 塔门: [22.4710, 114.3630], 蒲台岛: [22.1660, 114.2630],
  香港国际机场: [22.3090, 113.9150], 沙头角港侧: [22.5425, 114.2300], 迪士尼: [22.3130, 114.0450],
  南丫岛: [22.2050, 114.1250], 坪洲: [22.2860, 114.0390], 吉澳: [22.5420, 114.2930], 罗湖站: [22.5270, 114.1170],
  分流: [22.1960, 113.8480],
};
const badHk = Object.entries(HK).filter(([, [lat, lng]]) => !exempt(lat, lng)).map(([n]) => n);
ok('香港地标仍被豁免（含离岛 ' + Object.keys(HK).length + ' 点）', badHk.length === 0, '漏豁免: ' + badHk.join('、'));

// 澳门点：必须仍被豁免；珠海相邻点在上一组已覆盖
const MO = { 澳门半岛: [22.1980, 113.5490], 氹仔: [22.1570, 113.5560], 路环黑沙: [22.1200, 113.5670], 澳门机场: [22.1520, 113.5900], 关闸: [22.2130, 113.5520] };
const badMo = Object.entries(MO).filter(([, [lat, lng]]) => !exempt(lat, lng)).map(([n]) => n);
ok('澳门地标仍被豁免（' + Object.keys(MO).length + ' 点）', badMo.length === 0, '漏豁免: ' + badMo.join('、'));

ok('已移除港澳外接矩形（不再出现旧矩形常量）',
  !/113\.82\.\.114\.44/.test(ct) && !/113\.52\.\.113\.63/.test(ct));
ok('台湾矩形保留（其范围内无大陆陆地，无同类缺陷）', /lng in 119\.90\.\.122\.01 && lat in 21\.87\.\.25\.35/.test(ct));

// ---- 2026-09-24 全量审查修复回归锁定 ----
console.log('[P1 加入提交可被返回取消 AddFamilyScreen.kt]');
// room-check 最长 15s 才回调；期间用户返回离开页面后，迟到回调仍会 doSubmit()：
// 违背用户取消意图写入家庭码、清空成员列表并启动共享服务。修复形态：组合生命周期门控。
const ADD_FAMILY = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/ui/family/AddFamilyScreen.kt');
const afs = read(ADD_FAMILY);
ok('存在页面存活标记（remember + DisposableEffect 置 false）',
  /var pageActive by remember \{ mutableStateOf\(true\) \}/.test(afs) &&
  /DisposableEffect\(Unit\) \{\s*\n\s*onDispose \{ pageActive = false \}/.test(afs));
ok('room-check 回调先检查 pageActive 再提交（离开页面后不再 doSubmit）',
  /checkRoom\(code\) \{[\s\S]{0,400}?if \(!pageActive\) return@checkRoom/.test(afs));

console.log('[P2 撤销自动授权不得在主线程执行特权命令 HomeScreen.kt]');
// revokeAutoGranted → PrivShell.exec 底层是阻塞 socket/binder IO：
// 走内置 ADB 通道时主线程 socket 抛 NetworkOnMainThreadException（撤销必失败且误断通道），
// 走 Shizuku 载体时 readText() 阻塞主线程至 10s（ANR）。修复形态：withContext(Dispatchers.IO)。
ok('撤销调用包裹在 withContext(Dispatchers.IO)',
  /withContext\(Dispatchers\.IO\) \{\s*\n?\s*AdbAutoGrant\.revokeAutoGranted\(context\)/.test(hs));
ok('已引入 withContext/Dispatchers 导入',
  /import kotlinx\.coroutines\.Dispatchers/.test(hs) && /import kotlinx\.coroutines\.withContext/.test(hs));

console.log('[P2 灭屏启动悬浮窗服务不得空转采样 FloatingWindowService.kt]');
// SCREEN_OFF/ON 是边沿触发广播：服务在灭屏期间被拉起（开机恢复/无障碍恢复/FGS 重投递）时
// 不会再收到 SCREEN_OFF，2s 采样（含特权 shell 直读）会整夜空转。修复形态：isInteractive 门控
// + SCREEN_ON 时补建监控器。
const FWS = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/service/FloatingWindowService.kt');
const fws = read(FWS);
ok('startMonitoring 有灭屏门控（isInteractive 为 false 时不启动采样）',
  /private fun startMonitoring\(\)[\s\S]{0,400}?isInteractive/.test(fws));
ok('SCREEN_ON 时监控器缺失会补建（先判空再 start）',
  /Intent\.ACTION_SCREEN_ON -> \{[\s\S]{0,200}?if \(batteryMonitor == null\) startMonitoring\(\)/.test(fws));

console.log('[P2 非 START 动作不得遗留僵尸家人服务 FamilyLocationService.kt]');
// 服务未 setup 时被 REQUEST_LOCATION/APPROVE/REJECT 拉起：isRunning=true 但无信令连接，
// 会短路无障碍恢复路径（tryRestoreFamilyService 判 isRunning 即返回）且 UI 状态失真。
// 修复形态：这三个分支在 signal==null 时 stopSelf()。
const FLS = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/service/FamilyLocationService.kt');
const fls = read(FLS);
const cntStopSelfGuard = (fls.match(/if \(signal == null\) stopSelf\(\)/g) || []).length;
ok('三个非 START 动作分支均有 signal==null 即 stopSelf 守卫（共 3 处）',
  cntStopSelfGuard === 3, '实际 ' + cntStopSelfGuard + ' 处');

console.log('[P2 特权通道并发连接竞态 AdbConnectionManager.kt]');
// connectOnceInternal 约定"调用方持有 connectMutex"；setEnabled/onPaired/keyInit 三处曾裸调用，
// 并发连接各自 closeClientQuietly 互踢对方 client（实测模拟器环回通道 1ms 内多线程同时"已连接"，
// 随后 exec 失败(Socket closed/not A_WRTE or A_CLSE) 引发重连风暴与守护进程令牌 churn）。
const ACM = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/adb/AdbConnectionManager.kt');
const acm = read(ACM);
ok('不再存在未持锁的 scope.launch { connectOnceInternal() }',
  (acm.match(/scope\.launch \{ connectOnceInternal\(\) \}/g) || []).length === 0);
ok('三处触发点（setEnabled/onPaired/keyInit）均持 connectMutex 调用',
  (acm.match(/connectMutex\.withLock \{ connectOnceInternal\(\) \}/g) || []).length === 3,
  '实际 ' + (acm.match(/connectMutex\.withLock \{ connectOnceInternal\(\) \}/g) || []).length + ' 处');
ok('keyInit 就绪后的补连接在锁内',
  /if \(created != null && enabled\) \{[\s\S]{0,120}?connectMutex\.withLock \{ connectOnceInternal\(\) \}/.test(acm));

console.log('[P2 内置守护进程拉起防重入 BfdChannel.kt]');
// 并发连接各自 launchCarrier → 多个 startViaAdb 同时跑：每个都换令牌并杀旧实例，
// 与在途 ping/exec 竞态（实测 diag 日志"尝试1/2/3"跨线程交错）。修复形态与 ShizukuChannel 一致。
const BFD = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/adb/BfdChannel.kt');
const bfd = read(BFD);
ok('startViaAdb 有 AtomicBoolean 防重入守卫',
  /AtomicBoolean\(false\)/.test(bfd) && /if \(!starting\.compareAndSet\(false, true\)\)/.test(bfd));
ok('守卫在 finally 中复位', /finally \{[\s\S]{0,40}?starting\.set\(false\)/.test(bfd));

console.log('\n== 结果: ' + pass + ' 通过 / ' + fail + ' 失败 ==');
process.exit(fail === 0 ? 0 : 1);
