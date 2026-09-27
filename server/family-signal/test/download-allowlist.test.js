'use strict';
/**
 * ApkDownloader 下载域白名单 + 真实跳转链验证（零依赖）
 *
 * 目的：确认修复后 Gitee 真实跳转链（含 foruda.gitee.com 附件域）能通过逐跳校验，
 * 同时确认域名前后缀伪造（evil-gitee.com / gitee.com.evil.com）仍被拒绝。
 * 白名单与匹配逻辑必须与 ApkDownloader.kt 的 isAllowedDownloadUrl 保持一致。
 *
 * 运行：node server/family-signal/test/../ 见下方路径；或直接 node <该文件>
 */
const fs = require('fs');
const path = require('path');
const APK_DOWNLOADER = path.join(
  __dirname, '..', '..', '..',
  'app/src/main/java/com/example/batteryfloat/update/ApkDownloader.kt'
);

// 白名单**从真实 Kotlin 源码解析**（此前这里手抄一份 JS 副本，Kotlin 侧漂移时全套断言
// 仍会全绿——例如把 evil.com 加进真白名单，这里毫无反应）。解析失败即判失败，不静默退化。
const KOTLIN_SRC = fs.readFileSync(APK_DOWNLOADER, 'utf8');
const PARSED_HOSTS = (() => {
  const m = /ALLOWED_DOWNLOAD_HOSTS\s*=\s*setOf\(([\s\S]*?)\)/.exec(KOTLIN_SRC);
  if (!m) return null;
  return (m[1].match(/"([^"]+)"/g) || []).map((s) => s.slice(1, -1));
})();
// 期望集合：发布渠道的官方域（后缀规则自动覆盖官方子域）。新增渠道必须同步改这里，
// 否则下面第一条断言即失败——这正是"白名单不许悄悄放宽"的把关点。
const EXPECTED_HOSTS = ['gitee.com', 'github.com', 'objects.githubusercontent.com'];
// 与 Kotlin 侧同构的逻辑（最后一条断言同时校验 Kotlin 源码里确实存在这段实现）
const ALLOWED_DOWNLOAD_HOSTS = PARSED_HOSTS || [];
function isAllowedDownloadUrl(u) {
  // try/catch 与 Kotlin 侧 isAllowedDownloadUrl 的 try { ... } catch { false } 对齐
  try {
    const a = new URL(u);
    return a.protocol === 'https:' &&
      ALLOWED_DOWNLOAD_HOSTS.some((h) => a.host === h || a.host.endsWith('.' + h));
  } catch (e) {
    return false;
  }
}

let pass = 0, fail = 0;
function ok(name, cond, extra) {
  if (cond) { pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (extra ? '  -> ' + extra : '')); }
}

console.log('\n== 下载域白名单验证 ==\n');
ok('白名单从 ApkDownloader.kt 解析成功', Array.isArray(PARSED_HOSTS) && PARSED_HOSTS.length > 0,
  PARSED_HOSTS ? '' : '未找到 ALLOWED_DOWNLOAD_HOSTS = setOf(...)');
ok('真实白名单恰为已知发布域（新增/删除域必须显式改本测试）',
  !!PARSED_HOSTS &&
  PARSED_HOSTS.slice().sort().join(',') === EXPECTED_HOSTS.slice().sort().join(','),
  '实际: ' + JSON.stringify(PARSED_HOSTS));
ok('Kotlin 侧仍是"精确相等或 .后缀"匹配（不是 startsWith/contains）',
  /u\.host == it \|\| u\.host\.endsWith\("\.[$]it"\)/.test(KOTLIN_SRC));
ok('Kotlin 侧只放行 https',
  /u\.protocol\.equals\("https", true\)/.test(KOTLIN_SRC));

const allow = [
  ['Gitee Release 首跳', 'https://gitee.com/qinzuoyong/floating-data/releases/download/v1.83/yongge.apk'],
  ['Gitee 附件中间跳', 'https://gitee.com/qinzuoyong/floating-data/attach_files/3180781/download/yongge.apk'],
  ['Gitee 附件 CDN 末跳（子域规则覆盖）', 'https://foruda.gitee.com/attach_file/1789046144511812450/yongge.apk?token=x&ts=1&attname=yongge.apk'],
  ['GitHub Release', 'https://github.com/qinzuoyong/floating-data/releases/download/v1.83/yongge.apk'],
  ['GitHub 资产 CDN', 'https://objects.githubusercontent.com/github-production-release-asset/x/yongge.apk'],
  ['Gitee 官方子域（泛匹配）', 'https://files.gitee.com/yongge.apk']
];
for (const [name, u] of allow) ok('放行 ' + name, isAllowedDownloadUrl(u), u);

const deny = [
  ['后缀伪造 evil-gitee.com', 'https://evil-gitee.com/yongge.apk'],
  ['前缀伪造 gitee.com.evil.com', 'https://gitee.com.evil.com/yongge.apk'],
  ['路径内嵌 gitee.com', 'https://evil.com/gitee.com/yongge.apk'],
  ['明文 http', 'http://gitee.com/yongge.apk'],
  ['file 协议', 'file:///sdcard/yongge.apk'],
  ['任意主机', 'https://example.com/yongge.apk'],
  ['相似域 foruda-gitee.com', 'https://foruda-gitee.com/yongge.apk'],
  ['空串', '']
];
for (const [name, u] of deny) ok('拒绝 ' + name, !isAllowedDownloadUrl(u), u);

console.log('\n== 结果: ' + pass + ' 通过 / ' + fail + ' 失败 ==');
process.exit(fail === 0 ? 0 : 1);
