#!/usr/bin/env node
/**
 * Black-box regression tests for the shipped Kotlin/JS data artifact, not a browser.
 * Run from any directory (Node >= 18, no dependencies):
 *   node webApp/tests/verify-connect.mjs
 *   node webApp/tests/verify-connect.mjs --bundle /absolute/path/maibms-data.js
 *   node webApp/tests/verify-connect.mjs --case LuXing
 *   node webApp/tests/verify-connect.mjs --case Jbd
 *   node webApp/tests/verify-connect.mjs --case YanYang
 *
 * Never patch/evaluate extracted transport functions: execute the complete bundle
 * and drive only its public Maibms API. Failures are real failures, including on
 * a stale bundle. Rebuild separately after changing Kotlin; this script does not.
 * Fixture layouts come from Frame.kt, RealtimeDecoder.kt, LuXingProtocol.kt,
 * LanBaoProtocol.kt and ReadOnlyBoardProtocol/JbdProtocol/YanYangProtocol.kt.
 * Meter initialFrames() is empty: their handshake is polling. Meters deliberately
 * IGNORE ANT 7E/A1/01 probes and never emit ANT replies. New board fixtures reject
 * every uplink outside their read-only whitelist, including during connection.
 */
import assert from 'node:assert/strict';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

let bundlePath = fileURLToPath(new URL('../../docs/maibms-data.js', import.meta.url));
let filter = '';
let stateDir = null;
for (let i = 2; i < process.argv.length; i++) {
  const flag = process.argv[i];
  assert.ok(['--bundle', '--case', '--state-dir'].includes(flag) && process.argv[i + 1],
    'Usage: node verify-connect.mjs [--bundle path] [--case name-substring] [--state-dir directory]');
  if (flag === '--bundle') bundlePath = resolve(process.argv[++i]);
  else if (flag === '--state-dir') stateDir = resolve(process.argv[++i]);
  else filter = process.argv[++i].toLowerCase();
}
const source = readFileSync(bundlePath, 'utf8');
function saveUiState(h) {
  if (!stateDir) return;
  mkdirSync(stateDir, { recursive: true });
  writeFileSync(resolve(stateDir, `${h.state.family}.json`), JSON.stringify(h.state, null, 2));
}
const hostPath = fileURLToPath(new URL('../../docs/web/maibms-host.js', import.meta.url));
const hostSource = readFileSync(hostPath, 'utf8');
const uuid = (short) => `0000${short.toLowerCase()}-0000-1000-8000-00805f9b34fb`;
const FFE0 = uuid('ffe0'), FFE1 = uuid('ffe1'), FFE2 = uuid('ffe2');
const FF00 = uuid('ff00'), FF01 = uuid('ff01'), FF02 = uuid('ff02');
const NUS = '6e400001-b5a3-f393-e0a9-e50e24dcca9e';
const NUS_WRITE = '6e400002-b5a3-f393-e0a9-e50e24dcca9e';
const NUS_NOTIFY = '6e400003-b5a3-f393-e0a9-e50e24dcca9e';
const isReadOnlyBoard = (family) => ['Jbd', 'YanYang', 'Jikong', 'Yadi', 'Mingtang'].includes(family);
const JK_FRAMES = JSON.parse(readFileSync(new URL('../../composeApp/src/desktopTest/resources/jikong-frames.json', import.meta.url), 'utf8')).samples.JK02_32S;
const JK04_FRAMES = JSON.parse(readFileSync(new URL('../../composeApp/src/desktopTest/resources/jk04-frames.json', import.meta.url), 'utf8')).frames;
function jkResponse(command, revision = 0, jk04 = false) {
  const data = Buffer.from(jk04 ? JK04_FRAMES[command === 0x97 ? 'JK04_device_info' : 'JK04_cell']
    : command === 0x97 ? JK_FRAMES.dev : JK_FRAMES.cell, 'hex');
  if (command === 0x96 && revision) {
    if (jk04) data.writeFloatLE(3.4, 6);
    else data.writeUInt32LE(27000, 150);
    data[299] = data.subarray(0, 299).reduce((sum, b) => sum + b, 0) & 255;
  }
  return data;
}
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
function lanbaoResponse(table, revision = 0) {
  const data = Buffer.alloc(table === 1 ? 111 : 134);
  if (table === 1) {
    data.writeUInt16BE(revision ? 4950 : 4800, 0); data.writeInt32BE(-250, 2);
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

// JBD 03/04 are the supplied golden responses, unchanged. Only the 05 model
// and the explicitly requested second reading below are synthetic.
const JBD_GOLDEN = new Map([
  [3, Buffer.from('dd03001d0618fee101f201f4002a2c7c00000000000080640304030b8b0b8a0b84f88477', 'hex')],
  [4, Buffer.from('dd0400080d660d610d680d59fe3c77', 'hex')],
]);
const JBD_READS = new Map([
  [3, 'dda50300fffd77'], [4, 'dda50400fffc77'], [5, 'dda50500fffb77'],
]);
function jbdResponse(command, revision = 0) {
  if (revision === 0 && JBD_GOLDEN.has(command)) return JBD_GOLDEN.get(command);
  const data = command === 5 ? Buffer.from('JBD-VM-4S', 'ascii') : Buffer.from(JBD_GOLDEN.get(command).subarray(4, -3));
  if (command === 3) {
    data.writeUInt16BE(1600, 0); data.writeInt16BE(-250, 2); data[19] = 90;
  } else if (command === 4) {
    [3440, 3430, 3450, 3420].forEach((v, i) => data.writeUInt16BE(v, i * 2));
  }
  // A response checksum covers ONLY status + length + data, never command.
  const body = Buffer.concat([Buffer.from([0, data.length]), data]);
  const checksum = (-body.reduce((sum, b) => sum + b, 0)) & 0xffff;
  return Buffer.concat([Buffer.from([0xdd, command]), body,
    Buffer.from([checksum >>> 8, checksum & 255, 0x77])]);
}
const YANYANG_READS = new Map([[63, 4], [1, 79], [80, 80], [160, 81], [240, 20]]);
const YANYANG_LEGACY_READS = new Map([[63, 4], [1, 47], [48, 42], [90, 22]]);
const YANYANG_MAGIC = 0x4d444253;
function yanYangRequest(register) {
  const count = YANYANG_READS.get(register);
  return withCrc(Buffer.from([1, 3, register >>> 8, register & 255, count >>> 8, count & 255]));
}
function syntheticYanYangResponse(register, revision = 0) {
  if (register === 0) return null;
  // SYNTHETIC YYBMS B fixture, NOT a hardware capture. Payload offsets/lengths
  // follow NiuHelper@6ebda686 docs/yybms-bms-ble-protocol.md and YanYangProtocol:
  // profile 8 B, summary 158 B, status 160 B, settings 162 B, identity 40 B.
  const count = YANYANG_READS.get(register);
  assert.ok(count, `Unexpected synthetic YY register ${register}`);
  const data = Buffer.alloc(count * 2);
  if (register === 63) data.writeUInt32LE(YANYANG_MAGIC, 0);
  else if (register === 1) {
    data.set([0x59, 0x11, 0x20, 0x26, 1, 2, 3, 4], 2); // supported 5911 serial prefix
    data.write('SYNTHETIC-YY-4S', 12, 'ascii'); data.write('SYNTHETIC YYBMS B', 38, 'ascii');
    data.writeUInt16LE(123, 74); data.write('YY-VM-4S', 80, 'ascii');
    data.set([2, 1, 1], 103); data[122] = 100; data[123] = 123;
    data.writeUInt32LE(YANYANG_MAGIC, 124); data.writeUInt32LE(3600, 140);
    data[148] = 4; data[149] = 1;
    data.writeInt32LE(revision ? 13600 : 13380, 150); data.writeInt32LE(revision ? -300 : -250, 154);
  } else if (register === 80) {
    (revision ? [3410, 3400, 3390, 3400] : [3350, 3340, 3360, 3330])
      .forEach((v, i) => data.writeUInt16LE(v, 2 + i * 2));
    data.set([68, 69, 64, 65], 64); // MOS/balance/T1/T2: 28/29/24/25 C
    data.writeUInt16LE(1000, 76); data.writeUInt16LE(revision ? 750 : 800, 78);
    data[80] = revision ? 75 : 80; data[81] = 98;
    data.writeUInt16LE(30, 86); data.writeUInt16LE(120, 88); data[95] = 42;
    data.writeUInt32LE(5, 118); data.writeInt16LE(3400, 122); data.writeInt16LE(20, 124);
    data.writeUInt32LE((1 << 26) | 3, 144); // charging, charge/discharge MOS on
    data.writeUInt32LE((1 << 0) | (1 << 14), 152); // protection + low-SOC alarm
  } else if (register === 160) {
    [[18, 3550], [20, 3450], [24, 2800], [26, 2900], [36, 1440], [38, 1400],
      [42, 1000], [44, 1100], [62, 1000], [68, 5000]]
      .forEach(([offset, value]) => data.writeUInt16LE(value, offset));
    data.set([95, 90, 40, 45, 100, 95, 30, 35], 80); data.write('SYN-4S01', 94, 'ascii');
  } else if (register === 240) data.write('SYNTHETIC-YYBMS', 20, 'ascii');
  return withCrc(Buffer.concat([Buffer.from([1, 3, data.length]), data]));
}
function syntheticYanYangLegacyResponse(register, revision = 0, slave = 1) {
  if (register === 0) return withCrc(Buffer.from([slave, 3, 2, 0, slave]));
  const count = YANYANG_LEGACY_READS.get(register);
  if (!count) return null;
  const data = Buffer.alloc(count * 2);
  if (register === 1) {
    data[12] = 4; data[13] = 2;
    data.writeUInt16BE(revision ? 14960 : 14860, 22);
    data.writeInt16BE(-1234, 26);
    data.writeUInt16BE(213, 38);
    data.writeUInt16BE(4567, 40); data.writeUInt16BE(8000, 42);
    [revision ? 3800 : 3700, 3710, 3720, 3730].forEach((v, i) => data.writeUInt16BE(v, 44 + i * 2));
    data[81] = 68; data[80] = 70;
  }
  return withCrc(Buffer.concat([Buffer.from([slave, 3, data.length]), data]));
}

// Synthetic protocol fixtures; these are not captures from real hardware.
function yadiResponse(address, revision = 0) {
  const page = Buffer.alloc(124);
  page.writeUInt16LE(revision ? 5160 : 5110, 20);
  page.writeUInt16LE(600, 48); page.writeUInt16LE(3, 60); page[63] = 15;
  for (let i = 0; i < 15; i++) page.writeUInt16LE(3400 + i, 68 + i * 2);
  const count = new Map([[0x1000, 60], [0x103c, 59], [0x1077, 5]]).get(address);
  const raw = withCrc(Buffer.concat([Buffer.from([0xf4, 3, count]), page.subarray(address - 0x1000, address - 0x1000 + count)]));
  return Buffer.from(raw.toString('hex').toUpperCase(), 'ascii');
}
function mingtangResponse(revision = 0) {
  const tlv = (tag, data) => Buffer.concat([Buffer.from([tag >>> 8, tag & 255, data.length]), data]);
  const voltage = Buffer.alloc(2); voltage.writeUInt16BE(revision ? 1360 : 1338);
  const cells = Buffer.alloc(8); for (let i = 0; i < 4; i++) cells.writeUInt16BE(3340 + i, i * 2);
  const payload = Buffer.concat([tlv(10, voltage), tlv(38, Buffer.from([60])), tlv(4, cells)]);
  const header = Buffer.from([0xe1, 0x11, 0, 0, 0, 0]); header.writeUInt16BE(6 + payload.length, 4);
  return Buffer.concat([header, payload, Buffer.from([0])]);
}
function assertBoardRead(family, bytes, legacy = false, allowLegacyProbe = false) {
  if (family === 'Yadi') {
    const text = bytes.toString('ascii');
    assert.match(text, /^F403[0-9A-F]{10}$/);
    const raw = Buffer.from(text, 'hex'), address = raw.readUInt16BE(2);
    assert.equal(raw[4], new Map([[0x1000, 60], [0x103c, 59], [0x1077, 5]]).get(address));
    assert.equal(raw.readUInt16LE(5), crc16(raw.subarray(0, 5)));
    return address;
  }
  if (family === 'Mingtang') {
    assert.equal(bytes.toString('ascii'), 'stateread');
    return 'stateread';
  }
  if (family === 'Jikong') {
    assert.equal(bytes.length, 20);
    assert.equal(hex(bytes.subarray(0, 4)), 'aa5590eb');
    assert.ok([0x96, 0x97].includes(bytes[4]), 'JK only permits live/device info reads');
    assert.ok(bytes.subarray(5, 19).every((b) => b === 0));
    assert.equal(bytes[19], bytes.subarray(0, 19).reduce((sum, b) => sum + b, 0) & 255);
    return bytes[4];
  }
  if (family === 'Jbd') {
    assert.equal(bytes.length, 7, 'JBD permits only complete 03/04/05 read requests');
    assert.equal(hex(bytes), JBD_READS.get(bytes[2]), 'JBD read-only whitelist/header/checksum');
    return bytes[2];
  }
  assert.equal(family, 'YanYang');
  assert.equal(bytes.length, 8, 'YY permits only complete FC03 read requests');
  assert.equal(bytes[1], 3, 'YY uses only FC03');
  const register = bytes.readUInt16BE(2);
  const reads = legacy ? YANYANG_LEGACY_READS : YANYANG_READS;
  const count = bytes.readUInt16BE(4);
  const allowedCount = reads.get(register) === count ||
    (register === 1 && ((legacy && count === 79) || (allowLegacyProbe && count === 47)));
  assert.ok((bytes[0] === 0 && register === 0 && count === 1) ||
    (bytes[0] >= 1 && bytes[0] <= 247 && allowedCount), `YY read outside layout whitelist`);
  assert.equal(bytes.readUInt16LE(6), crc16(bytes.subarray(0, 6)), 'YY Modbus CRC');
  return register;
}

class Events {
  listeners = new Map();
  maxListeners = new Map();
  addEventListener(type, fn) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type).add(fn);
    this.maxListeners.set(type, Math.max(this.maxListeners.get(type) ?? 0, this.count(type)));
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
  disconnectCalls = 0;
  heldConnect = null;
  notificationViews = [];
  notificationAvailableAt = 0;
  dropInjected = false;
  constructor(clock, family, options = {}) {
    this.clock = clock; this.family = family; this.options = options;
    this.replyEnabled = family === 'Ant' || isReadOnlyBoard(family);
    this.boardRevision = 0;
    this.device = new Events();
    this.device.id = options.id ?? `vm-${family}`;
    this.device.name = options.name ?? { Ant: 'ANT-B2A20', LuXing: 'EM2APP-A581', LanBao: 'BlueBabe-METER',
      Jbd: 'JBD-SP04S034', YanYang: 'YY-NiuM-TEST', Jikong: 'JK-BMS-A', Yadi: 'FDA90705',
      Mingtang: 'custom-mingtang', Xiaoha: 'HBB8909006196SOC060' }[family];
    const characteristic = (short, properties) => {
      const c = Object.assign(new Events(), { uuid: short.length === 4 ? uuid(short) : short, properties, value: null, notifying: false });
      c.startNotifications = async () => {
        this.failAt('notifications');
        if (this.options.notificationDelay) await this.wait(this.options.notificationDelay);
        c.notifying = true; this.trace.push({ type: 'subscribe', uuid: c.uuid, at: this.clock.now });
        this.injectDrop('notifications');
        if (this.options.dropAt === 'settle' && !this.dropInjected) {
          this.clock.setTimeout(() => this.injectDrop('settle'), 100);
        }
        return c;
      };
      c.stopNotifications = async () => { c.notifying = false; return c; };
      c.writeValueWithResponse = (value) => this.write(c, value, 'response');
      c.writeValueWithoutResponse = (value) => this.write(c, value, 'without-response');
      return c;
    };
    this.notify = characteristic(family === 'Yadi' ? NUS_NOTIFY : family === 'Mingtang' ? 'fa71' : family === 'Jbd' ? 'ff01' : 'ffe1',
      { notify: true, write: family === 'Ant', writeWithoutResponse: family === 'Ant', read: false, indicate: false });
    this.writer = family === 'Ant' ? this.notify : characteristic(family === 'Yadi' ? NUS_WRITE : family === 'Mingtang' ? 'fa72'
      : family === 'Jbd' ? 'ff02' : family === 'Jikong' || options.singleFfe1 ? 'ffe1' : 'ffe2',
      { write: true, writeWithoutResponse: options.bothWriteModes === true, notify: false, read: false, indicate: false });
    const fallbackWriter = family === 'YanYang' && options.silentPrimary
      ? characteristic('ffe1', { write: true, writeWithoutResponse: false }) : null;
    this.service = {
      uuid: family === 'Yadi' ? NUS : family === 'Mingtang' ? uuid('fa00') : family === 'Jbd' ? FF00 : FFE0,
      getCharacteristics: async () => {
        this.failAt('characteristics');
        this.injectDrop('characteristics');
        // Notify-only FFE1 first: a property or UUID bug must not pass by luck.
        return family === 'Ant' ? [this.notify] : [this.notify, this.writer, ...(fallbackWriter ? [fallbackWriter] : [])];
      },
      getCharacteristic: async (wanted) => {
        const c = [this.notify, this.writer].find((item) => item.uuid === wanted.toLowerCase());
        assert.ok(c, `Unexpected characteristic UUID ${wanted}`); return c;
      },
    };
    this.server = {
      device: this.device, connected: false,
      connect: () => this.connect(),
      disconnect: () => {
        this.disconnectCalls++;
        this.trace.push({ type: 'disconnect-call', at: this.clock.now });
        // Deliberately do not settle held connect: platform abort is best effort.
        this.drop();
      },
      getPrimaryService: async (wanted) => {
        if (isReadOnlyBoard(family) && wanted.toLowerCase() !== this.service.uuid) {
          const error = new Error('fixture service absent'); error.name = 'NotFoundError'; throw error;
        }
        this.check(() => assert.equal(wanted.toLowerCase(), this.service.uuid, 'Must discover the family service'));
        this.failAt('service');
        if (this.options.serviceDelay) await this.wait(this.options.serviceDelay);
        this.injectDrop('service');
        return this.service;
      },
    };
    this.device.gatt = this.server;
    this.bluetooth = {
      getAvailability: async () => true,
      requestDevice: async (opts) => {
        this.requests.push(JSON.parse(JSON.stringify(opts)));
        this.check(() => {
          assert.ok(opts.optionalServices?.some((s) => s.toLowerCase() === FFE0), 'Chooser must authorize FFE0');
          if (family === 'Yadi' || family === 'Mingtang') {
            assert.ok(opts.optionalServices.some((s) => s.toLowerCase() === this.service.uuid), 'Chooser must authorize the new service');
          }
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
  wait(ms) { return new Promise((done) => this.clock.setTimeout(done, ms)); }
  injectDrop(stage) {
    if (this.options.dropAt !== stage || this.dropInjected) return;
    this.dropInjected = true;
    this.drop();
  }
  async connect() {
    const call = ++this.connectCalls;
    this.inFlight++;
    this.maxInFlight = Math.max(this.maxInFlight, this.inFlight);
    this.trace.push({ type: 'connect', at: this.clock.now });
    try {
      if (this.options.holdConnect && call === 1) {
        await new Promise((done) => { this.heldConnect = done; });
      } else {
        await this.wait(this.options.connectDelays?.[call - 1] ?? this.options.connectDelay ?? 30);
      }
      this.failAt('connect'); this.server.connected = true;
      this.trace.push({ type: 'connect-resolve', call, at: this.clock.now });
      return this.server;
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
      const expectedWrite = this.family === 'Yadi' ? NUS_WRITE : this.family === 'Mingtang' ? uuid('fa72')
        : this.family === 'Ant' || this.family === 'Jikong' || this.options.singleFfe1 ? FFE1 : this.family === 'Jbd' ? FF02 : FFE2;
      assert.ok(c.uuid === expectedWrite || (this.options.silentPrimary && c.uuid === FFE1), 'Wrong write characteristic UUID');
      assert.ok(this.server.connected, 'Write after GATT disconnect');
      assert.ok(this.notify.notifying, 'Subscribe before writing');
      assert.equal(this.writeInFlight, 0, 'Concurrent GATT writes');
      assert.equal(method, c.properties.writeWithoutResponse && !(['YanYang', 'Mingtang'].includes(this.family) && c.properties.write)
        ? 'without-response' : 'response', 'Wrong write method for properties');
      return bytes;
    });
    this.writes.push({ uuid: c.uuid, bytes: hex(bytes), at: this.clock.now });
    this.trace.push({ type: 'write', bytes: hex(bytes) });
    this.writeInFlight++;
    try {
      await this.wait(2);
      this.injectDrop('probe');
      if (!this.server.connected) return;
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
        } else if (isReadOnlyBoard(this.family)) {
          const silent = this.options.silentPrimary === true && c.uuid === FFE2;
          // A silent YY channel may be probed with either layout's base read.
          const command = assertBoardRead(this.family, bytes, this.options.legacy === true, silent);
          if (silent) return;
          response = this.family === 'Yadi' ? yadiResponse(command, this.boardRevision)
            : this.family === 'Mingtang' ? mingtangResponse(this.boardRevision)
            : this.family === 'Jbd' ? jbdResponse(command, this.boardRevision)
            : this.family === 'Jikong' ? jkResponse(command, this.boardRevision, this.options.jk04 === true)
            : this.options.legacy ? syntheticYanYangLegacyResponse(command, this.boardRevision, this.options.slave ?? 1)
            : syntheticYanYangResponse(command, this.boardRevision);
          if (this.family === 'YanYang' && this.options.slave && bytes[0] !== 0 && bytes[0] !== this.options.slave) response = null;
          if (this.family === 'YanYang' && this.options.legacy && command === 1 && bytes.readUInt16BE(4) !== 47) response = null;
          if (this.options.ignoreProfile && this.family === 'YanYang' && command === 63) response = null;
          if (this.options.ignoreAddress && this.family === 'YanYang' && command === 0) response = null;
        } else if (this.family === 'LuXing') {
          assert.equal(bytes.length, 8); const start = bytes.readUInt16BE(2);
          const read = LUXING_READS.get(start); assert.ok(read, `Unexpected EM02 register ${start}`);
          assert.equal(hex(bytes), read.hex, 'EM02 request/header/Modbus CRC');
          assert.equal(bytes.readUInt16BE(4), read.count); response = luxingResponse(start);
        } else {
          assert.equal(bytes.length, 19); const table = bytes[13];
          assert.ok(table === 1 || table === 2);
          assert.equal(hex(bytes), hex(lanbaoRead(table)), 'LanBao request/header/match code/private CRC');
          response = lanbaoResponse(table, this.boardRevision);
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
  fixtures = [];
  physicalStates = [];
  constructor(family = 'Ant', options = {}) {
    this.clock = new Clock(); this.fake = new FakeGatt(this.clock, family, options);
    this.fixtures.push(this.fake);
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
    this.api.onState((state) => {
      this.states.push(JSON.parse(JSON.stringify(state)));
      this.physicalStates.push({ link: state.link, connected: state.connected,
        physical: this.fake.server.connected, at: this.clock.now });
    });
    this.window = window;
    this.document = globals.document;
    assert.equal(this.api.supportsWebBluetooth(), true, 'VM must use the real BLE backend');
    assert.equal(this.state.hasData, false, 'Fresh VM must not restore cached/preview data');
    assert.equal(this.state.previewActive, false, 'Tests must not use preview snapshots');
  }
  choose(family, options = {}) {
    this.fake = new FakeGatt(this.clock, family, options);
    this.fixtures.push(this.fake);
    this.context.navigator.bluetooth = this.fake.bluetooth;
    return this.fake;
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
    for (const fixture of this.fixtures) {
      assert.deepEqual(fixture.violations, [], `GATT contract violations on ${fixture.device.id}\n${this.diagnostics()}`);
    }
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
    if (family === 'LanBao') {
      h.fake.boardRevision = 1;
      await h.until(() => h.state.device.totalVoltage === 49.5, 'LanBao polling did not refresh changed data');
      assert.equal(h.state.caps.stateOfHealth, false);
      assert.equal(h.state.caps.perCellVoltage, false);
      assert.equal(h.state.cells.length, 0);
    }
    saveUiState(h);
    const calls = h.fake.connectCalls, notifications = h.fake.notificationViews.length;
    if (family === 'LanBao') h.fake.boardRevision = 0;
    h.fake.drop();
    await h.until(() => h.fake.connectCalls > calls && h.state.connected && h.fake.server.connected,
      `${family} disconnect event must recover without BMS probing`);
    await h.until(() => h.state.hasData && h.fake.notificationViews.length > notifications &&
      (family !== 'LanBao' || h.state.device.totalVoltage === 48),
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

// Review regressions stay here and run against the FULL production artifact.
function assertOneSession(h) {
  assert.equal(h.fake.maxInFlight, 1, 'Native GATT promises must never overlap');
  assert.equal(h.fake.server.connected, true, 'Old cleanup disconnected the current server');
  assert.equal(h.fake.device.count('gattserverdisconnected'), 1, 'Exactly one current device listener');
  assert.equal(h.fake.notify.count('characteristicvaluechanged'), 1, 'Exactly one current notify listener');
  assert.ok(h.fake.device.maxListeners.get('gattserverdisconnected') <= 1, 'Overlapping device listeners');
  assert.ok(h.fake.notify.maxListeners.get('characteristicvaluechanged') <= 1, 'Overlapping notify listeners');
}
function assertNoFalseReady(h) {
  assert.ok(!h.physicalStates.some((s) => s.connected && !s.physical),
    `Published Connected with disconnected GATT: ${JSON.stringify(h.physicalStates)}`);
}
test('Review >15s native promise drains before retry, maxInFlight1', 'Ant',
  { connectDelays: [25_000, 30] }, async (h) => {
    h.api.connect(); await h.until(() => h.fake.inFlight === 1, 'First connect missing');
    await h.clock.advance(18_000);
    assert.equal(h.fake.connectCalls, 1, 'Timeout/backoff must not start a second unsettled connect');
    assert.equal(h.fake.inFlight, 1);
    await h.until(() => h.state.connected && h.state.hasData, 'Retry after native settlement failed', 12_000);
    const trace = h.fake.trace;
    const oldResolve = trace.findIndex((e) => e.type === 'connect-resolve' && e.call === 1);
    const oldDrop = trace.findIndex((e, i) => i > oldResolve && e.type === 'drop');
    const nextConnect = trace.findIndex((e, i) => i > oldResolve && e.type === 'connect');
    assert.ok(oldDrop > oldResolve && nextConnect > oldDrop, 'Late success must disconnect BEFORE next same-server connect');
    await h.clock.advance(2_000);
    assertOneSession(h); assertNoFalseReady(h); assertLive(h, 'Ant');
  });
test('Review timeout then manual disconnect cannot resurrect late success', 'Ant',
  { connectDelay: 25_000 }, async (h) => {
    h.api.connect(); await h.until(() => h.fake.inFlight === 1, 'Pending connect missing');
    await h.clock.advance(15_500);
    h.api.disconnect();
    await h.until(() => h.state.link === 'Disconnected', 'Disconnect must be immediate', 100);
    await h.clock.advance(30_000);
    assert.equal(h.fake.connectCalls, 1, 'Manual stop must cancel retry intent');
    assert.equal(h.fake.server.connected, false, 'Timed-out late success resurrected GATT');
    assert.equal(h.fake.writes.length, 0);
    assert.equal(h.fake.device.count('gattserverdisconnected'), 0);
    assert.ok(!h.states.some((s) => s.connected), 'Late promise must never publish Connected');
  });
test('Review pending disconnect releases user connectionGuard and old result cannot tear new', 'Ant',
  { connectDelays: [25_000, 30] }, async (h) => {
    h.api.connect(); await h.until(() => h.fake.inFlight === 1, 'Pending connect missing');
    h.api.disconnect();
    await h.until(() => h.state.link === 'Disconnected', 'Disconnect waited on unbounded native Promise', 100);
    h.api.connect();
    await h.until(() => h.fake.requests.length === 2, 'User reconnect must reach chooser immediately', 100);
    await h.clock.advance(500);
    assert.equal(h.fake.connectCalls, 1, 'Reconnect must wait behind old native operation');
    await h.until(() => h.state.connected && h.state.hasData, 'Reconnect never recovered after late result', 30_000);
    assert.equal(h.fake.connectCalls, 2);
    await h.clock.advance(2_000);
    assertOneSession(h); assertLive(h, 'Ant');
  });
for (const family of ['LuXing', 'LanBao']) {
  for (const dropAt of ['notifications', 'settle', 'service', 'characteristics']) {
    test(`Review ${family} ${dropAt} windowdrop never false Connected and retries`, family, { dropAt }, async (h) => {
      h.api.connect();
      await h.until(() => h.fake.dropInjected, 'Window drop fixture never fired');
      await h.clock.advance(500);
      assert.equal(h.state.connected, false, 'Dropped build must not become logically Connected');
      assert.equal(h.fake.notify.count('characteristicvaluechanged'), 0, 'Failed attempt listener leaked');
      assertNoFalseReady(h);
      await h.until(() => h.fake.connectCalls >= 2 && h.state.connected && h.fake.server.connected,
        'Window drop must retry into a valid meter session');
      h.fake.enableReplies();
      await h.until(() => h.state.hasData, 'Recovered meter stopped polling');
      assert.ok(!h.fake.writes.some((w) => w.bytes.startsWith('7ea101')), 'Meters must still skip ANT probes');
      assertOneSession(h); assertNoFalseReady(h);
    });
  }
}
test('Review ANT probe windowdrop must not commit Connected', 'Ant', { dropAt: 'probe' }, async (h) => {
  h.api.connect(); await h.until(() => h.fake.dropInjected, 'Probe drop never fired');
  await h.clock.advance(500);
  assert.equal(h.state.connected, false); assertNoFalseReady(h);
  await h.until(() => h.fake.connectCalls >= 2 && h.state.connected && h.state.hasData,
    'Probe drop failed to recover');
  assertOneSession(h);
});
test('Review BFCache resumes intended connection without duplicate listeners or started pipeline', 'Ant', {}, async (h) => {
  h.api.connect(); await h.until(() => h.state.connected && h.state.hasData, 'Initial connect failed');
  for (let round = 0; round < 2; round++) {
    await h.clock.advance(100); // finish already-started writes before freezing
    const calls = h.fake.connectCalls, writes = h.fake.writes.length;
    const windowCounts = ['pagehide', 'pageshow', 'message'].map((type) => h.window.count(type));
    const visibilityCount = h.document.count('visibilitychange');
    h.window.dispatchEvent({ type: 'pagehide', persisted: true });
    await h.until(() => !h.state.connected && !h.fake.server.connected, 'BFCache hide must suspend BLE', 100);
    await h.clock.advance(20_000);
    assert.equal(h.fake.connectCalls, calls, 'Frozen page must not reconnect');
    assert.equal(h.fake.writes.length, writes, 'Frozen page must not poll');
    h.window.dispatchEvent({ type: 'pageshow', persisted: true });
    h.window.dispatchEvent({ type: 'pageshow', persisted: true });
    await h.until(() => h.fake.connectCalls === calls + 1 && h.state.connected,
      'BFCache return must restore prior connection intent');
    await h.until(() => h.fake.writes.length > writes, 'Restored page must resume polling');
    assert.deepEqual(['pagehide', 'pageshow', 'message'].map((type) => h.window.count(type)), windowCounts);
    assert.equal(h.document.count('visibilitychange'), visibilityCount, 'Visibility listener duplicated');
    assert.equal(h.window.count('pagehide'), 1); assert.equal(h.window.count('pageshow'), 1);
    assert.equal(h.api.logText().split('网页端启动：').length - 1, 1, 'Data pipeline must only start once');
    assertOneSession(h);
  }
});
test('Review BFCache does not revive manual stop but subsequent user connect works', 'Ant', {}, async (h) => {
  h.api.connect(); await h.until(() => h.state.connected, 'Initial connect failed');
  h.api.disconnect(); await h.until(() => h.state.link === 'Disconnected', 'Manual stop failed');
  await h.clock.advance(100);
  const calls = h.fake.connectCalls;
  h.window.dispatchEvent({ type: 'pagehide', persisted: true });
  h.window.dispatchEvent({ type: 'pageshow', persisted: true });
  await h.clock.advance(20_000);
  assert.equal(h.fake.connectCalls, calls, 'pageshow must not revive manual disconnect intent');
  assert.equal(h.state.connected, false);
  h.api.connect(); await h.until(() => h.state.connected && h.state.hasData, 'User reconnect after BFCache failed');
  assertOneSession(h);
});
test('Review BFCache manual stop while suspended prevents automatic revival', 'Ant', {}, async (h) => {
  h.api.connect(); await h.until(() => h.state.connected, 'Initial connect failed');
  h.window.dispatchEvent({ type: 'pagehide', persisted: true });
  await h.clock.advance(100);
  h.api.disconnect(); await h.clock.advance(100);
  const calls = h.fake.connectCalls;
  h.window.dispatchEvent({ type: 'pageshow', persisted: true });
  await h.clock.advance(20_000);
  assert.equal(h.fake.connectCalls, calls); assert.equal(h.state.connected, false);
});
test('Review old A listeners and cleanup cannot act on selected B', 'Ant', { id: 'vm-A' }, async (h) => {
  h.api.connect(); await h.until(() => h.state.connected && h.state.hasData, 'A connect failed');
  const a = h.fake;
  const oldDrop = [...a.device.listeners.get('gattserverdisconnected')];
  const oldNotify = [...a.notify.listeners.get('characteristicvaluechanged')];
  assert.equal(oldDrop.length, 1); assert.equal(oldNotify.length, 1);
  const b = h.choose('LanBao', { id: 'vm-B' });
  h.api.connect(); await h.until(() => h.state.connected && h.state.family === 'LanBao', 'Selected B connect failed');
  assert.equal(a.device.count('gattserverdisconnected'), 0, 'A listener removed from wrong authorized device');
  assert.equal(a.notify.count('characteristicvaluechanged'), 0);
  assert.equal(a.server.connected, false);
  const bCalls = b.connectCalls;
  const stale = antRealtime();
  a.notify.value = new DataView(Uint8Array.from(stale).buffer);
  for (const fn of oldDrop) fn({ type: 'gattserverdisconnected', target: a.device });
  for (const fn of oldNotify) fn({ type: 'characteristicvaluechanged', target: a.notify });
  a.device.dispatchEvent({ type: 'gattserverdisconnected' });
  await h.clock.advance(500);
  assert.equal(h.state.hasData, false, 'Old ANT listener fed readings into B before B handshake');
  assert.equal(h.state.connected, true); assert.equal(b.server.connected, true);
  assert.equal(b.connectCalls, bCalls, 'Old A callback dropped/reconnected B');
  b.enableReplies(); await h.until(() => h.state.hasData, 'B polling failed after stale A callbacks');
  assertOneSession(h); assertLive(h, 'LanBao');
});
test('Review old A service completion cannot clean newly selected B', 'Ant',
  { id: 'vm-A', serviceDelay: 1_000 }, async (h) => {
    h.api.connect(); await h.until(() => h.fake.server.connected, 'A native connect missing');
    await h.clock.advance(50);
    const a = h.fake;
    const b = h.choose('LanBao', { id: 'vm-B' });
    h.api.connect(); await h.until(() => h.state.connected && h.state.family === 'LanBao', 'B connect missing');
    await h.clock.advance(2_000);
    assert.equal(a.device.count('gattserverdisconnected'), 0);
    assert.equal(a.server.connected, false);
    assert.equal(b.server.connected, true); assert.equal(b.connectCalls, 1);
    assertOneSession(h);
  });

test('Review suspended A Promise cannot block different B or tear B on late A success', 'Ant',
  { id: 'vm-held-A', holdConnect: true }, async (h) => {
    h.api.connect(); await h.until(() => h.fake.inFlight === 1, 'A held connect missing');
    const a = h.fake;
    h.api.disconnect();
    await h.until(() => h.state.link === 'Disconnected', 'Manual disconnect must not await A', 100);
    const b = h.choose('Ant', { id: 'vm-ready-B' });
    h.api.connect();
    await h.until(() => b.requests.length === 1, 'Chooser must return B', 100);
    await h.until(() => b.server.connected && h.state.connected && h.state.hasData,
      'Different B must connect while A Promise remains unsettled', 2_000);
    assert.equal(a.inFlight, 1, 'A must remain unsettled until explicitly released');
    assert.ok(a.disconnectCalls >= 1, 'Cancellation must try abort on A own GATT server');
    assert.equal(a.device.count('gattserverdisconnected'), 0);
    const calls = b.connectCalls, disconnects = b.disconnectCalls;
    a.heldConnect();
    await h.clock.advance(1_000);
    assert.equal(a.inFlight, 0); assert.equal(a.server.connected, false, 'Late A success must be drained');
    assert.equal(a.writes.length, 0, 'Late A must never probe or poll');
    assert.equal(b.connectCalls, calls, 'Late A must not force B to reconnect');
    assert.equal(b.disconnectCalls, disconnects, 'Late A cleanup must never disconnect B');
    assertOneSession(h); assertLive(h, 'Ant');
  });

for (const family of ['Jbd', 'YanYang', 'Jikong']) {
  test(`${family} manual family read-only telemetry keeps refreshing`, family, { name: 'custom-pack-name' }, async (h) => {
    h.api.connect(family);
    await h.until(() => h.state.connected && h.state.hasData && h.state.cells.length === (family === 'Jikong' ? 8 : 4),
      `${family} real protocol did not produce dashboard readings`);
    assert.equal(h.state.family, family);
    assert.equal(h.state.caps.readOnly, true);
    assert.equal(h.state.caps.permission, false);
    assert.equal(h.state.caps.controlCharge, false);
    assert.equal(h.state.relay.active, false);
    assert.ok(h.state.availableFields.includes('voltage'));
    assert.ok(h.state.temps.length > 0);
    assert.equal(h.state.channel, family === 'Jbd' ? 'jbd' : family === 'Jikong' ? 'jikong' : 'yanyang');
    const voltage = h.state.device.totalVoltage;
    h.fake.boardRevision = 1;
    await h.until(() => h.state.device.totalVoltage !== voltage, 'Polling did not refresh changed board data');
    assert.equal(h.state.device.totalVoltage, family === 'Jbd' ? 16 : family === 'Jikong' ? 27 : 13.6);
    assert.ok((h.state.boardSettings || []).length > 0, 'Read-only settings were not published');
    saveUiState(h);
    const write = await h.api.writeParam(0, 4300);
    const control = await h.api.control(6);
    const saved = await h.api.saveAllParams();
    assert.equal(write.ok, false); assert.equal(control.ok, false); assert.equal(saved.ok, false);
    assert.equal(write.code, -8); assert.equal(control.code, -8);
    const calls = h.fake.connectCalls;
    h.fake.drop();
    await h.until(() => h.fake.connectCalls > calls && h.state.connected && h.fake.server.connected,
      'Board reconnection did not recover');
    await h.clock.advance(1500);
    h.api.disconnect();
    await h.until(() => !h.state.connected && !h.fake.server.connected, 'Manual disconnect failed');
    h.api.connect(); // 广播名仍是 custom-pack-name，必须沿用档案的明确板型。
    await h.until(() => h.state.connected && h.state.family === family && h.fake.server.connected,
      'Unrecognized advertisement overwrote the remembered family');
    await h.clock.advance(600);
    assert.ok(h.fake.writes.every((w) => { assertBoardRead(family, Buffer.from(w.bytes, 'hex')); return true; }));
    assert.ok(!h.fake.writes.some((w) => w.bytes.startsWith('7e')), 'New board received ANT frames');
  });
}

for (const family of ['Yadi', 'Mingtang']) {
  test(`${family} full read-only connection and polling`, family, { bothWriteModes: true }, async (h) => {
    h.api.connect(family);
    await h.until(() => h.state.connected && h.state.hasData && h.state.cells.length === (family === 'Yadi' ? 15 : 4),
      'New protocol did not publish complete readings', 20_000);
    assert.equal(h.state.family, family);
    assert.equal(h.state.caps.readOnly, true); assert.equal(h.state.caps.controlCharge, false);
    assert.equal(h.state.device.totalVoltage, family === 'Yadi' ? 51.1 : 13.38);
    assert.equal(h.state.device.soc, 60);
    assert.ok(!h.state.availableFields.includes('current'), 'Unknown current must stay unavailable');
    assert.ok(!h.state.availableFields.includes('mos'), 'Unknown MOS must stay unavailable');
    assert.equal(h.state.channel, family === 'Yadi' ? 'yadi-nus' : 'mingtang');
    const voltage = h.state.device.totalVoltage;
    h.fake.boardRevision = 1;
    await h.until(() => h.state.device.totalVoltage !== voltage, 'Polling did not refresh new board', 20_000);
    assert.equal(h.state.device.totalVoltage, family === 'Yadi' ? 51.6 : 13.6);
    const write = await h.api.writeParam(0, 4300), control = await h.api.control(6);
    assert.equal(write.ok, false); assert.equal(control.ok, false);
    assert.ok(h.fake.writes.every((w) => { assertBoardRead(family, Buffer.from(w.bytes, 'hex')); return true; }));
    saveUiState(h);
  });
}

test('Xiaoha advertisement shows only SOC and never opens GATT', 'Xiaoha', {}, async (h) => {
  h.api.connect('Xiaoha');
  await h.clock.advance(1000);
  assert.equal(h.fake.connectCalls, 0); assert.equal(h.fake.writes.length, 0);
  assert.equal(h.state.connected, false); assert.equal(h.state.hasData, false);
  assert.match(h.state.hint, /广播电量 60%/);
});

test('YanYang FFE1 shared UUID handles and acknowledged writes work without profile reply', 'YanYang',
  { singleFfe1: true, bothWriteModes: true, ignoreProfile: true }, async (h) => {
    h.api.connect('YanYang');
    await h.until(() => h.state.connected && h.state.hasData && h.state.cells.length === 4,
      'No fallback from missing profile to signed summary', 20_000);
    assert.equal(h.state.channel, 'yanyang-ffe1');
    assert.ok(h.fake.writes.some((w) => Buffer.from(w.bytes, 'hex').readUInt16BE(2) === 1));
    assert.equal(h.state.device.totalVoltage, 13.38);
  });

for (const family of ['Jbd', 'YanYang', 'Jikong', 'Yadi', 'Mingtang']) {
  test(`${family} subscribed but silent never reports connected`, family, {}, async (h) => {
    h.fake.replyEnabled = false;
    h.api.connect(family);
    await h.clock.advance(10_000);
    assert.equal(h.state.connected, false);
    assert.equal(h.state.hasData, false);
    assert.ok(h.fake.writes.length > 0, 'Protocol probe did not run');
  });
}

test('YanYang silent subscribed first channel falls back to FFE1', 'YanYang', { silentPrimary: true }, async (h) => {
  h.api.connect('YanYang');
  await h.until(() => h.state.connected && h.state.hasData && h.state.channel === 'yanyang-ffe1',
    'Silent FFE2 did not fall back to working FFE1', 40_000);
  assert.ok(h.fake.writes.some((w) => w.uuid === FFE2));
  assert.ok(h.fake.writes.some((w) => w.uuid === FFE1));
  assert.equal(h.fake.connectCalls, 1, 'Candidate fallback should not require an extra physical reconnect');
});

test('JK04 captured float telemetry connects without inventing current or SOC', 'Jikong', { jk04: true }, async (h) => {
  h.api.connect('Jikong');
  await h.until(() => h.state.connected && h.state.hasData && h.state.cells.length === 16,
    'JK04 capture did not connect through production protocol');
  assert.equal(h.state.params.identity.protocol, 'JK04');
  assert.ok(Math.abs(h.state.device.totalVoltage - 53.166) < 0.002);
  for (const field of ['current', 'power', 'soc', 'capacity', 'remaining', 'mos', 'cycles', 'soh']) {
    assert.ok(!h.state.availableFields.includes(field), `JK04 invented ${field}`);
  }
  assert.equal(h.state.caps.mos, false);
  assert.equal(h.state.caps.capacity, false);
  assert.equal(h.state.temps.length, 0);
  const voltage = h.state.device.totalVoltage;
  h.fake.boardRevision = 1;
  await h.until(() => h.state.device.totalVoltage !== voltage, 'JK04 did not refresh float cells');
  if (stateDir) writeFileSync(resolve(stateDir, 'Jk04.json'), JSON.stringify(h.state, null, 2));
  const calls = h.fake.connectCalls;
  h.fake.drop();
  await h.until(() => h.fake.connectCalls > calls && h.state.connected && h.state.hasData,
    'JK04 did not recover after disconnect');
});

for (const { slave, ignoreProfile, ignoreAddress } of [
  { slave: 1 },
  { slave: 1, ignoreProfile: true, ignoreAddress: true },
  { slave: 7, ignoreProfile: true },
]) {
  const silent = ignoreProfile ? ' without profile reply' : '';
  test(`YanYang legacy A layout at slave ${slave}${silent} connects and keeps refreshing`, 'YanYang',
    { legacy: true, slave, ignoreProfile, ignoreAddress }, async (h) => {
      h.api.connect('YanYang');
      await h.until(() => h.state.connected && h.state.hasData && h.state.cells.length === 4,
        'Legacy A did not connect with its own blocks', 22_000);
      assert.equal(h.state.device.totalVoltage, 14.86);
      assert.equal(h.state.params.identity.protocol, 'YYBMS 旧 A 兼容布局');
      assert.ok(!h.state.availableFields.includes('soc'));
      assert.ok(!h.state.availableFields.includes('capacity'));
      assert.ok(!h.state.availableFields.includes('current'));
      assert.ok(!h.state.availableFields.includes('power'));
      assert.equal(h.state.caps.mos, false);
      assert.equal(h.state.device.battState, '方向待确认');
      assert.equal(h.fake.writes.filter((w) => w.bytes === '00030000000185db').length, ignoreProfile ? 1 : 0);
      h.fake.boardRevision = 1;
      await h.until(() => h.state.device.totalVoltage === 14.96, 'Legacy A voltage did not refresh');
      if (stateDir && !ignoreAddress) writeFileSync(resolve(stateDir, `YanYangA-${slave}.json`), JSON.stringify(h.state, null, 2));
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
