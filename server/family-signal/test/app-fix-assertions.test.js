'use strict';
/**
 * App 侧修复的源码级回归断言（零依赖）。
 *
 * 这些缺陷都是"行为静默错误"或"产品决定"，Kotlin 编译期无法发现，
 * 故用源码断言锁定修复形态，防止后续改动把逻辑或决定改回去。
 *
 * 运行：node server/family-signal/test/app-fix-assertions.test.js
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..', '..');
const AUTO_GRANT = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/adb/AdbAutoGrant.kt');
const AUTO_GRANT_CARD = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/ui/AdbAutoGrantCard.kt');
const PRIV_BASELINE = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/adb/PrivBaseline.kt');
const HOME = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/ui/HomeScreen.kt');
const A11Y_HEALER = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/service/A11ySelfHealer.kt');
const ADB_KEY = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/adb/AdbKey.kt');

let pass = 0, fail = 0;
function ok(name, cond, extra) {
  if (cond) { pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (extra ? '  -> ' + extra : '')); }
}
const read = (p) => fs.readFileSync(p, 'utf8');

console.log('\n== App 侧修复源码断言 ==\n');

const ag = read(AUTO_GRANT);
const hs = read(HOME);

// ---- 产品决定：撤销自动授权功能整体移除（2026-09） ----
// 撤销遍历中撤到运行时权限(定位/通知)会被系统强杀进程，序列后段执行不到；进程重启后
// 特权通道重连，自动授予流程又把已撤项原样授回。既不可靠也不可解释，故整体移除该功能，
// 自动授权卡片转为只读展示。本组断言锁定"不被误加回来"。
console.log('[撤销自动授权功能已移除 AdbAutoGrant.kt / AdbAutoGrantCard.kt]');
const agc = read(AUTO_GRANT_CARD);
ok('AdbAutoGrant 不再提供撤销入口（revokeAutoGranted 已移除）',
  !/fun revokeAutoGranted/.test(ag));
ok('AdbAutoGrant 不再有反向授权命令（pm revoke / appops default / whitelist -）',
  !/pm revoke/.test(ag) && !/SYSTEM_ALERT_WINDOW default/.test(ag) && !/deviceidle whitelist -/.test(ag));
ok('不再有撤销专用辅助函数与常量（removeGrantLog / ACCESSIBILITY_REVOKE_GRACE_MS）',
  !/removeGrantLog/.test(ag) && !/ACCESSIBILITY_REVOKE_GRACE_MS/.test(ag));
ok('自动授权卡片无撤销按钮/弹窗回调（只读展示）',
  !/onRevoke/.test(agc) && !/撤销全部自动授权/.test(agc) && !/AlertDialog/.test(agc));
ok('HomeScreen 不再持有撤销协程（无 revokeAutoGranted / rememberCoroutineScope）',
  !/revokeAutoGranted/.test(hs) && !/rememberCoroutineScope/.test(hs));
ok('自动授权卡片仍保留透明化展示（条目状态仍可见）',
  /items\.forEach \{ item ->/.test(agc) && /if \(item\.granted\) "已生效" else "已失效"/.test(agc));

// ---- 无障碍"用户意图"标记（2026-09 审查修复） ----
// 实例缺失(crashed/未重连)时无法 disableSelf，旧实现只跳系统设置而不打标记：用户随后在
// 系统设置里关掉服务，onDestroy 钩子与周期巡检判定不出用户意图，会把刚关掉的服务写回
// （表现为"无障碍关不掉"）。修复形态：else 分支同样打标记；开启分支提前清除标记，
// 避免"先关后开"后标记残留、自愈被一直压制。
// 标记的另一面是"用户点了关闭却没完成"：此时自愈被暂停而用户无从得知，故首页对
// 「系统侧仍开启 + 标记为关」这一组合显性提示，并提供取消入口（清除标记）。
console.log('[无障碍用户意图标记 HomeScreen.kt]');
ok('实例缺失分支先打「用户主动关」标记再跳系统设置',
  /A11ySelfHealer\.markUserDisabled\(context, true\)\s*\n\s*(a11yUserDisabled = true\s*\n\s*)?onOpenAccessibilitySettings\(\)/.test(hs));
ok('开启分支清除标记（先关后开不留残留）',
  /if \(enable\) \{[\s\S]{0,400}?A11ySelfHealer\.markUserDisabled\(context, false\)/.test(hs));
ok('关闭请求未完成态可派生（系统侧仍开启 且 标记为关）',
  /val a11yPendingOff = a11yKeepAlive && a11yUserDisabled/.test(hs));
ok('该状态提供取消入口（清除标记恢复自愈）',
  /if \(a11yPendingOff\) \{[\s\S]{0,1500}?A11ySelfHealer\.markUserDisabled\(context, false\)/.test(hs));
const healer = read(A11Y_HEALER);
ok('自愈与自动授权均以该标记为门控（尊重用户意图）',
  /if \(isUserDisabled\(ctx\)\) return@launch/.test(healer) &&
  /if \(isUserDisabled\(ctx\)\) return false/.test(healer));

// ---- 家人电量共享：stat-req / stat-res（2026-09 新增能力） ----
// 电量沿用的是与位置同一套"远端不可信"假设：服务端中继不校验来源，客户端必须自行
// 约束"只接受曾请求成员的回包"；且载荷校验要覆盖 Gson 缺字段退化出的 0（fail-closed）。
console.log('[家人电量共享 stat-req/stat-res]');
const PROTOCOL = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/p2p/SignalProtocol.kt');
const SERVICE = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/service/FamilyLocationService.kt');
// 信令分发与应答自 2026-09 起搬移进 FamilySignalHandler.kt（纯搬移零行为变化）：
// 下方 stat-req/stat-res 与 loc-res 断言改指向新文件，断言内容与计数一概不变
const HANDLER = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/service/FamilySignalHandler.kt');
const STORE = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/family/FamilyStore.kt');
const SERVER_JS = path.join(ROOT, 'server/family-signal/server.js');
const proto = read(PROTOCOL);
const service = read(SERVICE);
const handler = read(HANDLER);
const store = read(STORE);
const serverJs = read(SERVER_JS);
ok('协议常量两端一致（stat-req / stat-res）',
  /STAT_REQ = "stat-req"/.test(proto) && /STAT_RES = "stat-res"/.test(proto) &&
  /case 'stat-req'/.test(serverJs) && /case 'stat-res'/.test(serverJs));
ok('只接受曾请求成员的状态应答（防伪造 / 幽灵成员）',
  /SignalTypes\.STAT_RES -> \{[\s\S]{0,300}?val requestedAt = requestedStatus\[from\][\s\S]{0,200}?if \(requestedAt == null\)/.test(handler));
ok('状态载荷校验覆盖电量越界与缺失时间戳',
  /private fun isPlausibleStatus[\s\S]{0,300}?status\.ts <= 0L/.test(handler) &&
  /SignalTypes\.STAT_RES -> \{[\s\S]{0,900}?if \(!isPlausibleStatus\(status\)\)/.test(handler));
ok('隐私开关同时约束状态请求（关闭后位置与电量都不应答）',
  /SignalTypes\.STAT_REQ -> \{[\s\S]{0,200}?if \(!s\.allowLocReq\(\)\)/.test(handler));
ok('loc-res 准入校验未被放宽（曾请求 + 有效期 + 载荷合理性三层俱在）',
  /SignalTypes\.LOC_RES -> \{[\s\S]{0,300}?val requestedAt = requestedLocations\[from\][\s\S]{0,200}?if \(requestedAt == null\)/.test(handler) &&
  /SignalTypes\.LOC_RES -> \{[\s\S]{0,600}?LOC_RES_TTL_MS[\s\S]{0,400}?if \(!isPlausibleLocation\(loc\)\)/.test(handler));
ok('信令分发已抽到 FamilySignalHandler（主服务仅保留委托，不再重复实现）',
  /internal class FamilySignalHandler\(private val host: FamilyLocationService\)/.test(handler) &&
  /it\.onMessage = signalHandler::handleSignal/.test(service) &&
  !/SignalTypes\.STAT_RES ->/.test(service));
ok('电量只落到名册内成员（不自动建档）',
  /fun updateBattery\(uid: String, battery: Int, ts: Long\) \{[\s\S]{0,200}?val current = _members\.value\[uid\] \?: return/.test(store));
ok('服务端对状态载荷做白名单（电量 0-100）',
  /function sanitizeStatusPayload/.test(serverJs) && /battery < 0 \|\| battery > 100/.test(serverJs));
ok('服务端状态请求用独立限流桶（不占用位置请求额度）',
  /rateLimited\('stat:' \+ ws\.room/.test(serverJs));

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
// 权限回调分支同样能拉起共享（进入家人 Tab 会自动弹授权，用户点允许即触发），
// 必须与自动恢复分支共用同一门控；且"定位是否已授予"不能只看回调结果 map——
// RequestMultiplePermissions 的结果里只有本次被请求过的权限
const permCallback = (() => {
  const i = fsc.indexOf('ActivityResultContracts.RequestMultiplePermissions()');
  const j = fsc.indexOf('LaunchedEffect(Unit) {', i);
  return (i >= 0 && j > i) ? fsc.slice(i, j) : '';
})();
ok('权限回调分支受 FAMILY_WAS_RUNNING 门控（不把用户主动停止的共享静默拉起）',
  /prefs\.getBoolean\(PrefsKeys\.FAMILY_WAS_RUNNING, false\)/.test(permCallback),
  '权限回调分支缺少 FAMILY_WAS_RUNNING 门控');
ok('定位授予判定复核真实权限状态（结果 map 只含本次被请求的权限）',
  /result\[Manifest\.permission\.ACCESS_FINE_LOCATION\] == true[\s\S]{0,500}?checkSelfPermission\(/.test(permCallback),
  '仅按结果 map 判定会在只缺通知权限时把已授予的定位误判为未授予');

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
// 服务未 setup 时被 REQUEST_LOCATION/REQUEST_STATUS/APPROVE/REJECT 拉起：isRunning=true 但无
// 信令连接，会短路无障碍恢复路径（tryRestoreFamilyService 判 isRunning 即返回）且 UI 状态失真。
// 修复形态：这些分支在 signal==null 时 stopSelf()（新增请求类型必须照此加守卫）。
const FLS = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/service/FamilyLocationService.kt');
const fls = read(FLS);
const cntStopSelfGuard = (fls.match(/if \(signal == null\) stopSelf\(\)/g) || []).length;
// 2026-09 新增地点提醒轮询（ACTION_ALERT_POLL）后共 5 个非 START 动作；该分支用块式守卫
// （要先退出分支再轮询，写成单行会把轮询也放过去），故两类写法分别锁死，总数只增不减。
// 2026-09-27 起该分支在自停前先撤销轮询任务（通道建不起来还留着任务就是每 N 分钟空转唤醒），
// 故块式匹配只锁"必须 stopSelf"，不再要求它是首句——轮询分支的重建/续期另有
// geofence-assertions.test.js 的三条结构式断言单独锁死。
const cntStopSelfGuardBlock = (fls.match(/if \(signal == null\) \{\s*\n[\s\S]{0,120}?stopSelf\(\)/g) || []).length;
ok('非 START 动作全部有 signal==null 即 stopSelf 守卫（4 处单行 + 1 处块式，共 5）',
  cntStopSelfGuard === 4 && cntStopSelfGuardBlock === 1,
  '实际单行 ' + cntStopSelfGuard + ' 处 / 块式 ' + cntStopSelfGuardBlock + ' 处');
ok('地点提醒轮询分支同时受该守卫约束（不留僵尸实例）',
  /ACTION_ALERT_POLL -> \{[\s\S]{0,400}?if \(signal == null\) \{[\s\S]{0,120}?stopSelf\(\)/.test(fls));

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
