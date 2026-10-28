/*
 * 控制台主题（Bootstrap 5 · 侧栏仪表盘布局）
 *
 * 布局是这套主题的卖点：宽屏（≥992px）左侧固定侧栏 + 12 列仪表盘网格，
 * 单体/温度/趋势并排放（bento 式），窄屏侧栏退化成顶部横向标签。
 * 与其余主题的"手机竖屏 + 底部标签 + 单列卡片"形成对照。
 *
 * 数据仍来自 window.Maibms，与前几套主题吃同一份 state。
 */
(function () {
  'use strict';
  // 设备被宿主拦截（iOS 无 Web Bluetooth）：宿主已铺全页提示，这里不再渲染任何界面
  if (window.__MAIBMS_BLOCKED) return;


  var M = window.Maibms;
  var THEME_FILE = 'admin.html';

  var ui = {
    tab: 'dash', settingsPage: 'root',
    meta: null, state: {}, logLines: [], lastRenderKey: null
  };

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

  /** 带数据门槛的数值格式化：无数据一律 "--"，不拿 0 冒充读数 */
  function reading(st, v, digits) { return st.hasData ? num(v, digits) : '--'; }

  function toast(text) {
    var holder = document.getElementById('toastHolder');
    if (!holder) {
      holder = h('div', 'toast-container position-fixed bottom-0 end-0 p-3');
      holder.id = 'toastHolder';
      document.body.appendChild(holder);
    }
    var t = h('div', 'toast align-items-center border-0 show mb-2',
      h('div', 'd-flex',
        h('div', 'toast-body', text),
        (function () {
          var b = h('button', 'btn-close me-2 m-auto');
          b.addEventListener('click', function () { t.remove(); });
          return b;
        })()));
    t.setAttribute('role', 'status');
    holder.appendChild(t);
    setTimeout(function () { if (t.parentNode) t.parentNode.removeChild(t); }, 2600);
  }

  /** Bootstrap modal；返回 close */
  function modal(title, build) {
    var root = document.getElementById('modal');
    var body = document.getElementById('modalBody');
    clear(body);
    body.appendChild(h('h5', 'modal-title mb-1', title));
    build(body, function () { bootstrap.Modal.getInstance(root).hide(); });
    bootstrap.Modal.getOrCreateInstance(root).show();
    root.addEventListener('hidden.bs.modal', function () { clear(body); }, { once: true });
  }

  /* ---------------- 主题（深/浅） ---------------- */

  function applyTheme() {
    var mode = M.themeMode();
    document.documentElement.setAttribute('data-bs-theme',
      mode === 'dark' || (mode !== 'light' && window.matchMedia('(prefers-color-scheme: dark)').matches)
        ? 'dark' : 'light');
  }

  /* ---------------- 骨架 ---------------- */

  var TABS = [
    { id: 'dash', label: '总览', icon: '⚡' },
    { id: 'params', label: '参数', icon: '⚙' },
    { id: 'control', label: '控制', icon: '🎛' },
    { id: 'settings', label: '设置', icon: '☰' }
  ];

  function ensureShell() {
    if ($('#sideNav').children.length) return;

    var nav = $('#sideNav');
    TABS.forEach(function (t) {
      var a = h('a', 'nav-link' + (t.id === ui.tab ? ' active' : ''));
      a.dataset.tab = t.id;
      a.appendChild(h('span', null, t.icon + ' ' + t.label));
      a.addEventListener('click', function () { ui.tab = t.id; renderAll(); });
      nav.appendChild(a);
    });

    // 顶栏外观按钮
    var head = h('div', 'pagehead');
    head.id = 'pagehead';
    $('#content').appendChild(head);
  }

  function renderPagehead(st) {
    var head = $('#pagehead');
    if (!head) return;
    clear(head);

    var t = h('h1', null, ({
      dash: '总览', params: '参数', control: '控制', settings: '设置'
    })[ui.tab]);
    head.appendChild(t);

    var status = h('span', 'badge-soft' + (st.connected && st.stalled ? ' bad'
      : st.connected ? ' on' : st.link === 'Connecting' ? ' warn' : ''));
    status.textContent = st.connected && st.stalled ? '设备失联' : st.linkText;
    head.appendChild(status);

    if (st.channelLabel) head.appendChild(h('span', 'badge-soft', '通道 ' + st.channelLabel));
    if (st.familyLabel !== '未知设备') head.appendChild(h('span', 'badge-soft', st.familyLabel));

    // 外观与主题按钮
    var btn = h('button', 'btn btn-sm btn-outline-secondary', '◐ 外观 / 主题');
    btn.addEventListener('click', openAppearance);
    head.appendChild(btn);
  }

  function openAppearance() {
    modal('外观与主题', function (body, close) {
      body.appendChild(h('p', 'hint', '深色 / 浅色 / 跟随系统。选择会被记住，所有主题共用。'));

      var seg = h('div', 'btn-group w-100 mb-3');
      [['system', '跟随系统'], ['light', '浅色'], ['dark', '深色']].forEach(function (kv) {
        var b = h('button', 'btn btn-outline-secondary' + (M.themeMode() === kv[0] ? ' active' : ''), kv[1]);
        b.addEventListener('click', function () {
          M.setThemeMode(kv[0]); applyTheme();
          seg.querySelectorAll('.btn').forEach(function (x) { x.classList.remove('active'); });
          b.classList.add('active');
        });
        seg.appendChild(b);
      });
      body.appendChild(seg);

      var sw = h('button', 'btn btn-primary w-full w-100', '切换页面主题…');
      sw.addEventListener('click', function () {
        location.href = 'switch.html?from=' + encodeURIComponent(THEME_FILE);
      });
      body.appendChild(sw);

      body.appendChild(h('p', 'hint', '需要更大的字？用浏览器的页面缩放即可。'));
    });
  }

  /* ---------------- 通用块 ---------------- */

  /* 健康摘要行：一眼看出"现在要不要管它" */
  function healthSummary(st) {
    if (!st.hasData) return null;
    var d = st.device, flags = st.flags || {};
    var notes = [];
    (flags.alarm || []).forEach(function (t) { notes.push(t); });
    (flags.protect || []).forEach(function (t) { notes.push('保护：' + t); });
    if (d.chMosReason) notes.push('充电 MOS 关（' + d.chMosReason + '）');
    if (d.disMosReason) notes.push('放电 MOS 关（' + d.disMosReason + '）');
    var level = (flags.protect || []).length ? 'bad' : notes.length ? 'warn' : 'ok';
    // mw-12：admin 的 12 列网格里，非 panel 子元素默认只占 1 列，摘要行必须通栏
    var cls = 'alert mw-12 ' + (level === 'bad' ? 'alert-danger' : level === 'warn' ? 'alert-warning' : 'alert-success');
    var line = h('div', cls + ' py-2 d-flex align-items-center gap-2 summary');
    line.appendChild(h('i', 'dot'));
    line.appendChild(h('span', null, level === 'ok'
      ? '运行正常 · ' + d.battState
      : (level === 'bad' ? '有保护动作 · ' : '注意 · ') + notes.slice(0, 2).join('，') + (notes.length > 2 ? ' 等 ' + notes.length + ' 项' : '')));
    line.title = notes.join('；') || '无告警、无保护动作';
    return line;
  }

  /* 能量流向条：方向 + 功率 + 预计时长 */
  function flowStrip(st) {
    if (!st.hasData) return null;
    var d = st.device;
    var charging = d.current < 0 || (!d.power && d.battState === '充电');
    var idle = !d.power;
    var strip = h('div', 'flow' + (idle ? ' idle' : charging ? ' rev' : ' fwd'));
    strip.appendChild(h('span', 'lbl', '电池组'));
    var track = h('div', 'track');
    track.appendChild(h('span', 'dash'));
    track.appendChild(h('span', 'bubble', num(d.power, 0) + ' W'));
    strip.appendChild(track);
    strip.appendChild(h('span', 'lbl', idle ? '待机' : charging ? '充电器' : '负载'));
    var eta = charging ? d.remainChargeText : d.remainDischargeText;
    var wrap = h('div');
    wrap.appendChild(strip);
    wrap.appendChild(h('div', 'flow-sub', (idle ? '静置中' : charging ? '充电中' : '放电中') +
      (eta && eta !== '--' ? ' · ' + (charging ? '充满' : '可用') + '约 ' + eta : '')));
    return wrap;
  }

  /* 单体分布带：电压在 min~max 上的排布，颜色=高低，圈=均衡 */
  function lerpColor(a, b, t) {
    return 'rgb(' + Math.round(a[0] + (b[0] - a[0]) * t) + ',' +
      Math.round(a[1] + (b[1] - a[1]) * t) + ',' + Math.round(a[2] + (b[2] - a[2]) * t) + ')';
  }

  function bandChart(cells) {
    var ok = cells.filter(function (c) { return !c.missing; });
    if (ok.length < 3) return null;
    var vmin = Infinity, vmax = -Infinity;
    ok.forEach(function (c) {
      if (c.volt < vmin) vmin = c.volt;
      if (c.volt > vmax) vmax = c.volt;
    });
    var span = Math.max(vmax - vmin, 0.0005);
    var strip = h('div', 'band');
    ok.forEach(function (c) {
      var t = (c.volt - vmin) / span;
      var dot = h('i', 'bdot' + (c.balancing ? ' bal' : ''));
      dot.style.left = (4 + t * 92) + '%';
      dot.style.background = lerpColor([47, 111, 224], [184, 118, 26], t);
      dot.title = '#' + c.index + '  ' + num(c.volt, 3) + 'V' + (c.balancing ? ' · 均衡中' : '');
      strip.appendChild(dot);
    });
    var lab = h('div', 'band-lab');
    lab.appendChild(h('span', null, '低 ' + num(vmin, 3)));
    lab.appendChild(h('span', null, '高 ' + num(vmax, 3)));
    var wrap = h('div');
    wrap.appendChild(strip);
    wrap.appendChild(lab);
    return wrap;
  }

  function panel(title, body, colClass) {
    var c = h('div', 'card panel ' + (colClass || 'mw-12'));
    var b = h('div', 'card-body');
    if (title) b.appendChild(h('div', 'panel-title', title));
    b.appendChild(typeof body === 'string' ? h('div', null, body) : body);
    c.appendChild(b);
    return c;
  }

  function metric(label, value, unit) {
    var m = h('div');
    m.appendChild(h('div', 'text-[11px]', label));
    var v = h('div', 'fs-5 fw-semibold tabular-nums');
    v.appendChild(document.createTextNode(value));
    if (unit) v.appendChild(h('small', 'text-muted ms-1', unit));
    m.appendChild(v);
    return m;
  }

  function pill(text, kind) {
    return h('span', 'badge-soft ' + (kind || ''), text);
  }

  /* ---------------- 总览 ---------------- */

  function renderDashboard(st) {
    var wrap = h('div', 'mw-grid');

    if (!M.supportsWebBluetooth()) {
      wrap.appendChild(panel('此浏览器无法连接保护板', (function () {
        var d = h('div');
        d.appendChild(h('p', 'mb-2', '网页版通过 Web Bluetooth 直连保护板，需要：'));
        var ul = h('ul', 'mb-2 ps-4');
        ['安卓手机上的 Chrome（桌面 Chrome / Edge 也支持）',
         '通过 https 或 localhost 打开（浏览器的安全要求）'].forEach(function (x) {
          ul.appendChild(h('li', 'mb-1', x));
        });
        d.appendChild(ul);
        d.appendChild(h('p', 'hint m-0', '微信/QQ 内置浏览器、Safari、Firefox 都不支持 Web Bluetooth。'));
        return d;
      })()));
      return wrap;
    }

    if (!st.connected && !st.hasData) {
      var connectBody = h('div');
      connectBody.appendChild(h('p', 'mb-3', st.scanning
        ? '正在等待浏览器设备选择器…'
        : '点下面的按钮，浏览器会弹出附近的蓝牙设备列表。'));
      var b = h('button', 'btn btn-primary', '选择设备并连接');
      b.addEventListener('click', function () { M.connect(); });
      connectBody.appendChild(b);

      var saved = (st.devices || []).filter(function (d) { return d.saved; })[0];
      if (saved) {
        connectBody.appendChild(h('p', 'hint mt-3',
          '上次连接：' + saved.name + '（' + saved.familyLabel + '）。浏览器要求每次打开都重新选择一次设备。'));
      }
      connectBody.appendChild(h('p', 'hint mt-2',
        '关掉页面就断开，无法后台监控，也不发通知。'));
      wrap.appendChild(panel('连接保护板', connectBody));
      if (st.scanError) wrap.appendChild(panel('', (function () {
        var a = h('div', 'alert alert-danger py-2 mb-0', st.scanError);
        return a;
      })(), 'mw-12'));
      return wrap;
    }

    if (!st.connected && st.hasData) {
      wrap.appendChild(panel('', h('div', 'alert alert-warning py-2 mb-0',
        '界面预览：以下是示例数据，不是真机读数。'), 'mw-12'));
    }
    if (st.hint && !st.connected) {
      wrap.appendChild(panel('', h('div', 'alert alert-info py-2 mb-0', st.hint), 'mw-12'));
    }
    if (st.stalled) {
      wrap.appendChild(panel('', h('div', 'alert alert-danger py-2 mb-0',
        '设备失联：连续多拍收不到实时数据，正在尝试恢复。'), 'mw-12'));
    }

    var sum = healthSummary(st);
    if (sum) wrap.appendChild(sum);
    wrap.appendChild(overviewPanel(st, 'mw-12'));
    if (st.caps.perCellVoltage && st.cells.length) wrap.appendChild(cellsPanel(st, 'mw-6'));
    if (st.caps.multiTemp || st.temps.length) wrap.appendChild(tempsPanel(st, 'mw-6'));
    wrap.appendChild(trendPanel(st, 'mw-12'));
    if (st.caps.protectAlarm) wrap.appendChild(flagsPanel(st, 'mw-6'));
    var sc = controlShortcuts(st);
    if (sc) wrap.appendChild(panel('快捷控制', sc, 'mw-6'));
    return wrap;
  }

  function overviewPanel(st, col) {
    var d = st.device;
    var body = h('div');

    var hero = h('div', 'hero');
    var ring = h('div', 'ring');
    var r = 42, circ = 2 * Math.PI * r;
    var pct = Math.max(0, Math.min(100, d.soc)) / 100;
    ring.innerHTML =
      '<svg width="104" height="104" viewBox="0 0 104 104">' +
      '<circle cx="52" cy="52" r="' + r + '" fill="none" stroke="var(--bs-tertiary-bg)" stroke-width="10"/>' +
      '<circle cx="52" cy="52" r="' + r + '" fill="none" stroke="var(--bs-success)" stroke-width="10" ' +
      'stroke-linecap="round" stroke-dasharray="' + circ.toFixed(1) + '" ' +
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
    if (st.relay.active) pills.appendChild(pill('中继 ' + st.relay.brand, 'on'));
    body.appendChild(pills);

    var flow = flowStrip(st);
    if (flow) body.appendChild(flow);

    var row = h('div', 'row g-2 mt-1');
    [['总电压', reading(d, d.totalVoltage, d.voltageDecimals), 'V'],
     ['电流', reading(d, d.current, 2), 'A'],
     ['功率', d.hasData ? String(d.power) : '--', 'W'],
     ['压差', d.hasData ? d.deltaCell : '--', 'V']].forEach(function (kv) {
      var c = h('div', 'col-6 col-md-3');
      c.appendChild(metric(kv[0], kv[1], kv[2]));
      row.appendChild(c);
    });
    body.appendChild(row);

    return panel(null, body, col);
  }

  function mosPill(label, text, reason) {
    if (text === '开启') return pill(label + ' 开', 'on');
    if (reason) return pill(label + ' 关 · ' + reason, 'warn');
    if (text === '关闭') return pill(label + ' 关');
    return pill(label + ' --');
  }

  function cellsPanel(st, col) {
    var d = st.device;
    var body = h('div');

    var row = h('div', 'row g-2 mb-2');
    [['最高', d.maxCell], ['最低', d.minCell], ['平均', d.avgCell], ['压差', d.deltaCell]].forEach(function (kv) {
      var c = h('div', 'col-3');
      c.appendChild(metric(kv[0], kv[1], 'V'));
      row.appendChild(c);
    });
    body.appendChild(row);

    var band = bandChart(st.cells);
    if (band) body.appendChild(band);

    var grid = h('div', 'cells');
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
    legend.innerHTML =
      '<span><i style="background:var(--bs-warning-text-emphasis)"></i>最高</span>' +
      '<span><i style="background:var(--bs-primary-text-emphasis)"></i>最低</span>' +
      '<span><i style="background:var(--bs-success);border-radius:50%"></i>均衡中</span>' +
      '<span>共 ' + st.cells.filter(function (c) { return !c.missing; }).length + ' 串</span>';
    body.appendChild(legend);

    var ranked = st.cells.filter(function (c) { return !c.missing; })
      .slice().sort(function (a, b) { return a.volt - b.volt; }).slice(0, 3);
    if (ranked.length === 3 && ranked[2].volt < ranked[0].volt + 0.001) {
      // 电压几乎相同，没有区分度就不显示
    } else if (ranked.length) {
      var weak = h('div', 'weak');
      weak.appendChild(h('span', 'k', '最弱 ' + ranked.length + ' 串'));
      ranked.forEach(function (c, i) {
        if (i) weak.appendChild(h('span', 'sep', '·'));
        weak.appendChild(h('span', 'w', '#' + c.index + ' ' + num(c.volt, 3) + (c.balancing ? ' ◈' : '')));
      });
      body.appendChild(weak);
    }
    return panel('单体电压', body, col);
  }

  function tempsPanel(st, col) {
    var body = h('div', 'temps');
    st.temps.forEach(function (t) {
      var cls = 'temp' + (t.value >= 55 ? ' hot'
        : t.value >= 45 ? ' warm'
        : t.value <= 0 ? ' cold' : '');
      var box = h('div', cls);
      box.appendChild(h('div', 'k', t.name));
      box.appendChild(h('div', 'v', num(t.value, 1)));
      body.appendChild(box);
    });
    return panel('温度', body, col);
  }

  function trendPanel(st, col) {
    var body = h('div');
    var cur = st.trend.current || [], volt = st.trend.volt || [];
    var n = Math.max(cur.length, volt.length);
    if (!n) {
      body.appendChild(h('p', 'hint m-0', '有数据后显示最近 40 拍的趋势'));
    } else {
      var w = 400, hh = 76;
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
      if (cp) svg += '<path d="' + cp + '" fill="none" stroke="var(--bs-info)" stroke-width="2" stroke-linejoin="round"/>';
      if (vp) svg += '<path d="' + vp + '" fill="none" stroke="var(--bs-success)" stroke-width="2" stroke-linejoin="round"/>';
      svg += '</svg>';
      var holder = h('div');
      holder.innerHTML = svg;
      body.appendChild(holder);

      var legend = h('div', 'legend');
      legend.innerHTML =
        '<span><i style="background:var(--bs-info)"></i>电流</span>' +
        '<span><i style="background:var(--bs-success)"></i>电压</span>';
      body.appendChild(legend);
    }
    return panel('趋势', body, col);
  }

  function flagsPanel(st, col) {
    function block(title, list, emptyHint) {
      var box = h('div', 'bg-body-tertiary rounded-3 p-2 px-3');
      var head = h('div', 'd-flex justify-content-between text-xs fw-semibold opacity-75 mb-1');
      head.appendChild(h('span', null, title));
      head.appendChild(h('span', null, String(list.length)));
      box.appendChild(head);
      if (!list.length) {
        box.appendChild(h('div', 'small opacity-50', emptyHint));
      } else {
        var ul = h('ul', 'small text-danger ps-4 m-0');
        list.forEach(function (t) { ul.appendChild(h('li', null, t)); });
        box.appendChild(ul);
      }
      return box;
    }

    var body = h('div');
    var grid = h('div', 'row g-2');
    var c1 = h('div', 'col-6'); c1.appendChild(block('保护', st.flags.protect, '无保护动作'));
    var c2 = h('div', 'col-6'); c2.appendChild(block('告警', st.flags.alarm, '无告警'));
    grid.appendChild(c1); grid.appendChild(c2);
    body.appendChild(grid);

    if (st.flags.states.length) {
      body.appendChild(h('p', 'hint mt-2 mb-0', '运行状态：' + st.flags.states.join('、')));
    }
    return panel('保护与告警', body, col);
  }

  function controlShortcuts(st) {
    var caps = st.caps;
    if (!caps.controlCharge && !caps.controlDischarge && !caps.controlBalance) return null;
    var canWrite = st.permission > 0;
    var row = h('div', 'btn-row');

    function mk(label, cmd) {
      var b = h('button', 'btn btn-sm btn-outline-secondary', label);
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
      body.appendChild(h('p', 'hint m-0', '当前为只读（0 级）。到「设置 → 权限」输入密码后可控制。'));
    }
    return body;
  }

  /* ---------------- 参数 ---------------- */

  function renderParams(st) {
    var wrap = h('div', 'mw-grid');
    if (st.params.reading) {
      wrap.appendChild(panel('', h('div', 'alert alert-info py-2 mb-0', '正在读取参数区…（200+ 项）'), 'mw-12'));
    }
    if (st.params.fromCache) {
      wrap.appendChild(panel('', h('div', 'alert alert-warning py-2 mb-0',
        '显示的是上次成功连接时缓存的配置（非实时）。'), 'mw-12'));
    }

    var groups = (ui.meta || {}).paramGroups || [];
    var live = st.connected;

    if (!groups.length || !Object.keys(st.params.values || {}).length) {
      wrap.appendChild(panel('', h('div', 'empty',
        h('div', null, live ? '等待设备回读参数区…' : '连接保护板后可以查看与修改参数。')), 'mw-12'));
      return wrap;
    }

    var canWrite = live && st.permission > 0;
    if (!live) wrap.appendChild(panel('', h('div', 'alert alert-info py-2 mb-0',
      '离线查看：显示缓存或快照里的参数，不能修改。'), 'mw-12'));
    else if (!canWrite) wrap.appendChild(panel('', h('div', 'alert alert-warning py-2 mb-0',
      '当前为只读（0 级）：到「设置 → 权限」输入密码后可修改。'), 'mw-12'));

    // 参数用 **表格** 展示（控制台主题的取向：信息密度优先），与前几套的列表行不同
    groups.forEach(function (g) {
      var table = h('table', 'table table-sm table-borderless align-middle mb-0');
      var tbody = h('tbody');
      g.defs.forEach(function (def) {
        var tr = h('tr' + (!def.readOnly && canWrite ? ' user-select-none' : ''),
          null);
        tr.style.cursor = (!def.readOnly && canWrite) ? 'pointer' : '';
        var nameTd = h('td');
        nameTd.appendChild(h('div', null, def.name));
        nameTd.appendChild(h('div', 'text-xs opacity-50', def.addrHex + ' · ' + def.rangeText));
        tr.appendChild(nameTd);
        var valTd = h('td', 'text-end tabular-nums text-nowrap' + (def.display === '--' ? ' opacity-50' : ''));
        valTd.appendChild(document.createTextNode(def.display));
        if (def.display !== '--' && def.unit) valTd.appendChild(h('small', 'text-muted ms-1', def.unit));
        tr.appendChild(valTd);
        if (!def.readOnly && canWrite) {
          tr.addEventListener('click', function () { editParam(def); });
        }
        tbody.appendChild(tr);
      });
      table.appendChild(tbody);
      wrap.appendChild(panel(g.name + '（' + g.defs.length + ' 项）', table, 'mw-12'));
    });

    if (live) {
      var row = h('div', 'btn-row');
      var rb = h('button', 'btn btn-sm btn-outline-secondary', '重新读取');
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
      wrap.appendChild(panel('操作', row, 'mw-12'));
    }
    return wrap;
  }

  function editParam(def) {
    modal('修改参数', function (body, close) {
      body.appendChild(h('p', 'text-muted small mb-3',
        '写入后会回读确认；设备侧还需「保存参数」才持久化。'));

      body.appendChild(h('label', 'form-label small mb-1', def.name + '（' + def.unit + '）'));
      var input = h('input', 'form-control');
      input.type = 'number';
      input.step = 'any';
      input.value = def.display === '--' ? '' : def.display;
      body.appendChild(input);
      body.appendChild(h('div', 'form-text', '地址 ' + def.addrHex + ' · 范围 ' + def.rangeText));

      var err = h('div', 'text-danger small mt-2');
      err.style.display = 'none';
      body.appendChild(err);

      var row = h('div', 'd-flex gap-2 mt-3');
      var cancel = h('button', 'btn btn-sm btn-secondary', '取消');
      cancel.addEventListener('click', close);
      row.appendChild(cancel);
      var ok = h('button', 'btn btn-sm btn-primary flex-grow-1', '写入');
      ok.addEventListener('click', async function () {
        var v = parseFloat(input.value.replace('，', '.'));
        if (!isFinite(v) || v < def.min - 1e-9 || v > def.max + 1e-9) {
          err.textContent = '请输入合法数值（' + def.rangeText + '）';
          err.style.display = '';
          return;
        }
        close();
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
    var wrap = h('div', 'mw-grid');
    if (!st.connected) {
      wrap.appendChild(panel('', h('div', 'empty', '未连接：连接保护板后可以下发控制命令。'), 'mw-12'));
      return wrap;
    }

    var canWrite = st.permission > 0;
    if (!canWrite) {
      wrap.appendChild(panel('', h('div', 'alert alert-warning py-2 mb-0',
        '当前为只读（0 级），控制命令不可用。到「设置 → 权限」输入密码。'), 'mw-12'));
    }

    var meta = ui.meta || {};
    var cmds = meta.commands || [];
    var toggles = cmds.filter(function (c) { return c.toggle; });
    var others = cmds.filter(function (c) { return !c.toggle && !c.dangerous; });
    var dangerous = cmds.filter(function (c) { return c.dangerous; });

    function rowOf(list) {
      var row = h('div', 'btn-row');
      list.forEach(function (c) {
        var b = h('button', 'btn btn-sm btn-outline-secondary', c.name);
        if (!canWrite) b.disabled = true;
        b.addEventListener('click', async function () {
          var r = await M.control(c.cmd);
          toast(c.name + (r.ok ? ' 已执行' : ' 失败：' + r.text));
        });
        row.appendChild(b);
      });
      return row;
    }

    if (toggles.length) wrap.appendChild(panel('开关', rowOf(toggles), 'mw-12'));
    if (others.length) wrap.appendChild(panel('维护', rowOf(others), 'mw-12'));

    if (dangerous.length) {
      var list = h('div', 'list-group list-group-flush');
      dangerous.forEach(function (c) {
        var item = h('div', 'list-group-item bg-transparent px-0 d-flex align-items-center gap-3');
        var main = h('div', 'flex-grow-1');
        main.appendChild(h('div', null, c.name));
        main.appendChild(h('div', 'text-xs opacity-60', c.warning || ''));
        item.appendChild(main);
        var b = h('button', 'btn btn-sm btn-outline-danger', '执行');
        if (!canWrite) b.disabled = true;
        b.addEventListener('click', function () { confirmCommand(c); });
        item.appendChild(b);
        list.appendChild(item);
      });
      wrap.appendChild(panel('危险操作（需二次确认）', list, 'mw-12'));
    }
    return wrap;
  }

  function confirmCommand(c) {
    modal(c.name, function (body, close) {
      body.appendChild(h('p', 'text-muted small mb-3', '这条命令会改变设备状态，请确认对象正确。'));
      if (c.warning) body.appendChild(h('div', 'alert alert-danger py-2', c.warning));

      var input = null;
      if (c.typedConfirm) {
        body.appendChild(h('label', 'form-label small', '此操作不可撤销，请输入「确认」继续'));
        input = h('input', 'form-control');
        input.placeholder = '确认';
        body.appendChild(input);
      }

      var row = h('div', 'd-flex gap-2 mt-3');
      var cancel = h('button', 'btn btn-sm btn-secondary', '取消');
      cancel.addEventListener('click', close);
      row.appendChild(cancel);
      var ok = h('button', 'btn btn-sm btn-danger flex-grow-1', '确认执行');
      ok.addEventListener('click', async function () {
        if (c.typedConfirm && input.value.trim() !== '确认') { toast('请先输入「确认」'); return; }
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

  function listRow(title, value, onClick) {
    var row = h('div', 'param-row' + (onClick ? ' user-select-none' : ''));
    row.style.cursor = onClick ? 'pointer' : '';
    var main = h('div', 'main flex-grow-1');
    main.appendChild(h('div', 'title', title));
    row.appendChild(main);
    if (value) row.appendChild(h('span', 'val dim', value));
    if (onClick) row.appendChild(h('span', 'opacity-50', '›'));
    if (onClick) row.addEventListener('click', onClick);
    return row;
  }

  function renderSettingsRoot(st) {
    var wrap = h('div', 'mw-grid');
    var meta = ui.meta || {};

    var connBody = h('div');
    var row = h('div', 'btn-row mb-2');
    var cb = h('button', 'btn btn-sm ' + (st.connected ? 'btn-outline-secondary' : 'btn-primary'),
      st.connected ? '断开连接' : '连接设备');
    cb.addEventListener('click', function () { if (st.connected) M.disconnect(); else M.connect(); });
    row.appendChild(cb);
    connBody.appendChild(row);
    connBody.appendChild(h('p', 'hint m-0', st.connected
      ? st.deviceName + ' · 通道 ' + (st.channelLabel || '?') + ' · ' + st.familyLabel
      : (st.hint || '尚未连接设备')));
    wrap.appendChild(panel('连接', connBody, 'mw-12'));

    // 外观 + 轮询 + 自动升权 并排（仪表盘布局的优势：设置也能并排）
    var themeBody = h('div');
    var seg = h('div', 'btn-group w-100 mb-2');
    [['system', '跟随系统'], ['light', '浅色'], ['dark', '深色']].forEach(function (kv) {
      var b = h('button', 'btn btn-outline-secondary btn-sm' + (M.themeMode() === kv[0] ? ' active' : ''), kv[1]);
      b.addEventListener('click', function () { M.setThemeMode(kv[0]); applyTheme(); });
      seg.appendChild(b);
    });
    themeBody.appendChild(seg);
    var sw = h('button', 'btn btn-sm btn-primary w-100 mt-2', '切换页面主题…');
    sw.addEventListener('click', function () {
      location.href = 'switch.html?from=' + encodeURIComponent(THEME_FILE);
    });
    themeBody.appendChild(sw);
    wrap.appendChild(panel('外观', themeBody, 'col-4'));

    var pollBody = h('div');
    var poll = h('div', 'btn-group w-100');
    [[900, '更稳'], [600, '默认'], [300, '跟手']].forEach(function (kv) {
      var b = h('button', 'btn btn-outline-secondary btn-sm' + (st.settings.pollInterval === kv[0] ? ' active' : ''),
        kv[1] + ' ' + kv[0] + 'ms');
      b.addEventListener('click', function () {
        M.setPollInterval(kv[0]); toast('轮询频率已设为 ' + kv[0] + ' ms'); renderAll();
      });
      poll.appendChild(b);
    });
    pollBody.appendChild(poll);
    wrap.appendChild(panel('轮询频率', pollBody, 'col-4'));

    var upBody = h('div');
    var up = h('div', 'btn-group w-100');
    [0, 1, 2, 3, 4, 5].forEach(function (v) {
      var b = h('button', 'btn btn-outline-secondary btn-sm' + (st.settings.autoUpgradeTarget === v ? ' active' : ''),
        v === 0 ? '自动' : v + ' 级');
      b.addEventListener('click', function () { M.setAutoUpgradeTarget(v); renderAll(); });
      up.appendChild(b);
    });
    upBody.appendChild(up);
    wrap.appendChild(panel('连接后自动升到', upBody, 'col-4'));

    var navList = h('div', 'list-group list-group-flush');
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
    wrap.appendChild(panel('更多', navList, 'mw-12'));

    wrap.appendChild(panel('网页版限制', (function () {
      var ul = h('ul', 'small opacity-75 mb-0 ps-4');
      ['关掉页面即断开连接：浏览器不允许网页在后台常驻蓝牙。',
       '没有告警通知与后台监控：需要这两项请安装 Android 版。',
       '每次打开需重新选择设备：浏览器安全策略要求用户手势授权。',
       '配置与密码存在本机浏览器里（localStorage），换浏览器/清缓存会丢。'].forEach(function (t) {
        ul.appendChild(h('li', 'mb-1', t));
      });
      return ul;
    })(), 'mw-12'));
    return wrap;
  }

  function renderAuth(st) {
    var wrap = h('div', 'mw-grid');
    wrap.appendChild(panel('', h('div', 'alert alert-info py-2 mb-0',
      '未连接：可以先保存密码，连上后会自动升权。'), 'mw-12'));

    var list = h('div', 'list-group list-group-flush');
    for (var lv = 1; lv <= 5; lv++) {
      (function (level) {
        var has = st.passwords.indexOf(level) >= 0;
        var isCurrent = st.connected && st.permission === level;
        var item = h('div', 'list-group-item bg-transparent px-0 d-flex align-items-center gap-3');
        var main = h('div', 'flex-grow-1');
        main.appendChild(h('div', null, level + ' 级' + (isCurrent ? ' · 当前' : '')));
        main.appendChild(h('div', 'small opacity-60', has ? '已记住密码' : '未设置'));
        item.appendChild(main);

        var b = h('button', 'btn btn-sm ' + (has ? 'btn-outline-secondary' : 'btn-primary'), has ? '更换' : '设置');
        b.addEventListener('click', function () { askPassword(level, st); });
        item.appendChild(b);

        if (has) {
          var del = h('button', 'btn btn-sm btn-link text-danger text-decoration-none', '清除');
          del.addEventListener('click', function () {
            M.forgetPassword(level); toast('已清除 ' + level + ' 级密码'); renderAll();
          });
          item.appendChild(del);
        }
        list.appendChild(item);
      })(lv);
    }
    wrap.appendChild(panel('密码（按设备保存）', list, 'mw-12'));

    wrap.appendChild(panel('连接后自动升到', (function () {
      var body = h('div');
      var seg = h('div', 'btn-group w-100');
      [0, 1, 2, 3, 4, 5].forEach(function (v) {
        var b = h('button', 'btn btn-outline-secondary btn-sm' + (st.settings.autoUpgradeTarget === v ? ' active' : ''),
          v === 0 ? '自动' : v + ' 级');
        b.addEventListener('click', function () { M.setAutoUpgradeTarget(v); renderAll(); });
        seg.appendChild(b);
      });
      body.appendChild(seg);
      body.appendChild(h('p', 'hint mt-2 mb-0',
        '「自动」= 用已记住的最高一级密码。设备闲置约 5 分钟后权限会回落到 0 级，网页端会自动重升。'));
      return body;
    })(), 'mw-12'));

    wrap.appendChild(panel('说明', h('p', 'hint m-0',
      '密码保存在浏览器的 localStorage（按设备分开存）。清除浏览器数据、换浏览器或换域名都会丢失。' +
      '密码不会上传到任何服务器。'), 'mw-12'));
    return wrap;
  }

  function askPassword(level, st) {
    modal(level + ' 级密码', function (body, close) {
      body.appendChild(h('p', 'text-muted small mb-3', '密码用于解锁参数写入与设备控制。'));
      var input = h('input', 'form-control');
      input.type = 'password';
      input.placeholder = '请输入 ' + level + ' 级密码';
      body.appendChild(input);

      var row = h('div', 'd-flex gap-2 mt-3');
      var cancel = h('button', 'btn btn-sm btn-secondary', '取消');
      cancel.addEventListener('click', close);
      row.appendChild(cancel);
      var ok = h('button', 'btn btn-sm btn-primary flex-grow-1', st.connected ? '校验并保存' : '仅保存');
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
    var wrap = h('div', 'mw-grid');
    wrap.appendChild(panel('', (function () {
      var devices = st.devices || [];
      if (!devices.length) {
        return h('div', 'empty', '还没有历史设备。连接一次后会自动记在这里。');
      }
      var list = h('div', 'list-group list-group-flush');
      devices.forEach(function (p) {
        var item = h('div', 'list-group-item bg-transparent px-0 d-flex align-items-center gap-3');
        var main = h('div', 'flex-grow-1');
        main.appendChild(h('div', null, p.name));
        main.appendChild(h('div', 'small opacity-60', p.familyLabel + ' · ' +
          (p.passwords ? p.passwords + ' 级密码' : '无密码') + (p.saved ? ' · 上次连接' : '')));
        item.appendChild(main);
        var b = h('button', 'btn btn-sm btn-outline-danger', '删除');
        b.addEventListener('click', function () {
          modal('删除 ' + p.name + '？', function (body, close) {
            body.appendChild(h('p', 'text-muted small mb-3',
              '该设备的密码、配置缓存与全部快照会一并删除，无法恢复。'));
            var row = h('div', 'd-flex gap-2 mt-3');
            var c = h('button', 'btn btn-sm btn-secondary', '取消');
            c.addEventListener('click', close);
            row.appendChild(c);
            var d = h('button', 'btn btn-sm btn-danger flex-grow-1', '删除');
            d.addEventListener('click', function () {
              M.deleteDevice(p.address); close(); toast('已删除 ' + p.name); renderAll();
            });
            row.appendChild(d);
            body.appendChild(row);
          });
        });
        item.appendChild(b);
        list.appendChild(item);
      });
      return list;
    })(), 'mw-12'));
    return wrap;
  }

  function renderChannels(st) {
    var wrap = h('div', 'mw-grid');
    var list = h('div', 'list-group list-group-flush');
    st.channels.forEach(function (ch) {
      var isCurrent = st.channelLabel === ch.label;
      var item = h('div', 'list-group-item bg-transparent px-0 d-flex align-items-center gap-3');
      var main = h('div', 'flex-grow-1');
      main.appendChild(h('div', null, ch.label + (isCurrent ? ' · 当前' : '')));
      main.appendChild(h('div', 'small opacity-60', ch.warn || (ch.tag + ' · 可用于固件升级')));
      item.appendChild(main);
      if (!isCurrent) {
        var b = h('button', 'btn btn-sm btn-outline-secondary', '切换');
        b.addEventListener('click', function () {
          toast('正在切换到' + ch.label + '…');
          M.switchChannel(ch.id);
        });
        item.appendChild(b);
      }
      list.appendChild(item);
    });
    wrap.appendChild(panel('可用通道', list, 'mw-12'));
    wrap.appendChild(panel('说明', h('p', 'hint m-0',
      '只有暴露可写 FFF5 特征的硬件才有备用通道。备用通道会占用蓝牙屏/充电器那一路，' +
      '使用期间对应外设无法与保护板通信，且不支持固件升级。'), 'mw-12'));
    return wrap;
  }

  function renderLog(st) {
    var wrap = h('div', 'mw-grid');
    var box = h('div', 'log-box bg-body-tertiary rounded-3 p-3');
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
    var copy = h('button', 'btn btn-sm btn-outline-secondary', '复制全部');
    copy.addEventListener('click', async function () {
      try { await navigator.clipboard.writeText(M.logText()); toast('已复制到剪贴板'); }
      catch (e) { toast('复制失败（浏览器拒绝访问剪贴板）'); }
    });
    row.appendChild(copy);
    var clr = h('button', 'btn btn-sm btn-outline-danger', '清空');
    clr.addEventListener('click', function () { M.clearLog(); ui.logLines = []; toast('日志已清空'); renderAll(); });
    row.appendChild(clr);
    body.appendChild(row);
    wrap.appendChild(panel('日志（' + ui.logLines.length + ' 条）', body, 'mw-12'));
    setTimeout(function () { box.scrollTop = box.scrollHeight; }, 0);
    return wrap;
  }

  function renderAbout(st) {
    var wrap = h('div', 'mw-grid');
    var v = st.version || {};

    wrap.appendChild(panel('', (function () {
      var d = h('div', 'text-center py-2');
      d.appendChild(h('div', 'fs-5 fw-bold', '麻衣 BMS · 控制台主题'));
      d.appendChild(h('div', 'small opacity-60', 'v' + (v.name || '')));
      return d;
    })(), 'mw-12'));

    var meta = ui.meta || {};
    var list = h('div', 'list-group list-group-flush');
    [['版本', String(v.name || '--')],
     ['构建时间', meta.builtAt ? new Date(meta.builtAt * 1000).toLocaleString() : '--'],
     ['运行平台', '网页（Bootstrap 5 · 侧栏仪表盘布局）'],
     ['设备连接', 'Web Bluetooth']].forEach(function (kv) {
      list.appendChild(listRow(kv[0], kv[1], null));
    });
    wrap.appendChild(panel('版本信息', list, 'mw-12'));

    var row = h('div', 'btn-row');
    var repo = h('button', 'btn btn-sm btn-outline-secondary', '项目主页');
    repo.addEventListener('click', function () { window.open('https://github.com/lswlc33/maibms', '_blank'); });
    row.appendChild(repo);
    var dl = h('button', 'btn btn-sm btn-primary', '下载 Android 版');
    dl.addEventListener('click', function () { window.open('https://github.com/lswlc33/maibms/releases/latest', '_blank'); });
    row.appendChild(dl);
    wrap.appendChild(panel('链接', row, 'mw-12'));

    wrap.appendChild(panel('为什么没有「检查更新」', h('p', 'hint m-0',
      '网页版没有安装过程，每次打开加载的都是最新构建，所以不存在「需要更新」的状态。' +
      '想要推送通知与后台监控，请使用 Android 版。'), 'mw-12'));
    return wrap;
  }

  /* ---------------- 渲染策略 ---------------- */

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
    renderPagehead(st);

    var content = $('#content');
    if (!content) return;

    var key = renderKey(st);
    if (key !== null && key === ui.lastRenderKey) { renderPagehead(st); return; }
    ui.lastRenderKey = key;

    // 只清直接子级的网格（renderX 自己返回 .mw-grid 包装，不能嵌套在外层网格里——
    // 嵌套后内层宽度塌成 53px、面板全被压瘪，这是实测踩过的坑）
    Array.from(content.children).forEach(function (e) {
      if (e.classList.contains('mw-grid')) e.remove();
    });

    content.appendChild(
      ui.tab === 'params' ? renderParams(st)
        : ui.tab === 'control' ? renderControl(st)
        : ui.tab === 'settings' ? renderSettings(st)
        : renderDashboard(st));

    // 侧栏选中态
    var links = document.querySelectorAll('#sideNav .nav-link');
    links.forEach(function (a) {
      a.className = 'nav-link' + (a.dataset.tab === ui.tab ? ' active' : '');
    });
  }

  /* ---------------- 启动 ---------------- */

  if (!M) {
    document.body.innerHTML = '<div class="p-4 font-sans">' +
      '<h2 class="fs-5 fw-bold">数据层加载失败</h2>' +
      '<p class="small text-muted">请确认 ../maibms-data.js 可访问（不要直接双击打开，需用 http(s) 打开）。</p></div>';
    return;
  }

  applyTheme();
  if (window.matchMedia) {
    var mq = window.matchMedia('(prefers-color-scheme: dark)');
    if (mq.addEventListener) mq.addEventListener('change', applyTheme);
  }

  M.onState(function (s) {
    ui.state = s;
    renderAll();
  });

  M.onLog(function (lines) {
    ui.logLines = Array.prototype.slice.call(lines);
    if (ui.tab === 'settings' && ui.settingsPage === 'log') renderAll();
  });

  ui.state = M.state || {};
  ui.meta = M.meta();

  // 启动渲染包一层防御：渲染异常显示到页面上，而不是静默白屏
  // （renderAll 由桥的回调反复触发，这里只兜"首绘"这次；后续回调的异常由桥记录到日志）
  function safeRender() {
    try { renderAll(); }
    catch (e) {
      var msg = String(e && e.stack || e);
      var app = document.getElementById('app');
      if (app) {
        app.setAttribute('data-render-error', msg.slice(0, 300));
        var box = app.querySelector('.render-error') || app.appendChild(h('div', 'render-error alert alert-danger m-3'));
        box.textContent = '主题渲染出错：' + msg;
      }
      throw e;
    }
  }
  try { safeRender(); } catch (e) { /* 已在页面上显示 */ }

  window.addEventListener('maibms-ready', function () {
    ui.meta = M.meta();
    try { safeRender(); } catch (e) { /* 已在页面上显示 */ }
  });
})();
