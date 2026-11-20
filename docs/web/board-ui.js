/* 彦阳/JBD/极空共用只读页面：三个主题使用同一份字段显隐规则。 */
(function () {
  'use strict';
  var scanFamily = null;
  function el(tag, text) {
    var node = document.createElement(tag);
    if (text != null) node.textContent = String(text);
    return node;
  }
  function supported(st) { return ['YanYang', 'Jbd', 'Jikong'].indexOf(st.family) >= 0; }
  function has(st, field, capability) {
    return !!st.hasData && (!capability || (st.caps || {})[capability] !== false) &&
      (st.availableFields == null || st.availableFields.indexOf(field) >= 0);
  }
  function text(value) { return value != null && String(value).trim() !== '' && value !== '--'; }
  function number(value, digits) { return Number(value).toFixed(digits == null ? 2 : digits); }
  function button(label, action) {
    var b = el('button', label); b.type = 'button'; b.className = 'board-button';
    b.addEventListener('click', action); return b;
  }
  function card(title) {
    var c = el('section'); c.className = 'board-card';
    if (title) c.appendChild(el('h3', title)); return c;
  }
  function row(parent, label, value) {
    if (!text(value)) return;
    var r = el('div'); r.className = 'board-row';
    r.appendChild(el('span', label)); r.appendChild(el('strong', value)); parent.appendChild(r);
  }
  function note(parent, message) {
    if (!message) return;
    var p = el('p', message); p.className = 'board-note'; parent.appendChild(p);
  }
  function styles() {
    if (document.getElementById('board-ui-style')) return;
    var s = el('style'); s.id = 'board-ui-style';
    s.textContent = '.board-card{padding:16px;border-radius:16px;background:var(--card,var(--surface,rgba(127,127,127,.09)));color:inherit;margin:0 0 12px;box-sizing:border-box;min-width:0}.board-card h3{font-size:14px;margin:0 0 10px}.board-row{display:flex;justify-content:space-between;gap:16px;align-items:baseline;padding:7px 0;font-size:13px}.board-row strong{text-align:right;overflow-wrap:anywhere;font-variant-numeric:tabular-nums}.board-note{font-size:12px;line-height:1.7;opacity:.8;margin:8px 0}.board-button{font:inherit;color:inherit;background:transparent;border:1px solid currentColor;border-radius:10px;padding:9px 12px;margin:4px 8px 4px 0;cursor:pointer}.board-cells{display:grid;grid-template-columns:repeat(auto-fit,minmax(68px,1fr));gap:7px}.board-cell{padding:10px 6px;border-radius:10px;background:rgba(127,127,127,.1);text-align:center;position:relative;font-variant-numeric:tabular-nums}.board-cell small{display:block;opacity:.65;margin-bottom:3px}.board-cell.max{color:#b97c22}.board-cell.min{color:#458bdf}.board-cell.bal::after{content:"";position:absolute;right:6px;top:6px;width:5px;height:5px;border-radius:50%;background:#58ca76}.board-access{position:fixed;inset:0;z-index:9999;background:rgba(0,0,0,.5);display:grid;place-items:center;padding:20px}.board-access form{max-width:420px;width:100%;max-height:85vh;overflow:auto;background:var(--card,var(--surface,#202225));color:inherit;border-radius:18px;padding:20px;box-sizing:border-box}.board-access input,.board-access select{display:block;box-sizing:border-box;width:100%;font:inherit;padding:10px;margin:6px 0 14px;border-radius:8px;border:1px solid #888;background:transparent;color:inherit}';
    document.head.appendChild(s);
  }
  function overlay(title, build) {
    styles();
    var mask = el('div'); mask.className = 'board-access';
    var form = el('form'); form.appendChild(el('h3', title));
    form.addEventListener('submit', function (e) { e.preventDefault(); });
    function close() { mask.remove(); }
    build(form, close); mask.appendChild(form); document.body.appendChild(mask);
  }
  function chooseConnection() {
    overlay('选择保护板种类', function (form, close) {
      note(form, '广播名被改过时请手动选择。板型会跟随设备记住，后续重连沿用。');
      var select = el('select'); select.setAttribute('aria-label', '保护板种类');
      [['', '自动识别'], ['YanYang', '彦阳 YYBMS'], ['Jbd', '嘉佰达 JBD'], ['Jikong', '极空 JK'], ['Ant', '蚂蚁 ANT'],
        ['LanBao', '蓝宝电量计'], ['LuXing', '陆行电量计']].forEach(function (pair) {
        var option = el('option', pair[1]); option.value = pair[0]; select.appendChild(option);
      });
      select.value = scanFamily || ''; form.appendChild(select);
      form.appendChild(button('选择设备并连接', function () {
        scanFamily = select.value || null; close(); window.Maibms.connect(scanFamily);
      }));
      form.appendChild(button('关闭', close));
    });
  }
  function access() {
    var M = window.Maibms;
    overlay('嘉佰达蓝牙访问', function (form, close) {
      var saved = M.boardAccess();
      note(form, '仅使用已有密码进行本次连接登录，不改设备密码。需要系统配对的模块，请先在系统蓝牙中配对。');
      var pw = el('input'); pw.type = 'password'; pw.maxLength = 6;
      pw.placeholder = saved.configured ? '已记住凭据；输入新值可替换' : '已有蓝牙密码（6 位字符）';
      pw.setAttribute('aria-label', '已有蓝牙密码'); form.appendChild(pw);
      var mac = el('input'); mac.value = saved.macAddress || ''; mac.placeholder = 'AA:BB:CC:DD:EE:FF';
      mac.setAttribute('aria-label', '模块真实 MAC 地址'); form.appendChild(mac);
      note(form, 'MAC 可先留空；旧挑战登录模块才需要真实地址。凭据仅保存在此浏览器。');
      var error = el('p'); form.appendChild(error);
      form.appendChild(button('登录并读取', function () {
        if (pw.value.length === 6 && M.setBoardAccess(pw.value, mac.value)) close();
        else error.textContent = '请输入 6 位 ASCII 密码；MAC 可留空，填写时需完整地址';
      }));
      form.appendChild(button('清除本机凭据', function () { M.setBoardAccess('', ''); close(); }));
      form.appendChild(button('关闭', close));
    });
  }
  function render(st, tab) {
    styles();
    var M = window.Maibms, wrap = document.createDocumentFragment(), d = st.device || {}, caps = st.caps || {};
    var header = card(st.familyLabel + ' · 只读');
    if (st.previewActive) note(header, '快照预览 · 以下为已保存的数据');
    else if (!st.connected) note(header, st.hasData ? '已断开 · 显示最后收到的读数' : '未连接');
    else if (!st.hasData) note(header, '蓝牙已连接 · 等待设备返回仪表数据…');
    if (st.readError) note(header, st.readError);
    if (st.hint && !st.connected) note(header, st.hint);
    if (st.stalled) note(header, '实时数据已中断，请检查距离、设备休眠或其他 App 的连接占用。');
    if (st.connected) header.appendChild(button('断开连接', function () { M.disconnect(); }));
    else if (!st.previewActive) header.appendChild(button('选择设备连接', chooseConnection));
    wrap.appendChild(header);
    if (tab === 'params') {
      var groups = Object.create(null);
      (st.boardSettings || []).forEach(function (item) {
        if (!text(item.value)) return;
        var group = item.group || '设备信息';
        if (!groups[group]) groups[group] = card(group);
        row(groups[group], item.name, item.value + (item.unit ? ' ' + item.unit : ''));
      });
      Object.keys(groups).forEach(function (key) { wrap.appendChild(groups[key]); });
      var controls = card('读取选项');
      if (st.connected && !st.previewActive) controls.appendChild(button('刷新只读参数', function () { M.refreshParams(); }));
      if (st.family === 'Jbd' && !st.previewActive) controls.appendChild(button('蓝牙访问', access));
      if (!Object.keys(groups).length) note(controls, '尚未收到可直接读取的参数。');
      note(controls, st.family === 'Jbd' ? '经典 JBD 的保护阈值需要进入工厂模式，本应用不读取这部分。' : '仅展示设备实际返回的参数。');
      wrap.appendChild(controls); return wrap;
    }
    if (tab === 'settings') {
      var info = card('设备信息');
      var id = (st.params || {}).identity || {};
      [['model','设备型号'],['productName','产品型号'],['swVersion','软件版本'],['hwVersion','硬件版本'],
        ['batteryType','电池类型'],['serialNo','序列号'],['cellCount','电芯串数'],['productionDate','生产日期']]
        .forEach(function (pair) { row(info, pair[1], id[pair[0]]); });
      row(info, '蓝牙名称', st.deviceName); row(info, '应用版本', (st.version || {}).name);
      note(info, '新板仅支持读取；没有参数修改、MOS 控制、校准或密码修改入口。');
      if (st.family === 'Jbd' && !st.previewActive) info.appendChild(button('蓝牙访问', access));
      if (st.channels && st.channels.length > 1 && !st.previewActive) {
        var channels = card('通信通道');
        st.channels.forEach(function (ch) {
          channels.appendChild(button(ch.label + (st.channel === ch.id ? ' · 当前' : ''), function () { M.switchChannel(ch.id); }));
        });
        note(channels, '切换后重新连接并等待有效数据；不会修改保护板设置。');
        wrap.appendChild(channels);
      }
      wrap.appendChild(info); return wrap;
    }
    var live = card('仪表数据');
    [['voltage','总电压',d.totalVoltage,'V',caps.voltageDecimals], ['current','电流',d.current,'A',2],
      ['power','功率',d.power,'W',0], ['soc','电量',d.soc,'%',0], ['remaining','剩余容量',d.remainCapAh,'Ah',1],
      ['capacity','总容量',d.totalCapAh,'Ah',1], ['cycles','循环次数',d.cycles,'次',0]]
      .forEach(function (item) { if (has(st,item[0])) row(live,item[1],number(item[2],item[4])+' '+item[3]); });
    if (caps.stateOfHealth && has(st,'soh')) row(live,'健康度',d.soh+'%');
    if (has(st,'state')) row(live,'电池状态',d.battState);
    if (st.relay && st.relay.active) {
      row(live, '中继保护板', st.relay.brand); row(live, '保护板名称', st.relay.name); row(live, '保护板地址', st.relay.mac);
    }
    if (caps.runtime && has(st,'runtime')) row(live,'运行时间',d.runtime);
    if (caps.mos && has(st,'mos')) { row(live,'充电 MOS',d.chMos); row(live,'放电 MOS',d.disMos); }
    if (caps.balance && has(st,'balance')) row(live,'均衡',d.balance);
    if (caps.extTimes && has(st,'remainingTime')) {
      if (d.current < 0 && d.remainChargeText !== '--') row(live,'充电剩余',d.remainChargeText);
      if (d.current >= 0 && d.remainDischargeText !== '--') row(live,'放电剩余',d.remainDischargeText);
    }
    if (st.hasData) wrap.appendChild(live);
    if ((st.cells || []).length) {
      var cells = card('单体电压'), grid = el('div'); grid.className = 'board-cells';
      st.cells.forEach(function (cell) {
        var node = el('div'); node.className = 'board-cell' + (cell.isMax ? ' max' : '') + (cell.isMin ? ' min' : '') + (cell.balancing ? ' bal' : '');
        node.appendChild(el('small','#'+cell.index)); node.appendChild(el('strong',cell.missing ? '--' : number(cell.volt,3)));
        grid.appendChild(node);
      }); cells.appendChild(grid);
      if (text(d.maxCell)) row(cells,'最高 / 最低',d.maxCell+' / '+d.minCell+' V');
      if (text(d.deltaCell)) row(cells,'压差',d.deltaCell+' V');
      wrap.appendChild(cells);
    } else if (text(d.maxCell) || text(d.minCell) || text(d.deltaCell)) {
      var summary = card('单体电压汇总');
      if (text(d.maxCell)) row(summary, '最高', d.maxCell + ' V');
      if (text(d.minCell)) row(summary, '最低', d.minCell + ' V');
      if (text(d.deltaCell)) row(summary, '压差', d.deltaCell + ' V');
      wrap.appendChild(summary);
    }
    if ((st.temps || []).length) {
      var temps = card('温度'); st.temps.forEach(function (t) { row(temps,t.name,number(t.value,1)+' ℃'); }); wrap.appendChild(temps);
    }
    var flags = st.flags || {};
    [['保护',flags.protect],['告警',flags.alarm],['运行状态',flags.states]].forEach(function (entry) {
      if (!entry[1] || !entry[1].length) return;
      var c = card(entry[0]); entry[1].forEach(function (label) { note(c,label); }); wrap.appendChild(c);
    });
    return wrap;
  }
  function renderKey(st, tab) {
    if (tab !== 'params' && tab !== 'settings') return null;
    return JSON.stringify([st.family, tab, st.connected, st.previewActive, st.stalled,
      st.hint, st.readError, st.channel, st.channels, st.boardSettings, (st.params || {}).identity]);
  }
  window.MaibmsBoards = { supports: supported, render: render, connect: chooseConnection,
    hasReading: has, renderKey: renderKey };
})();
