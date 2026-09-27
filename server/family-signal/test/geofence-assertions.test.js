'use strict';
/**
 * 家人到达/离开提醒（地点提醒）的源码级回归断言（零依赖）。
 *
 * 这一组锁的是"决定与阈值"：滞回系数、半径与间隔上下限、去重与新鲜度窗口、
 * 通知渠道名、准入校验的挂载点、诊断日志脱敏。它们编译期无法发现，
 * 被改回"在边界来回刷通知"或"把 loc-res 准入放宽"都不会报错。
 *
 * 运行：node server/family-signal/test/geofence-assertions.test.js
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..', '..');
const PKG = 'app/src/main/java/com/example/batteryfloat';
const EVALUATOR = path.join(ROOT, PKG, 'family/GeofenceEvaluator.kt');
const PLACE_STORE = path.join(ROOT, PKG, 'family/PlaceStore.kt');
const DIAG_LOG = path.join(ROOT, PKG, 'diag/DiagLog.kt');
const SIGNAL_CLIENT = path.join(ROOT, PKG, 'p2p/SignalClient.kt');
const SERVICE = path.join(ROOT, PKG, 'service/FamilyLocationService.kt');
const HANDLER = path.join(ROOT, PKG, 'service/FamilySignalHandler.kt');
const NOTIFS = path.join(ROOT, PKG, 'notif/Notifs.kt');
const PREFS = path.join(ROOT, PKG, 'PrefsKeys.kt');
const ALERT_SCREEN = path.join(ROOT, PKG, 'ui/family/FamilyAlertScreen.kt');
const LIST_CONTENT = path.join(ROOT, PKG, 'ui/family/FamilyListContent.kt');
const FAMILY_SCREEN = path.join(ROOT, PKG, 'ui/family/FamilyScreen.kt');
const STRINGS = path.join(ROOT, 'app/src/main/res/values/strings.xml');
const README = path.join(ROOT, 'README.md');

let pass = 0, fail = 0;
function ok(name, cond, extra) {
  if (cond) { pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (extra ? '  -> ' + extra : '')); }
}
const read = (p) => fs.readFileSync(p, 'utf8');
const exists = (p) => fs.existsSync(p);

console.log('\n== 家人到达/离开提醒源码断言 ==\n');

console.log('[新文件与职责边界]');
for (const [name, p] of [
  ['family/GeofenceEvaluator.kt（纯函数判定）', EVALUATOR],
  ['family/PlaceStore.kt（地点与状态存储）', PLACE_STORE],
  ['diag/DiagLog.kt（脱敏诊断落盘）', DIAG_LOG],
  ['ui/family/FamilyAlertScreen.kt（提醒 UI）', ALERT_SCREEN]
]) {
  ok('新文件存在：' + name, exists(p));
}
const ev = read(EVALUATOR);
const ps = read(PLACE_STORE);
const svc = read(SERVICE);
const handler = read(HANDLER);
const notifs = read(NOTIFS);
const prefs = read(PREFS);
const strings = read(STRINGS);
const diag = read(DIAG_LOG);

console.log('[判定核心：纯函数、双阈值滞回、样本过滤]');
ok('判定核心不依赖 Android（无 android./Context 引用）',
  !/import android\./.test(ev) && !/Context/.test(ev));
ok('滞回系数为 1.25（离开阈值 = 半径 × 1.25）',
  /const val EXIT_HYSTERESIS_FACTOR = 1\.25/.test(ev) &&
  /val exitRadius = radiusMeters \* EXIT_HYSTERESIS_FACTOR/.test(ev));
ok('进入阈值 = 半径（distance <= radius 判在内）',
  /distanceMeters <= radiusMeters -> true/.test(ev));
ok('离开判定用放大后的阈值（distance > exitRadius 才判在外）',
  /distanceMeters > exitRadius -> false/.test(ev));
ok('滞回区内保持上次状态（不抖动）',
  /else -> previous\?\.inside \?: false/.test(ev));
ok('精度差于半径的样本丢弃', /if \(accuracyMeters > radiusMeters\) return Decision\.Discarded/.test(ev));
ok('超出新鲜度窗口的样本丢弃（15 分钟）',
  /const val SAMPLE_FRESHNESS_MS = 15 \* 60_000L/.test(ev) &&
  /nowMs - sampleTs > SAMPLE_FRESHNESS_MS\) return Decision\.Discarded/.test(ev));
ok('首次样本只建立基线（不通知）', /if \(previous == null\) return Decision\.Quiet/.test(ev));
ok('同一（成员 × 地点）10 分钟内至多一条提醒',
  /const val NOTIFY_COOLDOWN_MS = 10 \* 60_000L/.test(ev) &&
  /nowMs - previous\.lastNotifyAt < NOTIFY_COOLDOWN_MS/.test(ev));
ok('距离用 Haversine（含地球半径常量）',
  /private const val EARTH_RADIUS_M = 6_371_008\.8/.test(ev) && /fun distanceMeters\(/.test(ev));

console.log('[地点存储：半径与间隔上下限、状态落盘]');
ok('半径限 100-5000 米（并有收敛而非报错）',
  /const val RADIUS_MIN_M = 100/.test(ps) && /const val RADIUS_MAX_M = 5000/.test(ps) &&
  /coerceIn\(RADIUS_MIN_M, RADIUS_MAX_M\)/.test(ps));
ok('轮询间隔默认 10 分钟、下限 5 分钟',
  /const val INTERVAL_MIN_MINUTES = 5/.test(ps) &&
  /const val INTERVAL_DEFAULT_MINUTES = 10/.test(ps) &&
  /coerceIn\(INTERVAL_MIN_MINUTES, INTERVAL_MAX_MINUTES\)/.test(ps));
ok('地点字段齐备（name/lat/lng/radiusMeters/watchUids/enabled）',
  /data class AlertPlace\(/.test(ps) && /val name: String/.test(ps) && /val lat: Double/.test(ps) &&
  /val lng: Double/.test(ps) && /val radiusMeters: Int/.test(ps) &&
  /val watchUids: List<String> = emptyList\(\)/.test(ps) && /val enabled: Boolean = true/.test(ps));
ok('watchUids 为空即监视全部成员（语义写进注释与轮询实现）',
  /watchUids 为空的地点视为监视全部在线成员|空 = 监视全部成员/.test(ps) &&
  /if \(place\.watchUids\.isEmpty\(\)\) \{[\s\S]{0,120}?online\.forEach/.test(svc));
ok('每个（成员 × 地点）状态落盘、重启延续',
  /fun putState\(uid: String, placeId: String, state: GeofenceEvaluator\.GeofenceState\)/.test(ps) &&
  /fun stateFor\(uid: String, placeId: String\)/.test(ps) &&
  /loadStates\(\)/.test(ps));
ok('删除地点时一并清除其判定状态',
  /fun remove\(id: String\)[\s\S]{0,400}?filterKeys \{ !it\.endsWith\(KEY_SEPARATOR \+ id\) \}/.test(ps));
ok('存储键进 PrefsKeys（地点/总开关/状态/间隔）',
  /const val FAMILY_ALERT_PLACES = "family_alert_places"/.test(prefs) &&
  /const val FAMILY_ALERT_ENABLED = "family_alert_enabled"/.test(prefs) &&
  /const val FAMILY_ALERT_STATES = "family_alert_states"/.test(prefs) &&
  /const val FAMILY_ALERT_INTERVAL_MIN = "family_alert_interval_min"/.test(prefs));

console.log('[调度与通知：非精确轮询、只在转换时发一条]');
ok('非精确定时任务（setAndAllowWhileIdle，非 setRepeating）',
  /setAndAllowWhileIdle\(/.test(svc) && !/\.setRepeating\(/.test(svc));
ok('轮询投递本服务的 ACTION_ALERT_POLL（触发后由服务续下一次）',
  /const val ACTION_ALERT_POLL = "com\.yongge\.batteryfloat\.action\.FAMILY_ALERT_POLL"/.test(svc) &&
  /PendingIntent\.getService\([\s\S]{0,200}?setAction\(ACTION_ALERT_POLL\)/.test(svc) &&
  /ACTION_ALERT_POLL -> \{[\s\S]{0,400}?syncAlertPoll\(this\)/.test(svc));
// 结构式断言：取出 ACTION_ALERT_POLL 分支本体（到同级右括号为止），比窗口式匹配更不易误判。
// 这三条锁"轮询链永不断链"：进程被回收后由告警直接拉起时通道为空，
// 旧实现只 stopSelf 不续期，地点提醒会静默停摆（真机/模拟器均已复现）。
const pollBranch = (() => {
  const i = svc.indexOf('ACTION_ALERT_POLL -> {');
  if (i < 0) return '';
  const j = svc.indexOf('\n            }', i);
  return j > i ? svc.slice(i, j) : '';
})();
ok('轮询分支：通道未建立时按用户意图重建（与开机广播/无障碍恢复共用 shouldAutoRestore 门控）',
  /if \(signal == null && shouldAutoRestore\(this\)\) setup\(\)/.test(pollBranch),
  '告警送达时不再重建通道，轮询链会静默断掉');
ok('轮询分支：通道建不起来时撤销轮询并退出（不留死链、不留僵尸实例）',
  /cancelAlertPoll\(this\)/.test(pollBranch) && /stopSelf\(\)/.test(pollBranch));
ok('轮询分支：通道可用时续下一次轮询并立即请求位置',
  /syncAlertPoll\(this\)/.test(pollBranch) && /pollAlertPlaces\(\)/.test(pollBranch));
ok('总开关关闭或无启用地点时撤销任务（不空转唤醒）',
  /if \(am == null \|\| !store\.alertsEnabled\(\) \|\| store\.activePlaces\(\)\.isEmpty\(\)\) \{\s*\n\s*cancelAlertPoll\(context\)/.test(svc));
ok('停止共享即撤销轮询（不再请求家人位置）',
  /ACTION_STOP -> \{[\s\S]{0,400}?cancelAlertPoll\(this\)/.test(svc));
ok('轮询目标复用 requestMemberLocation（loc-res 准入记账不放宽）',
  /for \(uid in targets\) signalHandler\.requestMemberLocation\(uid\)/.test(svc));
ok('只有通过准入校验的位置才会喂给判定器',
  /if \(!isPlausibleLocation\(loc\)\) \{[\s\S]{0,200}?s\.updateLocation\(from, loc\)\s*\n\s*onLocation\?\.invoke\(from, loc\)/.test(handler));
ok('只在转换（Alert）时发通知，Quiet/Discarded 不发',
  /is GeofenceEvaluator\.Decision\.Alert -> \{[\s\S]{0,600}?notifyPlaceAlert\(/.test(svc) &&
  (svc.match(/notifyPlaceAlert\(/g) || []).length === 2); // 定义 1 处 + 调用 1 处
ok('只对名册内成员判定（防幽灵成员）',
  /val member = FamilyStore\.get\(this\)\.members\.value\[uid\] \?: return/.test(svc));
ok('文案形如「ma 到达 学校」/「ma 离开 学校」（带成员名与地点名）',
  /family_alert_arrive_title">%1\$s 到达 %2\$s</.test(strings) &&
  /family_alert_leave_title">%1\$s 离开 %2\$s</.test(strings) &&
  /family_alert_time_text">时间：%1\$s</.test(strings));

console.log('[通知渠道]');
ok('新增渠道「家人地点提醒」（family_place_alert）',
  /const val CHANNEL_FAMILY_ALERT = "family_place_alert"/.test(notifs) &&
  /notification_family_alert_channel">家人地点提醒</.test(strings));
ok('渠道被幂等创建（ensureChannels 内），且提醒会先确保渠道存在',
  /CHANNEL_FAMILY_ALERT,[\s\S]{0,200}?IMPORTANCE_DEFAULT/.test(notifs) &&
  /Notifs\.ensureChannels\(this\)[\s\S]{0,200}?familyPlaceAlert/.test(svc));
ok('同一（成员 × 地点）固定通知 id（重复提醒覆盖不堆叠）',
  /fun familyAlertId\(uid: String, placeId: String\): Int/.test(notifs) &&
  /Notifs\.familyAlertId\(uid, place\.id\)/.test(svc));

console.log('[UI 入口]');
ok('家人列表有地点提醒入口卡片',
  /FamilyAlertEntryCard\(/.test(read(LIST_CONTENT)) && /internal fun FamilyAlertEntryCard\(/.test(read(ALERT_SCREEN)));
ok('家人页有地点提醒路由（列表 → 子页）',
  /data object Alerts : FamilyRoute/.test(read(FAMILY_SCREEN)) &&
  /onOpenAlerts = \{ route = FamilyRoute\.Alerts \}/.test(read(FAMILY_SCREEN)) &&
  /FamilyRoute\.Alerts -> FamilyAlertScreen\(/.test(read(FAMILY_SCREEN)));
ok('UI 提供总开关与增删改（新增/编辑/删除回调齐备）',
  /family_alert_master_switch/.test(read(ALERT_SCREEN)) &&
  /placeStore\.add\(/.test(read(ALERT_SCREEN)) && /placeStore\.update\(/.test(read(ALERT_SCREEN)) &&
  /placeStore\.remove\(/.test(read(ALERT_SCREEN)));
ok('每次变更后同步轮询任务（开关/频率/增删改）',
  (read(ALERT_SCREEN).match(/FamilyLocationService\.syncAlertPoll\(context\)/g) || []).length >= 5);

console.log('[隐私与代价：只存本机、代价写进 README]');
ok('地点与状态只存本机、不上行（无发送地点/状态的协议调用）',
  !/sendAlert|alert-req|place-req/.test(handler) && !/sendAlert|place-req/.test(svc) &&
  /不上行/.test(ps));
ok('被监视成员所见仍只是「有人请求了位置」（复用 loc-req，未新增上行字段）',
  /requestMemberLocation\(uid\)/.test(svc) && !/watchUids[\s\S]{0,80}?sendLocReq/.test(svc));
ok('README 写明定时请求会增加对方定位唤醒与耗电',
  /耗电/.test(read(README)) && /地点提醒|到达/.test(read(README)));

console.log('[诊断日志：脱敏、有上限、无明文密钥]');
ok('落盘有容量上限（超过即清空重写）',
  /private const val MAX_BYTES = 64_000L/.test(diag) && /if \(file\.length\(\) > MAX_BYTES\) file\.writeText\(""\)/.test(diag));
ok('写入口统一脱敏（mask 覆盖 uid 与家庭码两个模式）',
  /fun append\(context: Context, line: String\)[\s\S]{0,500}?mask\(line\)/.test(diag) &&
  /replace\(UID_PATTERN, "d-\*\*\*"\)/.test(diag) && /replace\(ROOM_PATTERN, "\*{6}"\)/.test(diag));
// 脱敏正则按 Kotlin 语义反转义后在 JS 侧真跑一遍：只比对字面量的话，
// 转义写错（少一个反斜杠 → \d 变成 d）也会"通过"，而那时脱敏其实已经失效
const BS = String.fromCharCode(92);
const unescapeKotlin = (s) => s.split(BS + BS).join(BS);
const roomSrc = (diag.match(/ROOM_PATTERN = Regex\("(.+?)"\)/) || [])[1];
const uidSrc = (diag.match(/UID_PATTERN = Regex\("(.+?)"\)/) || [])[1];
const roomRe = roomSrc ? new RegExp(unescapeKotlin(roomSrc)) : null;
const uidRe = uidSrc ? new RegExp(uidSrc) : null;
ok('家庭码模式：6 位数字被打码，5/7 位与关闭码不受影响（不误伤诊断信息）',
  !!roomRe && roomRe.test('509913') && !roomRe.test('50991') && !roomRe.test('1509913') && !roomRe.test('code=1006'));
ok('uid 模式：d- 开头长十六进制被打码，短串不受影响',
  !!uidRe && uidRe.test('d-0123456789abcdef') && !uidRe.test('d-0123'));
const maskJs = (v) => String(v).slice(0, 80).replace(uidRe, 'd-***').replace(roomRe, '******');
ok('脱敏后的诊断行既保留排障信息、又不含家庭码/uid 明文',
  maskJs('SIGNAL close code=1006 reason=509913 remote=true') ===
    'SIGNAL close code=1006 reason=****** remote=true' &&
  maskJs('SIGNAL open endpoint=1/2 uid=d-0123456789abcdef') ===
    'SIGNAL open endpoint=1/2 uid=d-***');
ok('连接事件落盘覆盖 open/close（含关闭码与 remote）',
  /SIGNAL open endpoint=/.test(read(SIGNAL_CLIENT)) &&
  /SIGNAL close " \+ detail/.test(read(SIGNAL_CLIENT)) &&
  /diag\("SIGNAL close-stale " \+ detail\)/.test(read(SIGNAL_CLIENT)));
ok('落盘行不含家庭码/uid 明文（无 room=/uid= 拼装）',
  (read(SIGNAL_CLIENT).match(/diag\([^\n]*\broom\b/g) || []).length === 0 &&
  (read(SIGNAL_CLIENT).match(/diag\([^\n]*\buid\b/g) || []).length === 0);
ok('服务注入落盘回调（写入口走 DiagLog）',
  /it\.diagLogger = \{ line -> DiagLog\.append\(this, line\) \}/.test(svc));

console.log('\n== 结果: ' + pass + ' 通过 / ' + fail + ' 失败 ==');
process.exit(fail === 0 ? 0 : 1);
