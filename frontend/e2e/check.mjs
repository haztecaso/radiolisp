// End-to-end check of radiolisp, driven over the Chrome DevTools Protocol.
//
//   bb dev                      # in another shell, or serve a release build
//   bb e2e                      # URL=http://host:port/ to point elsewhere
//
// Uses the real Radio Browser API and real streams, so it needs network.
// It never touches Radio Browser's statistics: /json/url (click counting)
// and /json/vote are blocked. Chromium runs with IPv6 and its async DNS
// resolver off: on some networks those stall the first request to a host for
// many seconds, which measures the network rather than the app. Screenshots
// land in frontend/target/e2e.
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Browser, sleep } from '../../.agents/skills/design-taste-frontend/scripts/lib/cdp.mjs';

const URL = process.env.URL || 'http://localhost:8080/';
const OUT = join(dirname(fileURLToPath(import.meta.url)), '..', 'target', 'e2e');
mkdirSync(OUT, { recursive: true });

const results = [];
const check = (name, ok, detail = '') => { results.push({ name, ok, detail }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`); };

// Real Radio Browser stations (uuids are stable across servers).
const STATIONS = {
  bbc: { id: '98adecf7-2683-4408-9be7-02d3f9098eb8', name: 'BBC World Service' },
  inter: { id: '33960c43-0464-44b4-abfa-73591ebf647f', name: 'France Inter' }, // HLS
  jazz: { id: 'd28420a4-eccf-47a2-ace1-088c7e7cb7e0', name: '101 SMOOTH JAZZ' },
  sky: { id: '512e876e-806b-4a61-8a57-f15e39b64907', name: 'sky news UK' }, // fails its checks
};
const STORAGE_KEY = 'radiolisp/favorites';
const edn = (favs) => `{:version 1 :favorites [${favs.map((f) => `{:id "${f.id}" :name "${f.name}" :key ${f.key ? `"${f.key}"` : 'nil'}}`).join(' ')}]}`;

const KEYS = {
  Escape: { code: 'Escape', windowsVirtualKeyCode: 27 },
  Enter: { code: 'Enter', windowsVirtualKeyCode: 13, text: '\r' },
  ArrowDown: { code: 'ArrowDown', windowsVirtualKeyCode: 40 },
  ArrowUp: { code: 'ArrowUp', windowsVirtualKeyCode: 38 },
  Backspace: { code: 'Backspace', windowsVirtualKeyCode: 8 },
  ' ': { code: 'Space', windowsVirtualKeyCode: 32, text: ' ' },
  '/': { code: 'Slash', windowsVirtualKeyCode: 191, text: '/' },
  '?': { code: 'Slash', windowsVirtualKeyCode: 191, text: '?' },
};

async function press(page, key, { ctrl = false, shift = false } = {}) {
  const spec = KEYS[key] || { code: /\d/.test(key) ? 'Digit' + key : 'Key' + key.toUpperCase(), windowsVirtualKeyCode: key.toUpperCase().charCodeAt(0), text: key };
  const modifiers = (ctrl ? 2 : 0) | (shift ? 8 : 0);
  const text = ctrl ? undefined : spec.text;
  await page.send('Input.dispatchKeyEvent', { type: text ? 'keyDown' : 'rawKeyDown', key, code: spec.code, windowsVirtualKeyCode: spec.windowsVirtualKeyCode, modifiers, text, unmodifiedText: text });
  await page.send('Input.dispatchKeyEvent', { type: 'keyUp', key, code: spec.code, windowsVirtualKeyCode: spec.windowsVirtualKeyCode, modifiers });
  await sleep(80);
}

async function type(page, s, gap = 60) {
  for (const ch of s) { await press(page, ch); await sleep(gap); }
}

const text = (page, sel) => page.eval(`document.querySelector(${JSON.stringify(sel)})?.textContent?.trim() ?? null`);
const exists = (page, sel) => page.eval(`!!document.querySelector(${JSON.stringify(sel)})`);
const activeId = (page) => page.eval(`document.activeElement?.id || document.activeElement?.tagName`);
const tileNames = (page) => page.eval(`[...document.querySelectorAll('.tile .name')].map(e => e.textContent)`);
const stored = (page) => page.eval(`localStorage.getItem(${JSON.stringify(STORAGE_KEY)})`);
const storedIds = async (page) => [...((await stored(page)) || '').matchAll(/:id "([^"]+)"/g)].map((m) => m[1]);
const toast = (page) => text(page, '.toast.show');
const barName = (page) => text(page, '.bar .now b span');
const rowCount = (page) => page.eval(`document.querySelectorAll('#search-list [role=option]').length`);
const dialogOpen = (page) => page.eval(`document.getElementById('dialog').open`);

async function waitFor(page, expr, timeoutMs = 12000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) { if (await page.eval(expr)) return true; await sleep(150); }
  return false;
}

async function click(page, sel) {
  await page.eval(`document.querySelector(${JSON.stringify(sel)}).click()`);
  await sleep(150);
}

// Drags the tile at `from` (1-based) onto `to`, a selector.
async function drag(page, fromSel, toSel) {
  await page.eval(`(() => {
    const dt = new DataTransfer();
    const from = document.querySelector(${JSON.stringify(fromSel)});
    const to = document.querySelector(${JSON.stringify(toSel)});
    from.dispatchEvent(new DragEvent('dragstart', { bubbles: true, dataTransfer: dt }));
    to.dispatchEvent(new DragEvent('dragover', { bubbles: true, cancelable: true, dataTransfer: dt }));
    to.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: dt }));
    from.dispatchEvent(new DragEvent('dragend', { bubbles: true, dataTransfer: dt }));
  })()`);
  await sleep(150);
}

// Before any script runs: remember the <audio> element, record downloads, and
// optionally pretend the browser has no native HLS so hls.js is used.
const initScript = ({ noNativeHls }) => `
  ${noNativeHls ? `const cp = HTMLMediaElement.prototype.canPlayType;
  HTMLMediaElement.prototype.canPlayType = function (t) { return /mpegurl/i.test(t) ? '' : cp.call(this, t); };` : ''}
  const A = window.Audio;
  window.Audio = function () { const a = new A(); window.__audio = a; return a; };
  window.__downloads = [];
  const click = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (this.download) { const name = this.download; fetch(this.href).then(r => r.text()).then(t => window.__downloads.push({ name, text: t })); return; }
    return click.call(this);
  };`;

async function newApp(browser, { width, height, mobile = false, seed = null, noNativeHls = false }) {
  const page = await browser.newPage({ width, height, mobile });
  await page.send('Network.enable');
  await page.send('Network.setBlockedURLs', { urls: ['*/json/url/*', '*/json/vote/*'] });
  await page.send('Emulation.setUserAgentOverride', { userAgent: 'Mozilla/5.0 radiolisp-e2e', acceptLanguage: 'es-ES,es' });
  await page.send('Page.addScriptToEvaluateOnNewDocument', { source: initScript({ noNativeHls }) });
  page.requests = [];
  browser.on(page.sessionId, 'Network.requestWillBeSent', (p) => { if (p.request.method === 'GET') page.requests.push(p.request.url); });
  await page.goto(URL, { settleMs: 200 });
  await page.eval(seed ? `localStorage.setItem(${JSON.stringify(STORAGE_KEY)}, ${JSON.stringify(seed)})` : 'localStorage.clear()');
  page.requests.length = 0;
  await page.goto(URL, { settleMs: 500 });
  return page;
}

const count = (page, re) => page.requests.filter((u) => re.test(u)).length;
const playing = (page) => waitFor(page, `window.__audio && !window.__audio.paused && window.__audio.readyState >= 3`, 15000);

const browser = await Browser.launch({ width: 1440, height: 900, extraArgs: ['--disable-ipv6', '--disable-features=AsyncDns'] });
try {
  // ---------------------------------------------------------------- empty start, adding from the palette
  const page = await newApp(browser, { width: 1440, height: 900 });
  check('empty state without favourites', await waitFor(page, `!!document.querySelector('.empty-state')`), await text(page, '.empty-state b'));
  check('no station request without favourites', count(page, /byuuid/) === 0);
  await page.screenshot({ path: `${OUT}/1-empty-desktop.jpg` });

  await press(page, 'a');
  check('a opens the palette', await exists(page, '.palette'));
  check('focus in the palette input', (await activeId(page)) === 'search-input');
  check('palette shows the top list', await waitFor(page, `document.querySelectorAll('#search-list [role=option]').length > 0`), await text(page, '.list-head span'));

  await type(page, 'BBC World Service');
  await waitFor(page, `document.querySelector('.list-head span')?.textContent.includes('emisora')`);
  check('search finds results', (await rowCount(page)) > 0, `${await rowCount(page)} rows`);
  await press(page, 'Enter', { shift: true });
  check('shift+enter adds a favourite', /añadida a favoritas/.test((await toast(page)) || ''), await toast(page));
  check('heart turns on in the row', await exists(page, '#search-list .hl .fav.on'));
  await page.screenshot({ path: `${OUT}/2-palette-desktop.jpg` });

  for (let i = 0; i < 17; i++) await press(page, 'Backspace');
  await type(page, '101 SMOOTH JAZZ');
  await waitFor(page, `document.querySelector('.list-head span')?.textContent.includes('emisora')`);
  await press(page, 'Escape');
  check('esc switches to browsing', (await text(page, '.list-head .mode')) === 'navegando');
  await press(page, 'f');
  check('f adds the highlighted station', (await storedIds(page)).length === 2, String((await storedIds(page)).length));
  await press(page, 'Enter');
  check('enter plays without closing the palette', (await exists(page, '.palette')) && (await barName(page)) !== 'Nada sonando', await barName(page));
  await press(page, 'Escape');
  check('esc closes the palette', !(await exists(page, '.palette')));
  check('grid shows both favourites and the add tile', (await tileNames(page)).length === 2 && (await exists(page, '.add-tile')), JSON.stringify(await tileNames(page)));

  // ---------------------------------------------------------------- reload: one request for every favourite
  const seed = edn([
    { ...STATIONS.bbc, key: '1' }, STATIONS.jazz, STATIONS.sky,
    { id: '00000000-dead-beef-0000-000000000000', name: 'Gone FM' },
  ]);
  const grid = await newApp(browser, { width: 1440, height: 900, seed });
  await waitFor(grid, `document.querySelectorAll('.tile .art img, .tile .art .mono').length >= 4`);
  await waitFor(grid, `!!document.querySelector('.flag')`);
  check('one byuuid request loads every favourite', count(grid, /byuuid/) === 1, String(count(grid, /byuuid/)));
  check('no search request on start', count(grid, /stations\/(search|topclick)/) === 0);
  check('order kept from storage', JSON.stringify(await tileNames(grid)) === JSON.stringify(['BBC World Service', '101 SMOOTH JAZZ', 'sky news UK', 'Gone FM']), JSON.stringify(await tileNames(grid)));
  const flags = await grid.eval(`[...document.querySelectorAll('.flag')].map(e => e.textContent)`);
  check('failing station flagged', flags.includes('Sin señal'), JSON.stringify(flags));
  check('vanished station flagged', flags.includes('Ya no está en Radio Browser'));
  check('assigned key shown on its tile', (await text(grid, '.tile:nth-child(1) .keycap')) === '1');
  await grid.screenshot({ path: `${OUT}/3-grid-desktop.jpg` });

  // play: click a tile, counts a click (blocked), pauses on a second click
  await click(grid, '.tile:nth-child(1) .play');
  check('clicking a tile plays it', (await barName(grid)) === 'BBC World Service', await barName(grid));
  check('playing counts a click in Radio Browser', count(grid, /\/json\/url\//) === 1);
  check('stream plays', await playing(grid), await text(grid, '.bar .meta-line'));
  check('playing tile outlined', await exists(grid, '.tile.playing'));
  check('quality chip in the bar', /MP3/.test((await text(grid, '.bar .q')) || ''), await text(grid, '.bar .q'));
  await grid.screenshot({ path: `${OUT}/4-playing-desktop.jpg` });
  await click(grid, '.tile:nth-child(1) .play');
  check('a second click pauses', await waitFor(grid, `window.__audio.paused`, 3000));
  await grid.eval(`document.activeElement.blur()`);
  await press(grid, ' ');
  check('space resumes', await playing(grid));

  // keys: assign with the dialog, then play with it
  await click(grid, '.tile:nth-child(2) .keycap');
  check('key dialog opens', await dialogOpen(grid));
  await press(grid, 'j');
  check('reserved key refused', /la usa radiolisp/.test((await text(grid, '.keymsg')) || ''), await text(grid, '.keymsg'));
  await press(grid, '1');
  check('taken key warns it moves', /Ahora es de/.test((await text(grid, '.keymsg')) || ''), await text(grid, '.keymsg'));
  await press(grid, 'q');
  check('free key previewed', (await text(grid, '.keyprev')) === 'q');
  await grid.screenshot({ path: `${OUT}/5-key-dialog-desktop.jpg` });
  await press(grid, 'Enter');
  check('enter saves the key', !(await dialogOpen(grid)) && (await text(grid, '.tile:nth-child(2) .keycap')) === 'q');
  check('key persisted', /:key "q"/.test(await stored(grid)));
  await press(grid, 'q');
  check('the assigned key plays its favourite', (await barName(grid)) === '101 SMOOTH JAZZ', await barName(grid));
  await press(grid, '1');
  check('another key switches station', (await barName(grid)) === 'BBC World Service', await barName(grid));

  // the station menu
  await click(grid, '.tile:nth-child(3) .more');
  check('station menu opens', await exists(grid, '#menu'));
  check('menu focuses its first item', await grid.eval(`document.activeElement?.getAttribute('role') === 'menuitem'`));
  check('menu shows the failed check', /Falló la comprobación/.test((await text(grid, '#menu .info')) || ''), await text(grid, '#menu .info'));
  await grid.screenshot({ path: `${OUT}/6-menu-desktop.jpg` });
  await press(grid, 'Escape');
  check('esc closes the menu', !(await exists(grid, '#menu')));
  await click(grid, '.tile:nth-child(3) .more');
  await grid.eval(`[...document.querySelectorAll('#menu [role=menuitem]')].find(e => /Mover al principio/.test(e.textContent)).click()`);
  await sleep(150);
  check('move to front', (await tileNames(grid))[0] === 'sky news UK', JSON.stringify(await tileNames(grid)));
  check('new order persisted', (await storedIds(grid))[0] === STATIONS.sky.id);
  await click(grid, '.tile:nth-child(2) .more');
  await grid.eval(`[...document.querySelectorAll('#menu [role=menuitem]')].find(e => /Votar/.test(e.textContent)).click()`);
  check('vote request sent (blocked here) and reported', await waitFor(grid, `/votar/i.test(document.querySelector('.toast.show')?.textContent || '')`, 5000), await toast(grid));
  check('vote went to /json/vote', count(grid, /\/json\/vote\//) === 1);

  // drag and drop
  await drag(grid, '.tile:nth-child(1)', '.add-tile');
  check('drag to the end', (await tileNames(grid)).at(-1) === 'sky news UK', JSON.stringify(await tileNames(grid)));
  await drag(grid, '.tile:nth-child(3)', '.tile:nth-child(1)');
  check('drag before a tile', (await tileNames(grid))[0] === 'Gone FM', JSON.stringify(await tileNames(grid)));
  check('drag order persisted', (await storedIds(grid))[0] === '00000000-dead-beef-0000-000000000000');

  // filter: a funnel button until it is used
  check('filter folded at rest', !(await exists(grid, '#filter-input')));
  await press(grid, '/');
  check('/ focuses the filter', (await activeId(grid)) === 'filter-input');
  await type(grid, 'jazz');
  check('filter narrows the grid', JSON.stringify(await tileNames(grid)) === JSON.stringify(['101 SMOOTH JAZZ']), JSON.stringify(await tileNames(grid)));
  check('add tile hidden while filtering', !(await exists(grid, '.add-tile')));
  await press(grid, 'Enter');
  check('enter plays the first match', (await barName(grid)) === '101 SMOOTH JAZZ', await barName(grid));
  await press(grid, 'Escape');
  check('esc clears the filter and folds it', !(await exists(grid, '#filter-input')));
  await press(grid, '/');
  await type(grid, 'qzxq');
  check('no match offers a Radio Browser search', /Buscar «qzxq» en Radio Browser/.test((await text(grid, '.empty-state .text-btn')) || ''));
  await grid.screenshot({ path: `${OUT}/7-no-match-desktop.jpg` });
  await click(grid, '.empty-state .text-btn');
  check('the link opens the palette with the query', (await grid.eval(`document.getElementById('search-input')?.value`)) === 'qzxq');
  await press(grid, 'Escape'); await press(grid, 'Escape');
  await grid.eval(`document.getElementById('filter-input')?.focus()`);
  await press(grid, 'Escape');

  // removing asks first
  await click(grid, '.bar .fav');
  check('the bar heart asks before removing', await dialogOpen(grid), await text(grid, '#dialog h2'));
  await grid.screenshot({ path: `${OUT}/8-remove-desktop.jpg` });
  await click(grid, '#dialog .btn.danger');
  check('removed after confirming', !(await tileNames(grid)).includes('101 SMOOTH JAZZ') && !(await dialogOpen(grid)), JSON.stringify(await tileNames(grid)));
  check('removal persisted', !(await storedIds(grid)).includes(STATIONS.jazz.id));
  check('the station keeps playing', (await barName(grid)) === '101 SMOOTH JAZZ');

  // export and import
  await click(grid, '#list-menu');
  await grid.eval(`[...document.querySelectorAll('#menu [role=menuitem]')].find(e => /Exportar/.test(e.textContent)).click()`);
  await waitFor(grid, `window.__downloads.length > 0`, 3000);
  const download = (await grid.eval(`window.__downloads[0]`)) || {};
  check('export downloads an M3U', download.name === 'radiolisp-favoritas.m3u' && download.text.startsWith('#EXTM3U\n'), download.name);
  check('the M3U carries the uuids', download.text?.includes(`radiobrowser-uuid="${STATIONS.bbc.id}"`));
  check('keys are not exported', !/:key|"1"/.test(download.text || ''));

  const m3u = join(OUT, 'import.m3u');
  writeFileSync(m3u, [
    '#EXTM3U',
    `#EXTINF:-1 radiobrowser-uuid="${STATIONS.inter.id}",France Inter`, 'https://example.invalid/whatever.m3u8',
    `#EXTINF:-1 radiobrowser-uuid="${STATIONS.bbc.id}",BBC`, 'https://example.invalid/bbc',
    '#EXTINF:-1,Not in Radio Browser', 'https://example.invalid/nothing.mp3', '',
  ].join('\n'));
  await grid.send('Page.setInterceptFileChooserDialog', { enabled: true });
  const chooser = new Promise((resolve) => browser.on(grid.sessionId, 'Page.fileChooserOpened', resolve));
  await click(grid, '#list-menu');
  await grid.eval(`[...document.querySelectorAll('#menu [role=menuitem]')].find(e => /Importar/.test(e.textContent)).click()`);
  const { backendNodeId } = await chooser;
  await grid.send('DOM.setFileInputFiles', { files: [m3u], backendNodeId });
  check('import reports added and missing', await waitFor(grid, `/Importada 1 · 1 no encontrada/.test(document.querySelector('.toast.show')?.textContent || '')`, 15000), await toast(grid));
  check('imported station in the grid', (await tileNames(grid)).includes('France Inter'), JSON.stringify(await tileNames(grid)));
  check('no duplicates after import', (await storedIds(grid)).filter((id) => id === STATIONS.bbc.id).length === 1);

  // help and language
  await grid.eval(`document.activeElement.blur()`);
  await press(grid, '?');
  check('? opens help', await grid.eval(`document.getElementById('help').open`));
  await grid.screenshot({ path: `${OUT}/9-help-desktop.jpg` });
  await press(grid, 'Escape');
  await sleep(200);
  check('esc closes help', !(await grid.eval(`document.getElementById('help').open`)));
  await click(grid, '#list-menu');
  await grid.eval(`[...document.querySelectorAll('#menu [role=menuitem]')].find(e => /Atajos/.test(e.textContent)).click()`);
  await sleep(150);
  check('the list menu opens the shortcuts', await grid.eval(`document.getElementById('help').open`));
  await press(grid, 'Escape');
  await sleep(200);
  await click(grid, '.top button[lang=en]');
  check('EN switches texts', (await grid.eval(`document.querySelector('.top .add').getAttribute('aria-label')`)) === 'Add station');
  check('html lang updated', (await grid.eval(`document.documentElement.lang`)) === 'en');
  await click(grid, '.top button[lang=es]');

  check('no uncaught exceptions (desktop)', page.consoleErrors.length + grid.consoleErrors.length === 0, [...page.consoleErrors, ...grid.consoleErrors].join(' | '));
  await page.close();
  await grid.close();

  // ---------------------------------------------------------------- HLS through hls.js
  const hls = await newApp(browser, { width: 1280, height: 800, seed: edn([STATIONS.inter]), noNativeHls: true });
  await waitFor(hls, `!document.querySelector('.tile .play')?.disabled`);
  check('hls.js not downloaded up front', count(hls, /\/js\/hls\.js/) === 0);
  await click(hls, '.tile .play');
  check('HLS station plays through hls.js', await playing(hls), await text(hls, '.bar .meta-line'));
  check('hls.js loaded on demand', count(hls, /\/js\/hls\.js/) === 1);
  check('HLS chip in the bar', (await text(hls, '.bar .q')) === 'HLS');
  check('no uncaught exceptions (hls)', hls.consoleErrors.length === 0, hls.consoleErrors.join(' | '));
  await hls.close();

  // ---------------------------------------------------------------- phone
  const phone = await newApp(browser, { width: 390, height: 844, mobile: true, seed });
  await waitFor(phone, `!!document.querySelector('.flag')`);
  const columns = await phone.eval(`getComputedStyle(document.querySelector('.grid')).gridTemplateColumns.split(' ').length`);
  check('phone grid has two columns', columns === 2, String(columns));
  check('phone shows the tile menu button', await phone.eval(`getComputedStyle(document.querySelector('.tile .more')).opacity === '1'`));
  await phone.screenshot({ path: `${OUT}/10-grid-phone.jpg` });
  await click(phone, '.tile:nth-child(1) .play');
  await sleep(500);
  await phone.screenshot({ path: `${OUT}/11-playing-phone.jpg` });
  check('phone hides the volume slider', await phone.eval(`getComputedStyle(document.querySelector('.vol .slider')).display === 'none'`));
  await click(phone, '.top .add');
  await waitFor(phone, `document.querySelectorAll('#search-list [role=option]').length > 0`);
  await phone.screenshot({ path: `${OUT}/12-palette-phone.jpg` });
  const box = await phone.eval(`(() => { const r = document.querySelector('.palette .box').getBoundingClientRect(); return { w: r.width, h: r.height }; })()`);
  check('phone palette fills the screen', box.w === 390 && box.h >= 840, JSON.stringify(box));
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
