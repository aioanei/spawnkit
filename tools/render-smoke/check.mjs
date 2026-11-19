// Browser integration test for the local, test-only Wasm preview. Node 22+, no npm dependencies.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

const url = new URL(process.argv[2] ?? 'http://127.0.0.1:8081/');
assert(url.protocol === 'http:' && ['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname), 'Use a local test server.');
const output = resolve('build/render-smoke/results');
await mkdir(output, { recursive: true });
const profile = await mkdtemp(join(output, 'profile-'));
const chrome = process.env.CHROME_BIN ?? (process.platform === 'win32'
  ? 'C:/Program Files/Google/Chrome/Application/chrome.exe' : 'google-chrome');
const child = spawn(chrome, [
  '--headless', '--no-first-run', '--no-default-browser-check',
  '--remote-debugging-address=127.0.0.1', '--remote-debugging-port=0',
  `--user-data-dir=${profile}`, 'about:blank',
], { windowsHide: true, stdio: ['ignore', 'ignore', 'pipe'] });
let chromeLog = '';
let launchError;
child.stderr.on('data', bytes => { chromeLog += bytes; });
child.on('error', error => { launchError = error; });
let socket;
const report = { url: url.href, checks: [], screenshots: [], errors: [] };

async function waitFor(predicate, label, timeout = 20000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (launchError) throw launchError;
    const value = await predicate();
    if (value) return value;
    await delay(30);
  }
  throw new Error(`Timed out: ${label}`);
}

try {
  const portFile = await waitFor(async () => {
    try { return await readFile(join(profile, 'DevToolsActivePort'), 'utf8'); } catch { return null; }
  }, 'isolated Chrome startup');
  const port = portFile.split('\n')[0];
  const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  const target = targets.find(item => item.type === 'page' && item.url === 'about:blank');
  assert(target, 'Chrome created a fresh blank test page.');
  socket = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((ok, fail) => {
    socket.addEventListener('open', ok, { once: true });
    socket.addEventListener('error', fail, { once: true });
  });
  let sequence = 0;
  const pending = new Map();
  let state;
  let fontLoaded = false;
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    if (message.id) {
      const request = pending.get(message.id);
      if (!request) return;
      pending.delete(message.id);
      clearTimeout(request.timer);
      if (message.error) request.fail(new Error(JSON.stringify(message.error)));
      else request.ok(message.result);
    } else if (message.method === 'Runtime.consoleAPICalled') {
      for (const argument of message.params.args) {
        if (typeof argument.value === 'string' && argument.value.startsWith('SPAWNKIT_QA ')) {
          state = JSON.parse(argument.value.slice('SPAWNKIT_QA '.length));
        }
      }
    } else if (message.method === 'Runtime.exceptionThrown') {
      report.errors.push(message.params.exceptionDetails.exception?.description ?? message.params.exceptionDetails.text);
    } else if (message.method === 'Network.responseReceived') {
      const response = message.params.response;
      if (response.url.endsWith('/font/lilita_one.ttf') && response.status === 200) fontLoaded = true;
    }
  });
  function send(method, params = {}) {
    return new Promise((ok, fail) => {
      const id = ++sequence;
      const timer = setTimeout(() => { pending.delete(id); fail(new Error(`Protocol timeout: ${method}`)); }, 10000);
      pending.set(id, { ok, fail, timer });
      socket.send(JSON.stringify({ id, method, params }));
    });
  }
  async function ticks(count) {
    const targetTick = state.tick + count;
    return { ...await waitFor(() => state?.tick >= targetTick && state, `${count} simulation ticks`) };
  }
  async function key(code, key, down) {
    await send('Input.dispatchKeyEvent', { type: down ? 'rawKeyDown' : 'keyUp', code, key,
      windowsVirtualKeyCode: key === ' ' ? 32 : key.toUpperCase().charCodeAt(0) });
  }
  async function screenshot(name) {
    const result = await send('Page.captureScreenshot', { format: 'png' });
    await writeFile(join(output, name), Buffer.from(result.data, 'base64'));
    report.screenshots.push(name);
  }
  function passed(name) { report.checks.push(name); console.log(`PASS ${name}`); }

  report.browser = await send('Browser.getVersion');
  await send('Runtime.enable');
  await send('Network.enable');
  await send('Page.enable');
  await send('Emulation.setFocusEmulationEnabled', { enabled: true });
  await send('Emulation.setDeviceMetricsOverride', { width: 1600, height: 1000, deviceScaleFactor: 1, mobile: false });
  await send('Page.navigate', { url: url.href });
  await waitFor(() => state?.tick >= 30 && state.grounded && fontLoaded, 'real world, grounded player and bundled font', 45000);
  report.initialFocus = await send('Runtime.evaluate', { expression: 'document.hasFocus()', returnByValue: true });
  assert.equal(report.initialFocus.result.value, true, 'Test page starts with host window focus.');
  await send('Page.bringToFront');
  // The browser owns DOM canvas focus; enter it through a real pointer tap.
  await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: 800, y: 400, button: 'left', clickCount: 1 });
  await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: 800, y: 400, button: 'left', clickCount: 1 });
  assert.equal(state.deaths, 0);
  passed('Wasm starts and loads bundled Lilita One');
  await screenshot('wasm-gameview-wide.png');

  const start = { ...state };
  await key('KeyD', 'd', true);
  const moved = await ticks(40);
  await key('KeyD', 'd', false);
  assert(moved.x > start.x + 4, `Keyboard moved player: ${JSON.stringify({ start, moved })}`);
  assert(moved.coins >= 1, 'Movement collects a real coin and updates the HUD variable.');
  const released = await ticks(3);
  const idle = await ticks(15);
  assert.equal(idle.x, released.x, 'Key release stops movement.');
  passed('keyboard movement, coin collection and release');
  await screenshot('wasm-gameview-collected.png');

  const groundY = state.y;
  await key('Space', ' ', true);
  const jumped = await ticks(6);
  assert(jumped.y < groundY - 0.5, 'Space jumps through the browser input path.');
  await key('Space', ' ', false);
  await waitFor(() => state.grounded, 'landing after keyboard jump');
  passed('keyboard jump');

  await key('KeyD', 'd', true);
  await ticks(5);
  await send('Emulation.setFocusEmulationEnabled', { enabled: false });
  const blurTarget = await send('Target.createTarget', { url: 'about:blank' });
  await send('Target.activateTarget', { targetId: blurTarget.targetId });
  report.blurredFocus = await send('Runtime.evaluate', { expression: 'document.hasFocus()', returnByValue: true });
  assert.equal(report.blurredFocus.result.value, false, 'Browser test actually removed window focus.');
  // A background tab may suspend animation frames. Return before observing simulation ticks;
  // deliberately do not send key-up until the stuck-key assertion has passed.
  await delay(150);
  await send('Target.closeTarget', { targetId: blurTarget.targetId });
  await send('Target.activateTarget', { targetId: target.id });
  await send('Emulation.setFocusEmulationEnabled', { enabled: true });
  const blurred = await ticks(3);
  const afterBlur = await ticks(15);
  assert.equal(afterBlur.x, blurred.x, 'Losing focus releases a held movement key.');
  await key('KeyD', 'd', false);
  await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: 800, y: 400, button: 'left', clickCount: 1 });
  await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: 800, y: 400, button: 'left', clickCount: 1 });
  const refocused = { ...state };
  await key('KeyA', 'a', true);
  const resumedMovement = await ticks(5);
  await key('KeyA', 'a', false);
  assert(resumedMovement.x < refocused.x - 0.3, 'Canvas tap restores keyboard control.');
  passed('focus loss clears held input and canvas tap restores focus');

  await send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
  await waitFor(() => state.grounded, 'grounded before touch check');
  const beforeTouch = { ...state };
  await send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [
    { x: 154, y: 938, id: 1 }, { x: 1538, y: 938, id: 2 },
  ] });
  const touched = await ticks(8);
  assert(touched.x > beforeTouch.x + 0.3, 'Right touch button moves the player.');
  assert(touched.y < beforeTouch.y - 0.3, 'Jump works while movement touch is held.');
  await send('Input.dispatchTouchEvent', { type: 'touchCancel', touchPoints: [] });
  const cancelled = await ticks(3);
  const afterCancel = await ticks(15);
  assert.equal(afterCancel.x, cancelled.x, 'Touch cancellation releases movement.');
  passed('simultaneous touch movement/jump and cancellation');

  await send('Emulation.setDeviceMetricsOverride', { width: 1080, height: 2400, deviceScaleFactor: 1, mobile: true });
  await ticks(15);
  await screenshot('wasm-gameview-tall.png');
  await send('Emulation.setDeviceMetricsOverride', { width: 3440, height: 1000, deviceScaleFactor: 1, mobile: false });
  await ticks(15);
  await screenshot('wasm-gameview-ultrawide.png');
  passed('tall and ultra-wide viewport captures');
  assert.deepEqual(report.errors, [], 'No uncaught browser runtime exceptions.');
  assert.equal(state.deaths, 0, 'Smoke interactions stayed in a safe part of the real template.');
  report.finalState = state;
  report.passed = true;
  await send('Browser.close').catch(() => {});
} catch (error) {
  report.passed = false;
  report.failure = error.stack;
  throw error;
} finally {
  socket?.close();
  if (child.exitCode === null) child.kill();
  await writeFile(join(output, 'report.json'), JSON.stringify(report, null, 2) + '\n');
  await writeFile(join(output, 'chrome.log'), chromeLog);
}
