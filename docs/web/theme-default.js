/*
 * 默认主题 · 逻辑
 *
 * 数据全部来自 window.Maibms（../maibms-data.js）。这个文件只做三件事：
 *   1. 把 state 画成 DOM（整份重画，见下方 renderAll 的说明）；
 *   2. 把点击接到 Maibms 的动作上；
 *   3. 记住"当前在哪个标签页/哪个子页"这类纯界面状态。
 *
 * 刻意不在这里做任何协议/参数换算：倍率、u32 双字、未读回(0xFFFF) 的处理都在
 * Kotlin 侧的 ParamTable 里，主题只显示 meta.paramGroups[i].defs[j].display。
 */
(function () {
  'use strict';
  // 设备被宿主拦截（iOS 无 Web Bluetooth）：宿主已铺全页提示，这里不再渲染任何界面
  if (window.__MAIBMS_BLOCKED) return;


  var M = window.Maibms;
  if (!M) {
    document.body.innerHTML = '<div style="padding:24px;font-family:sans-serif">' +
      '<h2>数据层加载失败</h2><p>请确认 ../maibms-data.js 可访问（不要直接双击打开 HTML 文件，需用 http(s) 打开）。</p></div>';
    return;
  }

  /* ---------------- 主题登记 ---------------- */

  /*
   * 主题清单由数据层提供（Maibms.themes()），这里只负责"我是谁"。
   * 新主题只需在自己的 HTML/JS 里写上本文件名即可被 switch 页识别。
   */
  var THEME_FILE = 'index.html';

  /* ---------------- 界面状态（不落盘的纯 UI 状态） ---------------- */

  var ui = {
    lastRenderKey: null,
    tab: 'dash',
    settingsPage: 'root',
    paramsGroup: 0,
    meta: M.meta(),
    state: M.state || {},
    logLines: []
  };

  /* ---------------- 小工具 ---------------- */

  function $(sel, root) { return (root || document).querySelector(sel); }

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = String(text);
    return e;
  }

  function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); }

  function toast(text) {
    var t = document.getElementById('toast');
    if (!t) return;
    t.textContent = text;
    t.classList.add('show');
    clearTimeout(toast._timer);
    toast._timer = setTimeout(function () { t.classList.remove('show'); }, 2200);
  }

  /** 定点小数；不拿 0 冒充"没有数据" */
  function num(v, digits) {
    if (v === null || v === undefined || (typeof v === 'number' && !isFinite(v))) return '--';
    return Number(v).toFixed(digits === undefined ? 2 : digits);
  }

  /** 从 state 取"有数据才显示"的读数 */
  function reading(st, v, digits) {
    return st.hasData ? num(v, digits) : '--';
  }

  /** 弹层；返回 close 函数 */
  function sheet(build) {
    var mask = el('div', 'sheet-mask');
    function close() { if (mask.parentNode) mask.parentNode.removeChild(mask); }
    var box = build(close);
    mask.appendChild(box);
    mask.addEventListener('click', function (e) { if (e.target === mask) close(); });
    document.body.appendChild(mask);
    return close;
  }

  function seg(options, current, onPick) {
    var box = el('div', 'seg');
    options.forEach(function (o) {
      var b = el('button', o.value === current ? 'on' : '', o.label);
      b.addEventListener('click', function () { onPick(o.value); });
      box.appendChild(b);
    });
    return box;
  }

  /* ---------------- 主题（深/浅） ---------------- */

  function applyTheme() {
    var dark = M.isDark();
    document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
    var meta = document.querySelector('meta[name=theme-color]');
    if (meta) meta.setAttribute('content', dark ? '#0a0c10' : '#f2f3f5');
  }

  function openThemeSheet() {
    sheet(function (close) {
      var box = el('div', 'sheet');
      box.appendChild(el('h2', null, '外观'));
      box.appendChild(el('div', 'sub', '深色 / 浅色 / 跟随浏览器。选择会被记住，各主题共用。'));

      var segBox = seg([
        { value: 'system', label: '跟随系统' },
        { value: 'light', label: '浅色' },
        { value: 'dark', label: '深色' }
      ], M.themeMode(), function (v) {
        M.setThemeMode(v);
        applyTheme();
        close();
        renderAll();
      });
      box.appendChild(segBox);

      box.appendChild(el('div', 'hint-line', '需要更大的字？直接用浏览器的页面缩放，比应用内缩放更好用。'));

      // 换主题入口：所有主题共用同一张切换页
      var row = el('div', 'btn-row');
      row.style.marginTop = '12px';
      var b = el('button', 'btn primary block', '切换页面主题…');
      b.addEventListener('click', function () { location.href = 'switch.html?from=' + encodeURIComponent(THEME_FILE); });
      row.appendChild(b);
      box.appendChild(row);
      return box;
    });
  }

  /* ---------------- 顶栏 / 底栏 ---------------- */

  var TABS = [
    { id: 'dash', label: '总览', icon: 'M13 2 4.5 13.5H11l-1 8.5 8.5-11.5H12z' },
    { id: 'params', label: '参数', icon: 'M4 6h9M17 6h3M4 12h3M11 12h9M4 18h9M17 18h3M15 4v4M9 10v4M15 16v4' },
    { id: 'control', label: '控制', icon: 'M5 4v6M5 14v6M12 4v10M12 18v2M19 4v2M19 10v10M2 10h6M9 14h6M16 8h6' },
    { id: 'settings', label: '设置', icon: 'M4 7h16M4 12h16M4 17h16' }
  ];

  function icon(path, size) {
    var span = el('span', 'ico');
    span.innerHTML = '<svg width="' + (size || 18) + '" height="' + (size || 18) + '" viewBox="0 0 24 24" ' +
      'fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" ' +
      'stroke-linejoin="round" aria-hidden="true"><path d="' + path + '"/></svg>';
    return span;
  }

  /**
   * 顶栏：**只更新文字，不重建节点**。
   *
   * 早先每次重绘都 clear() 重建整条顶栏，于是每 600ms 轮询一次就换掉一批 DOM 节点——
   * 状态徽标（主题入口）正好在那个位置，用户点下去时节点常已被替换，表现为"点了没反应"
   * （不是事件没绑，是绑在了一个已经不显示的旧节点上）。现在节点只建一次，之后改 textContent。
   */
  function renderTopbar(st) {
    var bar = $('.topbar');
    if (!bar.querySelector('.link-badge')) {
      clear(bar);
      var title = el('h1');
      title.appendChild(el('span', 'tab-title'));
      title.appendChild(el('span', 'sub'));
      bar.appendChild(title);

      var badge = el('span', 'link-badge');
      badge.appendChild(el('i', 'dot'));
      badge.appendChild(el('span', 'badge-text'));
      badge.title = '点击切换外观与主题';
      badge.style.cursor = 'pointer';
      badge.addEventListener('click', openThemeSheet);
      bar.appendChild(badge);
    }

    var t = bar.querySelector('.tab-title');
    if (t) t.textContent = ({ dash: '总览', params: '参数', control: '控制', settings: '设置' })[ui.tab];

    var sub = bar.querySelector('.sub');
    if (sub) sub.textContent = ' · ' + (st.connected ? st.deviceName : '未连接');

    var cls = '', text = st.linkText;
    if (st.connected && st.stalled) { cls = 'bad'; text = '设备失联'; }
    else if (st.connected) { cls = 'on'; }
    else if (st.link === 'Connecting') { cls = 'wait'; text = st.hint || '连接中…'; }
    else if (st.link === 'Disconnected' && st.hint) { cls = 'wait'; text = st.hint; }

    var badge = bar.querySelector('.link-badge');
    if (badge) {
      badge.className = ('link-badge ' + cls).trim();
      var bt = badge.querySelector('.badge-text');
      if (bt) bt.textContent = text;
    }
  }

  /**
   * 底栏：**只建一次**，之后只更新选中态。
   * 与顶栏同一个坑：早先每拍 clear() 重建，节点被换掉后用户的点击会落在
   * 即将被丢弃的旧节点上——表现为"点标签偶尔没反应"。
   */
  function renderTabbar(st) {
    var bar = $('.tabbar');
    if (!bar.querySelector('button')) {
      clear(bar);
      TABS.forEach(function (t) {
        var b = el('button');
        b.appendChild(icon(t.icon));
        b.appendChild(el('span', null, t.label));
        b.addEventListener('click', function () { ui.tab = t.id; renderAll(); });
        b.dataset.tab = t.id;
        bar.appendChild(b);
      });
    }
    bar.querySelectorAll('button').forEach(function (b) {
      b.className = b.dataset.tab === ui.tab ? 'on' : '';
    });
  }

  /* ---------------- 总览 ---------------- */

  function card(titleText, tag, body) {
    var c = el('section', 'card');
    if (titleText) {
      var head = el('div', 'card-head');
      head.appendChild(el('h2', null, titleText));
      head.appendChild(el('span', 'tag', tag || ''));
      c.appendChild(head);
    }
    c.appendChild(body);
    return c;
  }

  function banner(text, kind, action) {
    var b = el('div', ('banner ' + (kind || '')).trim());
    b.appendChild(el('span', 'ico', kind === 'bad' ? '!' : 'i'));
    var body = el('div', 'body');
    body.appendChild(el('span', null, text));
    if (action) {
      var a = el('button', 'act', action[0]);
      a.addEventListener('click', action[1]);
      body.appendChild(a);
    }
    b.appendChild(body);
    return b;
  }

  function metric(k, v, unit) {
    var m = el('div', 'metric');
    m.appendChild(el('div', 'k', k));
    var val = el('div', 'v');
    val.appendChild(el('span', null, v));
    if (unit) val.appendChild(el('small', null, unit));
    m.appendChild(val);
    return m;
  }

  function pill(text, cls) { return el('span', ('pill ' + (cls || '')).trim(), text); }

  function emptyState(big, title, desc) {
    var e = el('div', 'empty-state');
    e.appendChild(el('div', 'big', big));
    e.appendChild(el('h2', null, title));
    e.appendChild(el('p', null, desc));
    return e;
  }

  function renderDashboard(st) {
    var wrap = document.createDocumentFragment(); // 平铺进 main：宽屏 grid 需要卡片是直接子代

    if (!M.supportsWebBluetooth()) {
      wrap.appendChild(card('此浏览器无法连接保护板', '', el('div').appendChild
        ? (function () {
            var d = el('div');
            var p = el('p', null, '网页版通过 Web Bluetooth 直连保护板，需要：');
            p.style.marginTop = '0';
            d.appendChild(p);
            var ul = el('ul');
            ul.style.cssText = 'margin:0 0 10px;padding-left:20px;font-size:12.5px;color:var(--muted);line-height:1.8';
            ['安卓手机上的 Chrome（桌面 Chrome / Edge 也支持）',
             '通过 https 或 localhost 打开（浏览器的安全要求）'].forEach(function (t) {
              ul.appendChild(el('li', null, t));
            });
            d.appendChild(ul);
            d.appendChild(el('p', 'hint-line', '微信/QQ 内置浏览器、Safari、Firefox 都不支持 Web Bluetooth。'));
            d.appendChild(el('p', 'hint-line', '想要后台监控与通知，请下载 Android 版。'));
            return d;
          })()
        : el('div')));
      return wrap;
    }

    // 未连接且无数据：给出连接入口
    if (!st.connected && !st.hasData) {
      wrap.appendChild(connectCard(st));
      if (st.scanError) wrap.appendChild(banner(st.scanError, 'bad'));
      return wrap;
    }

    if (!st.connected && st.hasData) {
      wrap.appendChild(banner('界面预览：以下是示例数据，不是真机读数。点这里连接真实设备。', 'info',
        ['连接设备', function () { M.connect(); }]));
    }
    if (st.hint && !st.connected) wrap.appendChild(banner(st.hint));
    if (st.stalled) wrap.appendChild(banner('设备失联：连续多拍收不到实时数据，正在尝试恢复（信号弱或设备休眠）', 'bad'));

    var sum = healthSummary(st);
    if (sum) wrap.appendChild(sum);
    wrap.appendChild(overviewCard(st));
    if (st.caps.perCellVoltage && st.cells.length) wrap.appendChild(cellsCard(st));
    if (st.caps.multiTemp || st.temps.length) wrap.appendChild(tempsCard(st));
    wrap.appendChild(trendCard(st));
    if (st.caps.protectAlarm) wrap.appendChild(flagsCard(st));
    var sc = controlShortcuts(st);
    if (sc) wrap.appendChild(sc);
    return wrap;
  }

  function connectCard(st) {
    var body = el('div');
    var p = el('p', null, st.scanning ? '正在等待浏览器设备选择器…'
      : '点下面的按钮，浏览器会弹出附近的蓝牙设备列表。');
    p.style.cssText = 'font-size:13px;color:var(--muted);margin:0 0 12px';
    body.appendChild(p);

    var row = el('div', 'btn-row');
    var b = el('button', 'btn primary', '① 选择设备并连接');
    b.addEventListener('click', function () { M.connect(); });
    row.appendChild(b);
    body.appendChild(row);

    var saved = (st.devices || []).filter(function (d) { return d.saved; })[0];
    if (saved) {
      body.appendChild(el('p', 'hint-line', '上次连接：' + saved.name +
        '（' + saved.familyLabel + '）。浏览器要求每次打开都重新选择一次设备。'));
    }
    body.appendChild(el('p', 'hint-line',
      '网页版受浏览器限制：关掉页面就断开，无法后台监控，也不发通知。'));
    return card('连接保护板', '', body);
  }

  /* ---------- 健康摘要行：一眼看出"现在要不要管它" ---------- */
  function healthSummary(st) {
    if (!st.hasData) return null;
    var d = st.device, flags = st.flags || {};
    var notes = [];
    (flags.alarm || []).forEach(function (t) { notes.push(t); });
    (flags.protect || []).forEach(function (t) { notes.push('保护：' + t); });
    if (d.chMosReason) notes.push('充电 MOS 关（' + d.chMosReason + '）');
    if (d.disMosReason) notes.push('放电 MOS 关（' + d.disMosReason + '）');
    var level = (flags.protect || []).length ? 'bad' : notes.length ? 'warn' : 'ok';
    var line = el('div', 'summary ' + level);
    line.appendChild(el('i', 'dot'));
    var txt = level === 'ok'
      ? '运行正常 · ' + d.battState
      : (level === 'bad' ? '有保护动作 · ' : '注意 · ') + notes.slice(0, 2).join('，') + (notes.length > 2 ? ' 等 ' + notes.length + ' 项' : '');
    line.appendChild(el('span', null, txt));
    line.title = notes.join('；') || '无告警、无保护动作';
    return line;
  }

  /* ---------- 能量流向条：方向 + 功率 + 预计时长，一眼读懂"现在在干嘛" ---------- */
  function flowStrip(st) {
    if (!st.hasData) return null;
    var d = st.device;
    var charging = d.current < 0 || (!d.power && d.battState === '充电');
    var idle = !d.power;
    var strip = el('div', 'flow' + (idle ? ' idle' : charging ? ' rev' : ' fwd'));

    strip.appendChild(el('span', 'lbl', '电池组'));
    var track = el('div', 'track');
    track.appendChild(el('span', 'dash'));
    var bubble = el('span', 'bubble', num(d.power, 0).replace(/\.0$/, '') + ' W');
    track.appendChild(bubble);
    strip.appendChild(track);
    strip.appendChild(el('span', 'lbl', idle ? '待机' : charging ? '充电器' : '负载'));

    var eta = charging ? d.remainChargeText : d.remainDischargeText;
    var sub = el('div', 'flow-sub');
    sub.textContent = (idle ? '静置中' : charging ? '充电中' : '放电中') +
      (eta && eta !== '--' ? ' · ' + (charging ? '充满' : '可用') + '约 ' + eta : '');
    var wrap = el('div');
    wrap.appendChild(strip);
    wrap.appendChild(sub);
    return wrap;
  }

  /* ---------- 单体分布带：电压在 min~max 上的排布，颜色=高低，圈=均衡 ---------- */
  function bandChart(cells) {
    var ok = cells.filter(function (c) { return !c.missing; });
    if (ok.length < 3) return null;
    var vmin = Infinity, vmax = -Infinity;
    ok.forEach(function (c) {
      if (c.volt < vmin) vmin = c.volt;
      if (c.volt > vmax) vmax = c.volt;
    });
    var span = Math.max(vmax - vmin, 0.0005);
    var strip = el('div', 'band');
    ok.forEach(function (c) {
      var t = (c.volt - vmin) / span;                    // 0=最低 1=最高
      var col = lerpColor([47, 111, 224], [184, 118, 26], t); // 低蓝 → 高琥珀
      var dot = el('i', 'bdot' + (c.balancing ? ' bal' : ''));
      dot.style.left = (4 + t * 92) + '%';
      dot.style.background = col;
      dot.title = '#' + c.index + '  ' + num(c.volt, 3) + 'V' + (c.balancing ? ' · 均衡中' : '');
      strip.appendChild(dot);
    });
    var lab = el('div', 'band-lab');
    lab.appendChild(el('span', null, '低 ' + num(vmin, 3)));
    lab.appendChild(el('span', null, '高 ' + num(vmax, 3)));
    var wrap = el('div');
    wrap.appendChild(strip);
    wrap.appendChild(lab);
    return wrap;
  }

  function lerpColor(a, b, t) {
    var r = Math.round(a[0] + (b[0] - a[0]) * t),
        g = Math.round(a[1] + (b[1] - a[1]) * t),
        bl = Math.round(a[2] + (b[2] - a[2]) * t);
    return 'rgb(' + r + ',' + g + ',' + bl + ')';
  }

  function overviewCard(st) {
    var d = st.device;
    var hero = el('div', 'hero');
    hero.setAttribute('style', 'display:block');

    // SOC 环
    var ringWrap = el('div', 'hero');
    var ring = el('div', 'ring');
    var r = 38, circ = 2 * Math.PI * r;
    var pct = Math.max(0, Math.min(100, d.soc)) / 100;
    ring.innerHTML =
      '<svg width="92" height="92" viewBox="0 0 92 92">' +
      '<circle cx="46" cy="46" r="' + r + '" fill="none" stroke="var(--card-2)" stroke-width="9"/>' +
      '<circle cx="46" cy="46" r="' + r + '" fill="none" stroke="var(--primary)" stroke-width="9" ' +
      'stroke-linecap="round" stroke-dasharray="' + circ.toFixed(1) + '" ' +
      'stroke-dashoffset="' + (circ * (1 - pct)).toFixed(1) + '"/></svg>';
    var pctEl = el('div', 'pct');
    pctEl.appendChild(el('span', null, d.hasData ? String(d.soc) : '--'));
    pctEl.appendChild(el('small', null, '%'));
    ring.appendChild(pctEl);

    var info = el('div', 'info');
    info.appendChild(el('div', 'name', st.deviceName));
    info.appendChild(el('div', 'state', !d.hasData ? '等待数据…' : (st.stalled ? '设备失联' : d.battState)));
    info.appendChild(el('div', 'cap', d.hasData
      ? num(d.remainCapAh, 1) + ' / ' + num(d.totalCapAh, 1) + ' Ah · SOH ' + d.soh + '% · ' + d.cycles + ' 次'
      : '-- / -- Ah'));

    ringWrap.appendChild(ring);
    ringWrap.appendChild(info);
    hero.replaceWith(ringWrap);

    var body = el('div');
    body.appendChild(ringWrap);

    // 状态胶囊
    var pills = el('div', 'pills');
    if (st.caps.mos) {
      pills.appendChild(mosPill('充电 MOS', d.chMos, d.chMosReason));
      pills.appendChild(mosPill('放电 MOS', d.disMos, d.disMosReason));
    }
    if (st.caps.balance) {
      if (!d.hasData) pills.appendChild(pill('均衡 --'));
      else if (d.balanceReason) pills.appendChild(pill('均衡 · ' + d.balanceReason, 'on'));
      else pills.appendChild(pill('均衡 ' + d.balance, d.balance === '均衡中' ? 'on' : ''));
    }
    if (st.caps.permission) {
      pills.appendChild(pill(st.permission > 0 ? '权限 ' + st.permission + ' 级' : '未升权',
        st.permission > 0 ? 'on' : ''));
    }
    if (st.channelLabel) pills.appendChild(pill('通道 ' + st.channelLabel));
    if (st.relay.active) pills.appendChild(pill('中继 ' + st.relay.brand, 'on'));
    body.appendChild(pills);

    var flow = flowStrip(st);
    if (flow) body.appendChild(flow);

    var metrics = el('div', 'metrics');
    metrics.appendChild(metric('总电压', reading(d, d.totalVoltage, d.voltageDecimals), 'V'));
    metrics.appendChild(metric('电流', reading(d, d.current, 2), 'A'));
    metrics.appendChild(metric('功率', d.hasData ? String(d.power) : '--', 'W'));
    metrics.appendChild(metric('压差', d.hasData ? d.deltaCell : '--', 'V'));
    body.appendChild(metrics);

    return card(null, '', body);
  }

  function mosPill(label, text, reason) {
    if (text === '开启') return pill(label + ' 开', 'on');
    if (reason) return pill(label + ' 关 · ' + reason, 'warn');
    if (text === '关闭') return pill(label + ' 关');
    return pill(label + ' --');
  }

  function cellsCard(st) {
    var grid = el('div', 'cells');
    st.cells.forEach(function (c) {
      var cls = 'cell' + (c.missing ? ' missing' : '') +
        (!c.missing && c.isMax ? ' max' : '') + (!c.missing && c.isMin ? ' min' : '');
      var cell = el('div', cls);
      cell.appendChild(el('div', 'idx', c.index));
      cell.appendChild(el('div', 'v', c.missing ? '--' : num(c.volt, 3)));
      if (c.balancing) cell.appendChild(el('span', 'bal'));
      grid.appendChild(cell);
    });

    var d = st.device;
    var head = el('div', 'metrics');
    head.appendChild(metric('最高', d.maxCell, 'V'));
    head.appendChild(metric('最低', d.minCell, 'V'));
    head.appendChild(metric('平均', d.avgCell, 'V'));
    head.appendChild(metric('压差', d.deltaCell, 'V'));

    var legend = el('div', 'cell-legend');
    [['var(--cell-max)', '最高', '3px'], ['var(--cell-min)', '最低', '3px'],
     ['var(--primary)', '均衡中', '50%']].forEach(function (item) {
      var s = el('span');
      var i = el('i');
      i.style.background = item[0];
      i.style.borderRadius = item[2];
      s.appendChild(i);
      s.appendChild(document.createTextNode(item[1]));
      legend.appendChild(s);
    });
    legend.appendChild(el('span', null, '共 ' + st.cells.filter(function (c) { return !c.missing; }).length + ' 串'));

    var body = el('div');
    body.appendChild(head);
    var band = bandChart(st.cells);
    if (band) body.appendChild(band);
    body.appendChild(grid);
    body.appendChild(legend);

    // 最弱几串：维护时最先看的地方
    var ranked = st.cells.filter(function (c) { return !c.missing; })
      .slice().sort(function (a, b) { return a.volt - b.volt; }).slice(0, 3);
    if (ranked.length >= 3 && ranked[2].volt < ranked[0].volt + 0.001) {
      // 电压几乎相同就不显示"最弱"（没有区分度）
    } else if (ranked.length) {
      var weak = el('div', 'weak');
      weak.appendChild(el('span', 'k', '最弱 ' + ranked.length + ' 串'));
      ranked.forEach(function (c, i) {
        if (i) weak.appendChild(el('span', 'sep', '·'));
        weak.appendChild(el('span', 'w', '#' + c.index + ' ' + num(c.volt, 3) + (c.balancing ? ' ◈' : '')));
      });
      body.appendChild(weak);
    }
    return card('单体电压', '', body);
  }

  function tempsCard(st) {
    var row = el('div', 'temps');
    st.temps.forEach(function (t) {
      var cls = t.value >= 55 ? 'temp hot'
        : t.value >= 45 ? 'temp warm'
        : t.value <= 0 ? 'temp cold' : 'temp';
      var box = el('div', cls);
      box.appendChild(el('div', 'k', t.name));
      box.appendChild(el('div', 'v', num(t.value, 1)));
      row.appendChild(box);
    });
    return card('温度', '', row);
  }

  function trendCard(st) {
    var body = el('div');
    var cur = st.trend.current || [], volt = st.trend.volt || [];
    var n = Math.max(cur.length, volt.length);

    if (!n) {
      body.appendChild(el('p', 'hint-line', '有数据后显示最近 40 拍的趋势'));
    } else {
      var w = 320, h = 64;
      function path(values) {
        if (values.length < 2) return '';
        var step = w / (values.length - 1);
        return values.map(function (v, i) {
          var x = i * step;
          var y = (h - 3) - (Math.max(0, Math.min(1, v)) * (h - 6));
          return (i ? 'L' : 'M') + x.toFixed(1) + ',' + y.toFixed(1);
        }).join(' ');
      }
      var svg = '<svg class="trend" viewBox="0 0 ' + w + ' ' + h + '" preserveAspectRatio="none">';
      var cp = path(cur), vp = path(volt);
      svg += '<line x1="0" y1="' + (h / 2) + '" x2="' + w + '" y2="' + (h / 2) + '" stroke="var(--card-2)" stroke-dasharray="3 4"/>';
      if (cp) {
        svg += '<path d="' + cp + ' L' + w + ',' + h + ' L0,' + h + ' Z" fill="var(--chart)" fill-opacity=".13" stroke="none"/>';
        svg += '<path d="' + cp + '" fill="none" stroke="var(--chart)" stroke-width="1.8" stroke-linejoin="round"/>';
      }
      if (vp) svg += '<path d="' + vp + '" fill="none" stroke="var(--primary)" stroke-width="1.8" stroke-linejoin="round"/>';
      if (cur.length > 1) {
        var last = cur[cur.length - 1];
        svg += '<circle cx="' + w + '" cy="' + ((h - 3) - (Math.max(0, Math.min(1, last)) * (h - 6))).toFixed(1) + '" r="3" fill="var(--chart)"/>';
      }
      svg += '</svg>';
      var holder = el('div');
      holder.innerHTML = svg;
      body.appendChild(holder);

      var legend = el('div', 'trend-legend');
      legend.innerHTML = '<span><i style="background:var(--chart)"></i>电流</span>' +
        '<span><i style="background:var(--primary)"></i>电压</span>';
      body.appendChild(legend);
    }
    return card('趋势', '最近 ' + n + ' 拍', body);
  }

  function flagsCard(st) {
    function block(title, list, emptyHint) {
      var box = el('div', 'block');
      var h = el('h3');
      h.appendChild(el('span', null, title));
      h.appendChild(el('span', 'n', list.length));
      box.appendChild(h);
      if (!list.length) {
        box.appendChild(el('div', 'empty', emptyHint));
      } else {
        var ul = el('ul');
        list.forEach(function (t) { ul.appendChild(el('li', null, t)); });
        box.appendChild(ul);
      }
      return box;
    }
    var body = el('div');
    var grid = el('div', 'flags');
    grid.appendChild(block('保护', st.flags.protect, '无保护动作'));
    grid.appendChild(block('告警', st.flags.alarm, '无告警'));
    body.appendChild(grid);
    if (st.flags.states.length) {
      body.appendChild(el('p', 'hint-line', '运行状态：' + st.flags.states.join('、')));
    }
    return card('保护与告警', '', body);
  }

  function controlShortcuts(st) {
    var caps = st.caps;
    if (!caps.controlCharge && !caps.controlDischarge && !caps.controlBalance) return null;
    var canWrite = st.permission > 0;
    var row = el('div', 'btn-row');

    function btn(label, cmd) {
      var b = el('button', 'btn', label);
      if (!canWrite) b.disabled = true;
      b.addEventListener('click', async function () {
        var r = await M.control(cmd);
        toast(label + (r.ok ? ' 已执行' : ' 失败：' + r.text));
      });
      return b;
    }
    if (caps.controlDischarge) row.appendChild(btn(st.device.disMos === '开启' ? '关闭放电' : '打开放电',
      st.device.disMos === '开启' ? 1 : 3));
    if (caps.controlCharge) row.appendChild(btn(st.device.chMos === '开启' ? '关闭充电' : '打开充电',
      st.device.chMos === '开启' ? 4 : 6));
    if (caps.controlBalance) row.appendChild(btn(st.device.balance === '均衡中' ? '关闭均衡' : '打开均衡',
      st.device.balance === '均衡中' ? 14 : 13));

    var body = el('div');
    body.appendChild(row);
    if (!canWrite) body.appendChild(el('p', 'hint-line', '当前为只读（0 级）。到「设置 → 权限」输入密码后可控制。'));
    return card('快捷控制', canWrite ? '' : '需输入密码', body);
  }

  /* ---------------- 参数 ---------------- */

  function renderParams(st) {
    var wrap = document.createDocumentFragment();

    if (st.params.reading) wrap.appendChild(banner('正在读取参数区…（200+ 项，通常几秒）', 'info'));
    if (st.params.fromCache) wrap.appendChild(banner('显示的是上次成功连接时缓存的配置（非实时）。'));

    var groups = (ui.meta || {}).paramGroups || [];
    var live = st.connected;
    if (!groups.length || !Object.keys(st.params.values || {}).length) {
      wrap.appendChild(card(null, '', emptyState('⚙', '还没有参数数据',
        live ? '等待设备回读参数区…' : '连接保护板后可以查看与修改参数。')));
      return wrap;
    }

    var canWrite = live && st.permission > 0;
    if (!live) wrap.appendChild(banner('离线查看（未连接设备）：显示缓存或快照里的参数，不能修改。', 'info'));
    else if (!canWrite) wrap.appendChild(banner('当前为只读（0 级）：只能查看。到「设置 → 权限」输入密码后可修改并保存。'));

    groups.forEach(function (g, gi) {
      var list = el('div', 'list');
      g.defs.forEach(function (def) {
        var row = el('div', 'row');
        if (!def.readOnly && canWrite) row.className = 'row tap';

        var main = el('div', 'main');
        main.appendChild(el('div', 'title', def.name));
        var desc = def.addrHex + ' · ' + def.rangeText;
        if (def.note) desc += ' · ' + def.note;
        main.appendChild(el('div', 'desc', desc));
        row.appendChild(main);

        var val = el('div', 'val' + (def.display === '--' ? ' dim' : ''));
        val.appendChild(el('span', null, def.display));
        if (def.display !== '--' && def.unit) val.appendChild(el('small', null, def.unit));
        row.appendChild(val);

        if (!def.readOnly && canWrite) {
          row.addEventListener('click', function () { editParam(def); });
        }
        list.appendChild(row);
      });
      wrap.appendChild(card(g.name, g.defs.length + ' 项', list));
    });

    var actions = el('div', 'btn-row');
    if (live) {
      var rb = el('button', 'btn', '重新读取');
      rb.addEventListener('click', function () {
        toast('正在重新读取参数…');
        M.refreshParams();
      });
      actions.appendChild(rb);

      var sb = el('button', 'btn primary', canWrite ? '保存全部参数' : '保存（需权限）');
      if (!canWrite) sb.disabled = true;
      sb.addEventListener('click', async function () {
        toast('正在保存全部参数…');
        var r = await M.saveAllParams();
        toast(r.ok ? '参数已保存到设备' : '保存失败：' + r.text);
      });
      actions.appendChild(sb);
    }
    if (actions.children.length) wrap.appendChild(card('操作', '', actions));
    return wrap;
  }

  /** 参数编辑弹层 */
  function editParam(def) {
    sheet(function (close) {
      var box = el('div', 'sheet');
      box.appendChild(el('h2', null, '修改参数'));
      box.appendChild(el('div', 'sub', '写入后会回读确认；设备侧还需「保存参数」才持久化。'));

      var field = el('div', 'field');
      field.appendChild(el('label', null, def.name + '（' + def.unit + '）'));

      var input = document.createElement('input');
      input.type = 'number';
      input.step = 'any';
      input.value = def.display === '--' ? '' : def.display;
      field.appendChild(input);
      field.appendChild(el('div', 'hint', '地址 ' + def.addrHex + ' · 范围 ' + def.rangeText));
      box.appendChild(field);

      var err = el('div', 'hint');
      err.style.color = 'var(--bad)';
      box.appendChild(err);

      var row = el('div', 'btn-row');
      var cancel = el('button', 'btn', '取消');
      cancel.addEventListener('click', close);
      row.appendChild(cancel);

      var ok = el('button', 'btn primary', '写入');
      ok.addEventListener('click', async function () {
        var v = parseFloat(input.value.replace('，', '.'));
        if (!isFinite(v) || v < def.min - 1e-9 || v > def.max + 1e-9) {
          err.textContent = '请输入合法数值（' + def.rangeText + '）';
          return;
        }
        // 原始值 = 工程量 × 倍率（与 App 同一口径）
        var raw = Math.round(v * def.scale);
        close();
        var r = await M.writeParam(def.addr, raw);
        toast(def.name + (r.ok ? ' 已写入' : ' 写入失败：' + r.text));
      });
      row.appendChild(ok);
      box.appendChild(row);
      return box;
    });
  }

  /* ---------------- 控制 ---------------- */

  function renderControl(st) {
    var wrap = document.createDocumentFragment();
    if (!st.connected) {
      wrap.appendChild(card(null, '', emptyState('🎛', '未连接', '连接保护板后可以下发控制命令。')));
      return wrap;
    }
    var canWrite = st.permission > 0;
    if (!canWrite) wrap.appendChild(banner('当前为只读（0 级），控制命令不可用。到「设置 → 权限」输入密码。'));

    var meta = ui.meta || {};
    var cmds = meta.commands || [];
    var toggles = cmds.filter(function (c) { return c.toggle; });
    var others = cmds.filter(function (c) { return !c.toggle && !c.dangerous; });
    var dangerous = cmds.filter(function (c) { return c.dangerous; });

    function btnRowOf(list) {
      var row = el('div', 'btn-row');
      list.forEach(function (c) {
        var b = el('button', 'btn sm', c.name);
        if (!canWrite) b.disabled = true;
        b.addEventListener('click', async function () {
          var r = await M.control(c.cmd);
          toast(c.name + (r.ok ? ' 已执行' : ' 失败：' + r.text));
        });
        row.appendChild(b);
      });
      return row;
    }

    if (toggles.length) wrap.appendChild(card('开关', '', btnRowOf(toggles)));
    if (others.length) wrap.appendChild(card('维护', '', btnRowOf(others)));

    if (dangerous.length) {
      var list = el('div', 'list');
      dangerous.forEach(function (c) {
        var row = el('div', 'row tap');
        var main = el('div', 'main');
        main.appendChild(el('div', 'title', c.name));
        main.appendChild(el('div', 'desc', c.warning || ''));
        row.appendChild(main);
        var b = el('button', 'btn sm danger', '执行');
        if (!canWrite) b.disabled = true;
        b.addEventListener('click', function () { confirmCommand(c); });
        row.appendChild(b);
        list.appendChild(row);
      });
      wrap.appendChild(card('危险操作', '需二次确认', list));
    }
    return wrap;
  }

  function confirmCommand(c) {
    sheet(function (close) {
      var box = el('div', 'sheet');
      box.appendChild(el('h2', null, c.name));
      box.appendChild(el('div', 'sub', '这条命令会改变设备状态，请确认对象正确。'));
      if (c.warning) {
        var w = el('div', 'warn-text', c.warning);
        box.appendChild(w);
      }

      var input = null;
      if (c.typedConfirm) {
        var field = el('div', 'field');
        field.appendChild(el('label', null, '此操作不可撤销，请输入「确认」继续'));
        input = document.createElement('input');
        input.type = 'text';
        input.placeholder = '输入 确认';
        field.appendChild(input);
        box.appendChild(field);
      }

      var row = el('div', 'btn-row');
      var cancel = el('button', 'btn', '取消');
      cancel.addEventListener('click', close);
      row.appendChild(cancel);

      var ok = el('button', 'btn danger', '确认执行');
      ok.addEventListener('click', async function () {
        if (c.typedConfirm && input.value.trim() !== '确认') { toast('请先输入「确认」'); return; }
        close();
        var r = await M.control(c.cmd);
        toast(c.name + (r.ok ? ' 已执行' : ' 失败：' + r.text));
      });
      row.appendChild(ok);
      box.appendChild(row);
      return box;
    });
  }

  /* ---------------- 设置 ---------------- */

  function renderSettings(st) {
    if (ui.settingsPage === 'auth') return renderAuth(st);
    if (ui.settingsPage === 'devices') return renderDevices(st);
    if (ui.settingsPage === 'log') return renderLog(st);
    if (ui.settingsPage === 'about') return renderAbout(st);
    if (ui.settingsPage === 'channels') return renderChannels(st);
    return renderSettingsRoot(st);
  }

  function backRow(title) {
    var bar = el('div', 'topbar');
    bar.style.position = 'static';
    var b = el('button', 'btn sm', '‹ 返回');
    b.addEventListener('click', function () { ui.settingsPage = 'root'; renderAll(); });
    bar.appendChild(b);
    bar.appendChild(el('h1', null, title));
    return bar;
  }

  function settingsRow(title, value, onClick) {
    var c = el('section', 'card');
    c.style.padding = '4px 14px';
    var row = el('div', 'row tap');
    var main = el('div', 'main');
    main.appendChild(el('div', 'title', title));
    row.appendChild(main);
    row.appendChild(el('span', 'val dim', value));
    var arrow = el('span', null, '›');
    arrow.style.color = 'var(--faint)';
    row.appendChild(arrow);
    row.addEventListener('click', onClick);
    c.appendChild(row);
    return c;
  }

  function renderSettingsRoot(st) {
    var wrap = document.createDocumentFragment();
    var meta = ui.meta || {};

    // 连接
    var conn = el('div', 'list');
    var crow = el('div', 'row');
    var cmain = el('div', 'main');
    cmain.appendChild(el('div', 'title', st.connected ? st.deviceName : '未连接'));
    cmain.appendChild(el('div', 'desc', st.connected
      ? '通道 ' + (st.channelLabel || '?') + ' · 设备家族 ' + st.familyLabel
      : (st.hint || '点下面的按钮选择设备')));
    crow.appendChild(cmain);
    var cbtn = el('button', 'btn sm' + (st.connected ? '' : ' primary'), st.connected ? '断开' : '连接');
    cbtn.addEventListener('click', function () {
      if (st.connected) M.disconnect(); else M.connect();
    });
    crow.appendChild(cbtn);
    conn.appendChild(crow);
    wrap.appendChild(card('连接', '', conn));

    // 外观
    var themeBody = el('div');
    themeBody.appendChild(seg([
      { value: 'system', label: '跟随系统' },
      { value: 'light', label: '浅色' },
      { value: 'dark', label: '深色' }
    ], M.themeMode(), function (v) {
      M.setThemeMode(v); applyTheme(); renderAll();
    }));
    themeBody.appendChild(el('p', 'hint-line',
      '需要更大的字？直接用浏览器的页面缩放（Chrome 菜单 → 缩放），比应用内缩放更好用。'));
    var sw = el('button', 'btn block', '切换页面主题…');
    sw.style.marginTop = '10px';
    sw.addEventListener('click', function () {
      location.href = 'switch.html?from=' + encodeURIComponent(THEME_FILE);
    });
    themeBody.appendChild(sw);
    wrap.appendChild(card('外观', '', themeBody));

    // 轮询
    var pollBody = el('div');
    pollBody.appendChild(seg([
      { value: 900, label: '更稳 900ms' },
      { value: 600, label: '默认 600ms' },
      { value: 300, label: '更跟手 300ms' }
    ], st.settings.pollInterval, function (v) {
      M.setPollInterval(v); toast('轮询频率已设为 ' + v + ' ms'); renderAll();
    }));
    pollBody.appendChild(el('p', 'hint-line',
      '网页端受浏览器节流影响：页面切到后台时轮询会暂停（回来立即恢复）。'));
    wrap.appendChild(card('轮询频率', '弱信号建议选更稳', pollBody));

    wrap.appendChild(settingsRow('权限',
      st.connected ? (st.permission > 0 ? st.permission + ' 级（可写）' : '只读（0 级）') : '未连接',
      function () { ui.settingsPage = 'auth'; renderAll(); }));

    if (st.channels.length) {
      wrap.appendChild(settingsRow('通信通道', st.channelLabel || '自动',
        function () { ui.settingsPage = 'channels'; renderAll(); }));
    }

    wrap.appendChild(settingsRow('历史设备', (st.devices || []).length + ' 台',
      function () { ui.settingsPage = 'devices'; renderAll(); }));
    wrap.appendChild(settingsRow('运行日志', ui.logLines.length + ' 条',
      function () { ui.settingsPage = 'log'; renderAll(); }));
    wrap.appendChild(settingsRow('关于', 'v' + ((st.version || {}).name || ''),
      function () { ui.settingsPage = 'about'; renderAll(); }));

    // 网页版限制（显眼说明，避免"为什么没有通知"的困惑）
    var lim = el('div');
    var ul = el('ul');
    ul.style.cssText = 'margin:0;padding-left:20px;font-size:12.5px;color:var(--muted);line-height:1.9';
    ['关掉页面即断开连接：浏览器不允许网页在后台常驻蓝牙。',
     '没有告警通知与后台监控：需要这两项请安装 Android 版。',
     '每次打开需重新选择设备：浏览器安全策略要求用户手势授权。',
     '配置与密码存在本机浏览器里（localStorage），换浏览器/清缓存会丢。'].forEach(function (t) {
      ul.appendChild(el('li', null, t));
    });
    lim.appendChild(ul);
    wrap.appendChild(card('网页版限制', '', lim));
    return wrap;
  }

  function renderAuth(st) {
    var wrap = el('div');
    wrap.appendChild(backRow('权限'));
    if (!st.connected) wrap.appendChild(banner('未连接：可以先保存密码，连上后会自动升权。'));

    var list = el('div', 'list');
    for (var lv = 1; lv <= 5; lv++) {
      (function (level) {
        var has = st.passwords.indexOf(level) >= 0;
        var isCurrent = st.connected && st.permission === level;
        var row = el('div', 'row tap');
        var main = el('div', 'main');
        main.appendChild(el('div', 'title', level + ' 级' + (isCurrent ? ' · 当前' : '')));
        main.appendChild(el('div', 'desc', has ? '已记住密码' : '未设置'));
        row.appendChild(main);

        var b = el('button', 'btn sm' + (has ? '' : ' primary'), has ? '更换' : '设置');
        b.addEventListener('click', function () { askPassword(level, st); });
        row.appendChild(b);

        if (has) {
          var del = el('button', 'btn sm ghost', '清除');
          del.addEventListener('click', function () {
            M.forgetPassword(level); toast('已清除 ' + level + ' 级密码'); renderAll();
          });
          row.appendChild(del);
        }
        list.appendChild(row);
      })(lv);
    }
    wrap.appendChild(card('密码', '按设备保存', list));

    // 自动升权目标
    var targetBody = el('div');
    var meta = ui.meta || {};
    targetBody.appendChild(seg([0, 1, 2, 3, 4, 5].map(function (v) {
      return { value: v, label: v === 0 ? '自动' : v + ' 级' };
    }), meta.settings.autoUpgradeTarget, function (v) {
      M.setAutoUpgradeTarget(v); renderAll();
    }));
    targetBody.appendChild(el('p', 'hint-line',
      '「自动」= 用已记住的最高一级密码。设备闲置约 5 分钟后权限会回落到 0 级，网页端会自动重升。'));
    wrap.appendChild(card('连接后自动升到', '', targetBody));

    wrap.appendChild(card('说明', '', el('p', 'hint-line',
      '密码保存在浏览器的 localStorage（按设备分开存）。清除浏览器数据、换浏览器或换域名都会丢失。' +
      '密码不会上传到任何服务器。')));
    return wrap;
  }

  function askPassword(level, st) {
    sheet(function (close) {
      var box = el('div', 'sheet');
      box.appendChild(el('h2', null, level + ' 级密码'));
      box.appendChild(el('div', 'sub', '密码用于解锁参数写入与设备控制。'));

      var field = el('div', 'field');
      var input = document.createElement('input');
      input.type = 'password';
      input.placeholder = '请输入 ' + level + ' 级密码';
      field.appendChild(input);
      box.appendChild(field);

      var row = el('div', 'btn-row');
      var cancel = el('button', 'btn', '取消');
      cancel.addEventListener('click', close);
      row.appendChild(cancel);

      var ok = el('button', 'btn primary', st.connected ? '校验并保存' : '仅保存');
      ok.addEventListener('click', async function () {
        var pw = input.value.trim();
        if (!pw) { toast('密码不能为空'); return; }
        close();
        if (st.connected) {
          toast('正在校验 ' + level + ' 级…');
          var r = await M.auth(level, pw);
          toast(r.ok ? level + ' 级校验通过（当前 ' + r.level + ' 级）' : '密码不正确');
        } else {
          M.savePassword(level, pw);
          toast(level + ' 级密码已保存（连上后自动校验）');
        }
        renderAll();
      });
      row.appendChild(ok);
      box.appendChild(row);
      return box;
    });
  }

  function renderDevices(st) {
    var wrap = el('div');
    wrap.appendChild(backRow('历史设备'));
    var meta = ui.meta || {};
    var devices = meta.devices || [];
    if (!devices.length) {
      wrap.appendChild(card(null, '', emptyState('📱', '还没有历史设备', '连接一次后会自动记在这里。')));
      return wrap;
    }
    var list = el('div', 'list');
    devices.forEach(function (p) {
      var row = el('div', 'row');
      var main = el('div', 'main');
      main.appendChild(el('div', 'title', p.name));
      main.appendChild(el('div', 'desc', p.familyLabel + ' · ' +
        (p.passwords ? p.passwords + ' 级密码' : '无密码') + (p.saved ? ' · 上次连接' : '')));
      row.appendChild(main);

      var del = el('button', 'btn sm', '删除');
      del.addEventListener('click', function () {
        sheet(function (close) {
          var box = el('div', 'sheet');
          box.appendChild(el('h2', null, '删除 ' + p.name + '？'));
          box.appendChild(el('div', 'sub', '该设备的密码、配置缓存与全部快照会一并删除，无法恢复。'));
          var r = el('div', 'btn-row');
          var c = el('button', 'btn', '取消');
          c.addEventListener('click', close);
          r.appendChild(c);
          var d = el('button', 'btn danger', '删除');
          d.addEventListener('click', function () {
            M.deleteDevice(p.address); close(); toast('已删除 ' + p.name); renderAll();
          });
          r.appendChild(d);
          box.appendChild(r);
          return box;
        });
      });
      row.appendChild(del);
      list.appendChild(row);
    });
    wrap.appendChild(card('共 ' + devices.length + ' 台', '', list));
    return wrap;
  }

  function renderChannels(st) {
    var wrap = el('div');
    wrap.appendChild(backRow('通信通道'));
    var list = el('div', 'list');
    st.channels.forEach(function (ch) {
      var isCurrent = st.channelLabel === ch.label;
      var row = el('div', 'row' + (isCurrent ? '' : ' tap'));
      var main = el('div', 'main');
      main.appendChild(el('div', 'title', ch.label + (isCurrent ? ' · 当前' : '')));
      main.appendChild(el('div', 'desc', ch.warn || (ch.tag + ' · 可用于固件升级')));
      row.appendChild(main);
      if (!isCurrent) {
        var b = el('button', 'btn sm', '切换');
        b.addEventListener('click', function () {
          toast('正在切换到' + ch.label + '…');
          M.switchChannel(ch.id);
        });
        row.appendChild(b);
      }
      list.appendChild(row);
    });
    wrap.appendChild(card('可用通道', '', list));
    wrap.appendChild(card('说明', '', el('p', 'hint-line',
      '只有暴露可写 FFF5 特征的硬件才有备用通道。备用通道会占用蓝牙屏/充电器那一路，' +
      '使用期间对应外设无法与保护板通信，且不支持固件升级。')));
    return wrap;
  }

  function renderLog(st) {
    var wrap = el('div');
    wrap.appendChild(backRow('运行日志'));

    var settings = el('div', 'list');
    var frow = el('div', 'row tap');
    var fmain = el('div', 'main');
    fmain.appendChild(el('div', 'title', '帧级日志（收发报文）'));
    fmain.appendChild(el('div', 'desc', '记录每一条 TX/RX 十六进制（密码帧已遮蔽）'));
    frow.appendChild(fmain);
    var meta = ui.meta || {};
    var sw = el('div', 'switch' + (st.settings.logFrameOn ? ' on' : ''));
    sw.appendChild(el('div', 'track'));
    frow.appendChild(sw);
    frow.addEventListener('click', function () {
      // 帧开关落在 Kotlin 侧的 BmsLog 上；这里通过重新载入 meta 不现实，直接调数据层没暴露的开关：
      // 走 Maibms.setPollInterval 同款思路——见 BridgeApi 里没有暴露，因此用 localStorage + 重载页
      toast('帧级日志开关请在 Android 版使用；网页端日志量小，默认全部记录');
    });
    settings.appendChild(frow);
    wrap.appendChild(card('日志设置', '', settings));

    var box = el('div', 'log-box');
    if (!ui.logLines.length) {
      box.appendChild(el('div', 'log-line d', '（暂无日志）'));
    } else {
      ui.logLines.slice(-300).forEach(function (line) {
        var cls = 'log-line';
        if (line.indexOf(' E ') >= 0) cls += ' e';
        else if (line.indexOf(' W ') >= 0) cls += ' w';
        else if (line.indexOf(' D ') >= 0) cls += ' d';
        box.appendChild(el('div', cls, line));
      });
    }

    var body = el('div');
    body.appendChild(box);
    var row = el('div', 'btn-row');
    row.style.marginTop = '10px';
    var copy = el('button', 'btn sm', '复制全部');
    copy.addEventListener('click', async function () {
      try {
        await navigator.clipboard.writeText(M.logText());
        toast('已复制到剪贴板');
      } catch (e) {
        toast('复制失败（浏览器拒绝访问剪贴板）');
      }
    });
    row.appendChild(copy);
    var clr = el('button', 'btn sm danger', '清空');
    clr.addEventListener('click', function () { M.clearLog(); ui.logLines = []; toast('日志已清空'); renderAll(); });
    row.appendChild(clr);
    body.appendChild(row);
    wrap.appendChild(card('日志', ui.logLines.length + ' 条', body));

    setTimeout(function () { box.scrollTop = box.scrollHeight; }, 0);
    return wrap;
  }

  function renderAbout(st) {
    var wrap = el('div');
    wrap.appendChild(backRow('关于'));
    var meta = ui.meta || {};
    var v = meta.version || {};

    var head = el('div');
    head.style.cssText = 'text-align:center;padding:8px 0 4px';
    var name = el('div', null, '麻衣 BMS · 网页版');
    name.style.cssText = 'font-size:18px;font-weight:700';
    head.appendChild(name);
    head.appendChild(el('div', 'hint-line', 'v' + (v.name || '')));

    var list = el('div', 'list');
    list.style.marginTop = '10px';
    [['版本', String(v.name || '--')],
     ['构建时间', meta.builtAt ? new Date(meta.builtAt * 1000).toLocaleString() : '--'],
     ['运行平台', '网页（' + browserName() + '）'],
     ['设备连接', 'Web Bluetooth']].forEach(function (kv) {
      var row = el('div', 'row');
      var main = el('div', 'main');
      main.appendChild(el('div', 'title', kv[0]));
      row.appendChild(main);
      row.appendChild(el('span', 'val dim', kv[1]));
      list.appendChild(row);
    });
    head.appendChild(list);
    wrap.appendChild(card(null, '', head));

    var aboutBody = el('div');
    aboutBody.appendChild(el('p', 'hint-line',
      '蚂蚁 BMS 保护板的非官方客户端。协议实现与 Android / iOS 版共用同一套代码，' +
      '所以读数口径、参数表、命令语义完全一致。'));
    aboutBody.appendChild(el('p', 'hint-line',
      '网页版的协议与界面均为本地运行：不上传任何数据。'));
    var links = el('div', 'btn-row');
    links.style.marginTop = '10px';
    var repo = el('button', 'btn sm', '查看项目主页');
    repo.addEventListener('click', function () { window.open('https://github.com/lswlc33/maibms', '_blank'); });
    links.appendChild(repo);
    var dl = el('button', 'btn sm', '下载 Android 版');
    dl.addEventListener('click', function () { window.open('https://github.com/lswlc33/maibms/releases/latest', '_blank'); });
    links.appendChild(dl);
    aboutBody.appendChild(links);
    wrap.appendChild(card('关于本软件', '', aboutBody));

    wrap.appendChild(card('为什么没有「检查更新」', '', el('p', 'hint-line',
      '网页版没有安装过程，每次打开加载的都是最新构建，所以不存在「需要更新」的状态。' +
      '想要推送通知与后台监控，请使用 Android 版。')));
    return wrap;
  }

  function browserName() {
    var ua = navigator.userAgent;
    if (ua.indexOf('Edg/') >= 0) return 'Edge';
    if (ua.indexOf('Chrome/') >= 0) return 'Chrome';
    if (ua.indexOf('Firefox/') >= 0) return 'Firefox';
    if (ua.indexOf('Safari/') >= 0) return 'Safari';
    return '未知浏览器';
  }

  /* ---------------- 整页渲染 ---------------- */

  /**
   * 按页渲染策略：不同标签对"变化"的定义不同。
   * 总览是实时数据每拍都画；参数/控制/设置页只依赖低频字段（参数原始值、权限、
   * 连接态、设备与设置），若每次状态推送都整页重建，用户在 200+ 行的参数列表里
   * 滚到一半就会被拽回顶部——真机不可接受。低频页用 renderKey 判断是否跳过重绘。
   */
  function renderKey(st) {
    switch (ui.tab) {
      case 'params':
        return JSON.stringify([st.params, st.permission, st.connected, st.stalled]);
      case 'control':
        return JSON.stringify([st.connected, st.permission, st.caps, st.device.chMos, st.device.disMos, st.device.balance]);
      case 'settings':
        return JSON.stringify([st.connected, st.permission, st.passwords, st.channels,
          st.devices, st.settings, ui.settingsPage, ui.settingsPage === 'log' ? ui.logLines.length : 0]);
      default:
        return null; // 总览：总是重画（实时读数）
    }
  }

  function renderAll() {
    try { renderAllInner(); }
    catch (e) {
      // 渲染异常显示到页面上而不是静默终止脚本：
      // 本函数由桥的 onState 回调触发，抛错会顺着回调终止整个主题脚本，
      // 页面表现为"半截内容 + 无提示"。显示出来才有的查。
      var msg = String(e && e.stack || e);
      var c = document.getElementById('content') || document.body;
      var box = c.querySelector('.render-error') || c.appendChild(h('div', 'render-error'));
      box.textContent = '渲染出错：' + msg;
      box.style.cssText = 'color:var(--bs-danger-text-emphasis, #ff7b72);padding:12px;font-size:12px;white-space:pre-wrap';
    }
  }

  function renderAllInner() {
    var st = ui.state || {};
    var app = document.getElementById('app');
    if (!app) return;

    // 首次：搭骨架
    if (!app.querySelector('.app')) {
      clear(app);
      var root = el('div', 'app');
      root.appendChild(el('div', 'topbar'));
      root.appendChild(document.createElement('main'));
      root.appendChild(el('nav', 'tabbar'));
      app.appendChild(root);
    }

    // 低频页：内容没变就不重绘（避免滚动跳变与点击丢失）
    var key = renderKey(st);
    if (key !== null && key === ui.lastRenderKey) {
      renderTopbar(st);
      return;
    }
    ui.lastRenderKey = key;

    // 滚动保持：同页重绘后内容高度通常不变，把滚动位置放回去
    var y = window.scrollY;

    renderTopbar(st);
    renderTabbar();

    var main = app.querySelector('main');
    clear(main);
    switch (ui.tab) {
      case 'params': main.appendChild(renderParams(st)); break;
      case 'control': main.appendChild(renderControl(st)); break;
      case 'settings': main.appendChild(renderSettings(st)); break;
      default: main.appendChild(renderDashboard(st));
    }
    if (Math.abs(document.documentElement.scrollHeight - main.scrollHeight - y) < 200) {
      window.scrollTo(0, y);
    }
  }

  /* ---------------- 接线 ---------------- */

  applyTheme();
  if (window.matchMedia) {
    var mq = window.matchMedia('(prefers-color-scheme: dark)');
    if (mq.addEventListener) mq.addEventListener('change', applyTheme);
  }

  // 状态订阅：每次变化重画整页。整份重画是有意的——比细粒度 diff 少一整类 bug，
  // 而这个界面最多几百个节点，浏览器重排是微秒级。
  M.onState(function (s) {
    ui.state = s;
    // 参数页需要最新的 display 值：meta 里的 display 是快照时算的，参数变化后要刷新
    if (ui.tab === 'params' || ui.tab === 'settings') ui.meta = M.meta();
    renderAll();
  });

  M.onLog(function (lines) {
    ui.logLines = Array.prototype.slice.call(lines);
    if (ui.tab === 'settings' && ui.settingsPage === 'log') renderAll();
  });

  // 数据层可能比本脚本更快就绪（同一个 bundle），先主动拉一份再订阅
  ui.state = M.state || {};
  ui.meta = M.meta();
  renderAll();

  window.addEventListener('maibms-ready', function () {
    ui.meta = M.meta();
    renderAll();
  });
})();
