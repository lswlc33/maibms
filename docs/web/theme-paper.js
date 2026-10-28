/*
 * 杂志主题（排版优先布局）· 逻辑
 *
 * 与其余主题吃同一份 window.Maibms。布局特点：
 *  - SOC 是一行特大数字（不是环/进度条），下面一行小字说明；
 *  - 关键指标横排成"图注"（大数值 + 大写小标签），不是卡片；
 *  - 全部信息在细分隔线上的键值行里，没有盒子；
 *  - 导航是顶部纯文字链接，当前项下划线；
 *  - 弹层用 Pico 的 <dialog>（语义用法，自带样式）。
 *
 * Pico classless 会直接样式化 <main>/<article>/<table> 等语义标签，
 * 所以这里尽量用语义标签而不是 div 堆叠——这也是这套主题的演示点之一。
 */
(function () {
  'use strict';

  var M = window.Maibms;
  var THEME_FILE = 'paper.html';

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

  function toast(text) {
    // Pico 没有内置 toast：用 article 底部的一条斜体说明替代
    var host = document.getElementById('toastHost');
    if (!host) {
      host = h('div');
      host.id = 'toastHost';
      host.style.cssText = 'position:fixed;left:50%;bottom:18px;transform:translateX(-50%);z-index:60;' +
        'background:var(--pico-background-color);border:1px solid var(--pico-muted-border-color);' +
        'border-radius:6px;padding:8px 16px;font-size:13px;max-width:86vw;text-align:center;';
      document.body.appendChild(host);
    }
    host.textContent = text;
    host.style.display = '';
    clearTimeout(toast._timer);
    toast._timer = setTimeout(function () { host.style.display = 'none'; }, 2400);
  }

  /** Pico <dialog> */
  function dialog(title, build) {
    var dlg = document.createElement('dialog');
    dlg.appendChild(h('h3', null, title));
    var body = h('div');
    dlg.appendChild(body);
    var close = function () { dlg.close(); };
    build(body, close);
    var actions = h('div', 'actions');
    var c = h('button', 'outline', '取消');
    c.addEventListener('click', close);
    actions.appendChild(c);
    dlg.appendChild(actions);
    document.body.appendChild(dlg);
    dlg.addEventListener('close', function () { if (dlg.parentNode) dlg.parentNode.removeChild(dlg); });
    dlg.showModal();
    return dlg;
  }

  function dlgAction(dlg, text, cls, onClick) {
    var b = h('button', cls || '', text);
    b.addEventListener('click', async function () { onClick(); });
    // Pico dialog：action 区里的按钮自动右对齐
    dlg.appendChild(b);
    b.addEventListener('click', function () { setTimeout(function () { if (dlg.parentNode) dlg.parentNode.removeChild(dlg); }, 50); });
    return b;
  }

  /* ---------------- 主题（深/浅） ---------------- */

  function applyTheme() {
    var mode = M.themeMode();
    var dark = mode === 'dark' || (mode !== 'light' && M.prefersDark && M.prefersDark());
    document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
  }

  /* ---------------- 顶栏（纯文字导航，建一次改一次） ---------------- */

  var TABS = [
    { id: 'dash', label: '总览' },
    { id: 'params', label: '参数' },
    { id: 'control', label: '控制' },
    { id: 'settings', label: '设置' }
  ];

  function ensureTop() {
    var top = document.getElementById('top');
    if (top.querySelector('.links')) return;

    clear(top);
    top.appendChild(h('span', 'brand', '麻衣 BMS'));

    var links = h('div', 'links');
    TABS.forEach(function (t) {
      var a = h('a', null, t.label);
      a.dataset.tab = t.id;
      a.addEventListener('click', function () { ui.tab = t.id; renderAll(); });
      links.appendChild(a);
    });
    top.appendChild(links);

    var status = h('span', 'status');
    status.id = 'topStatus';
    status.addEventListener('click', openAppearance);
    status.style.cursor = 'pointer';
    status.title = '外观与主题';
    top.appendChild(status);
  }

  function renderTop(st) {
    ensureTop();
    var links = document.querySelectorAll('#top .links a');
    links.forEach(function (a) {
      a.className = a.dataset.tab === ui.tab ? 'on' : '';
    });

    var status = document.getElementById('topStatus');
    if (!status) return;
    var text = st.connected && st.stalled ? '失联' : st.linkText;
    if (st.link === 'Connecting' && st.hint) text = st.hint;
    status.textContent = text + ' · ⚙ 外观';
  }

  function openAppearance() {
    dialog('外观与主题', function (body, close) {
      body.appendChild(h('p', 'muted', '深色 / 浅色 / 跟随系统。选择会被记住，所有主题共用。'));
      var actions = h('div', 'actions');
      [['system', '跟随系统'], ['light', '浅色'], ['dark', '深色']].forEach(function (kv) {
        var b = h('button', M.themeMode() === kv[0] ? '' : 'outline', kv[1]);
        b.addEventListener('click', function () {
          M.setThemeMode(kv[0]); applyTheme(); close(); renderAll();
        });
        actions.appendChild(b);
      });
      body.appendChild(actions);

      body.appendChild(h('p', 'muted', '需要更大的字？用浏览器的页面缩放即可。'));
      var sw = h('button', 'linklike', '→ 切换页面主题（共 6 套）');
      sw.addEventListener('click', function () {
        location.href = 'switch.html?from=' + encodeURIComponent(THEME_FILE);
      });
      body.appendChild(h('p', null, sw));
    });
  }

  /* ---------------- 总览 ---------------- */

  function section(title, count, content) {
    var s = h('section');
    var head = h('h2');
    head.appendChild(h('span', null, title));
    if (count) head.appendChild(h('span', 'n', count));
    s.appendChild(head);
    s.appendChild(typeof content === 'string' ? h('p', null, content) : content);
    return s;
  }

  function row(k, kSmall, v, vUnit, cls, onClick) {
    var r = h('div', 'row' + (onClick ? ' click' : ''));
    var kk = h('div', 'k');
    kk.appendChild(document.createTextNode(k));
    if (kSmall) kk.appendChild(h('small', null, kSmall));
    r.appendChild(kk);
    var vv = h('div', 'v' + (cls ? ' ' + cls : ''));
    vv.appendChild(document.createTextNode(v));
    if (vUnit) vv.appendChild(h('small', null, vUnit));
    r.appendChild(vv);
    if (onClick) r.addEventListener('click', onClick);
    return r;
  }

  function word(text, kind) { return h('span', 'word ' + (kind || ''), text); }

  function renderDashboard(st) {
    var d = st.device;
    var main = h('div');

    if (!M.supportsWebBluetooth()) {
      main.appendChild(h('p', null, '此浏览器不支持 Web Bluetooth。请使用安卓 Chrome 或桌面 Chrome/Edge，并通过 https 或 localhost 打开。'));
      main.appendChild(h('p', null, '微信/QQ 内置浏览器、Safari、Firefox 均不支持。想要后台监控与通知，请使用 Android 版。'));
      return main;
    }

    // SOC 特大数字
    var hero = h('div', 'soc-hero');
    hero.appendChild(h('p', 'soc-label', 'State of Charge'));
    var big = h('div', 'soc-num');
    big.appendChild(document.createTextNode(d.hasData ? String(d.soc) : '--'));
    big.appendChild(h('small', null, '%'));
    hero.appendChild(big);
    hero.appendChild(h('p', 'soc-sub',
      (!d.hasData ? '等待数据…' : (st.stalled ? '设备失联' : d.battState)) +
      (d.hasData ? ' · ' + num(d.remainCapAh, 1) + ' / ' + num(d.totalCapAh, 1) + ' Ah · SOH ' + d.soh + '% · ' + d.cycles + ' 次循环' : '')));
    main.appendChild(hero);

    // 连接入口（未连接时）
    if (!st.connected && !st.hasData) {
      var connectSec = section('连接', '', (function () {
        var c = h('div');
        c.appendChild(h('p', null, '按下方按钮，浏览器会弹出附近的蓝牙设备列表。'));
        var row = h('div', 'actions');
        var b = h('button', null, '选择设备并连接');
        b.addEventListener('click', function () { M.connect(); });
        row.appendChild(b);
        c.appendChild(row);
        c.appendChild(h('p', 'muted', '网页版受浏览器限制：关掉页面就断开，无法后台监控，不发通知；' +
          '每次打开需重新选择一次设备。'));
        return c;
      })());
      main.appendChild(connectSec);
      if (st.scanError) main.appendChild(h('p', null, st.scanError));
      return main;
    }

    if (!st.connected && st.hasData) {
      main.appendChild(h('p', 'muted', '界面预览：以下是内置示例数据，不是真机读数。'));
    }
    if (st.hint && !st.connected) main.appendChild(h('p', 'muted', st.hint));
    if (st.stalled) main.appendChild(h('p', 'muted', '设备失联：连续多拍收不到实时数据，正在尝试恢复。'));

    // 图注式指标
    var figs = h('div', 'figures');
    figs.appendChild(figItem('Total Voltage', reading(d, d.totalVoltage, d.voltageDecimals), 'V'));
    figs.appendChild(figItem('Current', reading(d, d.current, 2), 'A'));
    figs.appendChild(figItem('Power', d.hasData ? String(d.power) : '--', 'W'));
    figs.appendChild(figItem('Delta', d.hasData ? d.deltaCell : '--', 'V'));
    main.appendChild(figs);

    // MOS / 均衡（文字词，不做胶囊）
    if (st.caps.mos || st.caps.balance) {
      var mosBody = h('div', 'rows');
      if (st.caps.mos) {
        mosBody.appendChild(row('充电 MOS', d.chMosReason ? d.chMos + ' · ' + d.chMosReason : '',
          d.hasData ? d.chMos : '--', '', d.chMosReason ? 'warn' : d.chMos === '开启' ? 'on' : '', null));
        mosBody.appendChild(row('放电 MOS', d.disMosReason ? d.disMos + ' · ' + d.disMosReason : '',
          d.hasData ? d.disMos : '--', '', d.disMosReason ? 'warn' : d.disMos === '开启' ? 'on' : '', null));
      }
      if (st.caps.balance) {
        mosBody.appendChild(row('均衡', d.balanceReason ? d.balanceReason : '',
          d.hasData ? d.balance : '--', '', d.balance === '均衡中' ? 'on' : '', null));
      }
      if (st.caps.permission) {
        mosBody.appendChild(row('权限等级', st.permission > 0 ? '可读写' : '只读',
          st.permission > 0 ? st.permission + ' 级' : '0 级', '', st.permission > 0 ? 'on' : '', null));
      }
      if (st.channelLabel) {
        mosBody.appendChild(row('通信通道', '', st.channelLabel, '', '', null));
      }
      main.appendChild(section('MOS 与均衡', '', mosBody));
    }

    if (st.caps.perCellVoltage && st.cells.length) {
      var grid = h('div', 'cellgrid');
      st.cells.forEach(function (c) {
        var cls = 'cellrow' + (c.missing ? ' missing' : '') +
          (!c.missing && c.isMax ? ' max' : '') + (!c.missing && c.isMin ? ' min' : '');
        var r = h('div', cls);
        r.appendChild(h('span', 'idx', '#' + c.index + (c.balancing ? ' ◈' : '')));
        r.appendChild(h('span', 'v', c.missing ? '--' : num(c.volt, 3)));
        grid.appendChild(r);
      });
      var cellsBody = h('div');
      cellsBody.appendChild(grid);
      cellsBody.appendChild(h('p', 'muted',
        '加粗为最高（#' + cellIdxOf(st.cells, 'max') + '），' +
        '灰为最低（#' + cellIdxOf(st.cells, 'min') + '），◈ 表示均衡中，-- 为未接。'));
      main.appendChild(section('单体电压', st.cells.filter(function (c) { return !c.missing; }).length + ' 串', cellsBody));
    }

    if ((st.caps.multiTemp || st.temps.length) && st.temps.length) {
      var tBody = h('div', 'rows');
      st.temps.forEach(function (t) {
        tBody.appendChild(row(t.name, '', num(t.value, 1), '°C', t.value >= 55 ? 'bad' : '', null));
      });
      main.appendChild(section('温度', st.temps.length + ' 路', tBody));
    }

    if (st.caps.protectAlarm) {
      var paBody = h('div', 'rows');
      if (!st.flags.protect.length && !st.flags.alarm.length) {
        paBody.appendChild(h('p', 'muted', '无保护动作，无告警。'));
      } else {
        st.flags.protect.forEach(function (x) { paBody.appendChild(row('保护', '', x, '', 'bad', null)); });
        st.flags.alarm.forEach(function (x) { paBody.appendChild(row('告警', '', x, '', 'warn', null)); });
      }
      if (st.flags.states.length) {
        var words = h('div');
        st.flags.states.forEach(function (x) { words.appendChild(word(x)); });
        paBody.appendChild(words);
      }
      main.appendChild(section('保护与告警', '', paBody));
    }

    if (st.relay.active) {
      var rBody = h('div', 'rows');
      rBody.appendChild(row('中继品牌', '', st.relay.brand, '', '', null));
      rBody.appendChild(row('保护板名', '', st.relay.name, '', '', null));
      rBody.appendChild(row('保护板 MAC', '', st.relay.mac, '', 'dim', null));
      main.appendChild(section('中继信息', '', rBody));
    }

    // 终端之外，杂志主题也可以有"实时读数附录"：放一段当前键值（供快速目视校对）
    return main;
  }

  function figItem(label, value, unit) {
    var f = h('div', 'figure');
    var v = h('div', 'v');
    v.appendChild(document.createTextNode(value));
    if (unit) v.appendChild(h('small', null, unit));
    f.appendChild(v);
    f.appendChild(h('div', 'k', label));
    return f;
  }

  function cellIdxOf(cells, which) {
    for (var i = 0; i < cells.length; i++) {
      if (which === 'max' && cells[i].isMax && !cells[i].missing) return cells[i].index;
      if (which === 'min' && cells[i].isMin && !cells[i].missing) return cells[i].index;
    }
    return '--';
  }

  function reading(d, v, digits) { return d.hasData ? num(v, digits) : '--'; }

  /* ---------------- 参数 ---------------- */

  function renderParams(st) {
    var main = h('div');
    if (st.params.reading) main.appendChild(h('p', 'muted', '正在读取参数区…（200+ 项，通常几秒）'));
    if (st.params.fromCache) main.appendChild(h('p', 'muted', '显示的是缓存参数（非实时）。'));

    var groups = (ui.meta || {}).paramGroups || [];
    var live = st.connected;

    if (!groups.length || !Object.keys(st.params.values || {}).length) {
      main.appendChild(h('div', 'empty', live ? '等待设备回读参数区…' : '未连接。连接后可查看与修改参数。'));
      return main;
    }

    var canWrite = live && st.permission > 0;
    if (!live) main.appendChild(h('p', 'muted', '离线查看（未连接设备）：显示缓存或快照里的参数，不能修改。'));
    else if (!canWrite) main.appendChild(h('p', 'muted', '当前为只读（0 级）。到「设置 → 权限」输入密码后可修改。'));

    groups.forEach(function (g) {
      var rows = h('div', 'rows');
      g.defs.forEach(function (def) {
        var onClick = (!def.readOnly && canWrite) ? function () { editParam(def); } : null;
        rows.appendChild(row(def.name, def.addrHex + ' · ' + def.rangeText,
          def.display, def.display !== '--' && def.unit ? def.unit : '',
          def.display === '--' ? 'dim' : '', onClick));
      });
      main.appendChild(section(g.name, g.defs.length + ' 项', rows));
    });

    if (live) {
      var actions = h('div', 'actions');
      var rb = h('button', 'outline', '重新读取');
      rb.addEventListener('click', function () { toast('正在重新读取参数…'); M.refreshParams(); });
      actions.appendChild(rb);
      var sb = h('button', null, canWrite ? '保存全部参数' : '保存（需权限）');
      if (!canWrite) sb.disabled = true;
      sb.addEventListener('click', async function () {
        toast('正在保存全部参数…');
        var r = await M.saveAllParams();
        toast(r.ok ? '参数已保存到设备' : '保存失败：' + r.text);
      });
      actions.appendChild(sb);
      main.appendChild(section('操作', '', actions));
    }
    return main;
  }

  function editParam(def) {
    dialog('修改参数', function (body, close) {
      body.appendChild(h('p', 'sub', '写入后会回读确认；设备侧还需「保存参数」才持久化。'));
      body.appendChild(h('label', null, def.name + '（' + def.unit + '）'));
      var input = h('input');
      input.type = 'number';
      input.step = 'any';
      input.value = def.display === '--' ? '' : def.display;
      body.appendChild(input);
      body.appendChild(h('p', 'sub', '地址 ' + def.addrHex + ' · 范围 ' + def.rangeText));

      var err = h('p', 'sub');
      err.style.color = 'var(--pico-danger)';
      err.style.display = 'none';
      body.appendChild(err);

      var actions = h('div', 'actions');
      var cancel = h('button', 'outline', '取消');
      cancel.addEventListener('click', close);
      actions.appendChild(cancel);
      var ok = h('button', null, '写入');
      ok.addEventListener('click', async function () {
        var v = parseFloat(input.value.replace('，', '.'));
        if (!isFinite(v) || v < def.min - 1e-9 || v > def.max + 1e-9) {
          err.textContent = '请输入合法数值（' + def.rangeText + '）';
          err.style.display = '';
          return;
        }
        err.style.display = 'none';
        close();
        var raw = Math.round(v * def.scale);
        var r = await M.writeParam(def.addr, raw);
        toast(def.name + (r.ok ? ' 已写入' : ' 写入失败：' + r.text));
      });
      actions.appendChild(ok);
      body.appendChild(actions);
    });
  }

  /* ---------------- 控制 ---------------- */

  function renderControl(st) {
    var main = h('div');
    if (!st.connected) {
      main.appendChild(h('div', 'empty', '未连接。连接保护板后可以下发控制命令。'));
      return main;
    }

    var canWrite = st.permission > 0;
    if (!canWrite) main.appendChild(h('p', 'muted', '当前为只读（0 级），控制命令不可用。到「设置 → 权限」输入密码。'));

    var meta = ui.meta || {};
    var cmds = meta.commands || [];
    var toggles = cmds.filter(function (c) { return c.toggle; });
    var others = cmds.filter(function (c) { return !c.toggle && !c.dangerous; });
    var dangerous = cmds.filter(function (c) { return c.dangerous; });

    function cmdRows(list) {
      var rows = h('div', 'rows');
      list.forEach(function (c) {
        var b = h('button', 'linklike', '[ ' + c.name + ' ]');
        if (!canWrite) { b.disabled = true; b.style.opacity = '.4'; }
        b.addEventListener('click', async function () {
          var r = await M.control(c.cmd);
          toast(c.name + (r.ok ? ' 已执行' : ' 失败：' + r.text));
        });
        rows.appendChild(row(c.name, '', '', '', '', function () {
          b.click();
        }));
        // 把按钮放进行尾
        rows.lastChild.appendChild(b);
      });
      return rows;
    }

    if (toggles.length) main.appendChild(section('开关', '', cmdRows(toggles)));
    if (others.length) main.appendChild(section('维护', '', cmdRows(others)));

    if (dangerous.length) {
      var rows = h('div', 'rows');
      dangerous.forEach(function (c) {
        var b = h('button', 'linklike', '[ ' + c.name + ' ]');
        if (!canWrite) { b.disabled = true; b.style.opacity = '.4'; }
        b.addEventListener('click', function () { confirmCommand(c); });
        rows.appendChild(row(c.name, c.warning || '', '', '', 'warn', null));
        rows.lastChild.appendChild(b);
      });
      main.appendChild(section('危险操作', '需二次确认', rows));
    }
    return main;
  }

  function confirmCommand(c) {
    dialog('确认: ' + c.name, function (body, close) {
      body.appendChild(h('p', 'sub', '这条命令会改变设备状态，请确认对象正确。'));
      if (c.warning) body.appendChild(h('p', 'sub', c.warning));

      var input = null;
      if (c.typedConfirm) {
        body.appendChild(h('label', null, '此操作不可撤销，请输入「确认」继续'));
        input = h('input');
        input.placeholder = '确认';
        body.appendChild(input);
      }

      var actions = h('div', 'actions');
      var cancel = h('button', 'outline', '取消');
      cancel.addEventListener('click', close);
      actions.appendChild(cancel);
      var ok = h('button', null, '确认执行');
      ok.addEventListener('click', async function () {
        if (c.typedConfirm && input.value.trim() !== '确认') { toast('请先输入「确认」'); return; }
        close();
        var r = await M.control(c.cmd);
        toast(c.name + (r.ok ? ' 已执行' : ' 失败：' + r.text));
      });
      actions.appendChild(ok);
      body.appendChild(actions);
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

  function backLink(title) {
    var wrap = h('div');
    var row = h('div', 'top');
    var b = h('button', 'linklike', '← 返回');
    b.addEventListener('click', function () { ui.settingsPage = 'root'; renderAll(); });
    row.appendChild(b);
    row.appendChild(h('span', 'brand', title));
    wrap.appendChild(row);
    return wrap;
  }

  function renderSettingsRoot(st) {
    var main = h('div');
    var meta = ui.meta || {};

    var connBody = h('div');
    connBody.appendChild(h('p', 'muted', st.connected
      ? st.deviceName + ' · 通道 ' + (st.channelLabel || '?') + ' · ' + st.familyLabel
      : (st.hint || '尚未连接设备')));
    var actions = h('div', 'actions');
    var cb = h('button', st.connected ? 'outline' : '', st.connected ? '断开连接' : '连接设备');
    cb.addEventListener('click', function () { if (st.connected) M.disconnect(); else M.connect(); });
    actions.appendChild(cb);
    connBody.appendChild(actions);
    main.appendChild(section('连接', '', connBody));

    var themeBody = h('div');
    themeBody.appendChild(h('p', 'muted', '深色 / 浅色 / 跟随系统。选择会被记住，所有主题共用。'));
    var actions = h('div', 'actions');
    [['system', '跟随系统'], ['light', '浅色'], ['dark', '深色']].forEach(function (kv) {
      var b = h('button', M.themeMode() === kv[0] ? '' : 'outline', kv[1]);
      b.addEventListener('click', function () { M.setThemeMode(kv[0]); applyTheme(); renderAll(); });
      actions.appendChild(b);
    });
    themeBody.appendChild(actions);

    var sw = h('button', 'linklike', '→ 切换页面主题（默认 / Material / Daisy / 控制台 / 杂志）');
    sw.addEventListener('click', function () {
      location.href = 'switch.html?from=' + encodeURIComponent(THEME_FILE);
    });
    themeBody.appendChild(h('p', null, sw));

    themeBody.appendChild(h('p', 'muted', '需要更大的字？用浏览器的页面缩放即可（Chrome 菜单 → 缩放）。'));
    main.appendChild(section('外观', '', themeBody));

    var pollBody = h('div');
    var actions = h('div', 'actions');
    [[900, '更稳'], [600, '默认'], [300, '更跟手']].forEach(function (kv) {
      var b = h('button', st.settings.pollInterval === kv[0] ? '' : 'outline', kv[1] + ' ' + kv[0] + 'ms');
      b.addEventListener('click', function () {
        M.setPollInterval(kv[0]); toast('轮询频率已设为 ' + kv[0] + ' ms'); renderAll();
      });
      actions.appendChild(b);
    });
    pollBody.appendChild(actions);
    pollBody.appendChild(h('p', 'muted', '页面切到后台时轮询会暂停（浏览器节流），回来立即恢复。'));
    main.appendChild(section('轮询频率', '弱信号建议选更稳', pollBody));

    var nav = h('div', 'rows');
    nav.appendChild(row('权限',
      st.connected ? (st.permission > 0 ? '可读写' : '只读') : '未连接',
      '', '', '', function () { ui.settingsPage = 'auth'; renderAll(); }));
    if (st.channels.length) {
      nav.appendChild(row('通信通道', '', st.channelLabel || '自动', '', '', function () {
        ui.settingsPage = 'channels'; renderAll();
      }));
    }
    nav.appendChild(row('历史设备', '', (st.devices || []).length + ' 台', '', '', function () {
      ui.settingsPage = 'devices'; renderAll();
    }));
    nav.appendChild(row('运行日志', '', ui.logLines.length + ' 条', '', '', function () {
      ui.settingsPage = 'log'; renderAll();
    }));
    nav.appendChild(row('关于', '', 'v' + ((st.version || {}).name || ''), '', '', function () {
      ui.settingsPage = 'about'; renderAll();
    }));
    main.appendChild(section('更多', '', nav));

    main.appendChild(section('网页版限制', '', (function () {
      var ul = h('ul');
      ul.style.cssText = 'font-size:13px;line-height:1.9;margin:0;padding-left:18px';
      ['关掉页面即断开连接：浏览器不允许网页在后台常驻蓝牙。',
       '没有告警通知与后台监控：需要这两项请安装 Android 版。',
       '每次打开需重新选择设备：浏览器安全策略要求用户手势授权。',
       '配置与密码存在本机浏览器里（localStorage），换浏览器/清缓存会丢。'].forEach(function (t) {
        ul.appendChild(h('li', null, t));
      });
      return ul;
    })()));
    return main;
  }

  function renderAuth(st) {
    var main = h('div');
    main.appendChild(backLink('权限'));
    if (!st.connected) main.appendChild(h('p', 'muted', '未连接：可以先保存密码，连上后会自动升权。'));

    var rows = h('div', 'rows');
    for (var lv = 1; lv <= 5; lv++) {
      (function (level) {
        var has = st.passwords.indexOf(level) >= 0;
        var isCurrent = st.connected && st.permission === level;
        var b = h('button', 'linklike', has ? '[ 更换 ]' : '[ 设置 ]');
        b.addEventListener('click', function () { askPassword(level, st); });
        var r = row(level + ' 级' + (isCurrent ? ' · 当前' : ''), has ? '已记住密码' : '未设置',
          '', '', '', null);
        r.appendChild(b);
        if (has) {
          var del = h('button', 'linklike', '[ 清除 ]');
          del.addEventListener('click', function () {
            M.forgetPassword(level); toast('已清除 ' + level + ' 级密码'); renderAll();
          });
          r.appendChild(del);
        }
        rows.appendChild(r);
      })(lv);
    }
    main.appendChild(section('密码', '按设备保存', rows));

    var target = h('div', 'rows');
    [0, 1, 2, 3, 4, 5].forEach(function (v) {
      var isSel = st.settings.autoUpgradeTarget === v;
      var b = h('button', 'linklike', isSel ? '← 当前' : '选择');
      b.addEventListener('click', function () { M.setAutoUpgradeTarget(v); renderAll(); });
      var r = row(v === 0 ? '自动（最高已存）' : v + ' 级', '', '', '', '', null);
      r.appendChild(b);
      target.appendChild(r);
    });
    main.appendChild(section('连接后自动升到', '', target));

    main.appendChild(section('说明', '', h('p', 'muted',
      '密码保存在浏览器 localStorage（按设备分开存）。清除浏览器数据、换浏览器或换域名都会丢失。' +
      '密码不会上传到任何服务器。')));
    return main;
  }

  function askPassword(level, st) {
    dialog(level + ' 级密码', function (body, close) {
      body.appendChild(h('p', 'sub', '密码用于解锁参数写入与设备控制。'));
      var input = h('input');
      input.type = 'password';
      input.placeholder = '请输入 ' + level + ' 级密码';
      body.appendChild(input);

      var actions = h('div', 'actions');
      var cancel = h('button', 'outline', '取消');
      cancel.addEventListener('click', close);
      actions.appendChild(cancel);
      var ok = h('button', null, st.connected ? '校验并保存' : '仅保存');
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
      actions.appendChild(ok);
      body.appendChild(actions);
    });
  }

  function renderDevices(st) {
    var main = h('div');
    main.appendChild(backLink('历史设备'));
    var devices = st.devices || [];
    if (!devices.length) {
      main.appendChild(h('div', 'empty', '还没有历史设备。连接一次后会自动记在这里。'));
      return main;
    }
    var rows = h('div', 'rows');
    devices.forEach(function (p) {
      var b = h('button', 'linklike', '[ 删除 ]');
      b.addEventListener('click', function () {
        dialog('删除 ' + p.name + '？', function (body, close) {
          body.appendChild(h('p', 'sub', '该设备的密码、配置缓存与全部快照会一并删除，无法恢复。'));
          var actions = h('div', 'actions');
          var c = h('button', 'outline', '取消');
          c.addEventListener('click', close);
          actions.appendChild(c);
          var d = h('button', null, '删除');
          d.addEventListener('click', function () {
            M.deleteDevice(p.address); close(); toast('已删除 ' + p.name); renderAll();
          });
          actions.appendChild(d);
          body.appendChild(actions);
        });
      });
      var r = row(p.name, p.familyLabel + ' · ' +
        (p.passwords ? p.passwords + ' 级密码' : '无密码') + (p.saved ? ' · 上次连接' : ''),
        '', '', '', null);
      r.appendChild(b);
      rows.appendChild(r);
    });
    main.appendChild(section('共 ' + devices.length + ' 台', '', rows));
    return main;
  }

  function renderChannels(st) {
    var main = h('div');
    main.appendChild(backLink('通信通道'));
    var rows = h('div', 'rows');
    st.channels.forEach(function (ch) {
      var isCurrent = st.channelLabel === ch.label;
      var b = isCurrent ? null : h('button', 'linklike', '[ 切换 ]');
      if (b) b.addEventListener('click', function () {
        toast('正在切换到' + ch.label + '…');
        M.switchChannel(ch.id);
      });
      var r = row(ch.label + (isCurrent ? ' · 当前' : ''),
        ch.warn || (ch.tag + ' · 可用于固件升级'), '', '', '', null);
      if (b) r.appendChild(b);
      rows.appendChild(r);
    });
    main.appendChild(section('可用通道', '', rows));
    main.appendChild(section('说明', '', h('p', 'muted',
      '只有暴露可写 FFF5 特征的硬件才有备用通道。备用通道会占用蓝牙屏/充电器那一路，' +
      '使用期间对应外设无法与保护板通信，且不支持固件升级。')));
    return main;
  }

  function renderLog(st) {
    var main = h('div');
    main.appendChild(backLink('运行日志'));

    var log = h('div', 'log');
    if (!ui.logLines.length) {
      log.appendChild(h('div', 'd', '(日志为空)'));
    } else {
      ui.logLines.slice(-300).forEach(function (line) {
        var cls = '';
        if (line.indexOf(' E ') >= 0) cls = 'e';
        else if (line.indexOf(' W ') >= 0) cls = 'w';
        else if (line.indexOf(' D ') >= 0) cls = 'd';
        log.appendChild(h('div', cls, line));
      });
    }

    var body = h('div');
    body.appendChild(log);
    var actions = h('div', 'actions');
    var copy = h('button', 'outline', '复制全部');
    copy.addEventListener('click', async function () {
      try { await navigator.clipboard.writeText(M.logText()); toast('已复制到剪贴板'); }
      catch (e) { toast('复制失败（浏览器拒绝访问剪贴板）'); }
    });
    actions.appendChild(copy);
    var clr = h('button', 'outline', '清空');
    clr.addEventListener('click', function () { M.clearLog(); ui.logLines = []; toast('日志已清空'); renderAll(); });
    actions.appendChild(clr);
    body.appendChild(actions);
    main.appendChild(section('日志 ' + ui.logLines.length + ' 条', '', body));

    main.appendChild(section('说明', '', h('p', 'muted',
      '网页版不把日志写到磁盘（浏览器无此权限），关闭页面即清空；需要留档请点「复制全部」。')));
    return main;
  }

  function renderAbout(st) {
    var main = h('div');
    main.appendChild(backLink('关于'));
    var v = st.version || {};

    var info = h('div', 'rows');
    info.appendChild(row('版本', '', 'v' + (v.name || '--'), '', '', null));
    info.appendChild(row('构建时间', '', (ui.meta || {}).builtAt ? new Date((ui.meta || {}).builtAt * 1000).toLocaleString() : '--', '', 'dim', null));
    info.appendChild(row('运行平台', '', '网页（杂志排版布局）', '', '', null));
    info.appendChild(row('界面框架', '', 'Pico CSS classless + 自定义排版', '', '', null));
    info.appendChild(row('设备连接', '', 'Web Bluetooth', '', '', null));
    main.appendChild(section('版本信息', '', info));

    var actions = h('div', 'actions');
    var repo = h('button', 'outline', '项目主页');
    repo.addEventListener('click', function () { window.open('https://github.com/lswlc33/maibms', '_blank'); });
    actions.appendChild(repo);
    var dl = h('button', null, '下载 Android 版');
    dl.addEventListener('click', function () { window.open('https://github.com/lswlc33/maibms/releases/latest', '_blank'); });
    actions.appendChild(dl);
    main.appendChild(section('链接', '', actions));

    main.appendChild(section('为什么没有「检查更新」', '', h('p', 'muted',
      '网页版没有安装过程，每次打开加载的都是最新构建，所以不存在「需要更新」的状态。' +
      '想要推送通知与后台监控，请使用 Android 版。')));
    return main;
  }

  /* ---------------- 渲染策略 ---------------- */

  function renderKey(st) {
    switch (ui.tab) {
      case 'params':
        return JSON.stringify([st.params, st.permission, st.connected]);
      case 'control':
        return JSON.stringify([st.connected, st.permission, st.caps]);
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
      // 渲染异常显示到页面上而不是静默终止脚本（与其它主题同一约定）
      var c = document.getElementById('content') || document.body;
      var box = c.querySelector('.render-error') || c.appendChild(h('div', 'render-error'));
      box.textContent = '渲染出错：' + String(e && e.stack || e);
    }
  }

  function renderAllInner() {
    var st = ui.state || {};
    renderTop(st);

    var key = renderKey(st);
    if (key !== null && key === ui.lastRenderKey) return;
    ui.lastRenderKey = key;

    var y = window.scrollY;
    var content = document.getElementById('content');
    if (!content) return;
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
  }

  /* ---------------- 启动 ---------------- */

  if (!M) {
    document.body.innerHTML = '<main><p><strong>数据层加载失败。</strong><br>' +
      '请确认 ../maibms-data.js 可访问（用 http(s) 打开，勿双击文件）。</p></main>';
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
  renderAll();

  window.addEventListener('maibms-ready', function () {
    ui.meta = M.meta();
    renderAll();
  });
})();
