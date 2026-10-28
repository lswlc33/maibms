/*
 * 数据层 ↔ JS 的最小宿主（Hand-written，5 个函数 + iOS 拦截）
 *
 * 为什么需要这个文件：Kotlin/JS 里"往对象上挂动态键"这两条路都不通——
 *   · `api.foo = x`（dynamic 静态名赋值）会被编译成属性/设置器访问，实测**静默丢弃**；
 *   · `js("(function(o,k,v){o[k]=v})")` 会被当**表达式求值**（编译期就调掉），拿不到函数；
 *   · `@JsFun` 在 Kotlin 2.1 的 stdlib 里是 internal，业务代码用不了。
 *
 * 所以把"动态写入"放在真正的 JS 文件里，Kotlin 侧只做 external 声明调用。
 * 这几个函数足够小，不值得为它引一个互操作库。
 *
 * 本文件在所有页面里都是第一个加载的脚本（host → maibms-data → theme-xxx），
 * 因此也是唯一不需要改主题就能拦下整部设备的位置。
 */
(function (global) {
  'use strict';

  var host = {
    /** o[k] = v */
    put: function (o, k, v) { o[k] = v; },

    /** o[k] */
    get: function (o, k) { return o[k]; },

    /** {} */
    obj: function () { return {}; },

    /** [] */
    arr: function () { return []; },

    /** a.push(v) */
    push: function (a, v) { a.push(v); }
  };

  /*
   * iOS 拦截：iOS 的 Safari 以及所有 iOS 浏览器（Chrome/Edge 套壳按 App Store
   * 要求也走 WebKit）都不提供 Web Bluetooth，网页版在 iPhone/iPad 上永远连不上
   * 保护板。与其让用户进到界面、点"连接"再失败，不如在数据层之前整页拦下。
   *
   * 识别口径：
   *  - UA 带 iPad/iPhone/iPod——iOS 上所有浏览器都带（包括套壳）；
   *  - iPadOS 13+ 默认报桌面 UA（"Macintosh"），用"Mac 平台 + 多点触控"补一刀：
   *    Mac 没有触屏，maxTouchPoints 恒为 0，不会误伤桌面 Chrome。
   *
   * 命中后铺全页提示并设 `__MAIBMS_BLOCKED` 标志；各主题脚本启动时最先看这个
   * 标志，直接退出（否则主题的"数据层加载失败"兜底会把提示整页覆盖掉）。
   * MaibmsHost 照常安装：数据层正常装配、不产生一屏报错，只是没有主题来渲染。
   */
  function isIOSDevice() {
    var nav = global.navigator;
    if (!nav) return false;
    var ua = String(nav.userAgent || '');
    return /iPad|iPhone|iPod/.test(ua) ||
      (nav.platform === 'MacIntel' && (nav.maxTouchPoints || 0) > 1);
  }

  function blockIOS() {
    global.__MAIBMS_BLOCKED = 'ios';
    document.title = 'iOS 上暂不可用 · 麻衣 BMS';
    // 铺提示页。若本脚本在 <head> 里执行（body 尚不存在），推迟到 DOM 建好再画；
    // 标志是同步设的，主题脚本届时依然会被拦住。
    var paint = function () {
      var style = document.createElement('style');
      style.textContent =
        '.mb-block{min-height:100vh;display:flex;align-items:center;justify-content:center;' +
        'padding:24px;box-sizing:border-box;background:#101418;color:#e8eaed;' +
        'font:16px/1.7 system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;}' +
        '.mb-card{max-width:26em;}' +
        '.mb-card h1{font-size:1.35em;margin:0 0 .6em;}' +
        '.mb-card p{margin:.5em 0;color:#c3c8ce;}' +
        '.mb-card a{color:#7cc4ff;}' +
        '.mb-card code{background:#1d232a;border-radius:4px;padding:1px 6px;font-size:.9em;' +
        'word-break:break-all;}' +
        '.mb-note{font-size:.85em;color:#8a919a;margin-top:1.2em;}';
      (document.head || document.documentElement).appendChild(style);
      document.body.innerHTML =
        '<div class="mb-block"><div class="mb-card">' +
        '<h1>iOS 上暂时打不开网页版</h1>' +
        '<p>iOS 的 Safari 和所有 iOS 浏览器都不支持 Web Bluetooth，' +
        '网页版无法连接保护板。</p>' +
        '<p>两个替代方案：</p>' +
        '<p>1. 在 <strong>Android 手机</strong>上用 Chrome 打开本页；<br>' +
        '2. 安装 <a href="../#download">iOS App</a>' +
        '（官网下载页，功能更全，支持后台监控与通知）。</p>' +
        '<p class="mb-note">本页地址：<code>' + location.href + '</code>' +
        '（可在安卓设备上直接输入）</p>' +
        '</div></div>';
    };
    if (document.body) paint();
    else document.addEventListener('DOMContentLoaded', paint);
  }

  host.isIOS = isIOSDevice;
  host.blockIOS = blockIOS;

  if (isIOSDevice()) blockIOS();

  global.MaibmsHost = host;
})(typeof globalThis !== 'undefined' ? globalThis : window);
