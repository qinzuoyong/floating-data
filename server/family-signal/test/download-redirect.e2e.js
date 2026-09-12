'use strict';
/**
 * 真实 Gitee 跳转链 × ApkDownloader 逐跳校验逻辑（端到端）。
 *
 * 复刻 ApkDownloader.openValidatedConnection：
 *   instanceFollowRedirects=false，每跳 302 先过 isAllowedDownloadUrl。
 * 仅 HEAD，不下载 APK 本体。白名单/匹配规则必须与 ApkDownloader.kt 保持一致。
 *
 * 运行：node server/family-signal/test/download-redirect.e2e.js
 */
const START = 'https://gitee.com/qinzuoyong/floating-data/releases/download/v1.83/yongge.apk';
const MAX_REDIRECTS = 5;
const ALLOWED = ['gitee.com', 'github.com', 'objects.githubusercontent.com'];

function isAllowed(host) {
  return ALLOWED.some((h) => host === h || host.endsWith('.' + h));
}

let pass = 0, fail = 0;
function ok(name, cond, extra) {
  if (cond) { pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (extra ? '  -> ' + extra : '')); }
}

/** 复刻逐跳校验；返回 ok/hops/拒绝原因 */
async function walk() {
  let current = START;
  const hops = [];
  for (let i = 0; i <= MAX_REDIRECTS; i++) {
    const host = new URL(current).host;
    if (!isAllowed(host)) {
      hops.push('REJECT ' + host);
      return { ok: false, hops, rejectedHost: host };
    }
    const res = await fetch(current, { method: 'HEAD', redirect: 'manual' });
    hops.push(res.status + ' ' + host);
    if (res.status >= 300 && res.status < 400) {
      const loc = res.headers.get('location');
      if (!loc) return { ok: false, hops: hops.concat('302-no-location') };
      current = new URL(loc, current).toString();
      continue;
    }
    return { ok: res.status >= 200 && res.status < 300, hops, status: res.status };
  }
  return { ok: false, hops: hops.concat('too-many-redirects') };
}

(async () => {
  console.log('\n== 真实跳转链 × 逐跳校验（当前白名单）==\n');
  const r = await walk();
  console.log('  链路: ' + r.hops.join('  ->  '));
  ok('全链路通过逐跳校验并到达 2xx', r.ok, JSON.stringify(r));
  ok('末跳为 Gitee 附件 CDN（经 .后缀规则放行）',
    r.hops[r.hops.length - 1] === '200 foruda.gitee.com', r.hops[r.hops.length - 1]);
  ok('未出现任何 REJECT', !r.hops.some((h) => h.startsWith('REJECT')));

  // 伪造域必须仍被拒（回归保护）
  console.log('\n  -- 伪造域回归保护 --');
  for (const bad of ['evil-gitee.com', 'gitee.com.evil.com', 'foruda-gitee.com', 'evil.com']) {
    ok('拒绝 ' + bad, !isAllowed(bad));
  }

  console.log('\n== 结果: ' + pass + ' 通过 / ' + fail + ' 失败 ==');
  process.exit(fail === 0 ? 0 : 1);
})().catch((e) => { console.error('异常:', e); process.exit(1); });
