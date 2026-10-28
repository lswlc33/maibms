/*
 * 数据层 ↔ JS 的最小宿主（Hand-written，3 个函数）
 *
 * 为什么需要这个文件：Kotlin/JS 里"往对象上挂动态键"这两条路都不通——
 *   · `api.foo = x`（dynamic 静态名赋值）会被编译成属性/设置器访问，实测**静默丢弃**；
 *   · `js("(function(o,k,v){o[k]=v})")` 会被当**表达式求值**（编译期就调掉），拿不到函数；
 *   · `@JsFun` 在 Kotlin 2.1 的 stdlib 里是 internal，业务代码用不了。
 *
 * 所以把"动态写入"放在真正的 JS 文件里，Kotlin 侧只做 external 声明调用。
 * 这几个函数足够小，不值得为它引一个互操作库。
 */
(function (global) {
  'use strict';

  global.MaibmsHost = {
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
})(typeof globalThis !== 'undefined' ? globalThis : window);
