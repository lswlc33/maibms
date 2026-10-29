/*
 * 终端主题（HUD 布局）· 逻辑
 *
 * 与其余主题吃同一份 window.Maibms。界面特点：
 *  - 顶部固定 HUD：SOC 字符进度条 + 关键读数一行排开（每拍刷新，不重建节点）；
 *  - ASCII 边框面板，串口调试工具的观感；
 *  - 键盘快捷键：1~4 切面板、C 连接、X 断开；
 *  - 深色常驻（终端没有浅色模式，这是美学选择不是疏漏）。
 */
(function () {
  'use strict';
  // 设备被宿主拦截（iOS 无 Web Bluetooth）：宿主已铺全页提示，这里不再渲染任何界面
  if (window.__MAIBMS_BLOCKED) return;


  var M = window.Maibms;
  var THEME_FILE = 'console.html';

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

  /** 字符进度条：SOC 的终端表达（█ 填充 + ░ 空位） */
  function asciiBar(pct, width) {
    var w = width || 20;
    var on = Math.round(Math.max(0, Math.min(1, pct)) * w);
    var s = '';
    for (var i = 0; i < w; i++) s += i < on ? '█' : '░';
    return s;
  }

  function toast(text) {
    var host = document.getElementById('toastHost');
    var t = h('div', 'sysmsg', text);
    host.appendChild(t);
    setTimeout(function () { if (t.parentNode) t.parentNode.removeChild(t); }, 2600);
  }

  function dialog(title, build) {
    var mask = h('div', 'mask');
    var box = h('div', 'dialog');
    box.appendChild(h('h3', null, title));
    var body = h('div');
    box.appendChild(body);
    var close = function () { if (mask.parentNode) mask.parentNode.removeChild(mask); };
    build(body, close);
    mask.appendChild(box);
    mask.addEventListener('click', function (e) { if (e.target === mask) close(); });
    document.body.appendChild(mask);
  }

  /* ---------------- HUD（固定顶栏，只更新文字） ---------------- */

  function ensureHud() {
    var hud = document.getElementById('hud');
    if (hud.querySelector('.hud-line')) return;
    var line = h('div', 'hud-line');
    ['SOC', '总压', '电流', '功率', '链路'].forEach(function (k) {
      var item = h('span', 'hud-item');
      item.appendChild(h('span', 'k', k + ' '));
      item.appendChild(h('span', 'v', '--'));
      item.dataset.k = k;
      line.appendChild(item);
    });
    var cursor = h('span', 'hud-item');
    cursor.appendChild(h('span', 'cursor'));
    line.appendChild(cursor);
    var status = h('div', 'hud-status');
    status.id = 'hudStatus';
    hud.appendChild(status);
  }

  function renderHud(st) {
    ensureHud();
    var status = document.getElementById('hudStatus');
    if (status) {
      if (st.hasData) {
        var d0 = st.device, flags0 = st.flags || {}, notes0 = [];
        (flags0.alarm || []).forEach(function (t) { notes0.push(t); });
        (flags0.protect || []).forEach(function (t) { notes0.push('保护:' + t); });
        if (d0.chMosReason) notes0.push('充电MOS关(' + d0.chMosReason + ')');
        if (d0.disMosReason) notes0.push('放电MOS关(' + d0.disMosReason + ')');
        var level0 = (flags0.protect || []).length ? 'bad' : notes0.length ? 'warn' : 'ok';
        status.textContent = level0 === 'ok' ? '[✓] 运行正常'
          : '[⚠] ' + notes0.slice(0, 2).join('，') + (notes0.length > 2 ? ' 等' + notes0.length + '项' : '');
        status.className = 'hud-status ' + level0;
      } else {
        status.textContent = '[·] 等待数据';
        status.className = 'hud-status';
      }
    }
    var d = st.device;
    var byK = {};
    var items = document.querySelectorAll('#hud .hud-item .v');
    // 顺序与 ensureHud 一致：SOC/总压/电流/功率/链路
    if (items.length < 5) return;

    // SOC：字符进度条 + 百分比
    var soc = items[0];
    soc.innerHTML = '';
    var bar = h('span', 'bar');
    var filled = asciiBar(d.hasData ? d.soc / 100 : 0, 18);
    var onPart = h('span', 'on', filled.replace(/░+$/, ''));
    var offPart = h('span', 'off', filled.slice(filled.replace(/░+$/, '').length));
    bar.appendChild(onPart);
    bar.appendChild(offPart);
    soc.appendChild(bar);
    soc.appendChild(document.createTextNode(' ' + (d.hasData ? d.soc : '--') + '%'));

    items[1].textContent = d.hasData ? num(d.totalVoltage, d.voltageDecimals) + 'V' : '--';
    items[2].textContent = d.hasData ? num(d.current, 2) + 'A' : '--';
    items[3].textContent = d.hasData ? d.power + 'W' : '--';

    var link = items[4];
    var cls = '';
    var text = st.linkText;
    if (st.connected && st.stalled) { cls = 'bad'; text = '失联'; }
    else if (st.connected) { cls = ''; text = '在线'; }
    else if (st.link === 'Connecting') { cls = 'warn'; text = '连接中'; }
    else if (st.link === 'Disconnected' && st.hint) { cls = 'warn'; text = '已断开'; }
    link.className = 'v ' + cls;
    link.textContent = text;
  }

  /* ---------------- 标签条 ---------------- */

  var TABS = [
    { id: 'dash', label: '总览', key: '1' },
    { id: 'params', label: '参数', key: '2' },
    { id: 'control', label: '控制', key: '3' },
    { id: 'settings', label: '设置', key: '4' }
  ];

  function ensureTabs() {
    var bar = document.getElementById('tabs');
    if (bar.children.length) return;
    TABS.forEach(function (t) {
      var b = h('button', 'tab' + (t.id === ui.tab ? ' on' : ''));
      b.dataset.tab = t.id;
      b.appendChild(h('span', 'key', '[' + t.key + ']'));
      b.appendChild(document.createTextNode(t.label));
      b.addEventListener('click', function () { ui.tab = t.id; renderAll(); });
      bar.appendChild(b);
    });
    // 连接/断开命令
    var conn = h('button', 'cmd primary', '[C] 连接设备');
    conn.id = 'connCmd';
    conn.addEventListener('click', function () { M.connect(); });
    bar.appendChild(conn);
    var disc = h('button', 'cmd danger', '[X] 断开');
    disc.id = 'discCmd';
    disc.addEventListener('click', function () { M.disconnect(); });
    bar.appendChild(disc);

    // 主题入口 + 提示
    var theme = h('button', 'cmd', '[T] 主题');
    theme.addEventListener('click', function () {
      location.href = 'switch.html?from=' + encodeURIComponent(THEME_FILE);
    });
    bar.appendChild(theme);

    var hint = h('span', null, '');
    hint.style.cssText = 'color:var(--fg-faint);font-size:11px;align-self:center;margin-left:4px';
    hint.textContent = '键盘: 1-4 面板 / C 连接 / X 断开';
    bar.appendChild(hint);
  }

  function renderTabs(st) {
    ensureTabs();
    var bar = document.getElementById('tabs');
    bar.querySelectorAll('.tab').forEach(function (b) {
      b.className = 'tab' + (b.dataset.tab === ui.tab ? ' on' : '');
    });
    var conn = document.getElementById('connCmd');
    var disc = document.getElementById('discCmd');
    if (conn && disc) {
      conn.style.display = st.connected ? 'none' : '';
      disc.style.display = st.connected ? '' : 'none';
      conn.textContent = st.connected ? '[C] 已连接' : '[C] 连接设备';
    }
  }

  /* ---------------- 通用 ---------------- */

  function panel(title, body) {
    var p = h('div', 'panel');
    var head = h('div', 'panel-head');
    head.appendChild(h('span', 'rule'));
    head.appendChild(h('span', 't', '─ ' + title + ' '));
    head.appendChild(h('span', 'rule'));
    p.appendChild(head);
    var b = h('div', 'panel-body');
    b.appendChild(typeof body === 'string' ? h('div', null, body) : body);
    p.appendChild(b);
    return p;
  }

  function kvRow(k, v, cls) {
    var r = h('div');
    r.style.display = 'contents';
    r.appendChild(h('span', 'k', k));
    var vEl = h('span', 'v' + (cls ? ' ' + cls : ''), v);
    r.appendChild(vEl);
    return r;
  }
  /* 流向文本：▶ 放电 / ◀ 充电 / — 静置 + 预计时长 */
  function flowText(st) {
    if (!st.hasData) return '--';
    var d = st.device;
    var charging = d.current < 0 || (!d.power && d.battState === '充电');
    var idle = !d.power;
    var dir = idle ? '— 静置' : charging ? '◀ 充电器' : '▶ 负载';
    var eta = charging ? d.remainChargeText : d.remainDischargeText;
    return dir + ' · ' + d.power + ' W' +
      (!idle && eta && eta !== '--' ? ' · ' + (charging ? '充满' : '可用') + '约 ' + eta : '');
  }

  /* 盲文点阵迷你趋势：每字符 2 列 × 4 行，40 拍 → 20 字符 */
  function brailleSeries(vals) {
    if (!vals || vals.length < 2) return '';
    var L = [0x01, 0x02, 0x04, 0x10], R = [0x08, 0x20, 0x40, 0x80];
    var out = '';
    for (var i = 0; i < vals.length; i += 2) {
      var bits = 0;
      for (var k = 0; k < 2; k++) {
        var v = vals[i + k];
        if (v === undefined) continue;
        var cl = Math.max(0, Math.min(1, v));
        var row = Math.min(3, Math.floor((1 - cl) * 4));
        bits |= (k === 0 ? L : R)[row];
      }
      out += String.fromCharCode(0x2800 + bits);
    }
    return out;
  }


  function sysmsg(text, bad) {
    return h('div', 'sysmsg' + (bad ? ' bad' : ''), text);
  }

  function empty(text) { return h('div', 'empty', text); }

  /* ---------------- 总览 ---------------- */

  function renderDashboard(st) {
    var wrap = h('div');

    if (!M.supportsWebBluetooth()) {
      wrap.appendChild(sysmsg('当前环境不支持 Web Bluetooth。需要安卓 Chrome 或桌面 Chrome/Edge，且通过 https/localhost 打开。', true));
      wrap.appendChild(panel('说明', (function () {
        var kv = h('div', 'kv');
        kv.appendChild(kvRow('支持', 'Chrome / Edge'));
        kv.appendChild(kvRow('不支持', 'Safari / Firefox / 微信内置浏览器'));
        kv.appendChild(kvRow('协议要求', 'https 或 localhost'));
        kv.appendChild(kvRow('替代方案', 'Android 版（支持后台监控与通知）'));
        return kv;
      })()));
      return wrap;
    }

    if (!st.connected && !st.hasData) {
      var b = h('button', 'cmd primary', '[ 回车 ] 连接设备');
      b.addEventListener('click', function () { M.connect(); });
      wrap.appendChild(panel('连接', (function () {
        var d = h('div');
        d.appendChild(h('div', null, st.scanning ? '正在等待浏览器设备选择器…' :
          '按回车或点击下方命令，浏览器会弹出设备选择器。'));
        d.appendChild(b);
        var saved = (st.devices || []).filter(function (x) { return x.saved; })[0];
        if (saved) {
          d.appendChild(h('div', null, ''));
          d.appendChild(sysmsg('上次连接：' + saved.name + '（' + saved.familyLabel + '）。浏览器要求每次打开都重新选择一次设备。'));
        }
        d.appendChild(sysmsg('网页版限制：关掉页面即断开，无法后台监控，不发通知。'));
        return d;
      })()));
      return wrap;
    }

    if (!st.connected && st.hasData) {
      // 断开≠预览（docs/15.4）：previewActive 才是示例数据
      wrap.appendChild(sysmsg(st.previewActive ? '界面预览：以下是内置示例数据，不是真机读数。'
        : '已断开：显示断开前的最后读数。'));
    }
    if (st.hint && !st.connected) wrap.appendChild(sysmsg(st.hint));
    if (st.stalled) wrap.appendChild(sysmsg('失联：连续多拍未收到实时帧，正在恢复…', true));

    // 实时读数（ASCII 表格风）
    var d = st.device;
    var live = h('div', 'kv');
    live.appendChild(kvRow('SOC', (d.hasData ? d.soc : '--') + ' %'));
    live.appendChild(kvRow('剩余容量', d.hasData ? num(d.remainCapAh, 1) + ' / ' + num(d.totalCapAh, 1) + ' Ah' : '--'));
    live.appendChild(kvRow('SOH / 循环', d.hasData ? d.soh + '% / ' + d.cycles + ' 次' : '--'));
    live.appendChild(kvRow('总电压', reading(d, d.totalVoltage, d.voltageDecimals) + ' V'));
    live.appendChild(kvRow('电流', reading(d, d.current, 2) + ' A'));
    live.appendChild(kvRow('功率', d.hasData ? d.power + ' W' : '--'));
    live.appendChild(kvRow('流向', flowText(st)));
    live.appendChild(kvRow('压差', d.hasData ? d.deltaCell + ' V' : '--'));
    live.appendChild(kvRow('充电剩余', d.remainChargeText));
    live.appendChild(kvRow('放电剩余', d.remainDischargeText));
    live.appendChild(kvRow('本次充电', d.thisChargeText));
    live.appendChild(kvRow('距上次充电', d.lastChargeGapText));
    wrap.appendChild(panel('实时读数', live));

    // MOS / 均衡
    if (st.caps.mos || st.caps.balance) {
      var mos = h('div', 'kv');
      if (st.caps.mos) {
        mos.appendChild(kvRow('充电 MOS', d.chMos, d.chMosReason ? 'warn' : (d.chMos === '开启' ? '' : '')));
        if (d.chMosReason) mos.appendChild(kvRow('  断开原因', d.chMosReason, 'warn'));
        mos.appendChild(kvRow('放电 MOS', d.disMos, d.disMosReason ? 'warn' : ''));
        if (d.disMosReason) mos.appendChild(kvRow('  断开原因', d.disMosReason, 'warn'));
      }
      if (st.caps.balance) {
        mos.appendChild(kvRow('均衡', d.hasData ? (d.balanceReason ? d.balance + '（' + d.balanceReason + '）' : d.balance) : '--',
          d.balance === '均衡中' ? '' : ''));
      }
      wrap.appendChild(panel('MOS / 均衡', mos));
    }

    if (st.caps.perCellVoltage && st.cells.length) {
      var d2 = st.device;
      var cells = h('div', 'cells');
      st.cells.forEach(function (c) {
        var cls = 'cell' + (c.missing ? ' missing' : '') +
          (!c.missing && c.isMax ? ' max' : '') + (!c.missing && c.isMin ? ' min' : '') +
          (c.balancing ? ' bal' : '');
        var cell = h('div', cls);
        cell.appendChild(h('span', 'idx', c.index));
        cell.appendChild(h('div', 'v', c.missing ? '--' : num(c.volt, 3)));
        cells.appendChild(cell);
      });
      var info = h('div', null, '');
      info.style.cssText = 'margin-top:8px;color:var(--fg-faint);font-size:11.5px';
      info.textContent = 'MAX ' + d2.maxCell + ' · MIN ' + d2.minCell + ' · AVG ' + d2.avgCell +
        ' · Δ' + d2.deltaCell + ' · ' + st.cells.filter(function (c) { return !c.missing; }).length + ' 串';
      var body = h('div');
      body.appendChild(cells);
      body.appendChild(info);
      wrap.appendChild(panel('单体电压', body));
    }

    if ((st.caps.multiTemp || st.temps.length) && st.temps.length) {
      var t = h('div', 'kv');
      st.temps.forEach(function (x) {
        t.appendChild(kvRow(x.name, num(x.value, 1) + ' °C', x.value >= 55 ? 'warn' : ''));
      });
      wrap.appendChild(panel('温度', t));
    }

    // 趋势：盲文点阵（每个字符 2×4 点，一行装下 40 拍）
    var tr = st.trend || {};
    var cur = tr.current || [], volt = tr.volt || [];
    var nPts = Math.max(cur.length, volt.length);
    if (nPts > 1) {
      var tb = h('div');
      var s1 = h('div', 'spark', brailleSeries(cur));
      s1.title = '电流（最近 ' + cur.length + ' 拍，归一化）';
      tb.appendChild(s1);
      var s2 = h('div', 'spark dim', brailleSeries(volt));
      s2.title = '电压（最近 ' + volt.length + ' 拍，归一化）';
      tb.appendChild(s2);
      tb.appendChild(h('div', 'hint-line', '上 电流 / 下 电压 · 最近 ' + nPts + ' 拍'));
      wrap.appendChild(panel('趋势', tb));
    }

    if (st.caps.protectAlarm) {
      var f = h('div');
      var lines = [];
      st.flags.protect.forEach(function (x) { lines.push(['PROTECT', x]); });
      st.flags.alarm.forEach(function (x) { lines.push(['ALARM', x]); });
      if (!lines.length) {
        f.appendChild(h('div', null, '无保护动作 / 无告警'));
      } else {
        var kv = h('div', 'kv');
        lines.forEach(function (x) { kv.appendChild(kvRow(x[0], x[1], 'bad')); });
        f.appendChild(kv);
      }
      if (st.flags.states.length) {
        f.appendChild(h('div', null, ''));
        f.appendChild(h('div', null, '状态: ' + st.flags.states.join(' | ')));
      }
      wrap.appendChild(panel('保护与告警', f));
    }

    if (st.relay.active) {
      var r = h('div', 'kv');
      r.appendChild(kvRow('中继品牌', st.relay.brand));
      r.appendChild(kvRow('保护板名', st.relay.name));
      r.appendChild(kvRow('保护板MAC', st.relay.mac));
      wrap.appendChild(panel('中继信息', r));
    }

    // 终端主题的偏好：实时日志常驻总览页底部（这是"串口工具"的观感核心）
    wrap.appendChild(panel('日志（实时）', logBox(12)));
    return wrap;
  }

  function reading(d, v, digits) { return d.hasData ? num(v, digits) : '--'; }

  function logBox(limit) {
    var box = h('div', 'log');
    var lines = ui.logLines;
    if (!lines.length) {
      box.appendChild(h('div', 'd', '(日志为空)'));
    } else {
      lines.slice(-(limit || 300)).forEach(function (line) {
        var cls = '';
        if (line.indexOf(' E ') >= 0) cls = 'e';
        else if (line.indexOf(' W ') >= 0) cls = 'w';
        else if (line.indexOf(' D ') >= 0) cls = 'd';
        box.appendChild(h('div', cls, line));
      });
    }
    setTimeout(function () { box.scrollTop = box.scrollHeight; }, 0);
    return box;
  }

  /* ---------------- 参数 ---------------- */

  function renderParams(st) {
    var wrap = h('div');
    if (st.params.reading) wrap.appendChild(sysmsg('正在读取参数区…（200+ 项，通常几秒）'));
    if (st.params.fromCache) wrap.appendChild(sysmsg('显示的是缓存参数（非实时）。', true));

    var groups = (ui.meta || {}).paramGroups || [];
    var live = st.connected;
    if (!groups.length || !Object.keys(st.params.values || {}).length) {
      wrap.appendChild(empty(live ? '等待设备回读参数区…' : '未连接。连接后可查看与修改参数。'));
      return wrap;
    }

    var canWrite = live && st.permission > 0;
    if (!live) wrap.appendChild(sysmsg('离线查看：显示缓存或快照里的参数，不能修改。'));
    else if (!canWrite) wrap.appendChild(sysmsg('当前为只读（0 级）。[4] 设置 → 权限 可输入密码。'));

    groups.forEach(function (g) {
      var kv = h('div', 'kv');
      g.defs.forEach(function (def) {
        var vEl = kv.appendChild ? null : null;
        var row = h('div');
        row.style.display = 'contents';
        var k = h('span', 'k', def.name);
        var v = h('span', 'v' + (def.display === '--' ? ' dim' : ''),
          def.display + (def.display !== '--' && def.unit ? ' ' + def.unit : ''));
        row.appendChild(k);
        row.appendChild(v);
        if (!def.readOnly && canWrite) {
          row.style.cursor = 'pointer';
          row.addEventListener('click', function () { editParam(def); });
        }
        kv.appendChild(row);
      });
      wrap.appendChild(panel(g.name + ' (' + g.defs.length + ')', kv));
    });

    if (live) {
      var cmds = h('div', 'cmds');
      var rb = h('button', 'cmd', '[ 重新读取 ]');
      rb.addEventListener('click', function () { toast('正在重新读取参数…'); M.refreshParams(); });
      cmds.appendChild(rb);
      var sb = h('button', 'cmd primary', '[ 保存全部参数 ]');
      if (!canWrite) sb.disabled = true;
      sb.addEventListener('click', async function () {
        toast('正在保存全部参数…');
        var r = await M.saveAllParams();
        toast(r.ok ? '参数已保存到设备' : '保存失败：' + r.text);
      });
      cmds.appendChild(sb);
      wrap.appendChild(panel('操作', cmds));
    }
    return wrap;
  }

  function editParam(def) {
    dialog('修改参数 ' + def.name, function (body, close) {
      body.appendChild(h('div', null, '写入后会回读确认；设备侧还需「保存参数」才持久化。'));

      body.appendChild(h('label', null, def.name + '（' + def.unit + '）· 范围 ' + def.rangeText));
      var input = h('input');
      input.type = 'number';
      input.step = 'any';
      input.value = def.display === '--' ? '' : def.display;
      body.appendChild(input);

      var err = h('div', 'sysmsg bad');
      err.style.display = 'none';
      body.appendChild(err);

      var cmds = h('div', 'cmds');
      var cancel = h('button', 'cmd', '[ 取消 ]');
      cancel.addEventListener('click', close);
      cmds.appendChild(cancel);
      var ok = h('button', 'cmd primary', '[ 写入 ]');
      ok.addEventListener('click', async function () {
        var v = parseFloat(input.value.replace('，', '.'));
        if (!isFinite(v) || v < def.min - 1e-9 || v > def.max + 1e-9) {
          err.textContent = '非法数值（' + def.rangeText + '）';
          err.style.display = '';
          return;
        }
        close();
        var raw = Math.round(v * def.scale);
        var r = await M.writeParam(def.addr, raw);
        toast(def.name + (r.ok ? ' 已写入' : ' 写入失败：' + r.text));
      });
      cmds.appendChild(ok);
      body.appendChild(cmds);
    });
  }

  /* ---------------- 控制 ---------------- */

  function renderControl(st) {
    var wrap = h('div');
    if (!st.connected) {
      wrap.appendChild(empty('未连接。连接保护板后可下发控制命令。'));
      return wrap;
    }
    var canWrite = st.permission >= 3; // docs/09 §9.1：3 级及以上才可写
    if (!canWrite) wrap.appendChild(sysmsg('当前为只读（0 级），控制命令不可用。', true));

    var meta = ui.meta || {};
    var cmds = meta.commands || [];
    var toggles = cmds.filter(function (c) { return c.toggle; });
    var others = cmds.filter(function (c) { return !c.toggle && !c.dangerous; });
    var dangerous = cmds.filter(function (c) { return c.dangerous; });

    function cmdRow(list) {
      var row = h('div', 'cmds');
      list.forEach(function (c) {
        var b = h('button', 'cmd', '[ ' + c.name + ' ]');
        if (!canWrite) b.disabled = true;
        b.addEventListener('click', async function () {
          var r = await M.control(c.cmd);
          toast(c.name + (r.ok ? ' 已执行' : ' 失败：' + r.text));
        });
        row.appendChild(b);
      });
      return row;
    }

    if (toggles.length) wrap.appendChild(panel('开关', cmdRow(toggles)));
    if (others.length) wrap.appendChild(panel('维护', cmdRow(others)));

    if (dangerous.length) {
      var list = h('div', 'kv');
      dangerous.forEach(function (c) {
        var row = h('div');
        row.style.display = 'contents';
        var k = h('span', 'k', c.name + (c.warning ? ' — ' + c.warning : ''));
        row.appendChild(k);
        var v = h('span', 'v warn', '');
        var b = h('button', 'cmd danger', '[ 执行 ]');
        if (!canWrite) b.disabled = true;
        b.addEventListener('click', function () { confirmCommand(c); });
        v.appendChild(b);
        row.appendChild(v);
        list.appendChild(row);
      });
      wrap.appendChild(panel('危险操作', list));
    }
    return wrap;
  }

  function confirmCommand(c) {
    dialog('确认: ' + c.name, function (body, close) {
      body.appendChild(h('div', null, '这条命令会改变设备状态，请确认对象正确。'));
      if (c.warning) body.appendChild(sysmsg(c.warning, true));

      var input = null;
      if (c.typedConfirm) {
        body.appendChild(h('label', null, '此操作不可撤销，请输入「确认」继续'));
        input = h('input');
        input.placeholder = '确认';
        body.appendChild(input);
      }

      var cmds = h('div', 'cmds');
      var cancel = h('button', 'cmd', '[ 取消 ]');
      cancel.addEventListener('click', close);
      cmds.appendChild(cancel);
      var ok = h('button', 'cmd danger', '[ 确认执行 ]');
      ok.addEventListener('click', async function () {
        if (c.typedConfirm && input.value.trim() !== '确认') { toast('请先输入「确认」'); return; }
        close();
        var r = await M.control(c.cmd);
        toast(c.name + (r.ok ? ' 已执行' : ' 失败：' + r.text));
      });
      cmds.appendChild(ok);
      body.appendChild(cmds);
    });
  }

  /* ---------------- 设置 ---------------- */

  function renderSettings(st) {
    if (ui.settingsPage === 'auth') return renderAuth(st);
    if (ui.settingsPage === 'devices') return renderDevices(st);
    if (ui.settingsPage === 'log') return renderLogPage(st);
    if (ui.settingsPage === 'about') return renderAbout(st);
    if (ui.settingsPage === 'channels') return renderChannels(st);
    return renderSettingsRoot(st);
  }

  function backLink(title) {
    var row = h('div');
    row.style.cssText = 'display:flex;align-items:center;gap:10px;margin-bottom:6px';
    var b = h('button', 'cmd', '[ ← 返回 ]');
    b.addEventListener('click', function () { ui.settingsPage = 'root'; renderAll(); });
    row.appendChild(b);
    row.appendChild(h('span', null, title));
    return row;
  }

  function renderSettingsRoot(st) {
    var wrap = h('div');

    var conn = h('div', 'kv');
    conn.appendChild(kvRow('状态', st.connected ? '已连接 · ' + st.deviceName : '未连接',
      st.connected ? '' : 'dim'));
    if (st.connected) conn.appendChild(kvRow('通道', st.channelLabel || '?'));
    conn.appendChild(kvRow('家族', st.familyLabel));
    wrap.appendChild(panel('连接', conn));

    var cmds = h('div', 'cmds');
    var cb = h('button', 'cmd primary', st.connected ? '[ 断开 ]' : '[ 连接设备 ]');
    cb.addEventListener('click', function () { if (st.connected) M.disconnect(); else M.connect(); });
    cmds.appendChild(cb);
    var th = h('button', 'cmd', '[ 切换主题 ]');
    th.addEventListener('click', function () {
      location.href = 'switch.html?from=' + encodeURIComponent(THEME_FILE);
    });
    cmds.appendChild(th);
    wrap.appendChild(panel('命令', cmds));

    // 轮询
    var poll = h('div', 'kv');
    [900, 600, 300].forEach(function (ms) {
      var row = h('div');
      row.style.display = 'contents';
      var k = h('span', 'k', ms === 900 ? '更稳' : ms === 300 ? '更跟手' : '默认');
      row.appendChild(k);
      var v = h('span', 'v' + (st.settings.pollInterval === ms ? '' : ' dim'), String(ms) + ' ms');
      v.style.cursor = 'pointer';
      v.addEventListener('click', function () {
        M.setPollInterval(ms); toast('轮询频率已设为 ' + ms + ' ms'); renderAll();
      });
      row.appendChild(v);
      poll.appendChild(row);
    });
    wrap.appendChild(panel('轮询频率', poll));

    wrap.appendChild(panel('更多', (function () {
      var kv = h('div', 'kv');
      function add(k, v, fn) {
        var row = h('div');
        row.style.display = 'contents';
        row.appendChild(h('span', 'k', k));
        var vEl = h('span', 'v', v);
        if (fn) {
          vEl.style.cursor = 'pointer';
          vEl.addEventListener('click', fn);
        }
        row.appendChild(vEl);
        kv.appendChild(row);
      }
      add('权限', st.connected ? (st.permission > 0 ? st.permission + ' 级' : '只读') : '未连接',
        function () { ui.settingsPage = 'auth'; renderAll(); });
      if (st.channels.length) add('通信通道', st.channelLabel || '自动',
        function () { ui.settingsPage = 'channels'; renderAll(); });
      add('历史设备', (st.devices || []).length + ' 台',
        function () { ui.settingsPage = 'devices'; renderAll(); });
      add('运行日志', ui.logLines.length + ' 条',
        function () { ui.settingsPage = 'log'; renderAll(); });
      add('关于', 'v' + ((st.version || {}).name || ''),
        function () { ui.settingsPage = 'about'; renderAll(); });
      return kv;
    })()));

    wrap.appendChild(sysmsg('网页版限制：关掉页面即断开；无后台监控与通知；每次打开需重新选择设备；配置存于浏览器 localStorage。'));
    return wrap;
  }

  function renderAuth(st) {
    var wrap = h('div');
    wrap.appendChild(backLink('权限'));
    if (!st.connected) wrap.appendChild(sysmsg('未连接：可以先保存密码，连上后会自动升权。'));

    var list = h('div', 'kv');
    for (var lv = 1; lv <= 9; lv++) { // 9 级=管理员，App 同款入口
      (function (level) {
        var has = st.passwords.indexOf(level) >= 0;
        var isCurrent = st.connected && st.permission === level;
        var row = h('div');
        row.style.display = 'contents';
        row.appendChild(h('span', 'k', level + ' 级' + (isCurrent ? ' · 当前' : '') + ' — ' + (has ? '已记住' : '未设置')));
        var v = h('span', 'v', '');
        var b = h('button', 'cmd', has ? '[ 更换 ]' : '[ 设置 ]');
        b.addEventListener('click', function () { askPassword(level, st); });
        v.appendChild(b);
        if (has) {
          var d = h('button', 'cmd danger', '[ 清除 ]');
          d.addEventListener('click', function () {
            M.forgetPassword(level); toast('已清除 ' + level + ' 级密码'); renderAll();
          });
          v.appendChild(d);
        }
        row.appendChild(v);
        list.appendChild(row);
      })(lv);
    }
    wrap.appendChild(panel('密码', list));

    var target = h('div', 'kv');
    [0, 1, 2, 3, 4, 5, 6, 7, 8, 9].forEach(function (v) {
      var row = h('div');
      row.style.display = 'contents';
      row.appendChild(h('span', 'k', v === 0 ? '自动（最高已存）' : v + ' 级'));
      var vEl = h('span', 'v' + (st.settings.autoUpgradeTarget === v ? '' : ' dim'),
        st.settings.autoUpgradeTarget === v ? '← 当前' : '选择');
      vEl.style.cursor = 'pointer';
      vEl.addEventListener('click', function () { M.setAutoUpgradeTarget(v); renderAll(); });
      row.appendChild(vEl);
      target.appendChild(row);
    });
    wrap.appendChild(panel('连接后自动升到', target));

    wrap.appendChild(panel('说明', h('div', null,
      '密码保存在浏览器 localStorage（按设备分开存）。清除浏览器数据、换浏览器或换域名都会丢失。密码不会上传到任何服务器。')));
    return wrap;
  }

  function askPassword(level, st) {
    dialog(level + ' 级密码', function (body, close) {
      body.appendChild(h('div', null, '密码用于解锁参数写入与设备控制。'));
      var input = h('input');
      input.type = 'password';
      input.placeholder = '请输入 ' + level + ' 级密码';
      body.appendChild(input);

      var cmds = h('div', 'cmds');
      var cancel = h('button', 'cmd', '[ 取消 ]');
      cancel.addEventListener('click', close);
      cmds.appendChild(cancel);
      var ok = h('button', 'cmd primary', st.connected ? '[ 校验并保存 ]' : '[ 仅保存 ]');
      ok.addEventListener('click', async function () {
        var pw = input.value.trim();
        if (!pw) { toast('密码不能为空'); return; }
        close();
        if (st.connected) {
          toast('正在校验 ' + level + ' 级…');
          var r = await M.auth(level, pw);
          toast(r.ok ? level + ' 级校验通过（当前 ' + r.level + ' 级）'
            : '密码校验未通过：密码不对或设备无应答（弱信号 0x43 应答可超 2 秒），可重试。');
        } else {
          M.savePassword(level, pw);
          toast(level + ' 级密码已保存（连上后自动校验）');
        }
        renderAll();
      });
      cmds.appendChild(ok);
      body.appendChild(cmds);
    });
  }

  function renderDevices(st) {
    var wrap = h('div');
    wrap.appendChild(backLink('历史设备'));
    var devices = st.devices || [];
    if (!devices.length) {
      wrap.appendChild(empty('还没有历史设备。连接一次后会自动记在这里。'));
      return wrap;
    }
    var list = h('div', 'kv');
    devices.forEach(function (p) {
      var row = h('div');
      row.style.display = 'contents';
      row.appendChild(h('span', 'k', p.name + ' — ' + p.familyLabel + ' · ' +
        (p.passwords ? p.passwords + ' 级密码' : '无密码') + (p.saved ? ' · 上次连接' : '')));
      var v = h('span', 'v warn', '');
      var b = h('button', 'cmd danger', '[ 删除 ]');
      b.addEventListener('click', function () {
        dialog('删除 ' + p.name + '？', function (body, close) {
          body.appendChild(h('div', null, '该设备的密码、配置缓存与全部快照会一并删除，无法恢复。'));
          var cmds = h('div', 'cmds');
          var c = h('button', 'cmd', '[ 取消 ]');
          c.addEventListener('click', close);
          cmds.appendChild(c);
          var d = h('button', 'cmd danger', '[ 确认删除 ]');
          d.addEventListener('click', function () {
            M.deleteDevice(p.address); close(); toast('已删除 ' + p.name); renderAll();
          });
          cmds.appendChild(d);
          body.appendChild(cmds);
        });
      });
      v.appendChild(b);
      row.appendChild(v);
      list.appendChild(row);
    });
    wrap.appendChild(panel('共 ' + devices.length + ' 台', list));
    return wrap;
  }

  function renderChannels(st) {
    var wrap = h('div');
    wrap.appendChild(backLink('通信通道'));
    var list = h('div', 'kv');
    st.channels.forEach(function (ch) {
      var isCurrent = st.channelLabel === ch.label;
      var row = h('div');
      row.style.display = 'contents';
      row.appendChild(h('span', 'k', ch.label + (isCurrent ? ' · 当前' : '') + ' — ' +
        (ch.warn || (ch.id === 'ffe1' ? ch.tag + ' · 支持固件升级' : ch.tag + ' · 备用通道（不支持固件升级）'))));
      var v = h('span', 'v', '');
      if (!isCurrent) {
        var b = h('button', 'cmd', '[ 切换 ]');
        b.addEventListener('click', function () {
          toast('正在切换到' + ch.label + '…');
          M.switchChannel(ch.id);
        });
        v.appendChild(b);
      }
      row.appendChild(v);
      list.appendChild(row);
    });
    wrap.appendChild(panel('可用通道', list));
    wrap.appendChild(panel('说明', h('div', null,
      '只有暴露可写 FFF5 特征的硬件才有备用通道。备用通道会占用蓝牙屏/充电器那一路，' +
      '使用期间对应外设无法与保护板通信，且不支持固件升级。')));
    return wrap;
  }

  function renderLogPage(st) {
    var wrap = h('div');
    wrap.appendChild(backLink('运行日志'));

    var box = logBox(300);
    var body = h('div');
    body.appendChild(box);
    var cmds = h('div', 'cmds');
    var copy = h('button', 'cmd', '[ 复制全部 ]');
    copy.addEventListener('click', async function () {
      try { await navigator.clipboard.writeText(M.logText()); toast('已复制到剪贴板'); }
      catch (e) { toast('复制失败（浏览器拒绝访问剪贴板）'); }
    });
    cmds.appendChild(copy);
    var clr = h('button', 'cmd danger', '[ 清空 ]');
    clr.addEventListener('click', function () { M.clearLog(); ui.logLines = []; toast('日志已清空'); renderAll(); });
    cmds.appendChild(clr);
    body.appendChild(cmds);
    wrap.appendChild(panel('日志 ' + ui.logLines.length + ' 条', body));

    wrap.appendChild(panel('说明', h('div', null,
      '网页版不把日志写到磁盘（浏览器无此权限），关闭页面即清空；需要留档请点「复制全部」。')));
    return wrap;
  }

  function renderAbout(st) {
    var wrap = h('div');
    wrap.appendChild(backLink('关于'));
    var v = st.version || {};

    var info = h('div', 'kv');
    info.appendChild(kvRow('版本', 'v' + (v.name || '--')));
    info.appendChild(kvRow('运行平台', '网页（终端 HUD 布局）'));
    info.appendChild(kvRow('界面框架', '手写等宽 CSS（无框架）'));
    info.appendChild(kvRow('设备连接', 'Web Bluetooth'));
    wrap.appendChild(panel('版本信息', info));

    var cmds = h('div', 'cmds');
    var repo = h('button', 'cmd', '[ 项目主页 ]');
    repo.addEventListener('click', function () { window.open('https://github.com/lswlc33/maibms', '_blank'); });
    cmds.appendChild(repo);
    var dl = h('button', 'cmd', '[ 下载 Android 版 ]');
    dl.addEventListener('click', function () { window.open('https://github.com/lswlc33/maibms/releases/latest', '_blank'); });
    cmds.appendChild(dl);
    wrap.appendChild(panel('命令', cmds));

    wrap.appendChild(panel('为什么没有「检查更新」', h('div', null,
      '网页版没有安装过程，每次打开加载的都是最新构建，所以不存在「需要更新」的状态。' +
      '想要推送通知与后台监控，请使用 Android 版。')));
    return wrap;
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
        return null; // 总览实时（HUD + 日志）
    }
  }

  function renderAll() {
    var st = ui.state || {};
    renderHud(st);
    renderTabs(st);

    var key = renderKey(st);
    if (key !== null && key === ui.lastRenderKey) return;
    ui.lastRenderKey = key;

    var content = document.getElementById('content');
    if (!content) return;
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
  }

  /* ---------------- 键盘快捷键 ---------------- */

  function bindKeys() {
    document.addEventListener('keydown', function (e) {
      // 输入框聚焦时不拦截
      var t = e.target && e.target.tagName;
      if (t === 'INPUT' || t === 'SELECT' || t === 'TEXTAREA') return;
      var st = ui.state || {};
      switch (e.key) {
        case '1': ui.tab = 'dash'; renderAll(); break;
        case '2': ui.tab = 'params'; renderAll(); break;
        case '3': ui.tab = 'control'; renderAll(); break;
        case '4': ui.tab = 'settings'; renderAll(); break;
        case 'c': case 'C':
          if (!st.connected) M.connect(); break;
        case 'x': case 'X':
          if (st.connected) M.disconnect(); break;
        case 'Enter':
          if (!st.connected) M.connect(); break;
      }
    });
  }

  /* ---------------- 启动 ---------------- */

  if (!M) {
    document.body.innerHTML = '<div style="padding:16px;font-family:monospace">' +
      '[ERR] 数据层加载失败。请确认 ../maibms-data.js 可访问（用 http(s) 打开，勿双击文件）。</div>';
    return;
  }

  M.onState(function (s) {
    ui.state = s;
    renderAll();
  });

  M.onLog(function (lines) {
    ui.logLines = Array.prototype.slice.call(lines);
    // HUD 主题：总览页底部常驻日志，所以日志一变就重画总览
    if (ui.tab === 'dash') ui.lastRenderKey = null;
    if (ui.tab === 'settings' && ui.settingsPage === 'log') renderAll();
  });

  ui.state = M.state || {};
  ui.meta = M.meta();
  renderHud(ui.state);
  renderTabs(ui.state);
  renderAll();
  bindKeys();

  window.addEventListener('maibms-ready', function () {
    ui.meta = M.meta();
    renderAll();
  });
})();
