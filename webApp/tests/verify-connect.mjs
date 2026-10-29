#!/usr/bin/env node
/**
 * Black-box regression tests for the shipped Kotlin/JS data artifact, not a browser.
 * Run from any directory (Node >= 18, no dependencies):
 *   node webApp/tests/verify-connect.mjs
 *   node webApp/tests/verify-connect.mjs --bundle /absolute/path/maibms-data.js
 *   node webApp/tests/verify-connect.mjs --case LuXing
 *
 * Never patch/evaluate extracted transport functions: execute the complete bundle
 * and drive only its public Maibms API. Failures are real failures, including on
 * a stale bundle. Rebuild separately after changing Kotlin; this script does not.
 * Fixture layouts come from Frame.kt, RealtimeDecoder.kt, LuXingProtocol.kt and
 * LanBaoProtocol.kt. Meter initialFrames() is empty: their handshake is polling.
 * Meters deliberately IGNORE ANT 7E/A1/01 probes and never emit ANT replies.
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

let bundlePath = fileURLToPath(new URL('../../docs/maibms-data.js', import.meta.url));
let filter = '';
for (let i = 2; i < process.argv.length; i++) {
  const flag = process.argv[i];
  assert.ok(['--bundle', '--case'].includes(flag) && process.argv[i + 1],
    'Usage: node verify-connect.mjs [--bundle path] [--case name-substring]');
  if (flag === '--bundle') bundlePath = resolve(process.argv[++i]);
  else filter = process.argv[++i].toLowerCase();
}
const source = readFileSync(bundlePath, 'utf8');
const hostPath = fileURLToPath(new URL('../../docs/web/maibms-host.js', import.meta.url));
const hostSource = readFileSync(hostPath, 'utf8');
const uuid = (short) => `0000${short.toLowerCase()}-0000-1000-8000-00805f9b34fb`;
const FFE0 = uuid('ffe0'), FFE1 = uuid('ffe1'), FFE2 = uuid('ffe2');
const hex = (bytes) => Buffer.from(bytes).toString('hex');

function crc16(bytes, polynomial = 0xa001) {
  let crc = 0xffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ (crc & 1 ? polynomial : 0);
  }
  return crc & 0xffff;
}
function withCrc(bytes, polynomial = 0xa001, bigEndian = false) {
  const crc = crc16(bytes, polynomial);
  return Buffer.concat([Buffer.from(bytes), Buffer.from(bigEndian
    ? [crc >>> 8, crc & 255] : [crc & 255, crc >>> 8])]);
}
function antFrame(func, reg = 0, data = Buffer.alloc(0), length = 0xf5) {
  const body = Buffer.concat([Buffer.from([0xa1, func, reg & 255, reg >>> 8,
    func < 0x10 ? length : data.length]), data]);
  return Buffer.concat([Buffer.from([0x7e]), withCrc(body), Buffer.from([0xaa, 0x55])]);
}
function antRealtime() {
  // Header: temperature count at 2, cell count at 3 (not vice versa).
  const data = Buffer.alloc(28 + 4 * 2 + 2 * 2 + 78);
  data.set([5, 4, 2, 4]);
  [4200, 4190, 4180, 4210].forEach((v, i) => data.writeUInt16LE(v, 28 + i * 2));
  data.writeInt16LE(24, 36); data.writeInt16LE(25, 38);
  const base = 40;
  data.writeInt16LE(28, base); data.writeInt16LE(29, base + 2);
  data.writeUInt16LE(4800, base + 4); data.writeInt16LE(-25, base + 6);
  data.writeUInt16LE(80, base + 8); data.writeUInt16LE(100, base + 10);
  data[base + 12] = 1; data[base + 13] = 1;
  data.writeUInt32LE(100_000_000, base + 16);
  data.writeUInt32LE(80_000_000, base + 20);
  data.writeInt32LE(-120, base + 28);
  data.writeUInt16LE(4210, base + 40); data.writeUInt16LE(4, base + 42);
  data.writeUInt16LE(4180, base + 44); data.writeUInt16LE(3, base + 46);
  data.writeUInt16LE(30, base + 48); data.writeUInt16LE(4195, base + 50);
  return antFrame(0x11, 0, data);
}
const ANT_PROBE = antFrame(1);
const LUXING_READS = new Map([
  [4000, { count: 89, hex: '01030fa0005986c6' }],
  [4092, { count: 54, hex: '01030ffc003606f8' }],
  [4200, { count: 45, hex: '01031068002d00cb' }],
  [4152, { count: 48, hex: '010310380030c0d3' }],
]);
function luxingResponse(start) {
  const lengths = { 4000: 178, 4092: 108, 4200: 90, 4152: 96 };
  const data = Buffer.alloc(lengths[start]);
  if (start === 4092) {
    data.set([4, 1, 1, 68, 0, 0, 0, 2, 64, 65]); // temperatures encoded +40
    data.writeInt16LE(-120, 20);
    data.writeUInt32LE(48000, 22); data.writeInt32LE(-250, 26);
    [4200, 4190, 4180, 4210].forEach((v, i) => data.writeUInt16LE(v, 30 + 2 * i));
    data.writeUInt16LE(4210, 92); data.writeUInt16LE(4180, 94);
    data.writeUInt16LE(1000, 102); data.writeUInt16LE(1000, 104);
    data[106] = 80; data[107] = 100;
  } else if (start === 4200) {
    data[0] = data[1] = 1;
    data.writeUInt32LE(99000, 18); // relay voltage must NOT overwrite meter's 48 V
    data[50] = 2; data.write('ANT-RELAY', 51, 'ascii');
    data.set([0xaa, 0xbb, 0xcc, 0x11, 0x22, 0x33], 84);
  } else if (start === 4152) data.writeUInt16LE(42, 0);
  return withCrc(Buffer.concat([Buffer.from([1, 3, data.length]), data]));
}
function lanbaoRead(table) {
  return Buffer.concat([withCrc(Buffer.from([0xfa, 0x50, 0x28, 0xa0, 0x15, 0, 9,
    6, 6, 6, 6, 6, 6, table, 0, table === 1 ? 111 : 134]), 49960, true),
  Buffer.from([0xe5])]);
}
function lanbaoResponse(table) {
  const data = Buffer.alloc(table === 1 ? 111 : 134);
  if (table === 1) {
    data.writeUInt16BE(4800, 0); data.writeInt32BE(-250, 2);
    data.writeUInt16BE(800, 6); data.writeInt8(24, 14);
    data.writeUInt32BE(8_000_000, 15); data.writeInt32BE(-120, 19);
    data[50] = 1; data.set([0xaa, 0xbb, 0xcc, 0x11, 0x22, 0x33], 51);
    data.write('ANT-RELAY', 57, 'ascii');
    data.writeUInt16BE(4210, 82); data.writeUInt16BE(4180, 84);
    data.writeUInt16BE(30, 86);
  } else {
    data.write('LB-VM-0001', 0, 'ascii'); data.write('HW1.0', 16, 'ascii');
    data.write('SW2.0', 24, 'ascii'); data[46] = 2; data[47] = 4;
    data.writeUInt32BE(100000, 48); data.writeUInt16BE(42, 57);
  }
  // 16-byte reply header: swapped destination/source, echoed table/start/length.
  const header = Buffer.from([0xfa, 0x50, 0xa0, 0x28, 0x15, 0, data.length + 9,
    6, 6, 6, 6, 6, 6, table, 0, data.length]);
  return Buffer.concat([withCrc(Buffer.concat([header, data]), 49960, true), Buffer.from([0xe5])]);
}

class Events {
  listeners = new Map();
  addEventListener(type, fn) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type).add(fn);
  }
  removeEventListener(type, fn) { this.listeners.get(type)?.delete(fn); }
  dispatchEvent(event) {
    event.target ??= this;
    for (const fn of [...(this.listeners.get(event.type) ?? [])]) fn(event);
    return true;
  }
  count(type) { return this.listeners.get(type)?.size ?? 0; }
}

// Virtual time preserves Kotlin delay/backoff order, rather than shortening all
// timers to 0 ms. Flush native Promise jobs after EVERY callback. Fake Date.now
// uses the same clock, so stall/lifetime logic sees the elapsed delays honestly.
class Clock {
  now = 0;
  nextId = 0;
  timers = new Map();
  errors = [];
  setTimeout = (fn, ms = 0, ...args) => {
    assert.equal(typeof fn, 'function', 'VM timers must receive callbacks');
    const id = ++this.nextId;
    this.timers.set(id, { at: this.now + Math.max(0, Number(ms)), fn: () => fn(...args) });
    return id;
  };
  clearTimeout = (id) => { this.timers.delete(id); };
  async flush() { await new Promise((done) => setImmediate(done)); }
  async advance(ms, stop = () => false) {
    const end = this.now + ms;
    await this.flush();
    for (let turns = 0; !stop(); turns++) {
      assert.ok(turns < 100_000, 'VM timer loop did not quiesce');
      const next = [...this.timers].sort((a, b) => a[1].at - b[1].at || a[0] - b[0])[0];
      if (!next || next[1].at > end) {
        this.now = end; await this.flush();
        if (this.errors.length) throw this.errors[0];
        break;
      }
      this.now = next[1].at; this.timers.delete(next[0]);
      try { next[1].fn(); } catch (error) { this.errors.push(error); }
      await this.flush();
      if (this.errors.length) throw this.errors[0];
    }
  }
}

class FakeGatt {
  writes = [];
  requests = [];
  trace = [];
  violations = [];
  queued = [];
  emitted = [];
  inFlight = 0;
  maxInFlight = 0;
  writeInFlight = 0;
  connectCalls = 0;
  notificationViews = [];
  notificationAvailableAt = 0;
  constructor(clock, family, options = {}) {
    this.clock = clock; this.family = family; this.options = options;
    this.replyEnabled = family === 'Ant';
    this.device = new Events();
    this.device.id = `vm-${family}`;
    this.device.name = options.name ?? { Ant: 'ANT-B2A20', LuXing: 'EM2APP-A581', LanBao: 'BlueBabe-METER' }[family];
    const characteristic = (short, properties) => {
      const c = Object.assign(new Events(), { uuid: uuid(short), properties, value: null, notifying: false });
      c.startNotifications = async () => {
        this.failAt('notifications');
        c.notifying = true; this.trace.push({ type: 'subscribe', uuid: c.uuid }); return c;
      };
      c.stopNotifications = async () => { c.notifying = false; return c; };
      c.writeValueWithResponse = (value) => this.write(c, value, 'response');
      c.writeValueWithoutResponse = (value) => this.write(c, value, 'without-response');
      return c;
    };
    this.notify = characteristic('ffe1', { notify: true, write: family === 'Ant',
      writeWithoutResponse: family === 'Ant', read: false, indicate: false });
    this.writer = family === 'Ant' ? this.notify : characteristic('ffe2',
      { write: true, writeWithoutResponse: false, notify: false, read: false, indicate: false });
    this.service = {
      uuid: FFE0,
      getCharacteristics: async () => {
        this.failAt('characteristics');
        // Notify-only FFE1 first: a property or UUID bug must not pass by luck.
        return family === 'Ant' ? [this.notify] : [this.notify, this.writer];
      },
      getCharacteristic: async (wanted) => {
        const c = [this.notify, this.writer].find((item) => item.uuid === wanted.toLowerCase());
        assert.ok(c, `Unexpected characteristic UUID ${wanted}`); return c;
      },
    };
    this.server = {
      device: this.device, connected: false,
      connect: () => this.connect(),
      disconnect: () => this.drop(),
      getPrimaryService: async (wanted) => {
        this.check(() => assert.equal(wanted.toLowerCase(), FFE0, 'Must discover FFE0'));
        this.failAt('service'); return this.service;
      },
    };
    this.device.gatt = this.server;
    this.bluetooth = {
      getAvailability: async () => true,
      requestDevice: async (opts) => {
        this.requests.push(JSON.parse(JSON.stringify(opts)));
        this.check(() => {
          assert.ok(opts.optionalServices?.some((s) => s.toLowerCase() === FFE0), 'Chooser must authorize FFE0');
          assert.ok(opts.acceptAllDevices === true || opts.filters?.length, 'Invalid chooser options');
        });
        return this.device;
      },
    };
  }
  check(fn) {
    try { return fn(); } catch (error) { this.violations.push(error.message); throw error; }
  }
  failAt(stage) {
    if (this.options.failAt === stage) {
      const error = new Error(`fixture failure at ${stage}`);
      error.name = this.options.errorName ?? 'NotFoundError'; throw error;
    }
  }
  async connect() {
    this.connectCalls++; this.inFlight++;
    this.maxInFlight = Math.max(this.maxInFlight, this.inFlight);
    this.trace.push({ type: 'connect', at: this.clock.now });
    try {
      await new Promise((done) => this.clock.setTimeout(done, this.options.connectDelay ?? 30));
      this.failAt('connect'); this.server.connected = true; return this.server;
    } finally { this.inFlight--; }
  }
  drop() {
    if (!this.server.connected) return;
    this.server.connected = false; this.notify.notifying = false;
    this.trace.push({ type: 'drop', at: this.clock.now });
    this.device.dispatchEvent({ type: 'gattserverdisconnected' });
  }
  async write(c, value, method) {
    const bytes = this.check(() => {
      assert.ok(ArrayBuffer.isView(value) || Object.prototype.toString.call(value) === '[object ArrayBuffer]',
        'writeValue* must receive BufferSource, not a Kotlin/JS array');
      const bytes = ArrayBuffer.isView(value)
        ? Buffer.from(new Uint8Array(value.buffer, value.byteOffset, value.byteLength))
        : Buffer.from(new Uint8Array(value));
      assert.ok(bytes.length > 0 && bytes.length <= 20, 'BLE write chunks must be 1..20 bytes');
      assert.equal(c.uuid, this.family === 'Ant' ? FFE1 : FFE2, 'Wrong write characteristic UUID');
      assert.ok(this.server.connected, 'Write after GATT disconnect');
      assert.ok(this.notify.notifying, 'Subscribe before writing');
      assert.equal(this.writeInFlight, 0, 'Concurrent GATT writes');
      assert.equal(method, this.family === 'Ant' ? 'without-response' : 'response', 'Wrong write method for properties');
      return bytes;
    });
    this.writes.push({ uuid: c.uuid, bytes: hex(bytes), at: this.clock.now });
    this.trace.push({ type: 'write', bytes: hex(bytes) });
    this.writeInFlight++;
    try {
      await new Promise((done) => this.clock.setTimeout(done, 2));
      // Meter fixtures do NOT respond to 7E...01, even if production sends it.
      if (bytes[0] === 0x7e && this.family !== 'Ant') return;
      let response;
      this.check(() => {
        if (this.family === 'Ant') {
          assert.equal(bytes[0], 0x7e); assert.equal(bytes[1], 0xa1);
          assert.equal(bytes.readUInt16LE(bytes.length - 4), crc16(bytes.subarray(1, -4)), 'ANT write CRC');
          assert.equal(hex(bytes.subarray(-2)), 'aa55');
          if (bytes[2] === 1) { assert.equal(hex(bytes), hex(ANT_PROBE)); response = antRealtime(); }
          else if (bytes[2] === 2) response = antFrame(0x12, bytes.readUInt16LE(3), Buffer.alloc(bytes[5]));
        } else if (this.family === 'LuXing') {
          assert.equal(bytes.length, 8); const start = bytes.readUInt16BE(2);
          const read = LUXING_READS.get(start); assert.ok(read, `Unexpected EM02 register ${start}`);
          assert.equal(hex(bytes), read.hex, 'EM02 request/header/Modbus CRC');
          assert.equal(bytes.readUInt16BE(4), read.count); response = luxingResponse(start);
        } else {
          assert.equal(bytes.length, 19); const table = bytes[13];
          assert.ok(table === 1 || table === 2);
          assert.equal(hex(bytes), hex(lanbaoRead(table)), 'LanBao request/header/match code/private CRC');
          response = lanbaoResponse(table);
        }
      });
      if (response) {
        if (this.replyEnabled) this.send(response);
        else this.queued.push(response);
      }
    } finally { this.writeInFlight--; }
  }
  enableReplies() {
    this.replyEnabled = true;
    for (const response of this.queued.splice(0)) this.send(response);
  }
  send(bytes) {
    this.emitted.push(hex(bytes));
    // First ANT fragment contains its probe signature; meter fragments split
    // the header itself. Every notification is a DataView with nonzero offset
    // AND unrelated prefix/suffix bytes, never a whole backing ArrayBuffer.
    const sizes = this.family === 'Ant' ? [5, 1, 19, 7, 20] : [1, 1, 1, 17, 7, 20];
    let offset = 0, index = 0;
    const startAt = Math.max(this.clock.now + 5, this.notificationAvailableAt);
    while (offset < bytes.length) {
      const size = Math.min(sizes[index % sizes.length], bytes.length - offset);
      const padded = new Uint8Array(size + 11).fill(0xd7);
      padded.set(bytes.subarray(offset, offset + size), 4);
      const view = new DataView(padded.buffer, 4, size);
      this.clock.setTimeout(() => {
        if (!this.server.connected || !this.notify.notifying) return;
        this.notificationViews.push({ offset: view.byteOffset, length: view.byteLength, backing: view.buffer.byteLength });
        this.notify.value = view;
        this.notify.dispatchEvent({ type: 'characteristicvaluechanged' });
      }, startAt - this.clock.now + index * 2);
      offset += size; index++;
    }
    // GATT delivers a byte stream: consecutive response frames must not have
    // their fragments interleaved when the repository writes four requests.
    this.notificationAvailableAt = startAt + index * 2;
  }
}

let activeHarness;
const unhandled = (error) => {
  if (activeHarness) activeHarness.clock.errors.push(error instanceof Error ? error : new Error(String(error)));
  else { console.error(error); process.exitCode = 1; }
};
process.on('unhandledRejection', unhandled);
class Harness {
  states = [];
  consoleErrors = [];
  constructor(family = 'Ant', options = {}) {
    this.clock = new Clock(); this.fake = new FakeGatt(this.clock, family, options);
    const clock = this.clock, window = new Events(), storage = new Map();
    const location = { href: 'https://vm.invalid/web/index.html', search: '' };
    const localStorage = {
      getItem: (key) => storage.get(String(key)) ?? null,
      setItem: (key, value) => storage.set(String(key), String(value)),
      removeItem: (key) => storage.delete(String(key)), clear: () => storage.clear(),
    };
    class TestDate extends Date {
      constructor(...args) { super(...(args.length ? args : [1_800_000_000_000 + clock.now])); }
      static now() { return 1_800_000_000_000 + clock.now; }
    }
    Object.assign(window, { location, localStorage, setTimeout: clock.setTimeout,
      clearTimeout: clock.clearTimeout, matchMedia: () => ({ matches: false }) });
    const globals = { window, document: Object.assign(new Events(), { hidden: false }),
      navigator: { userAgent: 'Node VM artifact test', bluetooth: this.fake.bluetooth },
      location, localStorage, URLSearchParams, Date: TestDate, Error,
      setTimeout: clock.setTimeout, clearTimeout: clock.clearTimeout,
      CustomEvent: class { constructor(type) { this.type = type; } },
      console: { log() {}, warn() {}, error: (...args) => this.consoleErrors.push(args.map(String).join(' ')) },
    };
    // Kotlin's browser dispatcher uses Promise jobs and window.postMessage.
    window.postMessage = (data) => clock.setTimeout(() => window.dispatchEvent({
      type: 'message', source: window, data, stopPropagation() {},
    }), 0);
    this.context = vm.createContext(globals);
    activeHarness = this;
    vm.runInContext(hostSource, this.context, { filename: hostPath, timeout: 10_000 });
    window.MaibmsHost = this.context.MaibmsHost;
    vm.runInContext(source, this.context, { filename: bundlePath, timeout: 10_000 });
    this.api = window.Maibms;
    assert.ok(this.api?.state && typeof this.api.connect === 'function', 'Actual bundle must install Maibms');
    this.api.onState((state) => this.states.push(JSON.parse(JSON.stringify(state))));
    this.window = window;
    assert.equal(this.api.supportsWebBluetooth(), true, 'VM must use the real BLE backend');
    assert.equal(this.state.hasData, false, 'Fresh VM must not restore cached/preview data');
    assert.equal(this.state.previewActive, false, 'Tests must not use preview snapshots');
  }
  get state() { return this.api.state; }
  diagnostics() {
    return JSON.stringify({ state: { link: this.state.link, family: this.state.family,
      hasData: this.state.hasData, hint: this.state.hint, scanError: this.state.scanError },
    connects: this.fake.connectCalls, writes: this.fake.writes.slice(0, 6),
    violations: this.fake.violations, errors: this.consoleErrors,
    log: this.api.logText().split('\n').slice(-10) }, null, 2);
  }
  async until(predicate, message, ms = 12_000) {
    await this.clock.advance(ms, predicate);
    assert.ok(predicate(), `${message}\n${this.diagnostics()}`);
  }
  healthy() {
    assert.deepEqual(this.fake.violations, [], `GATT contract violations\n${this.diagnostics()}`);
    assert.deepEqual(this.consoleErrors, [], 'Bundle console.error');
    assert.equal(this.clock.errors.length, 0, 'Unhandled artifact errors');
  }
  async dispose() {
    this.api.disconnect();
    await this.clock.advance(100);
    this.window.dispatchEvent({ type: 'pagehide' });
    await this.clock.flush();
    this.clock.timers.clear();
    this.healthy();
    activeHarness = undefined;
  }
}

const tests = [];
const test = (name, family, options, body) => tests.push({ name, family, options, body });
function assertLive(h, family) {
  assert.equal(h.state.link, 'Connected'); assert.equal(h.state.connected, true);
  assert.equal(h.state.family, family); assert.equal(h.state.hasData, true);
  assert.equal(h.state.device.hasData, true); assert.equal(h.state.device.totalVoltage, 48);
  assert.equal(h.state.device.current, -2.5); assert.equal(h.state.device.soc, 80);
  assert.equal(h.state.device.power, -120);
  assert.equal(h.state.channel, family === 'Ant' ? 'ffe1' : 'meter');
  assert.ok(h.fake.notificationViews.length > 3, 'Must exercise notification fragmentation');
  assert.ok(h.fake.notificationViews.every((v) => v.offset > 0 && v.length < v.backing), 'Must exercise DataView offsets');
  h.healthy();
}
test('ANT valid probe and fragmented realtime data', 'Ant', {}, async (h) => {
  h.api.connect();
  await h.until(() => h.state.connected && h.state.hasData, 'ANT probe/data did not connect');
  assert.equal(h.fake.writes[0].bytes, hex(ANT_PROBE));
  assert.equal(h.fake.trace[0].type, 'connect');
  assert.ok(h.fake.trace.findIndex((e) => e.type === 'subscribe') < h.fake.trace.findIndex((e) => e.type === 'write'));
  assert.equal(h.state.cells.length, 4); assertLive(h, 'Ant');
});
for (const [family, name] of [['LuXing', 'EM2APP-A581'], ['LanBao', 'BlueBabe-METER'], ['LanBao', 'lb123']]) {
  test(`${family} ${name}: Connected before real meter handshake/data`, family, { name }, async (h) => {
    h.api.connect();
    // No notification whatsoever is supplied until the public state is Connected.
    await h.until(() => h.state.connected, `${family} must connect without an ANT probe response`);
    assert.equal(h.state.hasData, false, 'Link readiness must not invent meter readings');
    assert.equal(h.fake.emitted.length, 0);
    assert.ok(!h.fake.writes.some((w) => w.bytes.startsWith('7ea101')), 'Meters must never receive BMS 0x01 probes');
    await h.until(() => h.fake.queued.length > 0, 'Repository did not send real meter polling handshake');
    h.fake.enableReplies();
    await h.until(() => h.state.hasData && h.state.device.cycles === 42, 'Real meter replies were not decoded');
    assertLive(h, family);
    assert.equal(h.state.device.totalCapAh, 100); assert.equal(h.state.device.remainCapAh, 80);
    assert.equal(h.state.relay.name, 'ANT-RELAY'); assert.equal(h.state.relay.mac, 'AA:BB:CC:11:22:33');
    if (family === 'LuXing') {
      assert.equal(h.state.cells.length, 4);
      assert.equal(h.state.temps.find((t) => t.name === 'MOS').value, 28);
      assert.deepEqual(h.fake.writes.slice(0, 4).map((w) => w.bytes), [...LUXING_READS.values()].map((r) => r.hex));
    } else {
      assert.ok(h.fake.writes.some((w) => w.bytes === hex(lanbaoRead(2))), 'Table2 handshake missing');
      assert.equal(h.state.temps[0].value, 24);
    }
    const calls = h.fake.connectCalls, notifications = h.fake.notificationViews.length;
    h.fake.drop();
    await h.until(() => h.fake.connectCalls > calls && h.state.connected && h.fake.server.connected,
      `${family} disconnect event must recover without BMS probing`);
    await h.until(() => h.state.hasData && h.fake.notificationViews.length > notifications,
      `${family} recovery must resume real meter polling`);
    assertLive(h, family);
    assert.ok(!h.fake.writes.some((w) => w.bytes.startsWith('7ea101')), 'Recovery must also skip BMS probes');
  });
}
test('ANT disconnect event recovers; manual disconnect stops recovery', 'Ant', {}, async (h) => {
  h.api.connect(); await h.until(() => h.state.connected && h.state.hasData, 'Initial ANT connection failed');
  const calls = h.fake.connectCalls, statesBefore = h.states.length;
  h.fake.drop();
  await h.until(() => h.fake.connectCalls > calls && h.state.connected && h.fake.server.connected,
    'gattserverdisconnected must trigger automatic recovery');
  assert.ok(h.fake.device.count('gattserverdisconnected') > 0, 'Transport must register GATT disconnection handler');
  assert.ok(h.states.slice(statesBefore).some((s) => !s.connected), 'Drop must leave Connected state');
  await h.until(() => h.state.hasData, 'Recovery must resume data decoding');
  h.api.disconnect(); await h.until(() => h.state.link === 'Disconnected', 'Manual disconnect did not finish');
  const stoppedCalls = h.fake.connectCalls, stoppedWrites = h.fake.writes.length;
  h.fake.device.dispatchEvent({ type: 'gattserverdisconnected' });
  await h.clock.advance(20_000); // longer than maximum backoff + next attempt
  assert.equal(h.fake.connectCalls, stoppedCalls, 'Manual disconnect must stop reconnect loop');
  assert.equal(h.fake.writes.length, stoppedWrites, 'Manual disconnect must stop repository polling');
  assert.equal(h.fake.server.connected, false); assert.equal(h.state.connected, false);
  assert.equal(h.fake.notify.count('characteristicvaluechanged'), 0, 'Notification listener leaked');
});
test('ANT manual disconnect stops writes and ignores later drop events', 'Ant', {}, async (h) => {
  h.api.connect(); await h.until(() => h.state.connected && h.state.hasData, 'Initial ANT connection failed');
  h.api.disconnect();
  await h.until(() => h.state.link === 'Disconnected', 'Manual disconnect did not finish');
  // Drain any already-started write/notification callbacks before measuring.
  await h.clock.advance(100);
  const connects = h.fake.connectCalls, writes = h.fake.writes.length;
  h.fake.device.dispatchEvent({ type: 'gattserverdisconnected' });
  await h.clock.advance(20_000);
  assert.equal(h.fake.connectCalls, connects, 'Manual disconnect must stop recovery');
  assert.equal(h.fake.writes.length, writes, 'Manual disconnect must stop polling');
  assert.equal(h.fake.server.connected, false);
  assert.equal(h.state.connected, false);
  assert.equal(h.fake.notify.count('characteristicvaluechanged'), 0, 'Notification listener leaked');
});
test('ANT overlapping public connects serialize physical GATT connects', 'Ant', { connectDelay: 500 }, async (h) => {
  h.api.connect(); await h.until(() => h.fake.inFlight === 1, 'First GATT connect never started');
  h.api.connect();
  await h.until(() => h.state.connected && h.state.hasData, 'Repeated connect did not settle');
  assert.equal(h.fake.maxInFlight, 1, 'Canceled connect Promise must settle before another GATT connect begins');
  await h.clock.advance(1_000);
  assert.equal(h.fake.server.connected, true, 'Old canceled loop disconnected the new session');
  assertLive(h, 'Ant');
});
test('ANT manual disconnect during pending GATT connect stays stopped', 'Ant', { connectDelay: 500 }, async (h) => {
  h.api.connect(); await h.until(() => h.fake.inFlight === 1, 'Pending connect never started');
  h.api.disconnect();
  await h.clock.advance(20_000);
  assert.equal(h.state.link, 'Disconnected'); assert.equal(h.fake.connectCalls, 1);
  assert.equal(h.fake.server.connected, false, 'Late connect Promise resurrected manually stopped GATT');
  assert.equal(h.fake.writes.length, 0, 'Pending canceled connect must not probe/write');
});
for (const [stage, expected, forbidden] of [
  ['connect', /GATT|建立|连接/, /没有 FFE0/],
  ['service', /FFE0/, null],
  ['characteristics', /特征|发现通道|通道发现/, /没有 FFE0/],
  ['notifications', /订阅|通知/, /没有 FFE0/],
]) {
  test(`ANT ${stage} error hint identifies failing stage`, 'Ant', { failAt: stage }, async (h) => {
    h.api.connect();
    await h.until(() => h.states.some((s) => s.link === 'Disconnected' && s.hint), 'Failure must publish a hint');
    const hint = h.states.find((s) => s.link === 'Disconnected' && s.hint).hint;
    assert.match(hint, expected, `${stage} failure hint must identify its actual stage; got ${JSON.stringify(hint)}`);
    if (forbidden) assert.doesNotMatch(hint, forbidden,
      `NotFoundError outside service discovery must not claim FFE0 is absent; got ${JSON.stringify(hint)}`);
    assert.equal(h.state.connected, false);
  });
}

const selected = tests.filter((t) => t.name.toLowerCase().includes(filter));
assert.ok(selected.length, `No test matches --case ${filter}`);
console.log(`Artifact: ${bundlePath}\nNode VM/GATT tests: ${selected.length}`);
let failures = 0;
for (const { name, family, options, body } of selected) {
  let harness, failure;
  try { harness = new Harness(family, options); await body(harness); harness.healthy(); }
  catch (error) { failure = error; }
  finally {
    if (harness) {
      try { await harness.dispose(); } catch (error) { failure ??= error; }
    }
    activeHarness = undefined;
  }
  if (failure) {
    failures++; console.error(`FAIL ${name}\n${failure.stack ?? failure}`);
  } else console.log(`PASS ${name}`);
}
process.removeListener('unhandledRejection', unhandled);
console.log(`Result: ${selected.length - failures} passed, ${failures} failed`);
process.exitCode = failures ? 1 : 0;
