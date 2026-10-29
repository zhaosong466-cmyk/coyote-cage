const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { test } = require('node:test');
const vm = require('node:vm');

// Execute the page's actual script with camera/network access replaced by local fakes.
function page() {
  const html = readFileSync(join(__dirname, '../web/index.html'), 'utf8');
  const script = [...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)]
    .map((m) => m[1]).join('\n').replace(/\nboot\(\);\s*$/, '');
  const elements = new Map();
  const element = (id) => {
    if (!elements.has(id)) elements.set(id, {
      style: {}, classList: { toggle() {}, remove() {}, add() {} },
      addEventListener() {}, getContext: () => ({}),
    });
    return elements.get(id);
  };
  let now = 10000;
  let nextTimer = 1;
  const timers = new Map();
  const intervals = [];
  const sent = [];
  const context = vm.createContext({
    console, performance: { now: () => now },
    crypto: { randomUUID: () => String(nextTimer++) },
    document: { getElementById: element },
    window: { addEventListener() {} },
    ResizeObserver: class { observe() {} },
    localStorage: { getItem: () => null }, WebSocket: { OPEN: 1 },
    setInterval: (fn) => intervals.push(fn),
    setTimeout: (fn, ms) => { const id = nextTimer++; timers.set(id, { fn, at: now + ms }); return id; },
    clearTimeout: (id) => timers.delete(id),
  });
  vm.runInContext(script, context);
  const run = (code) => vm.runInContext(code, context);
  context.capture = (text) => sent.push(JSON.parse(text).data);
  run(`dg.ws = { readyState: 1, send: capture }; dg.phase = 'paired';
       dg.appId = 'app'; dg.devices = [{ slotId: 'device' }];`);
  return {
    run, sent, elements, timers,
    tick(ms = 60) { now += ms; intervals.forEach((fn) => fn()); },
    advance(ms) {
      now += ms;
      for (const [id, timer] of [...timers]) {
        if (timer.at <= now && timers.delete(id)) timer.fn();
      }
    },
  };
}

function pose(p, x) {
  p.run(`latest = { ts: performance.now(), points: Array.from({length: 33},
    () => ({ x: ${x}, y: 0.5, visibility: 1 })) };`);
}

const pulses = (p) => p.sent.filter((m) => m.m === 'device.op' && m.data.t === 0);

test('zero grace never fires for an inside, missing, or stale pose', () => {
  for (const state of ['inside', 'missing', 'stale']) {
    const p = page();
    p.run('S.graceMs = 0; running = true;');
    if (state !== 'missing') pose(p, state === 'inside' ? 0.5 : 0.95);
    p.tick(state === 'stale' ? 1000 : 60);
    assert.equal(p.run('violating'), false, state);
    assert.equal(pulses(p).length, 0, state);
  }
});

test('zero grace fires for a fresh outside pose and stops on return', () => {
  const p = page();
  p.run('S.graceMs = 0; running = true;');
  pose(p, 0.95);
  p.tick();
  assert.equal(pulses(p).length, 1);
  pose(p, 0.5);
  p.tick();
  assert.equal(p.run('violating'), false);
  assert.deepEqual(p.sent.slice(-3).map((m) => [m.m, m.data.t, m.data.c]), [
    ['device.op', 7, 0], ['device.op', 7, 1], ['device.op.clear', undefined, undefined],
  ]);
});

test('positive grace still waits for continuous outside samples', () => {
  const p = page();
  p.run('S.graceMs = 120; running = true;');
  pose(p, 0.95);
  p.tick();
  assert.equal(pulses(p).length, 0);
  p.tick();
  assert.equal(pulses(p).length, 1);
});

test('losing pose freshness stops active output', () => {
  const p = page();
  p.run('S.graceMs = 0; running = true;');
  pose(p, 0.95);
  p.tick();
  p.tick(1000);
  assert.equal(p.run('violating'), false);
  assert.equal(p.sent.at(-1).m, 'device.op.clear');
  assert.equal(pulses(p).length, 1);
});

test('emergency sends both zeros before clear without any replies or timeout', () => {
  const p = page();
  p.run('dg.emergency();');
  assert.deepEqual(p.sent.map((m) => [m.m, m.data.t, m.data.c]), [
    ['device.op', 7, 0], ['device.op', 7, 1], ['device.op.clear', undefined, undefined],
  ]);
  assert.equal(p.timers.size, 0);
});

test('emergency cancels test cleanup and leaves no later output', async () => {
  const p = page();
  await p.elements.get('btnTest').onclick();
  assert.equal(pulses(p).length, 1);
  p.elements.get('btnStop').onclick();
  const stopped = p.sent.length;
  p.advance(10000);
  p.tick();
  assert.equal(p.sent.length, stopped);
  assert.equal(p.timers.size, 0);
});

test('starting monitoring cancels test cleanup even after a channel change', async () => {
  const p = page();
  await p.elements.get('btnTest').onclick();
  p.run('S.channel = 1; S.graceMs = 0; S.prepareSec = 0;');
  p.elements.get('btnRun').onclick();
  p.advance(1000);
  pose(p, 0.95);
  p.tick();
  assert.equal(pulses(p).at(-1).data.c, 1);
  const started = p.sent.length;
  p.advance(1000);
  assert.equal(p.sent.length, started);
});

test('repeated tests and tests during monitoring do not overlap', async () => {
  const p = page();
  await p.elements.get('btnTest').onclick();
  await p.elements.get('btnTest').onclick();
  assert.equal(pulses(p).length, 1);
  p.elements.get('btnRun').onclick();
  await p.elements.get('btnTest').onclick();
  assert.equal(pulses(p).length, 1);
});

test('prepare countdown blocks judging until it ends', () => {
  const p = page();
  p.run('S.graceMs = 0; S.prepareSec = 3; S.frameMode = "manual";');
  p.elements.get('btnRun').onclick();
  pose(p, 0.95);
  p.tick(1000);
  assert.ok(p.run('prepareRemaining') > 0);
  assert.equal(pulses(p).length, 0);
  assert.equal(p.run('violating'), false);

  p.tick(1000);
  p.tick(1000);
  assert.equal(pulses(p).length, 0);

  pose(p, 0.95);
  p.tick(60);
  assert.equal(pulses(p).length, 1);
});

test('pose mode locks the reference and punishes movement', () => {
  const p = page();
  p.run('S.prepareSec = 0; S.frameMode = "pose"; S.poseTolerance = 3;');

  p.elements.get('btnRun').onclick();
  pose(p, 0.5);
  p.tick(60);
  assert.equal(p.run('referencePose !== null'), true);
  assert.equal(pulses(p).length, 0);

  // 横向 2% ≈ 12.8px / 480 ≈ 2.7%，在 3% 容差内
  pose(p, 0.52);
  p.tick(60);
  assert.equal(pulses(p).length, 0);
  assert.equal(p.run('violating'), false);

  // 横向 20% ≈ 128px / 480 ≈ 26%，超容差
  pose(p, 0.7);
  p.tick(60);
  assert.equal(pulses(p).length, 1);
  assert.equal(p.run('violating'), true);
});
