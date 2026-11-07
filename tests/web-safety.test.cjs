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
    if (!elements.has(id)) {
      elements.set(id, {
        style: {}, open: false, value: '', textContent: '', innerHTML: '', checked: false,
        handlers: {},
        classList: { toggle() {}, remove() {}, add() {}, contains: () => false },
        addEventListener(type, fn) { this.handlers[type] = fn; },
        appendChild() {}, getContext: () => ({}),
        showModal() { this.open = true; }, close() { this.open = false; },
      });
    }
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
    document: { getElementById: element, createElement: () => element('el-' + nextTimer++) },
    window: { addEventListener() {}, devicePixelRatio: 1 },
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
       dg.appId = 'app'; dg.devices = [{ slotId: 'device' }];
       S.askDuration = false;`);
  // 面板初始化会挂上所有 input/change 监听，测试里也照做一遍
  run('initSettingsUI();');
  return {
    html, run, sent, elements, timers,
    tick(ms = 60) { now += ms; intervals.forEach((fn) => fn()); },
    fire(id, type) { const h = element(id).handlers[type]; if (h) h({ target: element(id) }); },
    /** 重新挂上 WebSocket 假桩（dg.disconnect() 会把它清掉）。 */
    attach() { run('dg.ws = { readyState: 1, send: capture };'); },
    advance(ms) {
      now += ms;
      for (const [id, timer] of [...timers]) {
        if (timer.at <= now && timers.delete(id)) timer.fn();
      }
    },
  };
}

// 33 个点全部相同，可见度 1
function poseAll(x, y = 0.5, v = 1) {
  return Array.from({ length: 33 }, () => ({ x, y, visibility: v }));
}

function poseRaw(p, points) {
  p.run(`latest = { ts: performance.now(), points: ${JSON.stringify(points)} };`);
}

function pose(p, x) {
  poseRaw(p, poseAll(x));
}

/** 点开始（不等弹窗、不要准备倒计时、零容忍）。 */
function start(p) {
  p.run('S.prepareSec = 0; S.graceMs = 0; S.askDuration = false;');
  p.elements.get('btnRun').onclick();
}

const pulses = (p) => p.sent.filter((m) => m.m === 'device.op' && m.data.t === 0);
/** 挑逗和惩罚的波形帧数不同：挑逗按 teaseMs 算，惩罚按 shockMs 算。 */
const teasePulses = (p) => pulses(p).filter((m) => m.data.v.length !== Math.max(1, Math.floor(p.run('S.shockMs') / 100)));
const zeroTriple = (m) => [m.m, m.data.t, m.data.c];

test('页面上已经没有独立急停键，只有开关', () => {
  const p = page();
  assert.equal(p.html.includes('id="btnStop"'), false);
  assert.equal(p.html.includes('class="key stop"'), false);
  assert.equal(p.html.includes('>急停<'), false);
  assert.equal(p.html.includes('id="btnRun"'), true);
});

test('关闭走归零 + 清队列，结束后不再有任何输出', () => {
  const p = page();
  start(p);
  pose(p, 0.95);
  p.tick();
  assert.equal(pulses(p).length, 1);

  p.elements.get('btnRun').onclick();
  assert.equal(p.run('state'), 'ended');
  assert.deepEqual(p.sent.slice(-3).map(zeroTriple), [
    ['device.op', 7, 0], ['device.op', 7, 1], ['device.op.clear', undefined, undefined],
  ]);

  const stopped = p.sent.length;
  p.advance(10000);
  p.tick();
  assert.equal(p.sent.length, stopped);
});

test('关闭不会断开中继连接', () => {
  const p = page();
  start(p);
  p.elements.get('btnRun').onclick();
  assert.equal(p.run('dg.phase'), 'paired');
  assert.equal(p.run('state'), 'ended');
  assert.equal(p.run('isLive()'), false);
});

test('关闭后可以立刻再开始，状态不回滚', () => {
  const p = page();
  start(p);
  p.elements.get('btnRun').onclick();
  assert.equal(p.run('state'), 'ended');
  p.elements.get('btnRun').onclick();
  assert.equal(p.run('state'), 'running');
});

test('零容忍：新鲜越界立刻输出，回到框内停止', () => {
  const p = page();
  start(p);
  pose(p, 0.5);
  p.tick();
  assert.equal(pulses(p).length, 0);

  pose(p, 0.95);
  p.tick();
  assert.equal(pulses(p).length, 1);

  pose(p, 0.5);
  p.tick();
  assert.equal(p.run('violating'), false);
  assert.deepEqual(p.sent.slice(-3).map(zeroTriple), [
    ['device.op', 7, 0], ['device.op', 7, 1], ['device.op.clear', undefined, undefined],
  ]);
});

test('容忍时间：需要连续越界采样', () => {
  const p = page();
  start(p);
  p.run('S.graceMs = 120;');
  pose(p, 0.95);
  p.tick();
  assert.equal(pulses(p).length, 0);
  p.tick();
  assert.equal(pulses(p).length, 1);
});

test('姿态丢失默认视为越界', () => {
  const p = page();
  start(p);
  pose(p, 0.5);
  p.tick();
  assert.equal(pulses(p).length, 0);

  p.tick(1000); // 姿态过期
  assert.equal(pulses(p).length, 1);
  assert.equal(p.run('violating'), true);
});

test('姿态丢失可以切成暂停判定', () => {
  const p = page();
  p.run('S.poseLost = "pause";');
  start(p);
  pose(p, 0.5);
  p.tick();
  p.tick(1000);
  assert.equal(pulses(p).length, 0);
});

test('开局还没见过人时，姿态丢失不会凭空开罚', () => {
  const p = page();
  start(p);
  p.tick();
  p.tick();
  assert.equal(p.run('seenPose'), false);
  assert.equal(pulses(p).length, 0);
});

test('准备倒计时期间不判定', () => {
  const p = page();
  p.run('S.prepareSec = 3; S.graceMs = 0; S.askDuration = false;');
  p.elements.get('btnRun').onclick();
  assert.equal(p.run('state'), 'preparing');

  pose(p, 0.95);
  p.tick(1000);
  assert.equal(p.run('state'), 'preparing');
  assert.equal(pulses(p).length, 0);

  p.tick(1000);
  p.tick(1000);
  assert.equal(p.run('state'), 'running');
  assert.equal(pulses(p).length, 0);

  pose(p, 0.95);
  p.tick(60);
  assert.equal(pulses(p).length, 1);
});

test('姿势锁定锁住基准并惩罚移动', () => {
  const p = page();
  p.run('S.frameMode = "pose"; S.poseTolerance = 3;');
  start(p);
  pose(p, 0.5);
  p.tick();
  assert.equal(p.run('referencePose !== null'), true);
  assert.equal(pulses(p).length, 0);

  // 横向 2% ≈ 12.8px / 480 ≈ 2.7%，在 3% 容差内
  pose(p, 0.52);
  p.tick();
  assert.equal(pulses(p).length, 0);

  // 横向 20% ≈ 128px / 480 ≈ 26%，超容差
  pose(p, 0.7);
  p.tick();
  assert.equal(pulses(p).length, 1);
  assert.equal(p.run('violating'), true);
});

test('限时到点自动结束，带上时间到的原因', () => {
  const p = page();
  p.run('S.sessionMinutes = 1;');
  start(p);
  for (let i = 0; i < 1100 && p.run('state') !== 'ended'; i++) {
    pose(p, 0.5);
    p.tick();
  }
  assert.equal(p.run('state'), 'ended');
  assert.equal(p.run('endReason'), 'timeout');
  assert.equal(p.elements.get('phase').textContent, '时间到');
});

test('姿态丢失时计时冻结，不会白白算掉', () => {
  const p = page();
  p.run('S.sessionMinutes = 1;');
  start(p);
  pose(p, 0.5);
  p.tick();
  const before = p.run('sessionActiveMs');
  assert.ok(before > 0);
  p.tick(5000);
  assert.equal(p.run('sessionActiveMs'), before);
});

test('包围盒模式按中心判定：两端出框但中心在内不罚', () => {
  const spread = poseAll(0.5);
  spread[11] = { x: 0.15, y: 0.5, visibility: 1 };
  spread[12] = { x: 0.15, y: 0.5, visibility: 1 };
  spread[23] = { x: 0.85, y: 0.5, visibility: 1 };
  spread[24] = { x: 0.85, y: 0.5, visibility: 1 };

  // 逐点判定：有人出框
  const a = page();
  start(a);
  poseRaw(a, spread);
  a.tick();
  assert.equal(pulses(a).length, 1);

  // 包围盒判定：中心 0.5 在框内
  const b = page();
  b.run('S.judgeMode = "bbox";');
  start(b);
  poseRaw(b, spread);
  b.tick();
  assert.equal(pulses(b).length, 0);
});

test('低姿态点集在下肢不可见时仍然可判', () => {
  const sitting = poseAll(0.5, 0.5, 0.1); // 全部低可见度
  // 只有鼻 + 双肘 + 双腕可见（肩髋被挡住）
  for (const i of [0, 13, 14, 15, 16]) sitting[i] = { x: 0.95, y: 0.5, visibility: 0.9 };

  const torso = page();
  torso.run('S.pointSet = "torso"; S.poseLost = "pause";');
  start(torso);
  poseRaw(torso, sitting);
  torso.tick();
  assert.equal(pulses(torso).length, 0, '躯干点集在这个姿态下不可判');

  const low = page();
  low.run('S.pointSet = "low"; S.poseLost = "pause";');
  start(low);
  poseRaw(low, sitting);
  low.tick();
  assert.equal(pulses(low).length, 1, '低姿态点集应该判得出来');
});

test('挑逗只在监守且未越界时触发', () => {
  const p = page();
  p.run('S.tease = true; S.teaseMinSec = 1; S.teaseMaxSec = 1; S.teaseMs = 300;');
  start(p);

  // 一直越界：只有惩罚，没有挑逗
  for (let i = 0; i < 60; i++) {
    pose(p, 0.95);
    p.tick();
  }
  assert.equal(teasePulses(p).length, 0);

  // 站回框里：挑逗出现
  for (let i = 0; i < 60; i++) {
    pose(p, 0.5);
    p.tick();
  }
  assert.ok(teasePulses(p).length >= 1);
});

test('挑逗强度永远不超过强度上限', () => {
  const p = page();
  for (const [base, max, pct] of [[20, 12, 100], [10, 30, 100], [5, 25, 40], [40, 25, 100]]) {
    p.run(`S.base = ${base}; S.max = ${max}; S.teasePercent = ${pct};`);
    assert.ok(p.run('teaseTarget()') <= max, `base=${base} max=${max}`);
  }
  p.run('S.base = 10; S.max = 30; S.teasePercent = 40;');
  assert.equal(p.run('teaseTarget()'), 4);
});

test('关闭会打断进行中的挑逗，之后不再输出', () => {
  const p = page();
  p.run('S.tease = true; S.teaseMinSec = 1; S.teaseMaxSec = 1; S.teaseMs = 300;');
  start(p);
  pose(p, 0.5);
  for (let i = 0; i < 25 && p.run('teaseTimer') === null; i++) {
    pose(p, 0.5);
    p.tick();
  }
  assert.notEqual(p.run('teaseTimer'), null);

  p.elements.get('btnRun').onclick();
  assert.equal(p.run('teaseTimer'), null);
  const stopped = p.sent.length;
  p.advance(10000);
  p.tick();
  assert.equal(p.sent.length, stopped);
});

test('日志：用当前强度试一次不会和会话重叠', () => {
  const p = page();
  start(p);
  p.elements.get('btnTest').onclick();
  assert.equal(pulses(p).length, 0);
  p.elements.get('btnRun').onclick();
  p.elements.get('btnTest').onclick();
  assert.equal(pulses(p).length, 1);
});

test('重连后从「已结束」可以重新开始', () => {
  const p = page();
  start(p);
  p.elements.get('btnRun').onclick();
  p.run('dg.phase = "waiting";');
  p.elements.get('btnRun').onclick();
  assert.equal(p.run('state'), 'ended');
  p.run('dg.phase = "paired";');
  p.elements.get('btnRun').onclick();
  assert.equal(p.run('state'), 'running');
});

// ============================================================ 调试模式

test('打开调试模式会断开现有连接，并让「连接」连到假设备', () => {
  const p = page();
  start(p);
  assert.equal(p.run('state'), 'running');

  p.elements.get('cDebug').checked = true;
  p.fire('cDebug', 'change');

  assert.equal(p.run('S.debugMode'), true);
  assert.equal(p.run('dg.phase'), 'idle');
  assert.equal(p.run('state'), 'ended', '切换模式时正在跑的会话要收摊');

  p.run('connectNow()');
  assert.equal(p.run('dg.debugMode'), true);
  assert.equal(p.run('dg.phase'), 'paired');
  assert.equal(p.run('dg.devices[0].name'), '调试设备');

  const before = p.sent.length;
  p.run('connectNow()');
  p.run('dg.emergency();');
  assert.equal(p.sent.length, before, '调试模式不建立任何网络连接');
});

test('调试模式下判定照跑，但指令只进日志不发送', () => {
  const p = page();
  p.run('dg.disconnect(); S.debugMode = true; connectNow();');
  const before = p.sent.length;

  start(p);
  pose(p, 0.95);
  p.tick();

  assert.equal(p.sent.length, before, '不能有任何真实下发');
  assert.equal(p.run('dg.intensity[0]'), 5, '本地强度仍要更新');
  assert.ok(p.run('dg.debugLog.some(e => e.text.startsWith("波形 A"))'), '要记录波形');
});

test('调试日志能读懂归零和清队列', () => {
  const p = page();
  p.run('dg.disconnect(); S.debugMode = true; connectNow();');
  p.run('dg.emergency();');
  const texts = Array.from(p.run('dg.debugLog.map(e => e.text)'));
  assert.deepEqual(texts.slice(-3), ['归零 A', '归零 B', '清空 A 通道波形']);
  assert.equal(p.sent.length, 0);
});

test('关掉调试模式后恢复真实下发', () => {
  const p = page();
  p.run('dg.disconnect(); S.debugMode = true; connectNow();');
  start(p);
  pose(p, 0.95);
  p.tick();
  assert.equal(p.sent.length, 0);

  p.elements.get('cDebug').checked = false;
  p.fire('cDebug', 'change');
  assert.equal(p.run('S.debugMode'), false);
  assert.equal(p.run('dg.debugMode'), false);
  assert.equal(p.run('dg.debugLog.length'), 0);

  // 重新接上真实中继（测试里直接摆成已配对态）
  p.attach();
  p.run(`dg.phase = 'paired'; dg.appId = 'app'; dg.devices = [{ slotId: 'device' }];`);
  start(p);
  pose(p, 0.95);
  p.tick();
  assert.equal(pulses(p).length, 1);
});
