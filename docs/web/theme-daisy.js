/*
 * Daisy 主题（Tailwind + daisyUI）· 逻辑
 *
 * 与前两套主题吃同一份 window.Maibms，界面全用 daisyUI 的语义类：
 *   btn / card / badge / stat / tabs / modal / alert / collapse / input / select
 *
 * 与前两套的差别在于"主题色"：daisyUI 用 `data-theme`（dim / light / corporate…）
 * 切换整套配色，所以这里把应用的三档（跟随系统/浅色/深色）映射到两个 daisyUI 主题：
 *   dark → dim（深灰蓝，比纯黑更柔和）  light → light
 * 跟随系统时按 prefers-color-scheme 选，并监听变化。
 */
(function () {
  'use strict';

  var M = window.Maibms;
  var THEME_FILE = 'daisy.html';
  var DARK_THEME = 'dim';
  var LIGHT_THEME = 'light';

  var ui = { tab: 'dash', settingsPage: 'root', meta: null, state: {}, logLines: [], lastRenderKey: null };

  /* ---------------- 工具 ---------------- */

  function $(sel, root) { return (root || document).querySelector(sel); }

  function h(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = String(text);
    return e;
  }

  function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); }

  function num(v, digits) {
    if (v === null || v === undefined || (typeof v === 'number' && !isFinite(v))) return '--';
    return Number(v).toFixed(digits === undefined ? 2 : digits);
  }

  function reading(st, v, digits) { return st.hasData ? num(v, digits) : '--'; }

  /** daisyUI toast */
  function toast(text) {
    var box = document.getElementById('toastBox');
    if (!box) return;
    var t = h('div', 'alert alert-info shadow-lg text-sm', text);
    box.appendChild(t);
    setTimeout(function () { if (t.parentNode) t.parentNode.removeChild(t); }, 2400);
  }

  function seg(options, current, onPick) {
    var box = h('div', 'join w-full');
    options.forEach(function (o) {
      var b = h('button', 'btn btn-sm join-item flex-1' + (o.value === current ? ' btn-active' : ''), o.label);
      b.addEventListener('click', function () { onPick(o.value); });
      box.appendChild(b);
    });
    return box;
  }

  function card(body, opts) {
    opts = opts || {};
    var c = h('div', 'card bg-base-200 shadow-sm');
    var inner = h('div', 'card-body p-4');
    if (opts.title) {
      var head = h('div', 'flex items-center gap-2 mb-1');
      head.appendChild(h('h2', 'card-title text-sm font-semibold m-0 flex-1', opts.title));
      if (opts.tag) head.appendChild(h('span', 'text-xs opacity-60', opts.tag));
      inner.appendChild(head);
    }
    inner.appendChild(typeof body === 'string' ? h('div', null, body) : body);
    c.appendChild(inner);
    return c;
  }

  function alert(text, kind) {
    var cls = 'alert text-sm py-2.5 px-3.5 items-start';
    if (kind === 'bad') cls += ' alert-error';
    else if (kind === 'warn') cls += ' alert-warning';
    else cls += ' alert-info';
    var a = h('div', cls);
    a.appendChild(h('span', null, text));
    return a;
  }

  function stat(label, value, unit) {
    var s = h('div', 'bg-base-300/60 rounded-xl px-3 py-2');
    s.appendChild(h('div', 'text-[11px] opacity-60', label));
    var v = h('div', 'text-lg font-semibold tabular-nums whitespace-nowrap overflow-hidden text-ellipsis');
    v.appendChild(document.createTextNode(value));
    if (unit) v.appendChild(h('span', 'text-xs font-normal opacity-60 ml-0.5', unit));
    s.appendChild(v);
    return s;
  }

  function badge(text, kind) {
    var cls = 'badge badge-sm whitespace-nowrap';
    if (kind === 'on') cls += ' badge-success badge-outline';
    else if (kind === 'warn') cls += ' badge-warning badge-outline';
    else if (kind === 'bad') cls += ' badge-error badge-outline';
    else cls += ' badge-ghost';
    return h('span', cls, text);
  }

  function emptyState(title, desc) {
    var e = h('div', 'text-center py-10 px-4');
    e.appendChild(h('h3', 'text-base font-semibold mb-1.5', title));
    e.appendChild(h('p', 'text-sm opacity-60 leading-relaxed m-0', desc));
    return e;
  }

  /* ---------------- 主题映射 ---------------- */

  function applyTheme() {
    var mode = M.themeMode();
    var dark = mode === 'dark' || (mode !== 'light' && M.prefersDark && M.prefersDark());
    document.documentElement.setAttribute('data-theme', dark ? DARK_THEME : LIGHT_THEME);
  }

  /* ---------------- 骨架 ---------------- */

  var TABS = [
    { id: 'dash', label: '总览', emoji: '⚡' },
    { id: 'params', label: '参数', emoji: '⚙︎' },
    { id: 'control', label: '控制', emoji: '🎛︎' },
    { id: 'settings', label: '设置', emoji: '☰' }
  ];

  function ensureShell() {
    var app = document.getElementById('app');
    if (app.querySelector('.shell')) return;

    clear(app);
    var shell = h('div', 'shell max-w-[820px] mx-auto pb-24');

    // 顶栏：daisyUI navbar
    var nav = h('div', 'navbar bg-base-100/90 backdrop-blur sticky top-0 z-20 px-3 min-h-0 py-2');
    var left = h('div', 'flex-1 min-w-0');
    var title = h('div', 'font-semibold text-base truncate');
    title.id = 'navTitle';
    left.appendChild(title);
    nav.appendChild(left);

    var status = h('span', 'badge badge-sm badge-ghost mr-2');
    status.id = 'navStatus';
    nav.appendChild(status);

    var themeBtn = h('button', 'btn btn-ghost btn-sm btn-circle', '◐');
    themeBtn.title = '外观与主题';
    themeBtn.addEventListener('click', openAppearance);
    nav.appendChild(themeBtn);
    shell.appendChild(nav);

    var content = h('div', 'px-3 pt-1 space-y-3');
    content.id = 'content';
    shell.appendChild(content);

    // 底栏：daisyUI bottom nav（手写，daisyUI 4 没有现成 bottom-nav 组件）
    var bar = h('div', 'btm-nav btm-nav-sm fixed bottom-0 left-0 right-0 z-30 bg-base-100 border-t border-base-300');
    TABS.forEach(function (t) {
      var b = h('button', t.id === ui.tab ? 'active' : '');
      b.appendChild(h('span', 'text-lg leading-none', t.emoji));
      b.appendChild(h('span', 'btm-nav-label text-[11px]', t.label));
      b.addEventListener('click', function () { ui.tab = t.id; renderAll(); });
      bar.appendChild(b);
    });
    shell.appendChild(bar);

    app.appendChild(shell);
  }

  function renderTopbar(st) {
    var t = document.getElementById('navTitle');
    if (t) {
      t.textContent = ({ dash: '总览', params: '参数', control: '控制', settings: '设置' })[ui.tab] +
        (st.connected ? ' · ' + st.deviceName : '');
    }
    var s = document.getElementById('navStatus');
    if (s) {
      var text = st.connected && st.stalled ? '设备失联' : st.linkText;
      s.textContent = text;
      s.className = 'badge badge-sm mr-2 ' + (st.connected && st.stalled ? 'badge-error'
        : st.connected ? 'badge-success' : st.link === 'Connecting' ? 'badge-warning' : 'badge-ghost');
    }
  }

  /* ---------------- 外观 ---------------- */

  function openAppearance() {
    modal('外观与主题', function (body, close) {
      body.appendChild(h('p', 'text-sm opacity-60 m-0 mb-3',
        '深色 / 浅色 / 跟随系统。选择会被记住，所有主题共用。'));

      body.appendChild(seg([
        { value: 'system', label: '跟随系统' },
        { value: 'light', label: '浅色' },
        { value: 'dark', label: '深色' }
      ], M.themeMode(), function (v) {
        M.setThemeMode(v);
        applyTheme();
        close();
        renderAll();
      }));

      body.appendChild(h('p', 'text-xs opacity-60 mt-3 mb-0',
        '需要更大的字？用浏览器的页面缩放即可（Chrome 菜单 → 缩放）。'));

      var row = h('div', 'flex gap-2 mt-4');
      row.appendChild(primaryBtn('切换页面主题…', function () {
        location.href = 'switch.html?from=' + encodeURIComponent(THEME_FILE);
      }));
      body.appendChild(row);
    });
  }

  /** 通用 daisyUI modal */
  function modal(title, build) {
    var dlg = h('dialog', 'modal modal-bottom sm:modal-middle');
    var box = h('div', 'modal-box max-w-[520px]');
    box.appendChild(h('h3', 'font-bold text-lg mb-1', title));
    var body = h('div');
    box.appendChild(body);
    var foot = h('div', 'modal-action');
    var closeBtn = h('button', 'btn btn-sm', '关闭');
    closeBtn.addEventListener('click', function () { dlg.close(); });
    foot.appendChild(closeBtn);
    box.appendChild(foot);
    dlg.appendChild(box);
    document.body.appendChild(dlg);
    dlg.addEventListener('close', function () {
      if (dlg.parentNode) dlg.parentNode.removeChild(dlg);
    });
    build(body, function () { dlg.close(); });
    dlg.showModal();
    return dlg;
  }

  function primaryBtn(text, onClick, extraCls) {
    var b = h('button', 'btn btn-sm btn-primary' + (extraCls ? ' ' + extraCls : ''), text);
    if (onClick) b.addEventListener('click', onClick);
    return b;
  }

  /* ---------------- 总览 ---------------- */

  function renderDashboard(st) {
    var wrap = h('div', 'space-y-3');

    if (!M.supportsWebBluetooth()) {
      var d = h('div');
      d.appendChild(h('div', 'font-semibold mb-2', '此浏览器无法连接保护板'));
      d.appendChild(h('p', 'text-sm opacity-70 m-0', '网页版通过 Web Bluetooth 直连保护板，需要：'));
      var ul = h('ul', 'text-sm opacity-70 my-2 pl-5 leading-relaxed');
      ['安卓手机上的 Chrome（桌面 Chrome / Edge 也支持）',
       '通过 https 或 localhost 打开（浏览器的安全要求）'].forEach(function (x) {
        ul.appendChild(h('li', null, x));
      });
      d.appendChild(ul);
      d.appendChild(h('p', 'text-xs opacity-60 m-0 mt-2',
        '微信/QQ 内置浏览器、Safari、Firefox 都不支持 Web Bluetooth。想要后台监控与通知，请用 Android 版。'));
      wrap.appendChild(card(d, { title: '不支持' }));
      return wrap;
    }

    if (!st.connected && !st.hasData) {
      wrap.appendChild(connectCard(st));
      if (st.scanError) wrap.appendChild(alert(st.scanError, 'bad'));
      return wrap;
    }

    if (!st.connected && st.hasData) {
      wrap.appendChild(alert('界面预览：以下是示例数据，不是真机读数。', 'warn'));
    }
    if (st.hint && !st.connected) wrap.appendChild(alert(st.hint));
    if (st.stalled) wrap.appendChild(alert('设备失联：连续多拍收不到实时数据，正在尝试恢复。', 'bad'));

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
    var body = h('div');
    body.appendChild(h('p', 'text-sm opacity-70 m-0 mb-3', st.scanning
      ? '正在等待浏览器设备选择器…'
      : '点下面的按钮，浏览器会弹出附近的蓝牙设备列表。'));

    body.appendChild(primaryBtn('选择设备并连接', function () { M.connect(); }, 'btn-block'));

    var saved = (st.devices || []).filter(function (d) { return d.saved; })[0];
    if (saved) {
      body.appendChild(h('p', 'text-xs opacity-60 mt-3 mb-0',
        '上次连接：' + saved.name + '（' + saved.familyLabel + '）。浏览器要求每次打开都重新选择一次设备。'));
    }
    body.appendChild(h('p', 'text-xs opacity-60 mt-2 mb-0',
      '关掉页面就断开，无法后台监控，也不发通知。'));
    return card(body, { title: '连接保护板' });
  }

  function overviewCard(st) {
    var d = st.device;
    var body = h('div');

    var hero = h('div', 'flex items-center gap-4');
    var ring = h('div', 'soc-ring');
    var r = 40, circ = 2 * Math.PI * r;
    var pct = Math.max(0, Math.min(100, d.soc)) / 100;
    ring.innerHTML =
      '<svg width="96" height="96" viewBox="0 0 96 96">' +
      '<circle cx="48" cy="48" r="' + r + '" fill="none" stroke="hsl(var(--b3))" stroke-width="9"/>' +
      '<circle cx="48" cy="48" r="' + r + '" fill="none" stroke="hsl(var(--p))" stroke-width="9" ' +
      'stroke-linecap="round" stroke-dasharray="' + circ.toFixed(1) + '" ' +
      'stroke-dashoffset="' + (circ * (1 - pct)).toFixed(1) + '"/></svg>';
    var pctEl = h('div', 'pct');
    pctEl.appendChild(h('span', null, d.hasData ? String(d.soc) : '--'));
    pctEl.appendChild(h('small', null, '%'));
    ring.appendChild(pctEl);
    hero.appendChild(ring);

    var info = h('div', 'flex-1 min-w-0');
    info.appendChild(h('div', 'font-semibold text-[17px] truncate', st.deviceName));
    info.appendChild(h('div', 'text-[13px] opacity-70 mt-0.5',
      !d.hasData ? '等待数据…' : (st.stalled ? '设备失联' : d.battState)));
    info.appendChild(h('div', 'text-xs opacity-70 mt-1.5 tabular-nums', d.hasData
      ? num(d.remainCapAh, 1) + ' / ' + num(d.totalCapAh, 1) + ' Ah · SOH ' + d.soh + '% · ' + d.cycles + ' 次'
      : '-- / -- Ah'));
    hero.appendChild(info);
    body.appendChild(hero);

    var pills = h('div', 'flex flex-wrap gap-1.5 mt-3');
    if (st.caps.mos) {
      pills.appendChild(mosBadge('充电 MOS', d.chMos, d.chMosReason));
      pills.appendChild(mosBadge('放电 MOS', d.disMos, d.disMosReason));
    }
    if (st.caps.balance) {
      if (!d.hasData) pills.appendChild(badge('均衡 --'));
      else if (d.balanceReason) pills.appendChild(badge('均衡 · ' + d.balanceReason, 'on'));
      else pills.appendChild(badge('均衡 ' + d.balance, d.balance === '均衡中' ? 'on' : ''));
    }
    if (st.caps.permission) {
      pills.appendChild(badge(st.permission > 0 ? '权限 ' + st.permission + ' 级' : '未升权',
        st.permission > 0 ? 'on' : ''));
    }
    if (st.channelLabel) pills.appendChild(badge('通道 ' + st.channelLabel));
    if (st.relay.active) pills.appendChild(badge('中继 ' + st.relay.brand, 'on'));
    body.appendChild(pills);

    var g = h('div', 'grid grid-cols-2 sm:grid-cols-4 gap-2 mt-3');
    g.appendChild(stat('总电压', reading(d, d.totalVoltage, d.voltageDecimals), 'V'));
    g.appendChild(stat('电流', reading(d, d.current, 2), 'A'));
    g.appendChild(stat('功率', d.hasData ? String(d.power) : '--', 'W'));
    g.appendChild(stat('压差', d.hasData ? d.deltaCell : '--', 'V'));
    body.appendChild(g);
    return card(body);
  }

  function mosBadge(label, text, reason) {
    if (text === '开启') return badge(label + ' 开', 'on');
    if (reason) return badge(label + ' 关 · ' + reason, 'warn');
    if (text === '关闭') return badge(label + ' 关');
    return badge(label + ' --');
  }

  function cellsCard(st) {
    var body = h('div');
    var d = st.device;

    var g = h('div', 'grid grid-cols-2 sm:grid-cols-4 gap-2');
    g.appendChild(stat('最高', d.maxCell, 'V'));
    g.appendChild(stat('最低', d.minCell, 'V'));
    g.appendChild(stat('平均', d.avgCell, 'V'));
    g.appendChild(stat('压差', d.deltaCell, 'V'));
    body.appendChild(g);

    var grid = h('div', 'cells mt-3');
    st.cells.forEach(function (c) {
      var cls = 'cell' + (c.missing ? ' missing' : '') +
        (!c.missing && c.isMax ? ' max' : '') + (!c.missing && c.isMin ? ' min' : '');
      var cell = h('div', cls);
      cell.appendChild(h('div', 'idx', c.index));
      cell.appendChild(h('div', 'v', c.missing ? '--' : num(c.volt, 3)));
      if (c.balancing) cell.appendChild(h('span', 'bal'));
      grid.appendChild(cell);
    });
    body.appendChild(grid);

    var legend = h('div', 'flex flex-wrap gap-3 text-xs opacity-60 mt-2.5');
    legend.innerHTML =
      '<span><i class="inline-block w-2.5 h-2.5 rounded-sm mr-1 align-[-1px]" style="background:hsl(var(--wa))"></i>最高</span>' +
      '<span><i class="inline-block w-2.5 h-2.5 rounded-sm mr-1 align-[-1px]" style="background:hsl(var(--in))"></i>最低</span>' +
      '<span><i class="inline-block w-1.5 h-1.5 rounded-full mr-1 align-[1px]" style="background:hsl(var(--su))"></i>均衡中</span>';
    legend.appendChild(h('span', null, '共 ' +
      st.cells.filter(function (c) { return !c.missing; }).length + ' 串'));
    body.appendChild(legend);

    return card(body, { title: '单体电压' });
  }

  function tempsCard(st) {
    var body = h('div', 'flex flex-wrap gap-2');
    st.temps.forEach(function (t) {
      var box = h('div', 'flex-1 min-w-[76px] bg-base-300/60 rounded-xl px-2.5 py-2 text-center');
      box.appendChild(h('div', 'text-[10.5px] opacity-60', t.name));
      var v = h('div', 'text-base font-semibold tabular-nums', num(t.value, 1));
      if (t.value >= 55) v.className += ' text-error';
      else if (t.value <= 0) v.className += ' text-info';
      box.appendChild(v);
      body.appendChild(box);
    });
    return card(body, { title: '温度' });
  }

  function trendCard(st) {
    var body = h('div');
    var cur = st.trend.current || [], volt = st.trend.volt || [];
    var n = Math.max(cur.length, volt.length);

    if (!n) {
      body.appendChild(h('p', 'text-sm opacity-60 m-0', '有数据后显示最近 40 拍的趋势'));
    } else {
      var w = 320, hh = 68;
      function path(values) {
        if (values.length < 2) return '';
        var step = w / (values.length - 1);
        return values.map(function (v, i) {
          var x = i * step;
          var y = (hh - 3) - (Math.max(0, Math.min(1, v)) * (hh - 6));
          return (i ? 'L' : 'M') + x.toFixed(1) + ',' + y.toFixed(1);
        }).join(' ');
      }
      var svg = '<svg class="trend" viewBox="0 0 ' + w + ' ' + hh + '" preserveAspectRatio="none">';
      var cp = path(cur), vp = path(volt);
      if (cp) svg += '<path d="' + cp + '" fill="none" stroke="hsl(var(--wa))" stroke-width="2" stroke-linejoin="round"/>';
      if (vp) svg += '<path d="' + vp + '" fill="none" stroke="hsl(var(--p))" stroke-width="2" stroke-linejoin="round"/>';
      svg += '</svg>';
      var holder = h('div');
      holder.innerHTML = svg;
      body.appendChild(holder);

      var legend = h('div', 'flex gap-3.5 text-xs opacity-60 mt-1');
      legend.innerHTML =
        '<span><i class="inline-block w-2.5 h-[3px] rounded-sm mr-1 align-[3px]" style="background:hsl(var(--wa))"></i>电流</span>' +
        '<span><i class="inline-block w-2.5 h-[3px] rounded-sm mr-1 align-[3px]" style="background:hsl(var(--p))"></i>电压</span>';
      body.appendChild(legend);
    }
    return card(body, { title: '趋势', tag: n ? '最近 ' + n + ' 拍' : '' });
  }

  function flagsCard(st) {
    function block(title, list, emptyHint) {
      var box = h('div', 'bg-base-300/60 rounded-xl px-3 py-2.5');
      var head = h('div', 'flex justify-between text-xs font-semibold opacity-70 mb-1.5');
      head.appendChild(h('span', null, title));
      head.appendChild(h('span', null, String(list.length)));
      box.appendChild(head);
      if (!list.length) {
        box.appendChild(h('div', 'text-xs opacity-50', emptyHint));
      } else {
        var ul = h('ul', 'm-0 pl-4');
        list.forEach(function (t) { ul.appendChild(h('li', 'text-xs text-error', t)); });
        box.appendChild(ul);
      }
      return box;
    }

    var body = h('div');
    var grid = h('div', 'grid grid-cols-2 gap-2.5');
    grid.appendChild(block('保护', st.flags.protect, '无保护动作'));
    grid.appendChild(block('告警', st.flags.alarm, '无告警'));
    body.appendChild(grid);

    if (st.flags.states.length) {
      body.appendChild(h('p', 'text-xs opacity-60 mt-2 mb-0',
        '运行状态：' + st.flags.states.join('、')));
    }
    return card(body, { title: '保护与告警' });
  }

  function controlShortcuts(st) {
    var caps = st.caps;
    if (!caps.controlCharge && !caps.controlDischarge && !caps.controlBalance) return null;
    var canWrite = st.permission > 0;
    var row = h('div', 'flex flex-wrap gap-2');

    function mk(label, cmd) {
      var b = h('button', 'btn btn-sm', label);
      if (!canWrite) b.disabled = true;
      b.addEventListener('click', async function () {
        var r = await M.control(cmd);
        toast(label + (r.ok ? ' 已执行' : ' 失败：' + r.text));
      });
      return b;
    }
    if (caps.controlDischarge) row.appendChild(mk(
      st.device.disMos === '开启' ? '关闭放电' : '打开放电',
      st.device.disMos === '开启' ? 1 : 3));
    if (caps.controlCharge) row.appendChild(mk(
      st.device.chMos === '开启' ? '关闭充电' : '打开充电',
      st.device.chMos === '开启' ? 4 : 6));
    if (caps.controlBalance) row.appendChild(mk(
      st.device.balance === '均衡中' ? '关闭均衡' : '打开均衡',
      st.device.balance === '均衡中' ? 14 : 13));

    var body = h('div');
    body.appendChild(row);
    if (!canWrite) {
      body.appendChild(h('p', 'text-xs opacity-60 mt-2 mb-0',
        '当前为只读（0 级）。到「设置 → 权限」输入密码后可控制。'));
    }
    return card(body, { title: '快捷控制', tag: canWrite ? '' : '需输入密码' });
  }

  /* ---------------- 参数 ---------------- */

  function renderParams(st) {
    var wrap = h('div', 'space-y-3');
    if (st.params.reading) wrap.appendChild(alert('正在读取参数区…（200+ 项，通常几秒）'));
    if (st.params.fromCache) wrap.appendChild(alert('显示的是上次成功连接时缓存的配置（非实时）。', 'warn'));

    var groups = (ui.meta || {}).paramGroups || [];
    var live = st.connected;

    if (!groups.length || !Object.keys(st.params.values || {}).length) {
      wrap.appendChild(card(emptyState('还没有参数数据',
        live ? '等待设备回读参数区…' : '连接保护板后可以查看与修改参数。')));
      return wrap;
    }

    var canWrite = live && st.permission > 0;
    if (!live) wrap.appendChild(alert('离线查看（未连接设备）：显示缓存或快照里的参数，不能修改。'));
    else if (!canWrite) wrap.appendChild(alert('当前为只读（0 级）：到「设置 → 权限」输入密码后可修改。', 'warn'));

    groups.forEach(function (g) {
      var list = h('div', 'divide-y divide-base-300');
      g.defs.forEach(function (def) {
        var row = h('div', 'flex items-center gap-2.5 py-2.5');
        var main = h('div', 'flex-1 min-w-0');
        main.appendChild(h('div', 'text-sm', def.name));
        var desc = def.addrHex + ' · ' + def.rangeText;
        if (def.note) desc += ' · ' + def.note;
        main.appendChild(h('div', 'text-[11.5px] opacity-60 mt-0.5', desc));
        row.appendChild(main);

        var val = h('div', 'text-sm tabular-nums whitespace-nowrap' +
          (def.display === '--' ? ' opacity-50' : ''));
        val.appendChild(document.createTextNode(def.display));
        if (def.display !== '--' && def.unit) {
          val.appendChild(h('span', 'text-[11px] opacity-60 ml-0.5', def.unit));
        }
        row.appendChild(val);

        if (!def.readOnly && canWrite) {
          row.className += ' cursor-pointer hover:bg-base-300/40 rounded-lg px-1 -mx-1';
          row.addEventListener('click', function () { editParam(def); });
        }
        list.appendChild(row);
      });
      wrap.appendChild(card(list, { title: g.name, tag: g.defs.length + ' 项' }));
    });

    if (live) {
      var row = h('div', 'flex flex-wrap gap-2');
      var rb = h('button', 'btn btn-sm', '重新读取');
      rb.addEventListener('click', function () { toast('正在重新读取参数…'); M.refreshParams(); });
      row.appendChild(rb);

      var sb = h('button', 'btn btn-sm btn-primary', canWrite ? '保存全部参数' : '保存（需权限）');
      if (!canWrite) sb.disabled = true;
      sb.addEventListener('click', async function () {
        toast('正在保存全部参数…');
        var r = await M.saveAllParams();
        toast(r.ok ? '参数已保存到设备' : '保存失败：' + r.text);
      });
      row.appendChild(sb);
      wrap.appendChild(card(row));
    }
    return wrap;
  }

  function editParam(def) {
    modal('修改参数', function (body, close) {
      body.appendChild(h('p', 'text-sm opacity-60 m-0 mb-3',
        '写入后会回读确认；设备侧还需「保存参数」才持久化。'));

      body.appendChild(h('label', 'block text-xs opacity-60 mb-1.5',
        def.name + '（' + def.unit + '）'));
      var input = h('input', 'input input-bordered w-full');
      input.type = 'number';
      input.step = 'any';
      input.value = def.display === '--' ? '' : def.display;
      body.appendChild(input);
      body.appendChild(h('p', 'text-xs opacity-50 mt-1.5 mb-0',
        '地址 ' + def.addrHex + ' · 范围 ' + def.rangeText));

      var err = h('p', 'text-xs text-error mt-1.5 mb-0');
      err.style.display = 'none';
      body.appendChild(err);

      var row = h('div', 'flex gap-2 mt-4');
      var cancel = h('button', 'btn btn-sm btn-ghost', '取消');
      cancel.addEventListener('click', close);
      row.appendChild(cancel);

      var ok = h('button', 'btn btn-sm btn-primary flex-1', '写入');
      ok.addEventListener('click', async function () {
        var v = parseFloat(input.value.replace('，', '.'));
        if (!isFinite(v) || v < def.min - 1e-9 || v > def.max + 1e-9) {
          err.textContent = '请输入合法数值（' + def.rangeText + '）';
          err.style.display = '';
          return;
        }
        close();
        // 原始值 = 工程量 × 倍率（与 App 同一口径）
        var raw = Math.round(v * def.scale);
        var r = await M.writeParam(def.addr, raw);
        toast(def.name + (r.ok ? ' 已写入' : ' 写入失败：' + r.text));
      });
      row.appendChild(ok);
      body.appendChild(row);
    });
  }

  /* ---------------- 控制 ---------------- */

  function renderControl(st) {
    var wrap = h('div', 'space-y-3');
    if (!st.connected) {
      wrap.appendChild(card(emptyState('未连接', '连接保护板后可以下发控制命令。')));
      return wrap;
    }

    var canWrite = st.permission > 0;
    if (!canWrite) wrap.appendChild(alert('当前为只读（0 级），控制命令不可用。到「设置 → 权限」输入密码。', 'warn'));

    var meta = ui.meta || {};
    var cmds = meta.commands || [];
    var toggles = cmds.filter(function (c) { return c.toggle; });
    var others = cmds.filter(function (c) { return !c.toggle && !c.dangerous; });
    var dangerous = cmds.filter(function (c) { return c.dangerous; });

    function rowOf(list) {
      var row = h('div', 'flex flex-wrap gap-2');
      list.forEach(function (c) {
        var b = h('button', 'btn btn-sm', c.name);
        if (!canWrite) b.disabled = true;
        b.addEventListener('click', async function () {
          var r = await M.control(c.cmd);
          toast(c.name + (r.ok ? ' 已执行' : ' 失败：' + r.text));
        });
        row.appendChild(b);
      });
      return row;
    }

    if (toggles.length) wrap.appendChild(card(rowOf(toggles), { title: '开关' }));
    if (others.length) wrap.appendChild(card(rowOf(others), { title: '维护' }));

    if (dangerous.length) {
      var list = h('div', 'divide-y divide-base-300');
      dangerous.forEach(function (c) {
        var row = h('div', 'flex items-center gap-3 py-2.5');
        var main = h('div', 'flex-1 min-w-0');
        main.appendChild(h('div', 'text-sm', c.name));
        main.appendChild(h('div', 'text-[11.5px] opacity-60 mt-0.5', c.warning || ''));
        row.appendChild(main);
        var b = h('button', 'btn btn-sm btn-error btn-outline', '执行');
        if (!canWrite) b.disabled = true;
        b.addEventListener('click', function () { confirmCommand(c); });
        row.appendChild(b);
        list.appendChild(row);
      });
      wrap.appendChild(card(list, { title: '危险操作', tag: '需二次确认' }));
    }
    return wrap;
  }

  function confirmCommand(c) {
    modal(c.name, function (body, close) {
      body.appendChild(h('p', 'text-sm opacity-60 m-0 mb-3',
        '这条命令会改变设备状态，请确认对象正确。'));
      if (c.warning) body.appendChild(alert(c.warning, 'bad'));

      var input = null;
      if (c.typedConfirm) {
        body.appendChild(h('label', 'block text-xs opacity-60 mt-3 mb-1.5',
          '此操作不可撤销，请输入「确认」继续'));
        input = h('input', 'input input-bordered w-full');
        input.placeholder = '确认';
        body.appendChild(input);
      }

      var row = h('div', 'flex gap-2 mt-4');
      var cancel = h('button', 'btn btn-sm btn-ghost', '取消');
      cancel.addEventListener('click', close);
      row.appendChild(cancel);

      var ok = h('button', 'btn btn-sm btn-error flex-1', '确认执行');
      ok.addEventListener('click', async function () {
        if (c.typedConfirm && input.value.trim() !== '确认') {
          toast('请先输入「确认」');
          return;
        }
        close();
        var r = await M.control(c.cmd);
        toast(c.name + (r.ok ? ' 已执行' : ' 失败：' + r.text));
      });
      row.appendChild(ok);
      body.appendChild(row);
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
    var wrap = h('div');
    var row = h('div', 'flex items-center gap-2');
    var b = h('button', 'btn btn-sm btn-ghost', '‹ 返回');
    b.addEventListener('click', function () { ui.settingsPage = 'root'; renderAll(); });
    row.appendChild(b);
    row.appendChild(h('div', 'font-semibold', title));
    wrap.appendChild(row);
    return wrap;
  }

  function listRow(title, value, onClick) {
    var row = h('div', 'flex items-center gap-3 py-2.5 px-1 rounded-lg' +
      (onClick ? ' cursor-pointer hover:bg-base-300/40' : ''));
    var main = h('div', 'flex-1 min-w-0');
    main.appendChild(h('div', 'text-sm', title));
    row.appendChild(main);
    if (value) row.appendChild(h('span', 'text-xs opacity-60', value));
    if (onClick) {
      row.appendChild(h('span', 'opacity-40 text-sm', '›'));
      row.addEventListener('click', onClick);
    }
    return row;
  }

  function renderSettingsRoot(st) {
    var wrap = h('div', 'space-y-3');
    var meta = ui.meta || {};

    var connBody = h('div');
    var row = h('div', 'flex gap-2');
    var cb = h('button', 'btn btn-sm' + (st.connected ? '' : ' btn-primary'), st.connected ? '断开连接' : '连接设备');
    cb.addEventListener('click', function () { if (st.connected) M.disconnect(); else M.connect(); });
    row.appendChild(cb);
    connBody.appendChild(row);
    connBody.appendChild(h('p', 'text-xs opacity-60 mt-2 mb-0', st.connected
      ? st.deviceName + ' · 通道 ' + (st.channelLabel || '?') + ' · ' + st.familyLabel
      : (st.hint || '尚未连接设备')));
    wrap.appendChild(card(connBody, { title: '连接' }));

    var themeBody = h('div');
    themeBody.appendChild(seg([
      { value: 'system', label: '跟随系统' },
      { value: 'light', label: '浅色' },
      { value: 'dark', label: '深色' }
    ], M.themeMode(), function (v) { M.setThemeMode(v); applyTheme(); renderAll(); }));

    var sw = h('button', 'btn btn-sm btn-outline btn-block mt-3', '切换页面主题…');
    sw.addEventListener('click', function () {
      location.href = 'switch.html?from=' + encodeURIComponent(THEME_FILE);
    });
    themeBody.appendChild(sw);
    wrap.appendChild(card(themeBody, { title: '外观' }));

    var pollBody = h('div');
    pollBody.appendChild(seg([
      { value: 900, label: '更稳 900ms' },
      { value: 600, label: '默认 600ms' },
      { value: 300, label: '更跟手 300ms' }
    ], st.settings.pollInterval, function (v) {
      M.setPollInterval(v); toast('轮询频率已设为 ' + v + ' ms'); renderAll();
    }));
    pollBody.appendChild(h('p', 'text-xs opacity-60 mt-2 mb-0',
      '页面切到后台时轮询会暂停（浏览器节流），回来立即恢复。'));
    wrap.appendChild(card(pollBody, { title: '轮询频率', tag: '弱信号建议选更稳' }));

    var navList = h('div', 'divide-y divide-base-300');
    navList.appendChild(listRow('权限',
      st.connected ? (st.permission > 0 ? st.permission + ' 级' : '只读') : '未连接',
      function () { ui.settingsPage = 'auth'; renderAll(); }));
    if (st.channels.length) {
      navList.appendChild(listRow('通信通道', st.channelLabel || '自动',
        function () { ui.settingsPage = 'channels'; renderAll(); }));
    }
    navList.appendChild(listRow('历史设备', (st.devices || []).length + ' 台',
      function () { ui.settingsPage = 'devices'; renderAll(); }));
    navList.appendChild(listRow('运行日志', ui.logLines.length + ' 条',
      function () { ui.settingsPage = 'log'; renderAll(); }));
    navList.appendChild(listRow('关于', 'v' + ((st.version || {}).name || ''),
      function () { ui.settingsPage = 'about'; renderAll(); }));
    wrap.appendChild(card(navList));

    var lim = h('ul', 'text-xs opacity-60 m-0 pl-5 leading-relaxed');
    ['关掉页面即断开连接：浏览器不允许网页在后台常驻蓝牙。',
     '没有告警通知与后台监控：需要这两项请安装 Android 版。',
     '每次打开需重新选择设备：浏览器安全策略要求用户手势授权。',
     '配置与密码存在本机浏览器里（localStorage），换浏览器/清缓存会丢。'].forEach(function (t) {
      lim.appendChild(h('li', null, t));
    });
    wrap.appendChild(card(lim, { title: '网页版限制' }));
    return wrap;
  }

  function renderAuth(st) {
    var wrap = h('div', 'space-y-3');
    wrap.appendChild(backRow('权限'));
    if (!st.connected) wrap.appendChild(alert('未连接：可以先保存密码，连上后会自动升权。'));

    var list = h('div', 'divide-y divide-base-300');
    for (var lv = 1; lv <= 5; lv++) {
      (function (level) {
        var has = st.passwords.indexOf(level) >= 0;
        var isCurrent = st.connected && st.permission === level;
        var row = h('div', 'flex items-center gap-2 py-2.5');
        var main = h('div', 'flex-1 min-w-0');
        main.appendChild(h('div', 'text-sm', level + ' 级' + (isCurrent ? ' · 当前' : '')));
        main.appendChild(h('div', 'text-[11.5px] opacity-60 mt-0.5', has ? '已记住密码' : '未设置'));
        row.appendChild(main);

        var b = h('button', 'btn btn-xs' + (has ? '' : ' btn-primary'), has ? '更换' : '设置');
        b.addEventListener('click', function () { askPassword(level, st); });
        row.appendChild(b);

        if (has) {
          var del = h('button', 'btn btn-xs btn-ghost', '清除');
          del.addEventListener('click', function () {
            M.forgetPassword(level); toast('已清除 ' + level + ' 级密码'); renderAll();
          });
          row.appendChild(del);
        }
        list.appendChild(row);
      })(lv);
    }
    wrap.appendChild(card(list, { title: '密码', tag: '按设备保存' }));

    var targetBody = h('div');
    var meta = ui.meta || {};
    targetBody.appendChild(seg([0, 1, 2, 3, 4, 5].map(function (v) {
      return { value: v, label: v === 0 ? '自动' : v + ' 级' };
    }), meta.settings.autoUpgradeTarget, function (v) {
      M.setAutoUpgradeTarget(v); renderAll();
    }));
    targetBody.appendChild(h('p', 'text-xs opacity-60 mt-2 mb-0',
      '「自动」= 用已记住的最高一级密码。设备闲置约 5 分钟后权限会回落到 0 级，网页端会自动重升。'));
    wrap.appendChild(card(targetBody, { title: '连接后自动升到' }));

    wrap.appendChild(card(h('p', 'text-xs opacity-60 m-0 leading-relaxed',
      '密码保存在浏览器的 localStorage（按设备分开存）。清除浏览器数据、换浏览器或换域名都会丢失。' +
      '密码不会上传到任何服务器。'), { title: '说明' }));
    return wrap;
  }

  function askPassword(level, st) {
    modal(level + ' 级密码', function (body, close) {
      body.appendChild(h('p', 'text-sm opacity-60 m-0 mb-3', '密码用于解锁参数写入与设备控制。'));
      var input = h('input', 'input input-bordered w-full');
      input.type = 'password';
      input.placeholder = '请输入 ' + level + ' 级密码';
      body.appendChild(input);

      var row = h('div', 'flex gap-2 mt-4');
      var cancel = h('button', 'btn btn-sm btn-ghost', '取消');
      cancel.addEventListener('click', close);
      row.appendChild(cancel);

      var ok = h('button', 'btn btn-sm btn-primary flex-1', st.connected ? '校验并保存' : '仅保存');
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
      body.appendChild(row);
    });
  }

  function renderDevices(st) {
    var wrap = h('div', 'space-y-3');
    wrap.appendChild(backRow('历史设备'));
    var meta = ui.meta || {};
    var devices = meta.devices || [];
    if (!devices.length) {
      wrap.appendChild(card(emptyState('还没有历史设备', '连接一次后会自动记在这里。')));
      return wrap;
    }
    var list = h('div', 'divide-y divide-base-300');
    devices.forEach(function (p) {
      var row = h('div', 'flex items-center gap-2 py-2.5');
      var main = h('div', 'flex-1 min-w-0');
      main.appendChild(h('div', 'text-sm', p.name));
      main.appendChild(h('div', 'text-[11.5px] opacity-60 mt-0.5', p.familyLabel + ' · ' +
        (p.passwords ? p.passwords + ' 级密码' : '无密码') + (p.saved ? ' · 上次连接' : '')));
      row.appendChild(main);

      var del = h('button', 'btn btn-xs btn-error btn-outline', '删除');
      del.addEventListener('click', function () {
        modal('删除 ' + p.name + '？', function (body, close) {
          body.appendChild(h('p', 'text-sm opacity-70 m-0',
            '该设备的密码、配置缓存与全部快照会一并删除，无法恢复。'));
          var r = h('div', 'flex gap-2 mt-4');
          var c = h('button', 'btn btn-sm btn-ghost', '取消');
          c.addEventListener('click', close);
          r.appendChild(c);
          var d = h('button', 'btn btn-sm btn-error flex-1', '删除');
          d.addEventListener('click', function () {
            M.deleteDevice(p.address); close(); toast('已删除 ' + p.name); renderAll();
          });
          r.appendChild(d);
          body.appendChild(r);
        });
      });
      row.appendChild(del);
      list.appendChild(row);
    });
    wrap.appendChild(card(list, { title: '共 ' + devices.length + ' 台' }));
    return wrap;
  }

  function renderChannels(st) {
    var wrap = h('div', 'space-y-3');
    wrap.appendChild(backRow('通信通道'));
    var list = h('div', 'divide-y divide-base-300');
    st.channels.forEach(function (ch) {
      var isCurrent = st.channelLabel === ch.label;
      var row = h('div', 'flex items-center gap-2 py-2.5');
      var main = h('div', 'flex-1 min-w-0');
      main.appendChild(h('div', 'text-sm', ch.label + (isCurrent ? ' · 当前' : '')));
      main.appendChild(h('div', 'text-[11.5px] opacity-60 mt-0.5',
        ch.warn || (ch.tag + ' · 可用于固件升级')));
      row.appendChild(main);
      if (!isCurrent) {
        var b = h('button', 'btn btn-xs btn-outline', '切换');
        b.addEventListener('click', function () {
          toast('正在切换到' + ch.label + '…');
          M.switchChannel(ch.id);
        });
        row.appendChild(b);
      }
      list.appendChild(row);
    });
    wrap.appendChild(card(list, { title: '可用通道' }));
    wrap.appendChild(card(h('p', 'text-xs opacity-60 m-0 leading-relaxed',
      '只有暴露可写 FFF5 特征的硬件才有备用通道。备用通道会占用蓝牙屏/充电器那一路，' +
      '使用期间对应外设无法与保护板通信，且不支持固件升级。'), { title: '说明' }));
    return wrap;
  }

  function renderLog() {
    var wrap = h('div', 'space-y-3');
    wrap.appendChild(backRow('运行日志'));

    var box = h('div', 'log-box bg-base-300/60 rounded-xl p-3');
    if (!ui.logLines.length) {
      box.appendChild(h('div', 'd', '（暂无日志）'));
    } else {
      ui.logLines.slice(-300).forEach(function (line) {
        var cls = '';
        if (line.indexOf(' E ') >= 0) cls = 'e';
        else if (line.indexOf(' W ') >= 0) cls = 'w';
        else if (line.indexOf(' D ') >= 0) cls = 'd';
        box.appendChild(h('div', cls, line));
      });
    }

    var body = h('div');
    body.appendChild(box);
    var row = h('div', 'flex gap-2 mt-3');
    var copy = h('button', 'btn btn-sm btn-outline', '复制全部');
    copy.addEventListener('click', async function () {
      try {
        await navigator.clipboard.writeText(M.logText());
        toast('已复制到剪贴板');
      } catch (e) {
        toast('复制失败（浏览器拒绝访问剪贴板）');
      }
    });
    row.appendChild(copy);
    var clr = h('button', 'btn btn-sm btn-ghost', '清空');
    clr.addEventListener('click', function () {
      M.clearLog(); ui.logLines = []; toast('日志已清空'); renderAll();
    });
    row.appendChild(clr);
    body.appendChild(row);
    wrap.appendChild(card(body, { title: '日志', tag: ui.logLines.length + ' 条' }));

    setTimeout(function () { box.scrollTop = box.scrollHeight; }, 0);
    return wrap;
  }

  function renderAbout() {
    var wrap = h('div', 'space-y-3');
    wrap.appendChild(backRow('关于'));
    var meta = ui.meta || {};
    var v = meta.version || {};

    var head = h('div', 'text-center py-1');
    head.appendChild(h('div', 'text-lg font-bold', '麻衣 BMS · Daisy 主题'));
    head.appendChild(h('div', 'text-xs opacity-60', 'v' + (v.name || '')));
    wrap.appendChild(card(head));

    var list = h('div', 'divide-y divide-base-300');
    [['版本', String(v.name || '--')],
     ['构建时间', meta.builtAt ? new Date(meta.builtAt * 1000).toLocaleString() : '--'],
     ['运行平台', '网页（' + browserName() + '）'],
     ['界面框架', 'Tailwind CSS + daisyUI 4'],
     ['设备连接', 'Web Bluetooth']].forEach(function (kv) {
      list.appendChild(listRow(kv[0], kv[1], null));
    });
    wrap.appendChild(card(list, { title: '版本信息' }));

    var row = h('div', 'flex gap-2');
    var repo = h('button', 'btn btn-sm btn-outline flex-1', '项目主页');
    repo.addEventListener('click', function () { window.open('https://github.com/lswlc33/maibms', '_blank'); });
    row.appendChild(repo);
    var dl = h('button', 'btn btn-sm btn-primary flex-1', '下载 Android 版');
    dl.addEventListener('click', function () { window.open('https://github.com/lswlc33/maibms/releases/latest', '_blank'); });
    row.appendChild(dl);
    wrap.appendChild(card(row, { title: '链接' }));

    wrap.appendChild(card(h('p', 'text-xs opacity-60 m-0 leading-relaxed',
      '网页版没有安装过程，每次打开加载的都是最新构建，所以不存在「需要更新」的状态。' +
      '想要推送通知与后台监控，请使用 Android 版。'), { title: '为什么没有「检查更新」' }));
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

  /* ---------------- 渲染 ---------------- */

  /**
   * 按页渲染策略（与默认/mdui 主题一致）：总览实时画，
   * 参数/控制/设置页只在低频字段变化时重建，避免滚动跳变。
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
        return null;
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
    ensureShell();
    renderTopbar(st);

    var content = document.getElementById('content');
    if (!content) return;

    var key = renderKey(st);
    if (key !== null && key === ui.lastRenderKey) { renderTopbar(st); return; }
    ui.lastRenderKey = key;

    var y = window.scrollY;
    clear(content);

    switch (ui.tab) {
      case 'params': content.appendChild(renderParams(st)); break;
      case 'control': content.appendChild(renderControl(st)); break;
      case 'settings': content.appendChild(renderSettings(st)); break;
      default: content.appendChild(renderDashboard(st));
    }

    if (Math.abs(document.documentElement.scrollHeight - content.scrollHeight - y) < 200) {
      window.scrollTo(0, y);
    }

    var btns = document.querySelectorAll('.btm-nav > button');
    btns.forEach(function (b, i) {
      b.className = TABS[i].id === ui.tab ? 'active' : '';
    });
  }

  /* ---------------- 启动 ---------------- */

  if (!M) {
    document.body.innerHTML = '<div class="p-6 font-sans">' +
      '<h2 class="text-lg font-bold">数据层加载失败</h2>' +
      '<p class="text-sm opacity-70">请确认 ../maibms-data.js 可访问（不要直接双击打开，需用 http(s) 打开）。</p></div>';
    return;
  }

  applyTheme();
  if (window.matchMedia) {
    var mq = window.matchMedia('(prefers-color-scheme: dark)');
    if (mq.addEventListener) mq.addEventListener('change', applyTheme);
  }

  M.onState(function (s) {
    ui.state = s;
    if (ui.tab === 'params' || ui.tab === 'settings') ui.meta = M.meta();
    renderAll();
  });

  M.onLog(function (lines) {
    ui.logLines = Array.prototype.slice.call(lines);
    if (ui.tab === 'settings' && ui.settingsPage === 'log') renderAll();
  });

  ui.state = M.state || {};
  ui.meta = M.meta();
  renderAll();

  window.addEventListener('maibms-ready', function () {
    ui.meta = M.meta();
    renderAll();
  });
})();
