'use strict';
/**
 * BatteryFloating App 侧修复的源码级回归断言（零依赖）。
 *
 * 这些缺陷都是"行为静默错误"或"产品决定"，Kotlin 编译期无法发现，
 * 故用源码断言锁定修复形态，防止后续改动把逻辑或决定改回去。
 *
 * 本文件来自原 server/family-signal/test/app-fix-assertions.test.js 的拆分：
 * 家人定位相关断言已随功能迁至 FamilyLocation 项目，此处只保留
 * 悬浮窗 / ADB 特权通道 / 无障碍保活 三块。
 *
 * 运行：node tests/app-assertions.test.js
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const PKG = 'app/src/main/java/com/example/batteryfloat';

let pass = 0, fail = 0;
function ok(name, cond, extra) {
  if (cond) { pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (extra ? '  -> ' + extra : '')); }
}
const read = (p) => fs.readFileSync(p, 'utf8');

const AG = path.join(ROOT, PKG, 'adb/AdbAutoGrant.kt');
const AG_CARD = path.join(ROOT, PKG, 'ui/AdbAutoGrantCard.kt');
const PB = path.join(ROOT, PKG, 'adb/PrivBaseline.kt');
const HOME = path.join(ROOT, PKG, 'ui/HomeScreen.kt');
const HEALER = path.join(ROOT, PKG, 'service/A11ySelfHealer.kt');
const ADB_KEY = path.join(ROOT, PKG, 'adb/AdbKey.kt');
const ACM = path.join(ROOT, PKG, 'adb/AdbConnectionManager.kt');
const BFD = path.join(ROOT, PKG, 'adb/BfdChannel.kt');
const KA = path.join(ROOT, PKG, 'service/KeepAliveAccessibilityService.kt');
const FWS = path.join(ROOT, PKG, 'service/FloatingWindowService.kt');
const MAIN = path.join(ROOT, PKG, 'MainActivity.kt');
const NAV = path.join(ROOT, PKG, 'ui/AppNavigation.kt');

console.log('\n== BatteryFloating App 侧修复源码断言 ==\n');

const ag = read(AG);
const agc = read(AG_CARD);
const hs = read(HOME);

// ---- 产品决定：撤销自动授权功能整体移除（2026-09） ----
// 撤销遍历中撤到运行时权限(定位/通知)会被系统强杀进程，序列后段执行不到；进程重启后
// 特权通道重连，自动授予流程又把已撤项原样授回。既不可靠也不可解释，故整体移除该功能，
// 自动授权卡片转为只读展示。本组断言锁定"不被误加回来"。
console.log('[撤销自动授权功能已移除 AdbAutoGrant.kt / AdbAutoGrantCard.kt]');
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

// ---- 定位权限项已随家人功能拆分移除（2026-10 FamilyLocation 拆分） ----
// 原项目 Manifest 已无 ACCESS_*_LOCATION，若 AdbAutoGrant 仍保留 LOCATION 枚举并执行
// pm grant，卡片会显示"已授予"一个不存在的权限。断言锁定该枚举与授权步骤一并移除。
console.log('[定位权限项已随家人功能移除 AdbAutoGrant.kt]');
ok('AutoGrant 枚举不再含 LOCATION',
  !/LOCATION\("定位权限/.test(ag) && !/AutoGrant\.LOCATION/.test(ag));
ok('不再有 grantLocation 与 AUTO_PERMISSIONS 定位项',
  !/private suspend fun grantLocation/.test(ag) && !/AUTO_PERMISSIONS/.test(ag));
ok('Manifest 不再声明定位权限（三个 ACCESS_*_LOCATION 均已移除）',
  (() => {
    const mf = read(path.join(ROOT, 'app/src/main/AndroidManifest.xml'));
    return !/ACCESS_FINE_LOCATION/.test(mf) && !/ACCESS_COARSE_LOCATION/.test(mf) &&
      !/ACCESS_BACKGROUND_LOCATION/.test(mf) && !/FOREGROUND_SERVICE_LOCATION/.test(mf);
  })());

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
const healer = read(HEALER);
ok('自愈与自动授权均以该标记为门控（尊重用户意图）',
  /if \(isUserDisabled\(ctx\)\) return@launch/.test(healer) &&
  /if \(isUserDisabled\(ctx\)\) return false/.test(healer));

// ---- ADB 自愈基座:密钥未就绪时不得置"已尝试"标记 ----
console.log('[trust-key 时序 PrivBaseline.kt]');
const pb = read(PB);
const idxKeyNull = pb.indexOf('ADB 密钥尚未就绪');
const idxMarkTried = pb.indexOf('putBoolean(PrefsKeys.PRIV_BASELINE_KEY_TRIED, true)');
const idxElse = pb.indexOf('} else {', idxKeyNull);
ok('key == null 分支存在（留待重试）', idxKeyNull > 0 && /key == null/.test(pb));
ok('置"已尝试"标记位于 else 分支内（仅密钥就绪后才置位）',
  idxMarkTried > idxElse && idxElse > idxKeyNull,
  'markTried=' + idxMarkTried + ' else=' + idxElse + ' keyNull=' + idxKeyNull);
ok('peekKey 先于标记写入', pb.indexOf('peekKey()') < idxMarkTried);

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

// ---- 2026-09-24 全量审查修复回归锁定 ----
console.log('[P2 灭屏启动悬浮窗服务不得空转采样 FloatingWindowService.kt]');
// SCREEN_OFF/ON 是边沿触发广播：服务在灭屏期间被拉起（开机恢复/无障碍恢复/FGS 重投递）时
// 不会再收到 SCREEN_OFF，2s 采样（含特权 shell 直读）会整夜空转。修复形态：isInteractive 门控
// + SCREEN_ON 时补建监控器。
const fws = read(FWS);
ok('startMonitoring 有灭屏门控（isInteractive 为 false 时不启动采样）',
  /private fun startMonitoring\(\)[\s\S]{0,400}?isInteractive/.test(fws));
ok('SCREEN_ON 时监控器缺失会补建（先判空再 start）',
  /Intent\.ACTION_SCREEN_ON -> \{[\s\S]{0,200}?if \(batteryMonitor == null\) startMonitoring\(\)/.test(fws));

// ---- 无障碍通道恢复悬浮窗（拆分后仍保留：家人服务已移出，此通道只剩悬浮窗） ----
console.log('[无障碍通道恢复悬浮窗兜底 KeepAliveAccessibilityService.kt]');
const kas = read(KA);
ok('恢复悬浮窗服务有异常兜底（onServiceConnected 不得冒泡异常）',
  /try \{\s*\n\s*FloatingWindowService\.start\(this\)/.test(kas) &&
  /恢复悬浮窗服务失败/.test(kas),
  'FloatingWindowService.start 未被 try 包裹');
ok('家人服务恢复通道已随功能移除（不再引用 FamilyLocationService）',
  !/FamilyLocationService/.test(kas));

// ---- 2026-09 特权通道并发安全 ----
console.log('[P2 特权通道并发连接竞态 AdbConnectionManager.kt]');
// connectOnceInternal 约定"调用方持有 connectMutex"；setEnabled/onPaired/keyInit 三处曾裸调用，
// 并发连接各自 closeClientQuietly 互踢对方 client（实测模拟器环回通道 1ms 内多线程同时"已连接"，
// 随后 exec 失败(Socket closed/not A_WRTE or A_CLSE) 引发重连风暴与守护进程令牌 churn）。
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
const bfd = read(BFD);
ok('startViaAdb 有 AtomicBoolean 防重入守卫',
  /AtomicBoolean\(false\)/.test(bfd) && /if \(!starting\.compareAndSet\(false, true\)\)/.test(bfd));
ok('守卫在 finally 中复位', /finally \{[\s\S]{0,40}?starting\.set\(false\)/.test(bfd));

console.log('[P0 内置守护进程连接读超时 bfd_server.c]');
// bfd_server 是单线程 accept 循环：任何一次无超时的阻塞读都会把整个 daemon 卡死。
// 实测（模拟器 A/B，同一份二进制仅差超时设置）：一条"只连接不发数据"的连接建立后，
// 旧版连续 4 次 ping 全部超时、对端进程仍存活但永不响应（只能重启手机恢复）；
// 新版最多牺牲首轮请求，随后自动恢复服务。
// 故 handle_connection 必须在任何 read_full 之前装好 SO_RCVTIMEO/SO_SNDTIMEO。
const BFDC = read(path.join(ROOT, 'app/src/main/cpp/bfd_server.c'));
const idxHandler = BFDC.indexOf('static void handle_connection(int conn) {');
const idxFirstRead = BFDC.indexOf('read_full(conn', idxHandler);
const idxRcvOpt = BFDC.indexOf('SO_RCVTIMEO', idxHandler);
ok('bfd_server.c 定义连接读超时常量', /#define CONN_TIMEOUT_SEC\s+\d+/.test(BFDC));
ok('handle_connection 设置 SO_RCVTIMEO', idxRcvOpt > 0);
ok('handle_connection 设置 SO_SNDTIMEO', /SO_SNDTIMEO/.test(BFDC));
ok('超时设置位于任何 read_full 之前（顺序契约）',
  idxRcvOpt > 0 && idxFirstRead > idxRcvOpt && idxHandler > 0,
  `handler=${idxHandler} rcvOpt=${idxRcvOpt} firstRead=${idxFirstRead}`);
ok('超时值合理（1..30 秒）', (() => {
  const m = BFDC.match(/#define CONN_TIMEOUT_SEC\s+(\d+)/);
  return !!m && Number(m[1]) >= 1 && Number(m[1]) <= 30;
})());

console.log('[P0 APK 体积：剔除 BouncyCastle 死资源 app/build.gradle.kts]');
// R8 只删类不删依赖 jar 内的资源：pqc 的三份 lowmcL*.bin.properties 共 1,192 KB
// 会被原量打进 APK（实测基线 3.44 MiB 中占 1.21 MB），而 bouncycastle.pqc 在
// mapping.txt 里 0 条 —— 代码从未引用。锁定 excludes 防止被误删。
const GRADLE = read(path.join(ROOT, 'app/build.gradle.kts'));
ok('packaging.resources.excludes 排除 bouncycastle.pqc',
  /excludes \+= setOf\([\s\S]{0,300}?org\/bouncycastle\/pqc\/\*\*/.test(GRADLE));
ok('排除 CertPathReviewerMessages（审查器类未被保留）',
  /CertPathReviewerMessages\*\.properties/.test(GRADLE));
ok('保留 jniLibs.useLegacyPackaging（libbfd.so 需可执行位）',
  /jniLibs\.useLegacyPackaging = true/.test(GRADLE));

console.log('[中文资源收窄 androidResources.localeFilters]');
ok('localeFilters 限定 zh-rCN', /localeFilters \+= listOf\("zh-rCN"\)/.test(GRADLE));

// ---- 家人功能拆分后的结构契约（2026-10） ----
// 拆分后本应用只承载悬浮窗：底部导航 3 Tab、无家人入口、无信令/位置代码。
// 断言锁定这个边界，防止家人代码被误加回来（那会让两个应用再次分叉）。
console.log('[家人功能已拆分：结构边界]');
const nav = read(NAV);
ok('底部导航为 3 Tab（首页 / 外观 / 关于），无家人 Tab',
  /NavTab\("首页"/.test(nav) && /NavTab\("外观"/.test(nav) && /NavTab\("关于"/.test(nav) &&
  !/NavTab\("家人"/.test(nav) && !/FamilyScreen/.test(nav));
ok('AppNavigation 不再接收 onBeforeExternalIntent（家人页专用参数已移除）',
  !/onBeforeExternalIntent/.test(nav) && !/onBeforeExternalIntent/.test(read(MAIN)));
ok('家人相关包与文件已全部移出（p2p/family/location/map 及家人服务）',
  !fs.existsSync(path.join(ROOT, PKG, 'p2p')) &&
  !fs.existsSync(path.join(ROOT, PKG, 'family')) &&
  !fs.existsSync(path.join(ROOT, PKG, 'location')) &&
  !fs.existsSync(path.join(ROOT, PKG, 'map')) &&
  !fs.existsSync(path.join(ROOT, PKG, 'ui/family')) &&
  !fs.existsSync(path.join(ROOT, PKG, 'service/FamilyLocationService.kt')) &&
  !fs.existsSync(path.join(ROOT, PKG, 'service/FamilySignalHandler.kt')));
ok('服务端代码已随功能迁出（本项目不再有 server/ 目录）',
  !fs.existsSync(path.join(ROOT, 'server')));
ok('Manifest 不再声明家人服务',
  (() => {
    const mf = read(path.join(ROOT, 'app/src/main/AndroidManifest.xml'));
    return !/FamilyLocationService/.test(mf) && !/com\.amap\.api\.location\.APSService/.test(mf);
  })());
ok('通知渠道只剩电池与配对两条（家人两条渠道已移除）',
  (() => {
    const nf = read(path.join(ROOT, PKG, 'notif/Notifs.kt'));
    return /CHANNEL_BATTERY/.test(nf) && /CHANNEL_PAIRING/.test(nf) &&
      !/CHANNEL_FAMILY/.test(nf) && !/familyForeground/.test(nf) && !/familyPlaceAlert/.test(nf);
  })());
ok('PrefsKeys 不再含家人/地点提醒相关 key',
  (() => {
    const pk = read(path.join(ROOT, PKG, 'PrefsKeys.kt'));
    return !/FAMILY_/.test(pk) && !/family_/.test(pk);
  })());
ok('依赖不再含 Gson / Java-WebSocket / 高德定位 SDK',
  (() => {
    const bg = read(path.join(ROOT, 'app/build.gradle.kts'));
    const lv = read(path.join(ROOT, 'gradle/libs.versions.toml'));
    return !/libs\.gson/.test(bg) && !/java\.websocket/i.test(bg) && !/amap/i.test(bg) &&
      !/gson/.test(lv) && !/javaWebsocket/i.test(lv) && !/amapLocation/.test(lv);
  })());

// ---- 2026-10 全量审查修复的形态锁定 ----
// 这些缺陷都属"静默错误"：编译通过、模拟器也未必暴露，只有读源码才能确认，
// 故逐条锁定修复形态，防止后续改动把逻辑改回去。
const PRIV = read(path.join(ROOT, PKG, 'adb/PrivShell.kt'));
const DOWN = read(path.join(ROOT, PKG, 'update/ApkDownloader.kt'));
const PAIR = read(path.join(ROOT, PKG, 'adb/AdbPairingClient.kt'));
const MAIN_SRC = read(MAIN);
const ACM_SRC = read(ACM);
const BFD_SRC = read(BFD);

console.log('[修复 S1：关闭高精度数据源后特权读取必须真的停]');
// 缺陷成因：开关只关 AdbClient，内置载体 libbfd.so 已脱离 adbd 常驻且令牌无清除入口，
// 而 PrivBatteryProvider 的门控是 canExec()，原先只看"载体存活" → 开关形同虚设。
ok('PrivShell 持有开关状态并暴露 isEnabled()',
  /private var enabled = false/.test(PRIV) && /fun isEnabled\(\): Boolean = enabled/.test(PRIV));
ok('PrivShell.init 从 prefs 读取 ADB_PRIV_ENABLED（进程启动即同步）',
  /getBoolean\(PrefsKeys\.ADB_PRIV_ENABLED, false\)/.test(PRIV));
ok('canExec() 开关关闭时恒 false（特权采样的第一道闸）',
  /suspend fun canExec\(\)[\s\S]{0,200}?if \(!enabled\) return false/.test(PRIV));
ok('PrivShell.setEnabled 存在且关闭时作废存活缓存',
  /fun setEnabled\(value: Boolean\)[\s\S]{0,200}?if \(!value\) BfdChannel\.invalidateAlive\(\)/.test(PRIV));
ok('AdbConnectionManager.setEnabled 同步开关到 PrivShell',
  /fun setEnabled\(context: Context, value: Boolean\)[\s\S]{0,400}?PrivShell\.setEnabled\(value\)/.test(ACM_SRC));
ok('配对成功（onPaired）同样同步开关为 true',
  /fun onPaired\(context: Context\)[\s\S]{0,300}?PrivShell\.setEnabled\(true\)/.test(ACM_SRC));
ok('关闭开关时回收 daemon（BfdChannel.shutdown）',
  /BfdChannel\.shutdown\(\)/.test(ACM_SRC) && /suspend fun shutdown\(\)/.test(BFD_SRC));
ok('shutdown 结束时清空 token 并作废存活缓存',
  /suspend fun shutdown\(\)[\s\S]{0,1400}?token = null[\s\S]{0,120}?invalidateAlive\(\)/.test(BFD_SRC));

console.log('[修复 S2：ensureEnabled 必须尊重用户主动关闭]');
// 缺陷成因：先判 isEnabledInSettings 再判 isUserDisabled，导致「系统里还留着启用记录 +
// 用户在应用内点过关闭」直接走 forceRebind，把用户刚关掉的服务强行绑回来。
const ensureEnabledBody = (() => {
  const m = /suspend fun ensureEnabled\(context: Context\): Boolean \{([\s\S]*?)\n    \}/.exec(healer);
  return m ? m[1] : '';
})();
ok('ensureEnabled 第一行就判 isUserDisabled（早于 isEnabledInSettings）', (() => {
  const iUser = ensureEnabledBody.indexOf('isUserDisabled');
  const iEnabled = ensureEnabledBody.indexOf('isEnabledInSettings');
  return iUser > 0 && iEnabled > 0 && iUser < iEnabled;
})(), `len=${ensureEnabledBody.length}`);
ok('ensureEnabled 的 forceRebind 分支不夹带用户意图判断（意图已在最前统一处理）',
  ensureEnabledBody.length > 0 && !/isEnabledInSettings\(ctx\)\) \{[\s\S]{0,700}?isUserDisabled/.test(ensureEnabledBody));

console.log('[修复 S3：下载取消不得把状态卡在 Downloading]');
// 缺陷成因：CancellationException 是 Exception 子类被通用 catch 吞掉，而下载态下
// UpdateDownloadDialog 禁用关闭 → 弹窗永久卡死，只能杀进程。
ok('download 单独捕获 CancellationException 且位于 Exception 分支之前', (() => {
  const iCancel = DOWN.indexOf('catch (e: CancellationException)');
  const iGeneric = DOWN.indexOf('catch (e: Exception)', iCancel > 0 ? iCancel : 0);
  return iCancel > 0 && iGeneric > iCancel;
})());
ok('取消分支复位为 Idle 并把异常继续抛出（保持取消语义）',
  /catch \(e: CancellationException\)[\s\S]{0,900}?_downloadState\.value = DownloadState\.Idle[\s\S]{0,80}?throw e/.test(DOWN));
ok('取消分支清理半截文件（finally 只关流不删文件）',
  /catch \(e: CancellationException\)[\s\S]{0,900}?apkFile\(context\)[\s\S]{0,120}?delete\(\)/.test(DOWN));
ok('CancellationException 已显式 import',
  /import kotlinx\.coroutines\.CancellationException/.test(DOWN));
ok('两个调用方都捕获取消异常（避免未捕获异常逃到协程作用域）', (() => {
  const about = read(path.join(ROOT, PKG, 'ui/AboutScreen.kt'));
  return /catch \(_: kotlinx\.coroutines\.CancellationException\)/.test(MAIN_SRC) &&
    /catch \(_: kotlinx\.coroutines\.CancellationException\)/.test(about);
})());

console.log('[修复 M1：onTaskRemoved 不得保留永不可达的重启分支]');
// 该方法只在服务存活时回调，isRunning 恒 true → 原 AlarmManager 重启逻辑是死代码。
ok('onTaskRemoved 不再含 isRunning 提前返回与 AlarmManager 调度', (() => {
  const m = /override fun onTaskRemoved\([\s\S]*?\n    \}/.exec(fws);
  if (!m) return false;
  const body = m[0];
  return !/if \(isRunning\)/.test(body) &&
    !/setExactAndAllowWhileIdle/.test(body) &&
    !/AlarmManager/.test(body) &&
    /super\.onTaskRemoved/.test(body);
})());

console.log('[修复 M2：PeerInfo.Type 枚举取值须与 AOSP 一致]');
// AOSP: enum class Type : uint8_t { kRsaPublicKey = 0, kDeviceGuid = 1 }
ok('ADB_RSA_PUB_KEY = 0', /ADB_RSA_PUB_KEY\(0\.toByte\(\)\)/.test(PAIR));
ok('ADB_DEVICE_GUID = 1（曾误写 0，与公钥同值）', /ADB_DEVICE_GUID\(1\.toByte\(\)\)/.test(PAIR));

console.log('[修复 M3：安装前检查「安装未知应用」权限]');
ok('提供 canRequestPackageInstalls 判定', /canRequestPackageInstalls\(\)/.test(DOWN));
ok('提供跳转 ACTION_MANAGE_UNKNOWN_APP_SOURCES 的引导',
  /ACTION_MANAGE_UNKNOWN_APP_SOURCES/.test(DOWN));
ok('install() 未授权时先引导而非静默失败',
  /fun install\(context: Context, file: File\)[\s\S]{0,600}?!canInstallUnknownApps\(context\)[\s\S]{0,300}?openInstallPermissionSettings/.test(DOWN));
ok('MainActivity 两处安装回调均给出失败提示',
  (MAIN_SRC.match(/canInstallUnknownApps/g) || []).length >= 2);

console.log('[修复 M4：删除无引用的死字符串]');
ok('strings.xml 不再含 action_confirm / action_cancel', (() => {
  const s = read(path.join(ROOT, 'app/src/main/res/values/strings.xml'));
  return !/name="action_confirm"/.test(s) && !/name="action_cancel"/.test(s);
})());

console.log('[图标：自适应图标替代默认模板位图，与家人定位区分]');
// 两个项目原先逐字节相同（同一套 AS 模板机器人图标），且 5 张 PNG 共 93.7 KB。
ok('存在 mipmap-anydpi-v26/ic_launcher.xml 自适应图标',
  fs.existsSync(path.join(ROOT, 'app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml')));
ok('自适应图标含 background / foreground / monochrome 三层', (() => {
  const p = path.join(ROOT, 'app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml');
  if (!fs.existsSync(p)) return false;
  const x = read(p);
  return /<background /.test(x) && /<foreground /.test(x) && /<monochrome /.test(x);
})());
ok('旧的 5 套模板 PNG 已删除（minSdk 34 恒解析 anydpi-v26）', (() => {
  const dirs = ['mipmap-mdpi', 'mipmap-hdpi', 'mipmap-xhdpi', 'mipmap-xxhdpi', 'mipmap-xxxhdpi'];
  return dirs.every(d => !fs.existsSync(path.join(ROOT, 'app/src/main/res', d, 'ic_launcher.png')));
})());
ok('通知小图标使用独立的紧凑 drawable（不复用自适应前景）',
  /R\.drawable\.ic_notification/.test(read(path.join(ROOT, PKG, 'notif/Notifs.kt'))) &&
  fs.existsSync(path.join(ROOT, 'app/src/main/res/drawable/ic_notification.xml')));

console.log('\n== 结果: ' + pass + ' 通过 / ' + fail + ' 失败 ==');
process.exit(fail === 0 ? 0 : 1);
