'use strict';
/**
 * 真实 Gitee 跳转链 × ApkDownloader 逐跳校验逻辑（端到端）。
 *
 * 复刻 ApkDownloader.openValidatedConnection：
 *   instanceFollowRedirects=false，每跳 302 先过 isAllowedDownloadUrl。
 * 仅 HEAD，不下载 APK 本体。白名单/匹配规则必须与 ApkDownloader.kt 保持一致。
 *
 * 运行：node tests/download-redirect.e2e.js
 */
const START = 'https://gitee.com/qinzuoyong/floating-data/releases/download/v1.83/yongge.apk';
/** GitHub 回退链起点。曾因白名单缺 release-assets.githubusercontent.com 而第二跳即失败，
 *  这里必须真实走一遍，否则该缺陷只能靠用户撞上才发现。 */
const START_GITHUB = 'https://github.com/qinzuoyong/floating-data/releases/download/v2.0/yongge.apk';
const MAX_REDIRECTS = 5;
const ALLOWED = [
  'gitee.com',
  'github.com',
  'release-assets.githubusercontent.com',
  'objects.githubusercontent.com'
];

/**
 * 网络不可达时该链路**跳过**而非失败：本机 GitHub 直连常超时（需代理），
 * 把网络环境问题判成代码缺陷会造成误报。白名单本身的 allow/deny 断言不依赖网络，
 * 始终执行；这里跳过的只是"真实跳转链"这一层验证。
 */
function isNetworkError(e) {
  const msg = String(e && (e.cause ? e.cause.message : e.message) || '');
  return /fetch failed|Connect Timeout|ECONNREFUSED|ENOTFOUND|ETIMEDOUT|EAI_AGAIN/i.test(msg);
}

function isAllowed(host) {
  return ALLOWED.some((h) => host === h || host.endsWith('.' + h));
}

let pass = 0, fail = 0;
function ok(name, cond, extra) {
  if (cond) { pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (extra ? '  -> ' + extra : '')); }
}

/** 复刻逐跳校验；返回 ok/hops/拒绝原因 */
async function walk(startUrl) {
  let current = startUrl;
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
  const r = await walk(START);
  console.log('  Gitee 链路: ' + r.hops.join('  ->  '));
  ok('全链路通过逐跳校验并到达 2xx', r.ok, JSON.stringify(r));
  ok('末跳为 Gitee 附件 CDN（经 .后缀规则放行）',
    r.hops[r.hops.length - 1] === '200 foruda.gitee.com', r.hops[r.hops.length - 1]);
  ok('未出现任何 REJECT', !r.hops.some((h) => h.startsWith('REJECT')));

  // GitHub 回退链：Gitee 源失败时 UpdateChecker 会切到这里，必须同样可用
  console.log('\n  -- GitHub 回退链（release-assets CDN 回归保护）--');
  let g;
  try {
    g = await walk(START_GITHUB);
  } catch (e) {
    if (isNetworkError(e)) {
      console.log('  SKIP  GitHub 直连不可达（本机需代理），跳过真实链路验证');
      console.log('        白名单断言已在下个文件覆盖，不受影响');
    } else {
      throw e;
    }
  }
  if (g) {
    console.log('  GitHub 链路: ' + g.hops.join('  ->  '));
    ok('GitHub 回退链通过逐跳校验并到达 2xx', g.ok, JSON.stringify(g));
    ok('未出现任何 REJECT（白名单含 release-assets）',
      !g.hops.some((h) => h.startsWith('REJECT')));
    ok('链路确实经过 release-assets.githubusercontent.com（该域缺失即失败）',
      g.hops.some((h) => h.includes('release-assets.githubusercontent.com')),
      g.hops.join(' -> '));
  }

  // 伪造域必须仍被拒（回归保护）
  console.log('\n  -- 伪造域回归保护 --');
  for (const bad of ['evil-gitee.com', 'gitee.com.evil.com', 'foruda-gitee.com', 'evil.com']) {
    ok('拒绝 ' + bad, !isAllowed(bad));
  }

  console.log('\n== 结果: ' + pass + ' 通过 / ' + fail + ' 失败 ==');
  process.exit(fail === 0 ? 0 : 1);
})().catch((e) => { console.error('异常:', e); process.exit(1); });

