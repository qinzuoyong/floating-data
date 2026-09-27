'use strict';
/**
 * 家人到达/离开提醒的**数学层交叉验证**（零依赖）。
 *
 * 方法：按需求文字**独立实现一遍**判定（距离 + 双阈值滞回 + 样本过滤 + 去重），
 * 用同一组输入跑判定表，再把 Kotlin 源码里的常量解析出来逐一比对——
 * 实现与常量任何一边漂移（例如把滞回系数改成 1.0、把新鲜度窗口改成 1 小时）都会失败。
 *
 * 为什么不能只跑 App：判定是纯计算，模拟器上"看起来没提醒"既可能是滞回生效、
 * 也可能是阈值算错，只有把数字本身对一遍才能区分。
 *
 * 运行：node server/family-signal/test/geofence-math.test.js
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..', '..');
const EVALUATOR = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/family/GeofenceEvaluator.kt');
const PLACE_STORE = path.join(ROOT, 'app/src/main/java/com/example/batteryfloat/family/PlaceStore.kt');

let pass = 0, fail = 0;
function ok(name, cond, extra) {
  if (cond) { pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (extra ? '  -> ' + extra : '')); }
}
const near = (a, b, tol) => Math.abs(a - b) <= tol;

// ===== 独立实现（照需求文字重写，不是从 Kotlin 移植）=====
const EARTH_R = 6371008.8;          // 球面近似半径（与 Kotlin 侧同值，见下方常量比对）
const HYSTERESIS = 1.25;            // 离开阈值 = 半径 × 1.25
const FRESH_MS = 15 * 60 * 1000;    // 样本新鲜度窗口
const COOLDOWN_MS = 10 * 60 * 1000; // 同一（成员 × 地点）去重窗口

/** 球面距离（米）：用大圆公式的等价写法（半正矢 vs 余弦定理），两式互校 */
function haversine(lat1, lng1, lat2, lng2) {
  const rad = Math.PI / 180;
  const dLat = (lat2 - lat1) * rad;
  const dLng = (lng2 - lng1) * rad;
  const a = Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(dLng / 2) ** 2;
  return 2 * EARTH_R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}
function lawOfCosines(lat1, lng1, lat2, lng2) {
  const rad = Math.PI / 180;
  const c = Math.sin(lat1 * rad) * Math.sin(lat2 * rad) +
    Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.cos((lng2 - lng1) * rad);
  return EARTH_R * Math.acos(Math.min(1, Math.max(-1, c)));
}

/**
 * 判定（返回 {kind, inside?, entered?}）
 * @param prev   上次状态 {inside, lastNotifyAt} 或 null（首次）
 * @param radius 地点半径（米）
 * @param dist   样本到地点距离（米）
 * @param acc    样本精度（米）
 */
function evaluate(prev, radius, dist, acc, sampleTs, now) {
  if (acc > radius) return { kind: 'discarded' };
  if (sampleTs <= 0 || now - sampleTs > FRESH_MS) return { kind: 'discarded' };
  const exitRadius = radius * HYSTERESIS;
  let inside;
  if (dist <= radius) inside = true;
  else if (dist > exitRadius) inside = false;
  else inside = prev ? prev.inside : false;
  if (!prev) return { kind: 'quiet', inside };
  if (prev.inside === inside) return { kind: 'quiet', inside };
  if (now - prev.lastNotifyAt < COOLDOWN_MS) return { kind: 'quiet', inside };
  return { kind: 'alert', inside, entered: inside };
}

const NOW = 1_700_000_000_000;
const state = (inside, lastNotifyAt = 0) => ({ inside, lastNotifyAt });

console.log('\n== 到达/离开判定数学层交叉验证 ==\n');

console.log('[距离：两式互校 + 解析值核对]');
{
  const d1 = haversine(39.9073, 116.3912, 39.9083, 116.3912); // 正北 0.001°
  const d2 = lawOfCosines(39.9073, 116.3912, 39.9083, 116.3912);
  ok('0.001° 纬差 ≈ 111.19 米（球面半径 6371008.8）', near(d1, 111.195, 0.05), d1.toFixed(3));
  // 短距离上 acos 的条件数很差（两式差约 3e-7 相对），故短距离放到 1e-5；
  // 长距离（两式都良态）必须严丝合缝，用来锁公式本身没错
  ok('短距离两式一致（相对误差 < 1e-5，acos 在短距离上的固有精度损失）',
    near(d1 / d2, 1, 1e-5), (d1 / d2).toFixed(12));
  const bjShH = haversine(39.9073, 116.3912, 31.2304, 121.4737);
  const bjShC = lawOfCosines(39.9073, 116.3912, 31.2304, 121.4737);
  ok('北京—上海大圆距离 ≈ 1067 公里（已知量级）',
    bjShH / 1000 > 1050 && bjShH / 1000 < 1080, (bjShH / 1000).toFixed(1));
  ok('长距离两式一致（相对误差 < 1e-9）', near(bjShH / bjShC, 1, 1e-9), (bjShH / bjShC).toFixed(12));
  ok('同点距离为 0', haversine(31.23, 121.47, 31.23, 121.47) === 0);
  ok('距离对称', near(haversine(22.54, 114.05, 39.91, 116.39),
    haversine(39.91, 116.39, 22.54, 114.05), 1e-6));
}
{
  // 高纬度处同样的经度差对应的地面距离按 cos(lat) 收缩——验证经度项带 cos 因子
  const at39 = haversine(39.9, 116.0, 39.9, 117.0);
  const expect = 111.195 * Math.cos(39.9 * Math.PI / 180) * 1000;
  ok('1° 经差在 39.9°N ≈ 111.195 km × cos(lat)', near(at39, expect, 60), at39.toFixed(1));
  ok('经度项确含 cos(纬度)（不取整纬度比较的写法会偏出 30% 以上）',
    Math.abs(at39 - 111195) > 20000);
}
{
  // 小尺度（判定实际工作的量级）：与平面近似互校，相对误差应远小于 1%
  const lat0 = 39.9073, lng0 = 116.3912;
  const lat1 = lat0 + 300 / 111195;              // 正北 300 米
  const planar = 300;
  ok('300 米尺度与平面近似一致（误差 < 0.5 米）',
    near(haversine(lat0, lng0, lat1, lng0), planar, 0.5),
    haversine(lat0, lng0, lat1, lng0).toFixed(2));
}

console.log('[判定表：进入/离开/滞回/去重/丢弃]');
{
  const R = 300; // 半径 300 → 滞回区 (300, 375]
  ok('首次样本只建立基线（不通知）',
    evaluate(null, R, 5000, 10, NOW, NOW).kind === 'quiet' &&
    evaluate(null, R, 50, 10, NOW, NOW).inside === true);
  ok('半径外静止（无转换）不通知',
    evaluate(state(false), R, 1200, 10, NOW, NOW).kind === 'quiet');
  ok('进入半径 → 到达通知（唯一一次转换）',
    (() => { const r = evaluate(state(false), R, 50, 10, NOW, NOW); return r.kind === 'alert' && r.entered === true; })());
  ok('位置不变再判 → 不重复通知（状态未变）',
    evaluate(state(true, NOW), R, 50, 10, NOW, NOW).kind === 'quiet');
  ok('滞回区内（300 < d <= 375）保持在内、不抖动',
    evaluate(state(true), R, 350, 10, NOW, NOW).kind === 'quiet' &&
    evaluate(state(true), R, 375, 10, NOW, NOW).inside === true);
  ok('滞回区内反向保持在外（刚离开不会立刻又报到达）',
    evaluate(state(false), R, 350, 10, NOW, NOW).kind === 'quiet' &&
    evaluate(state(false), R, 350, 10, NOW, NOW).inside === false);
  ok('越过离开阈值（d > 375）→ 离开通知',
    (() => { const r = evaluate(state(true, NOW - COOLDOWN_MS), R, 376, 10, NOW, NOW); return r.kind === 'alert' && r.entered === false; })());
  ok('正好在半径上判为在内（<= 半径）',
    evaluate(state(false), R, R, 10, NOW, NOW).entered === true);
  ok('精度差于半径的样本被丢弃（不误报）',
    evaluate(state(false), R, 50, R + 1, NOW, NOW).kind === 'discarded');
  ok('精度等于半径仍可用（只有"差于"才丢）',
    evaluate(state(false), R, 50, R, NOW, NOW).kind === 'alert');
  ok('超出新鲜度窗口（>15 分钟）的样本被丢弃',
    evaluate(state(false), R, 50, 10, NOW - FRESH_MS - 1, NOW).kind === 'discarded' &&
    evaluate(state(false), R, 50, 10, NOW - FRESH_MS + 1, NOW).kind === 'alert');
  ok('时间戳为 0（缺字段）按不可用丢弃',
    evaluate(state(false), R, 50, 10, 0, NOW).kind === 'discarded');
  ok('10 分钟去重：窗口内的转换只更新状态、不通知',
    evaluate(state(false, NOW - COOLDOWN_MS + 1), R, 50, 10, NOW, NOW).kind === 'quiet');
  ok('去重窗口边界（正好 10 分钟）可通知',
    evaluate(state(false, NOW - COOLDOWN_MS), R, 50, 10, NOW, NOW).kind === 'alert');
}

console.log('[常量与 Kotlin 源码一致（实现漂移锁）]');
const ev = fs.readFileSync(EVALUATOR, 'utf8');
const ps = fs.readFileSync(PLACE_STORE, 'utf8');
/** 解析 `const val NAME = <数字算式>`（仅乘法/加法，下划线千分位） */
function constOf(src, name) {
  const m = src.match(new RegExp('const val ' + name + '\\s*=\\s*([0-9_.\\s*+]+)'));
  if (!m) return NaN;
  const expr = m[1].replace(/_/g, '').trim();
  if (!/^[0-9.\s*+]+$/.test(expr)) return NaN;
  return expr.split('*')
    .map((t) => t.trim())
    .filter((t) => t.length > 0)
    .reduce((a, b) => a * Number(b), 1);
}
ok('滞回系数 1.25 一致', constOf(ev, 'EXIT_HYSTERESIS_FACTOR') === HYSTERESIS,
  String(constOf(ev, 'EXIT_HYSTERESIS_FACTOR')));
ok('新鲜度窗口 15 分钟一致', constOf(ev, 'SAMPLE_FRESHNESS_MS') === FRESH_MS,
  String(constOf(ev, 'SAMPLE_FRESHNESS_MS')));
ok('去重窗口 10 分钟一致', constOf(ev, 'NOTIFY_COOLDOWN_MS') === COOLDOWN_MS,
  String(constOf(ev, 'NOTIFY_COOLDOWN_MS')));
ok('地球半径 6371008.8 一致', constOf(ev, 'EARTH_RADIUS_M') === EARTH_R,
  String(constOf(ev, 'EARTH_RADIUS_M')));
ok('半径上下限 100/5000 一致',
  constOf(ps, 'RADIUS_MIN_M') === 100 && constOf(ps, 'RADIUS_MAX_M') === 5000);
ok('轮询间隔 5/10/60 分钟一致',
  constOf(ps, 'INTERVAL_MIN_MINUTES') === 5 && constOf(ps, 'INTERVAL_DEFAULT_MINUTES') === 10 &&
  constOf(ps, 'INTERVAL_MAX_MINUTES') === 60);

console.log('\n== 结果: ' + pass + ' 通过 / ' + fail + ' 失败 ==');
process.exit(fail === 0 ? 0 : 1);
