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
// 必须与 ApkDownloader.ALLOWED_DOWNLOAD_HOSTS 一致（后缀规则自动覆盖官方子域）
const ALLOWED_DOWNLOAD_HOSTS = ['gitee.com', 'github.com', 'objects.githubusercontent.com'];
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
