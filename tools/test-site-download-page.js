// 官网 index.html 内联脚本的逻辑回归（Node 桩环境，真实执行内联脚本）：
// ① 缓存优先上屏、接口成功后转 live
// ② 拉取失败退避重试（15s → 30s → 60s），成功后停止
// ③ 页面重新可见/聚焦自检：距上次成功拉取 < 5min 不重拉，超过才拉；hidden 时不拉
// ④ cacheIsStale 新鲜度（无时间戳 / 超过 1h = 陈旧）
// ⑤ newerThan 的 semver 判定（正式/测试任一渠道变新才算）
// 脚本是异步 promise 链，所有断言前先 await drain()。
"use strict";
const fs = require("fs");
const path = require("path");

const html = fs.readFileSync(path.join(__dirname, "..", "docs", "index.html"), "utf8");
const lines = html.split("\n");
const start = lines.findIndex((l) => l.trim() === "<script>");
const end = lines.findIndex((l, i) => i > start && l.trim() === "</script>");
// 规范化 CRLF，避免插桩替换失配
const src = lines.slice(start + 1, end).join("\n").replace(/\r\n/g, "\n");
const target = src.lastIndexOf("})();");
const instrumented =
  src.slice(0, target) +
  "  __bind({ state: state, newerThan: newerThan, cacheIsStale: cacheIsStale });\n" +
  src.slice(target);

function makeEnv(opts = {}) {
  const store = new Map(Object.entries(opts.storage || {}));
  const timers = [];
  const listeners = {};
  const env = {
    store, timers, listeners, fetchCalls: [], responses: opts.responses || [],
    documentHidden: false, nowMs: opts.nowMs ?? 1_000_000,
  };
  function el() {
    return {
      href: "", dataset: {}, style: {}, textContent: "", innerHTML: "",
      classList: { add() {}, remove() {}, toggle() {} },
      addEventListener() {}, appendChild() {}, querySelector() { return el(); },
      querySelectorAll() { return []; }, offsetWidth: 0,
    };
  }
  env.document = {
    get hidden() { return env.documentHidden; },
    addEventListener(t, fn) { (listeners["doc:" + t] = listeners["doc:" + t] || []).push(fn); },
    querySelector: () => el(), querySelectorAll: () => [], createElement: () => el(),
    documentElement: { setAttribute() {}, getAttribute() { return "dark"; } },
  };
  env.localStorage = {
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => store.set(k, String(v)),
    removeItem: (k) => store.delete(k),
  };
  env.fetchImpl = (url) => {
    env.fetchCalls.push(url);
    const r = env.responses.shift();
    if (r === "fail") return Promise.reject(new Error("network"));
    return Promise.resolve({ ok: true, json: () => Promise.resolve(r) });
  };
  env.window = {
    setTimeout: (fn, ms) => { const t = { fn, ms, cleared: false, fired: false }; timers.push(t); return t; },
    clearTimeout: (t) => { if (t) t.cleared = true; },
    addEventListener(t, fn) { (listeners["win:" + t] = listeners["win:" + t] || []).push(fn); },
    localStorage: env.localStorage,
    matchMedia: () => ({ matches: false }),
    AbortController: undefined,
    fetch: env.fetchImpl,
  };
  // Date.now 全程由 env.nowMs 驱动（页面脚本用它在 recheck 里算距上次拉取的间隔）
  Date.now = () => env.nowMs;
  env.run = async function () {
    try {
      let captured;
      new Function("window", "document", "localStorage", "fetch", "setTimeout", "clearTimeout", "__bind", instrumented)
        .call(env.window, env.window, env.document, env.localStorage, env.fetchImpl,
              env.window.setTimeout, env.window.clearTimeout,
              (s) => { captured = s; });
      await drain();
      return captured;
    } finally { /* Date.now 保持受控，进程退出前还原 */ }
  };
  env.pendingTimers = () => timers.filter((t) => !t.cleared && !t.fired);
  env.fire = async function (t) { t.fired = true; t.fn(); await drain(); };
  return env;
}

function rel(tag, ver, pre) {
  return {
    tag_name: tag, prerelease: !!pre, draft: false,
    assets: [{ name: "maibms-" + ver + ".apk", size: 123456 }, { name: "maibms-" + ver + "-unsigned.ipa", size: 654321 }],
  };
}
const LIVE_019 = [rel("10-0.1.9", "0.1.9"), rel("v0.1.9-beta.48", "0.1.9-beta.48", true)];

async function drain() { await Promise.resolve(); await new Promise((r) => setImmediate(r)); await Promise.resolve(); }

let failures = 0;
function check(name, cond) { cond ? console.log("PASS", name) : (failures++, console.error("FAIL", name)); }

(async () => {
  // ---- 用例 1：无缓存 + 接口成功 → live，无重试 ----
  {
    const env = makeEnv({ responses: [LIVE_019] });
    const spy = await env.run();
    check("src=live after fetch", spy.state.src === "live");
    check("stable ver parsed", spy.state.releases.stable.ver === "0.1.9");
    check("no retry after success", env.pendingTimers().length === 0);
  }

  // ---- 用例 2：有缓存 + 接口失败 → 保留 cache + 15s 退避重试 ----
  {
    const cached = { stable: rel("10-0.1.9", "0.1.9"), beta: rel("v0.1.9-beta.48", "0.1.9-beta.48", true), ipaFallback: null };
    const env = makeEnv({
      storage: { "mai-releases": JSON.stringify(cached), "mai-releases:ts": "1000000" },
      responses: ["fail"],
    });
    const spy = await env.run();
    check("cache rendered on failure", spy.state.src === "cache");
    check("retry scheduled at 15s", env.timers.some((t) => t.ms === 15000 && !t.fired));
    // 第一次重试仍失败 → 30s
    env.responses.push("fail");
    await env.fire(env.timers.find((t) => t.ms === 15000 && !t.fired));
    check("second retry at 30s", env.timers.some((t) => t.ms === 30000 && !t.fired));
    check("still cache after failed retry", spy.state.src === "cache");
    // 第二次重试成功 → live + 数据更新，无更多重试
    env.responses.push([rel("10-0.2.0", "0.2.0"), rel("v0.1.9-beta.49", "0.1.9-beta.49", true)]);
    await env.fire(env.timers.find((t) => t.ms === 30000 && !t.fired));
    check("live after retry success", spy.state.src === "live");
    check("updated to 0.2.0", spy.state.releases.stable.ver === "0.2.0");
    check("no more retries pending", env.pendingTimers().filter((t) => t.ms !== 900).length === 0);  // 900ms 是 flashDownload 动画清理，非重试
    check("cache ts written", !!env.store.get("mai-releases:ts"));
  }

  // ---- 用例 3：visibilitychange/focus 自检节流 ----
  {
    const env = makeEnv({ responses: [LIVE_019] });
    const spy = await env.run();
    const afterBoot = env.fetchCalls.length;
    env.nowMs += 3 * 60 * 1000;               // 3 分钟后重新可见：不重拉
    env.listeners["doc:visibilitychange"][0](); await drain();
    check("no refetch within 5min", env.fetchCalls.length === afterBoot);
    env.nowMs += 3 * 60 * 1000;               // 累计 6 分钟：重拉
    env.responses.push(LIVE_019);
    env.listeners["doc:visibilitychange"][0](); await drain();
    check("refetch after 5min on visible", env.fetchCalls.length === afterBoot + 1);
    env.documentHidden = true;                // hidden 时不拉
    env.responses.push(LIVE_019);
    env.listeners["win:focus"][0](); await drain();
    check("no refetch while hidden", env.fetchCalls.length === afterBoot + 1);
    // 刚拉过又立即 visible：不重复拉
    env.documentHidden = false;
    env.listeners["doc:visibilitychange"][0](); await drain();
    check("no immediate refetch after live", env.fetchCalls.length === afterBoot + 1);
    void spy;
  }

  // ---- 用例 4：cacheIsStale 新鲜度 ----
  {
    // boot 拉取失败 → 无新 ts 写入，旧缓存（无 ts）应判陈旧
    const env = makeEnv({
      storage: { "mai-releases": JSON.stringify({ stable: rel("10-0.1.9", "0.1.9"), beta: rel("v0.1.9-beta.48", "0.1.9-beta.48", true) }) },
      responses: ["fail"],
    });
    const spy = await env.run();
    check("cache without ts is stale", spy.cacheIsStale() === true);
    env.localStorage.setItem("mai-releases:ts", String(env.nowMs - 30 * 60 * 1000));
    check("cache 30min old is fresh", spy.cacheIsStale() === false);
    env.localStorage.setItem("mai-releases:ts", String(env.nowMs - 2 * 60 * 60 * 1000));
    check("cache 2h old is stale", spy.cacheIsStale() === true);
    env.localStorage.setItem("mai-releases:ts", "not-a-number");
    check("garbage ts is stale", spy.cacheIsStale() === true);
  }

  // ---- 用例 5：newerThan semver 判定 ----
  {
    const env = makeEnv({ responses: [LIVE_019] });
    const spy = await env.run();
    const nt = spy.newerThan;
    const R = (s, b) => ({ stable: { ver: s }, beta: { ver: b } });
    check("newer stable detected", nt(R("0.2.0", "0.1.9-beta.48"), R("0.1.9", "0.1.9-beta.48")) === true);
    check("newer beta detected", nt(R("0.1.9", "0.1.9-beta.49"), R("0.1.9", "0.1.9-beta.48")) === true);
    check("same versions not newer", nt(R("0.1.9", "0.1.9-beta.48"), R("0.1.9", "0.1.9-beta.48")) === false);
    check("older not flagged", nt(R("0.1.8", "0.1.8-beta.1"), R("0.1.9", "0.1.9-beta.48")) === false);
    check("garbage handled as change", nt(R("x", "0.1.9-beta.48"), R("0.1.9", "0.1.9-beta.48")) === true);
  }

  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
