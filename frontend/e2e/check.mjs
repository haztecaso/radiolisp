// End-to-end check of radiolisp, driven over the Chrome DevTools Protocol.
//
//   bb dev                      # in another shell, or serve a release build
//   bb e2e                      # URL=http://host:port/ to point elsewhere
//
// Uses the real Radio Browser API and real streams, so it needs network.
// Chromium runs with IPv6 and its async DNS resolver off: on some networks
// those stall the first request to a host for many seconds, which measures
// the network rather than the app. Screenshots land in frontend/target/e2e.
import { mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Browser, sleep } from '../../.agents/skills/design-taste-frontend/scripts/lib/cdp.mjs';

const URL = process.env.URL || 'http://localhost:8080/';
const OUT = join(dirname(fileURLToPath(import.meta.url)), '..', 'target', 'e2e');
mkdirSync(OUT, { recursive: true });

const results = [];
const check = (name, ok, detail = '') => { results.push({ name, ok, detail }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`); };

const KEYS = {
  Escape: { code: 'Escape', windowsVirtualKeyCode: 27 },
  Enter: { code: 'Enter', windowsVirtualKeyCode: 13, text: '\r' },
  ArrowDown: { code: 'ArrowDown', windowsVirtualKeyCode: 40 },
  ArrowUp: { code: 'ArrowUp', windowsVirtualKeyCode: 38 },
  Backspace: { code: 'Backspace', windowsVirtualKeyCode: 8 },
  ' ': { code: 'Space', windowsVirtualKeyCode: 32, text: ' ' },
};

async function press(page, key, { ctrl = false, shift = false } = {}) {
  const spec = KEYS[key] || { code: 'Key' + key.toUpperCase(), windowsVirtualKeyCode: key.toUpperCase().charCodeAt(0), text: key };
  const modifiers = (ctrl ? 2 : 0) | (shift ? 8 : 0);
  const text = ctrl ? undefined : spec.text;
  await page.send('Input.dispatchKeyEvent', { type: text ? 'keyDown' : 'rawKeyDown', key, code: spec.code, windowsVirtualKeyCode: spec.windowsVirtualKeyCode, modifiers, text, unmodifiedText: text });
  await page.send('Input.dispatchKeyEvent', { type: 'keyUp', key, code: spec.code, windowsVirtualKeyCode: spec.windowsVirtualKeyCode, modifiers });
  await sleep(80);
}

async function type(page, s, gap = 60) {
  for (const ch of s) { await press(page, ch); await sleep(gap); }
}

const q = (page, fn) => page.eval(fn);
const text = (page, sel) => page.eval(`document.querySelector(${JSON.stringify(sel)})?.textContent?.trim() ?? null`);
const exists = (page, sel) => page.eval(`!!document.querySelector(${JSON.stringify(sel)})`);
const selectedIndex = (page) => page.eval(`[...document.querySelectorAll('#search-list [role=option]')].findIndex(e => e.getAttribute('aria-selected') === 'true')`);
const rowCount = (page) => page.eval(`document.querySelectorAll('#search-list [role=option]').length`);
const activeId = (page) => page.eval(`document.activeElement?.id || document.activeElement?.tagName`);

async function waitFor(page, expr, timeoutMs = 12000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) { if (await page.eval(expr)) return true; await sleep(150); }
  return false;
}

async function click(page, sel) {
  await page.eval(`document.querySelector(${JSON.stringify(sel)}).click()`);
  await sleep(150);
}

async function newApp(browser, { width, height, mobile = false }) {
  const page = await browser.newPage({ width, height, mobile });
  await page.send('Network.enable');
  await page.send('Emulation.setUserAgentOverride', { userAgent: 'Mozilla/5.0 radiolisp-e2e', acceptLanguage: 'es-ES,es' });
  page.requests = [];
  browser.on(page.sessionId, 'Network.requestWillBeSent', (p) => { if (p.request.method === 'GET') page.requests.push(p.request.url); });
  await page.goto(URL, { settleMs: 500 });
  return page;
}

const count = (page, re) => page.requests.filter((u) => re.test(u)).length;

const browser = await Browser.launch({ width: 1440, height: 900, extraArgs: ['--disable-ipv6', '--disable-features=AsyncDns'] });
try {
  // ---------------------------------------------------------------- desktop
  const page = await newApp(browser, { width: 1440, height: 900 });
  check('first screen loads top list', await waitFor(page, `document.querySelectorAll('#search-list [role=option]').length > 0`));
  check('Spanish detected', (await text(page, 'h1')) === 'Nada sonando', await text(page, 'h1'));
  check('list head is top', (await text(page, '.list-head span')) === 'Más escuchadas hoy');
  check('focus starts in search input', (await activeId(page)) === 'search-input');
  check('no close button without a station', !(await exists(page, '.field .close')));
  await page.screenshot({ path: `${OUT}/1-first-desktop.jpg` });

  const searchesBefore = count(page, /stations\/search/);
  await type(page, 'jazz');
  await waitFor(page, `document.querySelector('.list-head span')?.textContent.includes('emisoras')`);
  const searches = count(page, /stations\/search/) - searchesBefore;
  check('typing "jazz" fires one search request', searches === 1, `${searches} requests`);
  check('results shown', (await rowCount(page)) > 0, `${await rowCount(page)} rows`);
  await page.screenshot({ path: `${OUT}/2-results-desktop.jpg` });

  const topBefore = count(page, /topclick/);
  for (let i = 0; i < 3; i++) await press(page, 'Backspace');
  await sleep(500);
  check('short query returns to cached top list', (await text(page, '.list-head span')) === 'Más escuchadas hoy');
  check('cached top list needs no request', count(page, /topclick/) === topBefore);

  await type(page, 'azz');
  await waitFor(page, `document.querySelector('.list-head span')?.textContent.includes('emisoras')`);
  await press(page, 'ArrowDown'); await press(page, 'ArrowDown');
  check('ArrowDown moves highlight', (await selectedIndex(page)) === 2, String(await selectedIndex(page)));
  await press(page, 'k', { ctrl: true });
  check('ctrl-k moves up', (await selectedIndex(page)) === 1);
  check('query untouched by ctrl-k', (await page.eval(`document.getElementById('search-input').value`)) === 'jazz');
  await press(page, 'Escape');
  check('esc switches to browsing mode', (await text(page, '.list-head .mode')) === 'navegando');
  check('focus moves to list', (await activeId(page)) === 'search-list');
  await press(page, 'j');
  check('j moves down', (await selectedIndex(page)) === 2);
  await press(page, 'G', { shift: true });
  const n = await rowCount(page);
  check('G jumps to last', (await selectedIndex(page)) === n - 1, `${await selectedIndex(page)} of ${n}`);
  await press(page, 'g'); await press(page, 'g');
  check('gg jumps to first', (await selectedIndex(page)) === 0);
  await page.screenshot({ path: `${OUT}/3-browsing-desktop.jpg` });

  await press(page, 'Enter');
  check('enter closes the panel', !(await exists(page, '#panel')) && (await exists(page, '#search-opener')));
  check('focus on play toggle', (await activeId(page)) === 'player-toggle');
  const live = await waitFor(page, `/En directo|No se pudo/.test(document.querySelector('.status')?.textContent || '')`, 14000);
  const status = await text(page, '.status');
  check('stream connects or reports error', live, status);
  check('stream plays', /En directo/.test(status || ''), status);
  await sleep(2200);
  check('clock advances', /00:00:0[1-9]/.test((await text(page, '.clock')) || ''), await text(page, '.clock'));
  await page.screenshot({ path: `${OUT}/4-playing-desktop.jpg` });

  await press(page, ' ');
  await sleep(300);
  check('space on focused toggle pauses', /En pausa/.test((await text(page, '.status')) || ''), await text(page, '.status'));
  await click(page, '#search-opener');
  await press(page, 'Escape'); await press(page, 'Escape');
  check('esc esc closes search', !(await exists(page, '#panel')));
  await page.eval(`document.activeElement.blur()`);
  await press(page, ' ');
  await waitFor(page, `/En directo/.test(document.querySelector('.status')?.textContent || '')`, 12000);
  check('space (global) resumes', /En directo|Conectando/.test((await text(page, '.status')) || ''), await text(page, '.status'));
  await press(page, 'h'); await press(page, 'h');
  check('h h lowers volume to 60', (await text(page, '.vol-val')) === '60 %', await text(page, '.vol-val'));
  await press(page, 'l');
  check('l raises volume to 65', (await text(page, '.vol-val')) === '65 %');
  await press(page, 'm');
  check('m mutes', (await text(page, '.vol-val')) === '0 %');
  await press(page, 'm');
  check('m unmutes back to 65', (await text(page, '.vol-val')) === '65 %');

  await press(page, '/');
  check('/ opens search with close button', (await exists(page, '#panel')) && (await exists(page, '.field .close')));
  check('playing station marked in list', await exists(page, '.st.on'));
  await page.screenshot({ path: `${OUT}/5-search-over-player-desktop.jpg` });
  await click(page, '.field .close');
  check('close button closes search', !(await exists(page, '#panel')));

  await press(page, '?');
  check('? opens help', await page.eval(`document.getElementById('help').open`));
  await page.screenshot({ path: `${OUT}/6-help-desktop.jpg` });
  await press(page, 'Escape');
  await sleep(200);
  check('esc closes help', !(await page.eval(`document.getElementById('help').open`)));
  await press(page, 'm');
  check('keys work again after help closes', (await text(page, '.vol-val')) === '0 %');
  await press(page, 'm');

  await click(page, '.corner button[lang=en]');
  check('EN switches texts', (await text(page, '#search-opener span')) === 'Search stations', await text(page, '#search-opener span'));
  check('html lang updated', (await page.eval(`document.documentElement.lang`)) === 'en');
  check('aria-label translated', /^Pause |^Play /.test(await page.eval(`document.getElementById('player-toggle').getAttribute('aria-label')`)));
  await click(page, '.corner button[lang=es]');

  // offline error and retry
  await page.send('Network.emulateNetworkConditions', { offline: true, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
  await press(page, '/');
  await type(page, 'rock');
  const errored = await waitFor(page, `!!document.querySelector('.note.error')`, 10000);
  check('offline search shows network error', errored, await text(page, '.note.error span'));
  await page.screenshot({ path: `${OUT}/7-error-desktop.jpg` });
  await page.send('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
  await click(page, '.note.error .text-btn');
  check('retry recovers', await waitFor(page, `document.querySelectorAll('#search-list [role=option]').length > 0`, 12000));

  await press(page, 'Escape');
  for (let i = 0; i < 4; i++) await press(page, 'Backspace');
  await press(page, 'i');
  for (let i = 0; i < 4; i++) await press(page, 'Backspace');
  await type(page, 'qzxqzxqzx');
  check('nonsense query shows empty note', await waitFor(page, `!!document.querySelector('p.note')`, 10000), await text(page, 'p.note'));

  check('no uncaught exceptions (desktop)', page.consoleErrors.length === 0, page.consoleErrors.join(' | '));
  await page.close();

  // ---------------------------------------------------------------- phone
  const phone = await newApp(browser, { width: 390, height: 844, mobile: true });
  await waitFor(phone, `document.querySelectorAll('#search-list [role=option]').length > 0`);
  await phone.screenshot({ path: `${OUT}/8-first-phone.jpg` });
  const sheet = await phone.eval(`(() => { const c = document.querySelector('.col').getBoundingClientRect(); return {w: c.width, h: c.height}; })()`);
  check('phone search is a full-screen sheet', sheet.w === 390 && sheet.h >= 840, JSON.stringify(sheet));
  await click(phone, '#search-list [role=option]');
  await waitFor(phone, `/En directo|No se pudo/.test(document.querySelector('.status')?.textContent || '')`, 14000);
  await phone.screenshot({ path: `${OUT}/9-player-phone.jpg` });
  check('phone hides volume slider', await phone.eval(`getComputedStyle(document.querySelector('.slider')).display === 'none'`));
  await click(phone, '#search-opener');
  await phone.screenshot({ path: `${OUT}/10-search-phone.jpg` });
  check('phone player shrinks to a strip while searching', await phone.eval(`document.getElementById('player').getBoundingClientRect().height < 80`));
  const overflow = await phone.eval(`document.documentElement.scrollWidth > window.innerWidth`);
  check('no horizontal scroll on phone', !overflow);
  check('no uncaught exceptions (phone)', phone.consoleErrors.length === 0, phone.consoleErrors.join(' | '));
  await phone.close();
} finally {
  await browser.close();
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
