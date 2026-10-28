/*
 * Material 主题（mdui 2）· 逻辑
 *
 * 与默认主题吃同一份 window.Maibms 数据，但界面全部换成 **mdui 组件**：
 *   mdui-top-app-bar / mdui-navigation-bar / mdui-card / mdui-button /
 *   mdui-dialog / mdui-snackbar / mdui-list / mdui-icon
 *
 * 两条硬规矩：
 *  1. 组件的属性/方法只在**元素已升级**后可用（customElements.whenDefined）。
 *     mdui 的 `<mdui-dialog open>` 这类用属性控制，不走 JS 对象状态，所以这里尽量用属性。
 *  2. mdui 从 CDN 加载：加载失败（离线/内网）时降级成一张提示，而不是白屏。
 */
(function () {
  'use strict';

  var M = window.Maibms;
  var THEME_FILE = 'mdui.html';

  var ui = { tab: 'dash', settingsPage: 'root', meta: null, state: {}, logLines: [] };

  /* ---------------- 小工具 ---------------- */

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

  /** mdui-snackbar 提示 */
  function snack(text) {
    var bar = document.getElementById('snack');
    if (!bar) {
      bar = document.createElement('mdui-snackbar');
      bar.id = 'snack';
      document.body.appendChild(bar);
    }
    bar.textContent = text;
    if (typeof bar.open === 'function') bar.open = true;
    else bar.setAttribute('open', '');
  }

  /**
   * 等待 mdui 自定义元素升级。
   *
   * mdui 通过自定义元素实现，`<mdui-dialog>` 在被定义之前只是一个未知元素：
   * 属性（如 `open`）仍然能设，但方法（`.show()`）不存在。这里统一等一次再渲染，
   * 避免"第一屏 Dialog 点了没反应"。
   */
  function whenMduiReady(cb) {
    if (!window.customElements) { setTimeout(cb, 0); return; }
    var tags = ['mdui-top-app-bar', 'mdui-navigation-bar', 'mdui-card', 'mdui-dialog', 'mdui-snackbar'];
    Promise.all(tags.map(function (t) { return customElements.whenDefined(t); }))
      .then(function () { cb(true); })
      .catch(function () { cb(false); });
  }

  /* ---------------- 主题（深/浅） ---------------- */

  /*
   * mdui 有自己的主题机制：`<body class="mdui-theme-auto">` 跟随系统，
   * 或 `mdui-theme-light` / `mdui-theme-dark` 强制。这里把 Maibms 的三档映射过去，
   * 让「外观」选择的语义与默认主题完全一致。
   */
  function applyTheme() {
    var mode = M.themeMode();
    var body = document.body;
    body.classList.remove('mdui-theme-auto', 'mdui-theme-light', 'mdui-theme-dark');
    body.classList.add(mode === 'light' ? 'mdui-theme-light'
      : mode === 'dark' ? 'mdui-theme-dark' : 'mdui-theme-auto');
  }

  /* ---------------- 通用块 ---------------- */

  function card(body, opts) {
    opts = opts || {};
    var c = h('mdui-card', 'card-pad');
    if (opts.variant) c.setAttribute('variant', opts.variant);
    if (opts.title) {
      var head = h('div');
      head.style.cssText = 'display:flex;align-items:center;gap:8px;margin-bottom:10px';
      var t = h('div', null, opts.title);
      t.style.cssText = 'font-size:14px;font-weight:600;flex:1';
      head.appendChild(t);
      if (opts.tag) head.appendChild(h('div', 'muted', opts.tag));
      c.appendChild(head);
    }
    if (typeof body === 'string') c.appendChild(h('div', null, body));
    else c.appendChild(body);
    return c;
  }

  function note(text, kind) {
    var n = h('div', 'note ' + (kind || ''));
    var icon = document.createElement('mdui-icon');
    icon.setAttribute('name', kind === 'bad' ? 'error' : kind === 'warn' ? 'warning' : 'info');
    n.appendChild(icon);
    n.appendChild(h('div', null, text));
    return n;
  }

  function metric(k, v, unit) {
    var m = h('div', 'metric');
    m.appendChild(h('div', 'k', k));
    var val = h('div', 'v');
    val.appendChild(h('span', null, v));
    if (unit) val.appendChild(h('small', null, unit));
    m.appendChild(val);
    return m;
  }

  function pill(text, cls) { return h('span', 'pill ' + (cls || ''), text); }

  function button(text, opts) {
    opts = opts || {};
    var b = h('mdui-button');
    b.textContent = text;
    if (opts.variant) b.setAttribute('variant', opts.variant);
    if (opts.icon) b.setAttribute('icon', opts.icon);
    if (opts.danger) b.setAttribute('variant', 'tonal');
    if (opts.disabled) b.setAttribute('disabled', '');
    if (opts.className) b.className = opts.className;
    if (opts.onClick) b.addEventListener('click', opts.onClick);
    return b;
  }

  function emptyState(big, title, desc) {
    var e = h('div', 'empty');
    e.appendChild(h('div', 'big', big));
    e.appendChild(h('h3', null, title));
    e.appendChild(h('p', null, desc));
    return e;
  }

  /* ---------------- 骨架 ---------------- */

  var TABS = [
    { id: 'dash', label: '总览', icon: 'bolt' },
    { id: 'params', label: '参数', icon: 'tune' },
    { id: 'control', label: '控制', icon: 'gamepad' },
    { id: 'settings', label: '设置', icon: 'menu' }
  ];

  function ensureShell() {
    var app = document.getElementById('app');
    if (app.querySelector('.shell')) return;

    clear(app);
    var shell = h('div', 'shell');

    // 顶栏：标题 + 状态 + 主题按钮
    var bar = h('mdui-top-app-bar');
    bar.setAttribute('scroll-behavior', 'elevate');
    var title = h('mdui-top-app-bar-title');
    title.textContent = '麻衣 BMS';
    bar.appendChild(title);
    var statusChip = h('span', 'pill');
    statusChip.id = 'statusChip';
    bar.appendChild(statusChip);
    var themeBtn = h('mdui-button-icon');
    themeBtn.setAttribute('icon', 'palette');
    themeBtn.setAttribute('title', '外观与主题');
    themeBtn.addEventListener('click', openAppearance);
    bar.appendChild(themeBtn);
    shell.appendChild(bar);

    var content = h('div', 'content');
    shell.appendChild(content);

    var nav = h('mdui-navigation-bar');
    nav.setAttribute('value', ui.tab);
    TABS.forEach(function (t) {
      var item = h('mdui-navigation-bar-item');
      item.setAttribute('value', t.id);
      item.setAttribute('icon', t.icon);
      item.textContent = t.label;
      item.addEventListener('click', function () { ui.tab = t.id; renderAll(); });
      nav.appendChild(item);
    });
    shell.appendChild(nav);

    app.appendChild(shell);
  }

  function renderTopbar(st) {
    var title = $('mdui-top-app-bar-title');
    if (title) {
      title.textContent = ({
        dash: '总览', params: '参数', control: '控制', settings: '设置'
      })[ui.tab] + (st.connected ? ' · ' + st.deviceName : '');
    }

    var chip = document.getElementById('statusChip');
    if (!chip) return;
    chip.textContent = st.connected && st.stalled ? '设备失联' : st.linkText;
    chip.className = 'pill ' + (st.connected && st.stalled ? 'bad' : st.connected ? 'on'
      : st.link === 'Connecting' ? 'warn' : '');
  }

  /* ---------------- 外观 / 主题 ---------------- */

  function openAppearance() {
    var dlg = document.createElement('mdui-dialog');
    dlg.setAttribute('headline', '外观与主题');

    var body = h('div');
    body.appendChild(h('div', 'muted', '深色 / 浅色 / 跟随系统。选择会被记住，所有主题共用。'));

    var group = h('mdui-segmented-button-group', 'mt-3');
    group.setAttribute('selects', 'single');
    group.setAttribute('value', M.themeMode());
    [['system', '跟随系统'], ['light', '浅色'], ['dark', '深色']].forEach(function (kv) {
      var b = h('mdui-segmented-button', null, kv[1]);
      b.setAttribute('value', kv[0]);
      group.appendChild(b);
    });
    group.addEventListener('change', function (e) {
      var v = group.value || (e && e.target && e.target.value);
      if (v) { M.setThemeMode(v); applyTheme(); }
    });
    body.appendChild(group);

    body.appendChild(h('div', 'muted mt-3',
      '需要更大的字？用浏览器的页面缩放即可（Chrome 菜单 → 缩放）。'));

    var row = h('div', 'btn-row mt-4');
    row.appendChild(button('切换页面主题…', {
      variant: 'filled',
      icon: 'dashboard',
      onClick: function () { location.href = 'switch.html?from=' + encodeURIComponent(THEME_FILE); }
    }));
    body.appendChild(row);

    dlg.appendChild(body);

    var actions = h('div');
    actions.setAttribute('slot', 'action');
    var close = button('关闭', { variant: 'text', onClick: function () { dlg.open = false; } });
    actions.appendChild(close);
    dlg.appendChild(actions);

    document.body.appendChild(dlg);
    dlg.open = true;
    dlg.addEventListener('closed', function () {
      if (dlg.parentNode) dlg.parentNode.removeChild(dlg);
    });
  }

  /* ---------------- 总览 ---------------- */

  function renderDashboard(st) {
    var wrap = h('div', 'stack');

    if (!M.supportsWebBluetooth()) {
      wrap.appendChild(card((function () {
        var d = h('div');
        d.appendChild(h('div', null, '此浏览器无法连接保护板').then ? null : null);
        var t = h('div', null, '此浏览器无法连接保护板');
        t.style.cssText = 'font-size:15px;font-weight:600;margin-bottom:8px';
        d.appendChild(t);
        d.appendChild(h('div', 'muted', '网页版通过 Web Bluetooth 直连保护板，需要：'));
        var ul = h('ul');
        ul.style.cssText = 'margin:8px 0 10px;padding-left:20px;font-size:12.5px;line-height:1.9';
        ['安卓手机上的 Chrome（桌面 Chrome / Edge 也支持）',
         '通过 https 或 localhost 打开（浏览器的安全要求）'].forEach(function (x) {
          ul.appendChild(h('li', null, x));
        });
        d.appendChild(ul);
        d.appendChild(h('div', 'muted', '微信/QQ 内置浏览器、Safari、Firefox 都不支持 Web Bluetooth。'));
        d.appendChild(h('div', 'muted mt-2', '想要后台监控与通知，请下载 Android 版。'));
        return d;
      })(), { variant: 'outlined' }));
      return wrap;
    }

    if (!st.connected && !st.hasData) {
      wrap.appendChild(connectCard(st));
      if (st.scanError) wrap.appendChild(note(st.scanError, 'bad'));
      return wrap;
    }

    if (!st.connected && st.hasData) {
      var preview = note('界面预览：以下是示例数据，不是真机读数。', 'warn');
      wrap.appendChild(preview);
    }
    if (st.hint && !st.connected) wrap.appendChild(note(st.hint));
    if (st.stalled) wrap.appendChild(note('设备失联：连续多拍收不到实时数据，正在尝试恢复。', 'bad'));

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
    body.appendChild(h('div', 'muted', st.scanning
      ? '正在等待浏览器设备选择器…'
      : '点下面的按钮，浏览器会弹出附近的蓝牙设备列表。'));

    var row = h('div', 'btn-row mt-3');
    row.appendChild(button('选择设备并连接', {
      variant: 'filled', icon: 'bluetooth',
      onClick: function () { M.connect(); }
    }));
    body.appendChild(row);

    var meta = ui.meta || {};
    var saved = (meta.devices || []).filter(function (d) { return d.saved; })[0];
    if (saved) {
      body.appendChild(h('div', 'muted mt-3',
        '上次连接：' + saved.name + '（' + saved.familyLabel + '）。' +
        '浏览器要求每次打开都重新选择一次设备。'));
    }
    body.appendChild(h('div', 'muted mt-2',
      '关掉页面就断开，无法后台监控，也不发通知。'));
    return card(body, { title: '连接保护板', variant: 'filled' });
  }

  function overviewCard(st) {
    var d = st.device;
    var body = h('div');

    var hero = h('div', 'hero');
    var ring = h('div', 'ring');
    var r = 40, circ = 2 * Math.PI * r;
    var pct = Math.max(0, Math.min(100, d.soc)) / 100;
    ring.innerHTML =
      '<svg width="96" height="96" viewBox="0 0 96 96">' +
      '<circle cx="48" cy="48" r="' + r + '" fill="none" ' +
      'stroke="rgb(var(--mdui-color-surface-container-highest))" stroke-width="9"/>' +
      '<circle cx="48" cy="48" r="' + r + '" fill="none" ' +
      'stroke="rgb(var(--mdui-color-primary))" stroke-width="9" stroke-linecap="round" ' +
      'stroke-dasharray="' + circ.toFixed(1) + '" ' +
      'stroke-dashoffset="' + (circ * (1 - pct)).toFixed(1) + '"/></svg>';
    var pctEl = h('div', 'pct');
    pctEl.appendChild(h('span', null, d.hasData ? String(d.soc) : '--'));
    pctEl.appendChild(h('small', null, '%'));
    ring.appendChild(pctEl);
    hero.appendChild(ring);

    var info = h('div', 'hero-info');
    info.appendChild(h('div', 'name', st.deviceName));
    info.appendChild(h('div', 'state',
      !d.hasData ? '等待数据…' : (st.stalled ? '设备失联' : d.battState)));
    info.appendChild(h('div', 'cap', d.hasData
      ? num(d.remainCapAh, 1) + ' / ' + num(d.totalCapAh, 1) + ' Ah · SOH ' + d.soh + '% · ' + d.cycles + ' 次'
      : '-- / -- Ah'));
    hero.appendChild(info);
    body.appendChild(hero);

    var pills = h('div', 'pills');
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

    var metrics = h('div', 'metrics');
    metrics.appendChild(metric('总电压', reading(d, d.totalVoltage, d.voltageDecimals), 'V'));
    metrics.appendChild(metric('电流', reading(d, d.current, 2), 'A'));
    metrics.appendChild(metric('功率', d.hasData ? String(d.power) : '--', 'W'));
    metrics.appendChild(metric('压差', d.hasData ? d.deltaCell : '--', 'V'));
    body.appendChild(metrics);

    return card(body, { variant: 'filled' });
  }

  function mosPill(label, text, reason) {
    if (text === '开启') return pill(label + ' 开', 'on');
    if (reason) return pill(label + ' 关 · ' + reason, 'warn');
    if (text === '关闭') return pill(label + ' 关');
    return pill(label + ' --');
  }

  function cellsCard(st) {
    var body = h('div');
    var d = st.device;

    var metrics = h('div', 'metrics');
    metrics.style.marginTop = '0';
    metrics.appendChild(metric('最高', d.maxCell, 'V'));
    metrics.appendChild(metric('最低', d.minCell, 'V'));
    metrics.appendChild(metric('平均', d.avgCell, 'V'));
    metrics.appendChild(metric('压差', d.deltaCell, 'V'));
    body.appendChild(metrics);

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

    var legend = h('div', 'legend');
    [['rgb(var(--mdui-color-tertiary))', '最高', '3px'],
     ['rgb(var(--mdui-color-primary))', '最低', '3px'],
     ['rgb(var(--mdui-color-primary))', '均衡中', '50%']].forEach(function (it) {
      var s = h('span');
      var i = h('i');
      i.style.background = it[0];
      i.style.borderRadius = it[2];
      s.appendChild(i);
      s.appendChild(document.createTextNode(it[1]));
      legend.appendChild(s);
    });
    legend.appendChild(h('span', null,
      '共 ' + st.cells.filter(function (c) { return !c.missing; }).length + ' 串'));
    body.appendChild(legend);

    return card(body, { title: '单体电压', variant: 'filled' });
  }

  function tempsCard(st) {
    var body = h('div', 'temps');
    st.temps.forEach(function (t) {
      var cls = t.value >= 55 ? 'temp hot' : (t.value <= 0 ? 'temp cold' : 'temp');
      var box = h('div', cls);
      box.appendChild(h('div', 'k', t.name));
      box.appendChild(h('div', 'v', num(t.value, 1)));
      body.appendChild(box);
    });
    return card(body, { title: '温度', variant: 'filled' });
  }

  function trendCard(st) {
    var body = h('div');
    var cur = st.trend.current || [], volt = st.trend.volt || [];
    var n = Math.max(cur.length, volt.length);

    if (!n) {
      body.appendChild(h('div', 'muted', '有数据后显示最近 40 拍的趋势'));
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
      if (cp) svg += '<path d="' + cp + '" fill="none" stroke="rgb(var(--mdui-color-tertiary))" stroke-width="2" stroke-linejoin="round"/>';
      if (vp) svg += '<path d="' + vp + '" fill="none" stroke="rgb(var(--mdui-color-primary))" stroke-width="2" stroke-linejoin="round"/>';
      svg += '</svg>';
      var holder = h('div');
      holder.innerHTML = svg;
      body.appendChild(holder);

      var legend = h('div', 'legend');
      legend.innerHTML =
        '<span><i style="background:rgb(var(--mdui-color-tertiary))"></i>电流</span>' +
        '<span><i style="background:rgb(var(--mdui-color-primary))"></i>电压</span>';
      body.appendChild(legend);
    }
    return card(body, { title: '趋势', tag: n ? '最近 ' + n + ' 拍' : '', variant: 'filled' });
  }

  function flagsCard(st) {
    function block(title, list, emptyHint) {
      var box = h('div', 'block');
      var head = h('h4');
      head.appendChild(h('span', null, title));
      head.appendChild(h('span', null, String(list.length)));
      box.appendChild(head);
      if (!list.length) {
        box.appendChild(h('div', 'empty', emptyHint));
      } else {
        var ul = h('ul');
        list.forEach(function (t) { ul.appendChild(h('li', null, t)); });
        box.appendChild(ul);
      }
      return box;
    }

    var body = h('div');
    var grid = h('div', 'flags');
    grid.appendChild(block('保护', st.flags.protect, '无保护动作'));
    grid.appendChild(block('告警', st.flags.alarm, '无告警'));
    body.appendChild(grid);

    if (st.flags.states.length) {
      body.appendChild(h('div', 'muted mt-2', '运行状态：' + st.flags.states.join('、')));
    }
    return card(body, { title: '保护与告警', variant: 'filled' });
  }

  function controlShortcuts(st) {
    var caps = st.caps;
    if (!caps.controlCharge && !caps.controlDischarge && !caps.controlBalance) return null;
    var canWrite = st.permission > 0;
    var row = h('div', 'btn-row');

    function mk(label, cmd) {
      return button(label, {
        disabled: !canWrite,
        onClick: async function () {
          var r = await M.control(cmd);
          snack(label + (r.ok ? ' 已执行' : ' 失败：' + r.text));
        }
      });
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
      body.appendChild(h('div', 'muted mt-2',
        '当前为只读（0 级）。到「设置 → 权限」输入密码后可控制。'));
    }
    return card(body, { title: '快捷控制', tag: canWrite ? '' : '需输入密码', variant: 'filled' });
  }

  /* ---------------- 参数 ---------------- */

  function renderParams(st) {
    var wrap = h('div', 'stack');
    if (st.params.reading) wrap.appendChild(note('正在读取参数区…（200+ 项，通常几秒）'));
    if (st.params.fromCache) wrap.appendChild(note('显示的是上次成功连接时缓存的配置（非实时）。', 'warn'));

    var meta = ui.meta || {};
    var groups = meta.paramGroups || [];
    var live = st.connected;

    if (!groups.length || !Object.keys(st.params.values || {}).length) {
      wrap.appendChild(card(emptyState('tune', '还没有参数数据',
        live ? '等待设备回读参数区…' : '连接保护板后可以查看与修改参数。'), { variant: 'outlined' }));
      return wrap;
    }

    var canWrite = live && st.permission > 0;
    if (!live) wrap.appendChild(note('离线查看（未连接设备）：显示缓存或快照里的参数，不能修改。'));
    else if (!canWrite) wrap.appendChild(note('当前为只读（0 级）：到「设置 → 权限」输入密码后可修改。', 'warn'));

    groups.forEach(function (g) {
      var list = h('div');
      g.defs.forEach(function (def) {
        var row = h('div', 'param-row');
        var main = h('div', 'main');
        main.appendChild(h('div', 'title', def.name));
        var desc = def.addrHex + ' · ' + def.rangeText;
        if (def.note) desc += ' · ' + def.note;
        main.appendChild(h('div', 'desc', desc));
        row.appendChild(main);

        var val = h('div', 'val' + (def.display === '--' ? ' dim' : ''));
        val.appendChild(h('span', null, def.display));
        if (def.display !== '--' && def.unit) val.appendChild(h('small', null, def.unit));
        row.appendChild(val);

        if (!def.readOnly && canWrite) {
          row.style.cursor = 'pointer';
          row.addEventListener('click', function () { editParam(def); });
        }
        list.appendChild(row);
      });
      wrap.appendChild(card(list, { title: g.name, tag: g.defs.length + ' 项', variant: 'filled' }));
    });

    if (live) {
      var row = h('div', 'btn-row');
      row.appendChild(button('重新读取', {
        variant: 'outlined',
        onClick: function () { snack('正在重新读取参数…'); M.refreshParams(); }
      }));
      row.appendChild(button(canWrite ? '保存全部参数' : '保存（需权限）', {
        variant: 'filled',
        disabled: !canWrite,
        onClick: async function () {
          snack('正在保存全部参数…');
          var r = await M.saveAllParams();
          snack(r.ok ? '参数已保存到设备' : '保存失败：' + r.text);
        }
      }));
      wrap.appendChild(card(row, { variant: 'filled' }));
    }
    return wrap;
  }

  function editParam(def) {
    var dlg = document.createElement('mdui-dialog');
    dlg.setAttribute('headline', '修改参数');

    var body = h('div');
    body.appendChild(h('div', 'muted', '写入后会回读确认；设备侧还需「保存参数」才持久化。'));

    var field = h('mdui-text-field', 'w-full mt-3');
    field.setAttribute('label', def.name + '（' + def.unit + '）');
    field.setAttribute('type', 'number');
    field.setAttribute('value', def.display === '--' ? '' : def.display);
    field.setAttribute('helper', '地址 ' + def.addrHex + ' · 范围 ' + def.rangeText);
    body.appendChild(field);

    var err = h('div', 'note bad mt-2');
    err.style.display = 'none';
    body.appendChild(err);

    dlg.appendChild(body);

    var actions = h('div');
    actions.setAttribute('slot', 'action');
    var cancel = button('取消', { variant: 'text', onClick: function () { dlg.open = false; } });
    var ok = button('写入', {
      variant: 'filled',
      onClick: async function () {
        var v = parseFloat(field.value.replace('，', '.'));
        if (!isFinite(v) || v < def.min - 1e-9 || v > def.max + 1e-9) {
          err.textContent = '请输入合法数值（' + def.rangeText + '）';
          err.style.display = '';
          return;
        }
        dlg.open = false;
        // 原始值 = 工程量 × 倍率（与 App 同一口径）
        var raw = Math.round(v * def.scale);
        var r = await M.writeParam(def.addr, raw);
        snack(def.name + (r.ok ? ' 已写入' : ' 写入失败：' + r.text));
      }
    });
    actions.appendChild(cancel);
    actions.appendChild(ok);
    dlg.appendChild(actions);

    document.body.appendChild(dlg);
    dlg.open = true;
    dlg.addEventListener('closed', function () {
      if (dlg.parentNode) dlg.parentNode.removeChild(dlg);
    });
  }

  /* ---------------- 控制 ---------------- */

  function renderControl(st) {
    var wrap = h('div', 'stack');
    if (!st.connected) {
      wrap.appendChild(card(emptyState('gamepad', '未连接', '连接保护板后可以下发控制命令。'),
        { variant: 'outlined' }));
      return wrap;
    }

    var canWrite = st.permission > 0;
    if (!canWrite) wrap.appendChild(note('当前为只读（0 级），控制命令不可用。到「设置 → 权限」输入密码。', 'warn'));

    var meta = ui.meta || {};
    var cmds = meta.commands || [];
    var toggles = cmds.filter(function (c) { return c.toggle; });
    var others = cmds.filter(function (c) { return !c.toggle && !c.dangerous; });
    var dangerous = cmds.filter(function (c) { return c.dangerous; });

    function rowOf(list) {
      var row = h('div', 'btn-row');
      list.forEach(function (c) {
        row.appendChild(button(c.name, {
          disabled: !canWrite,
          onClick: async function () {
            var r = await M.control(c.cmd);
            snack(c.name + (r.ok ? ' 已执行' : ' 失败：' + r.text));
          }
        }));
      });
      return row;
    }

    if (toggles.length) wrap.appendChild(card(rowOf(toggles), { title: '开关', variant: 'filled' }));
    if (others.length) wrap.appendChild(card(rowOf(others), { title: '维护', variant: 'filled' }));

    if (dangerous.length) {
      var list = h('div');
      dangerous.forEach(function (c) {
        var row = h('div', 'param-row');
        var main = h('div', 'main');
        main.appendChild(h('div', 'title', c.name));
        main.appendChild(h('div', 'desc', c.warning || ''));
        row.appendChild(main);
        row.appendChild(button('执行', {
          variant: 'tonal',
          disabled: !canWrite,
          onClick: function () { confirmCommand(c); }
        }));
        list.appendChild(row);
      });
      wrap.appendChild(card(list, {
        title: '危险操作', tag: '需二次确认', variant: 'filled'
      }));
    }
    return wrap;
  }

  function confirmCommand(c) {
    var dlg = document.createElement('mdui-dialog');
    dlg.setAttribute('headline', c.name);

    var body = h('div');
    body.appendChild(h('div', 'muted', '这条命令会改变设备状态，请确认对象正确。'));
    if (c.warning) body.appendChild(note(c.warning, 'bad'));

    var field = null;
    if (c.typedConfirm) {
      field = h('mdui-text-field', 'w-full mt-3');
      field.setAttribute('label', '此操作不可撤销，请输入「确认」');
      field.setAttribute('placeholder', '确认');
      body.appendChild(field);
    }
    dlg.appendChild(body);

    var actions = h('div');
    actions.setAttribute('slot', 'action');
    actions.appendChild(button('取消', { variant: 'text', onClick: function () { dlg.open = false; } }));
    actions.appendChild(button('确认执行', {
      variant: 'filled',
      onClick: async function () {
        if (c.typedConfirm && field.value.trim() !== '确认') {
          snack('请先输入「确认」');
          return;
        }
        dlg.open = false;
        var r = await M.control(c.cmd);
        snack(c.name + (r.ok ? ' 已执行' : ' 失败：' + r.text));
      }
    }));
    dlg.appendChild(actions);

    document.body.appendChild(dlg);
    dlg.open = true;
    dlg.addEventListener('closed', function () {
      if (dlg.parentNode) dlg.parentNode.removeChild(dlg);
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

  function backBar(title) {
    var wrap = h('div');
    var row = h('div', 'btn-row');
    row.appendChild(button('返回', {
      variant: 'text', icon: 'arrow_back',
      onClick: function () { ui.settingsPage = 'root'; renderAll(); }
    }));
    var t = h('div', null, title);
    t.style.cssText = 'font-size:16px;font-weight:600;margin:8px 0 0 4px';
    row.appendChild(t);
    wrap.appendChild(row);
    return wrap;
  }

  function listRow(title, value, onClick) {
    var item = h('mdui-list-item');
    item.setAttribute('headline', title);
    if (value) item.setAttribute('trailing', value);
    if (onClick) {
      item.setAttribute('clickable', '');
      item.addEventListener('click', onClick);
    }
    return item;
  }

  function renderSettingsRoot(st) {
    var wrap = h('div', 'stack');
    var meta = ui.meta || {};

    // 连接
    var connBody = h('div');
    var row = h('div', 'btn-row');
    row.appendChild(button(st.connected ? '断开连接' : '连接设备', {
      variant: st.connected ? 'tonal' : 'filled',
      icon: 'bluetooth',
      onClick: function () { if (st.connected) M.disconnect(); else M.connect(); }
    }));
    connBody.appendChild(row);
    connBody.appendChild(h('div', 'muted mt-2', st.connected
      ? st.deviceName + ' · 通道 ' + (st.channelLabel || '?') + ' · ' + st.familyLabel
      : (st.hint || '尚未连接设备')));
    wrap.appendChild(card(connBody, { title: '连接', variant: 'filled' }));

    // 外观
    var themeBody = h('div');
    var group = h('mdui-segmented-button-group');
    group.setAttribute('selects', 'single');
    group.setAttribute('value', M.themeMode());
    [['system', '跟随系统'], ['light', '浅色'], ['dark', '深色']].forEach(function (kv) {
      var b = h('mdui-segmented-button', null, kv[1]);
      b.setAttribute('value', kv[0]);
      group.appendChild(b);
    });
    group.addEventListener('change', function () {
      var v = group.value;
      if (v) { M.setThemeMode(v); applyTheme(); }
    });
    themeBody.appendChild(group);
    themeBody.appendChild(h('div', 'btn-row mt-3').appendChild
      ? (function () {
          var r = h('div', 'btn-row mt-3');
          r.appendChild(button('切换页面主题…', {
            variant: 'outlined', icon: 'dashboard',
            onClick: function () { location.href = 'switch.html?from=' + encodeURIComponent(THEME_FILE); }
          }));
          return r;
        })()
      : null);
    wrap.appendChild(card(themeBody, { title: '外观', variant: 'filled' }));

    // 轮询
    var pollBody = h('div');
    var poll = h('mdui-segmented-button-group');
    poll.setAttribute('selects', 'single');
    poll.setAttribute('value', String(M.meta().settings.pollInterval));
    [[900, '更稳'], [600, '默认'], [300, '更跟手']].forEach(function (kv) {
      var b = h('mdui-segmented-button', null, kv[1] + ' ' + kv[0] + 'ms');
      b.setAttribute('value', String(kv[0]));
      poll.appendChild(b);
    });
    poll.addEventListener('change', function () {
      var v = parseInt(poll.value, 10);
      if (v) { M.setPollInterval(v); snack('轮询频率已设为 ' + v + ' ms'); }
    });
    pollBody.appendChild(poll);
    pollBody.appendChild(h('div', 'muted mt-2',
      '页面切到后台时轮询会暂停（浏览器节流），回来立即恢复。'));
    wrap.appendChild(card(pollBody, { title: '轮询频率', variant: 'filled' }));

    // 二级页入口
    var navList = h('div');
    navList.appendChild(listRow('权限',
      st.connected ? (st.permission > 0 ? st.permission + ' 级' : '只读') : '未连接',
      function () { ui.settingsPage = 'auth'; renderAll(); }));
    if (st.channels.length) {
      navList.appendChild(listRow('通信通道', st.channelLabel || '自动',
        function () { ui.settingsPage = 'channels'; renderAll(); }));
    }
    navList.appendChild(listRow('历史设备', (meta.devices || []).length + ' 台',
      function () { ui.settingsPage = 'devices'; renderAll(); }));
    navList.appendChild(listRow('运行日志', ui.logLines.length + ' 条',
      function () { ui.settingsPage = 'log'; renderAll(); }));
    navList.appendChild(listRow('关于', 'v' + ((meta.version || {}).name || ''),
      function () { ui.settingsPage = 'about'; renderAll(); }));
    wrap.appendChild(card(navList, { variant: 'filled' }));

    // 网页版限制
    var lim = h('div');
    var ul = h('ul');
    ul.style.cssText = 'margin:0;padding-left:20px;font-size:12.5px;line-height:1.9';
    ['关掉页面即断开连接：浏览器不允许网页在后台常驻蓝牙。',
     '没有告警通知与后台监控：需要这两项请安装 Android 版。',
     '每次打开需重新选择设备：浏览器安全策略要求用户手势授权。',
     '配置与密码存在本机浏览器里（localStorage），换浏览器/清缓存会丢。'].forEach(function (t) {
      ul.appendChild(h('li', null, t));
    });
    lim.appendChild(ul);
    wrap.appendChild(card(lim, { title: '网页版限制', variant: 'outlined' }));
    return wrap;
  }

  function renderAuth(st) {
    var wrap = h('div', 'stack');
    wrap.appendChild(backBar('权限'));
    if (!st.connected) wrap.appendChild(note('未连接：可以先保存密码，连上后会自动升权。'));

    var list = h('div');
    for (var lv = 1; lv <= 5; lv++) {
      (function (level) {
        var has = st.passwords.indexOf(level) >= 0;
        var isCurrent = st.connected && st.permission === level;
        var row = h('div', 'param-row');
        var main = h('div', 'main');
        main.appendChild(h('div', 'title', level + ' 级' + (isCurrent ? ' · 当前' : '')));
        main.appendChild(h('div', 'desc', has ? '已记住密码' : '未设置'));
        row.appendChild(main);
        row.appendChild(button(has ? '更换' : '设置', {
          variant: has ? 'text' : 'filled',
          onClick: function () { askPassword(level, st); }
        }));
        if (has) {
          row.appendChild(button('清除', {
            variant: 'text',
            onClick: function () {
              M.forgetPassword(level);
              snack('已清除 ' + level + ' 级密码');
              renderAll();
            }
          }));
        }
        list.appendChild(row);
      })(lv);
    }
    wrap.appendChild(card(list, { title: '密码', tag: '按设备保存', variant: 'filled' }));

    var targetBody = h('div');
    var meta = ui.meta || {};
    var group = h('mdui-segmented-button-group');
    group.setAttribute('selects', 'single');
    group.setAttribute('value', String(meta.settings.autoUpgradeTarget));
    [0, 1, 2, 3, 4, 5].forEach(function (v) {
      var b = h('mdui-segmented-button', null, v === 0 ? '自动' : v + ' 级');
      b.setAttribute('value', String(v));
      group.appendChild(b);
    });
    group.addEventListener('change', function () {
      var v = parseInt(group.value, 10);
      if (!isNaN(v)) { M.setAutoUpgradeTarget(v); renderAll(); }
    });
    targetBody.appendChild(group);
    targetBody.appendChild(h('div', 'muted mt-2',
      '「自动」= 用已记住的最高一级密码。设备闲置约 5 分钟后权限会回落到 0 级，网页端会自动重升。'));
    wrap.appendChild(card(targetBody, { title: '连接后自动升到', variant: 'filled' }));

    wrap.appendChild(card(
      h('div', 'muted',
        '密码保存在浏览器的 localStorage（按设备分开存）。清除浏览器数据、换浏览器或换域名都会丢失。' +
        '密码不会上传到任何服务器。'),
      { title: '说明', variant: 'outlined' }));
    return wrap;
  }

  function askPassword(level, st) {
    var dlg = document.createElement('mdui-dialog');
    dlg.setAttribute('headline', level + ' 级密码');

    var body = h('div');
    body.appendChild(h('div', 'muted', '密码用于解锁参数写入与设备控制。'));
    var field = h('mdui-text-field', 'w-full mt-3');
    field.setAttribute('label', '请输入 ' + level + ' 级密码');
    field.setAttribute('type', 'password');
    field.setAttribute('toggle-password', '');
    body.appendChild(field);
    dlg.appendChild(body);

    var actions = h('div');
    actions.setAttribute('slot', 'action');
    actions.appendChild(button('取消', { variant: 'text', onClick: function () { dlg.open = false; } }));
    actions.appendChild(button(st.connected ? '校验并保存' : '仅保存', {
      variant: 'filled',
      onClick: async function () {
        var pw = (field.value || '').trim();
        if (!pw) { snack('密码不能为空'); return; }
        dlg.open = false;
        if (st.connected) {
          snack('正在校验 ' + level + ' 级…');
          var r = await M.auth(level, pw);
          snack(r.ok ? level + ' 级校验通过（当前 ' + r.level + ' 级）' : '密码不正确');
        } else {
          M.savePassword(level, pw);
          snack(level + ' 级密码已保存（连上后自动校验）');
        }
        renderAll();
      }
    }));
    dlg.appendChild(actions);

    document.body.appendChild(dlg);
    dlg.open = true;
    dlg.addEventListener('closed', function () {
      if (dlg.parentNode) dlg.parentNode.removeChild(dlg);
    });
  }

  function renderDevices(st) {
    var wrap = h('div', 'stack');
    wrap.appendChild(backBar('历史设备'));
    var meta = ui.meta || {};
    var devices = meta.devices || [];
    if (!devices.length) {
      wrap.appendChild(card(emptyState('phone_iphone', '还没有历史设备', '连接一次后会自动记在这里。'),
        { variant: 'outlined' }));
      return wrap;
    }
    var list = h('div');
    devices.forEach(function (p) {
      var row = h('div', 'param-row');
      var main = h('div', 'main');
      main.appendChild(h('div', 'title', p.name));
      main.appendChild(h('div', 'desc', p.familyLabel + ' · ' +
        (p.passwords ? p.passwords + ' 级密码' : '无密码') + (p.saved ? ' · 上次连接' : '')));
      row.appendChild(main);
      row.appendChild(button('删除', {
        variant: 'text',
        onClick: function () {
          var dlg = document.createElement('mdui-dialog');
          dlg.setAttribute('headline', '删除 ' + p.name + '？');
          dlg.appendChild(h('div', 'muted', '该设备的密码、配置缓存与全部快照会一并删除，无法恢复。'));
          var acts = h('div');
          acts.setAttribute('slot', 'action');
          acts.appendChild(button('取消', { variant: 'text', onClick: function () { dlg.open = false; } }));
          acts.appendChild(button('删除', {
            variant: 'filled',
            onClick: function () {
              dlg.open = false;
              M.deleteDevice(p.address);
              snack('已删除 ' + p.name);
              renderAll();
            }
          }));
          dlg.appendChild(acts);
          document.body.appendChild(dlg);
          dlg.open = true;
          dlg.addEventListener('closed', function () {
            if (dlg.parentNode) dlg.parentNode.removeChild(dlg);
          });
        }
      }));
      list.appendChild(row);
    });
    wrap.appendChild(card(list, { title: '共 ' + devices.length + ' 台', variant: 'filled' }));
    return wrap;
  }

  function renderChannels(st) {
    var wrap = h('div', 'stack');
    wrap.appendChild(backBar('通信通道'));
    var list = h('div');
    st.channels.forEach(function (ch) {
      var isCurrent = st.channelLabel === ch.label;
      var row = h('div', 'param-row');
      var main = h('div', 'main');
      main.appendChild(h('div', 'title', ch.label + (isCurrent ? ' · 当前' : '')));
      main.appendChild(h('div', 'desc', ch.warn || (ch.tag + ' · 可用于固件升级')));
      row.appendChild(main);
      if (!isCurrent) {
        row.appendChild(button('切换', {
          variant: 'text',
          onClick: function () {
            snack('正在切换到' + ch.label + '…');
            M.switchChannel(ch.id);
          }
        }));
      }
      list.appendChild(row);
    });
    wrap.appendChild(card(list, { title: '可用通道', variant: 'filled' }));
    wrap.appendChild(card(
      h('div', 'muted',
        '只有暴露可写 FFF5 特征的硬件才有备用通道。备用通道会占用蓝牙屏/充电器那一路，' +
        '使用期间对应外设无法与保护板通信，且不支持固件升级。'),
      { title: '说明', variant: 'outlined' }));
    return wrap;
  }

  function renderLog(st) {
    var wrap = h('div', 'stack');
    wrap.appendChild(backBar('运行日志'));

    var box = h('div', 'log-box');
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
    var row = h('div', 'btn-row mt-3');
    row.appendChild(button('复制全部', {
      variant: 'outlined',
      onClick: async function () {
        try {
          await navigator.clipboard.writeText(M.logText());
          snack('已复制到剪贴板');
        } catch (e) {
          snack('复制失败（浏览器拒绝访问剪贴板）');
        }
      }
    }));
    row.appendChild(button('清空', {
      variant: 'text',
      onClick: function () { M.clearLog(); ui.logLines = []; snack('日志已清空'); renderAll(); }
    }));
    body.appendChild(row);
    wrap.appendChild(card(body, { title: '日志', tag: ui.logLines.length + ' 条', variant: 'filled' }));

    setTimeout(function () { box.scrollTop = box.scrollHeight; }, 0);
    return wrap;
  }

  function renderAbout() {
    var wrap = h('div', 'stack');
    wrap.appendChild(backBar('关于'));
    var meta = ui.meta || {};
    var v = meta.version || {};

    var head = h('div');
    head.style.cssText = 'text-align:center;padding:6px 0 2px';
    var name = h('div', null, '麻衣 BMS · Material 主题');
    name.style.cssText = 'font-size:18px;font-weight:700';
    head.appendChild(name);
    head.appendChild(h('div', 'muted', 'v' + (v.name || '')));
    wrap.appendChild(card(head, { variant: 'filled' }));

    var list = h('div');
    [['版本', String(v.name || '--')],
     ['构建时间', meta.builtAt ? new Date(meta.builtAt * 1000).toLocaleString() : '--'],
     ['运行平台', '网页（' + browserName() + '）'],
     ['界面框架', 'mdui 2 · Material You'],
     ['设备连接', 'Web Bluetooth']].forEach(function (kv) {
      list.appendChild(listRow(kv[0], kv[1], null));
    });
    wrap.appendChild(card(list, { title: '版本信息', variant: 'filled' }));

    var links = h('div', 'btn-row');
    links.appendChild(button('项目主页', {
      variant: 'outlined',
      onClick: function () { window.open('https://github.com/lswlc33/maibms', '_blank'); }
    }));
    links.appendChild(button('下载 Android 版', {
      variant: 'filled',
      onClick: function () { window.open('https://github.com/lswlc33/maibms/releases/latest', '_blank'); }
    }));
    wrap.appendChild(card(links, { title: '链接', variant: 'filled' }));

    wrap.appendChild(card(
      h('div', 'muted',
        '网页版没有安装过程，每次打开加载的都是最新构建，所以不存在「需要更新」的状态。' +
        '想要推送通知与后台监控，请使用 Android 版。'),
      { title: '为什么没有「检查更新」', variant: 'outlined' }));
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

  function renderAll() {
    var st = ui.state || {};
    ensureShell();
    renderTopbar(st);

    var content = $('#app .content');
    if (!content) return;
    clear(content);

    switch (ui.tab) {
      case 'params': content.appendChild(renderParams(st)); break;
      case 'control': content.appendChild(renderControl(st)); break;
      case 'settings': content.appendChild(renderSettings(st)); break;
      default: content.appendChild(renderDashboard(st));
    }

    // 底部导航选中态
    var nav = $('mdui-navigation-bar');
    if (nav) nav.setAttribute('value', ui.tab);
  }

  /* ---------------- 启动 ---------------- */

  if (!M) {
    document.body.innerHTML = '<div style="padding:24px;font-family:sans-serif">' +
      '<h2>数据层加载失败</h2><p>请确认 ../maibms-data.js 可访问（不要直接双击打开，需用 http(s) 打开）。</p></div>';
    return;
  }

  applyTheme();

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

  // 等 mdui 组件升级后再画第一屏（否则 <mdui-dialog> 等方法调不到）
  whenMduiReady(function (ok) {
    if (!ok) {
      var app = document.getElementById('app');
      app.innerHTML = '<div style="padding:24px">' +
        '<h3>mdui 组件库加载失败</h3>' +
        '<p style="font-size:13px;line-height:1.7">这个主题从 unpkg CDN 加载 mdui；' +
        '当前网络无法访问它。<br>可以改用 <a href="index.html">默认主题</a>，或连上网后刷新。</p></div>';
      return;
    }
    renderAll();
  });

  window.addEventListener('maibms-ready', function () {
    ui.meta = M.meta();
    renderAll();
  });
})();
