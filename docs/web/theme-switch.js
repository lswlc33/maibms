/*
 * 主题切换页逻辑。
 *
 * 清单来源：`Maibms.themes()`（Kotlin 侧 ThemeInfo 列表）。这样"有哪些主题"只有一处定义，
 * 各主题页里的"我是谁"（THEME_FILE）与之对应。
 *
 * 数据层没加载起来时（脚本 404、或用户直接双击打开 HTML），退化成内联静态清单——
 * 这一页是"换主题"的唯一入口，不能因为数据层挂了就打不开。
 */
(function () {
  'use strict';
  // 设备被宿主拦截（iOS 无 Web Bluetooth）：宿主已铺全页提示，这里不再渲染任何界面
  if (window.__MAIBMS_BLOCKED) return;


  var FALLBACK_THEMES = [
    {
      id: 'default', name: '默认', href: 'index.html', accent: '#4caf50',
      desc: '无边框扁平卡片 · 深色/浅色跟随系统 · 一屏尽览'
    },
    {
      id: 'mdui', name: 'Material', href: 'mdui.html', accent: '#6750a4',
      desc: 'mdui 2 组件库 · Material You 动态取色'
    },
    {
      id: 'daisy', name: 'Daisy', href: 'daisy.html', accent: '#1dcdbc',
      desc: 'Tailwind CSS + daisyUI · 现代卡片、徽标与统计块'
    },
    {
      id: 'admin', name: '控制台', href: 'admin.html', accent: '#0d6efd',
      desc: 'Bootstrap 侧栏仪表盘 · 宽屏并排布局，信息密度最高'
    },
    {
      id: 'console', name: '终端', href: 'console.html', accent: '#7ee787',
      desc: '等宽字体 HUD · 字符进度条、键盘快捷键（1-4 / C / X）'
    },
    {
      id: 'paper', name: '杂志', href: 'paper.html', accent: '#c9a227',
      desc: '排版优先 · 特大数字与细分隔线，无卡片无盒子'
    }
  ];

  /** 从 ?from= 读当前主题文件名（各主题页跳过来时带上），用于标"当前" */
  function currentFrom() {
    try {
      var p = new URLSearchParams(location.search).get('from');
      return p || '';
    } catch (e) {
      return '';
    }
  }

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = String(text);
    return e;
  }

  function render(themes) {
    var box = document.getElementById('themes');
    var from = currentFrom();
    box.innerHTML = '';

    themes.forEach(function (t) {
      var a = el('a', 'theme-card');
      a.href = t.href;
      a.style.setProperty('--swatch', t.accent || '#4caf50');
      if (from && from === t.href) a.className += ' current';

      var row = el('div', 'row');
      var info = el('div', 'info');

      var name = el('div', 'name');
      name.appendChild(el('span', null, t.name));
      // 默认主题的徽章写"推荐"而不是"默认"，否则标题与徽章都是"默认"，看着像重复
      if (t.id === 'default') name.appendChild(el('span', 'badge', '推荐'));
      info.appendChild(name);
      info.appendChild(el('div', 'desc', t.desc));
      info.appendChild(el('div', 'file', t.href));

      row.appendChild(info);
      row.appendChild(el('span', 'go', '打开 →'));
      a.appendChild(row);
      box.appendChild(a);
    });

    // 每套主题都提供一个"带预览参数"的入口
    var acts = document.getElementById('previewActions');
    acts.innerHTML = '';
    themes.forEach(function (t) {
      var a = document.createElement('a');
      a.href = t.href + '?preview=1';
      a.textContent = t.name + ' · 示例数据';
      acts.appendChild(a);
    });
  }

  // 数据层可用就用它的清单；否则用内联兜底
  var themes = null;
  try {
    if (window.Maibms && typeof window.Maibms.themes === 'function') {
      var list = window.Maibms.themes();
      if (list && list.length) themes = Array.prototype.slice.call(list);
    }
  } catch (e) {
    themes = null;
  }
  if (!themes || !themes.length) themes = FALLBACK_THEMES;

  render(themes);

  // 返回链接：有 from 就回那套主题，否则回默认主题首页
  var from = currentFrom();
  var back = document.getElementById('backLink');
  if (back) back.href = from || 'index.html';
})();
