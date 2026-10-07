import { createServer } from 'node:http';
import { readFile, stat, mkdir } from 'node:fs/promises';
import { resolve, extname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import AxeBuilder from '@axe-core/playwright';

const root = resolve(fileURLToPath(new URL('../dist/', import.meta.url)));
const results = fileURLToPath(new URL('../test-results/', import.meta.url));
await mkdir(results, { recursive: true });
const mime = { '.html': 'text/html', '.css': 'text/css', '.webp': 'image/webp', '.ttf': 'font/ttf', '.png': 'image/png' };
const server = createServer(async (req, res) => {
  try {
    let path = resolve(root, `.${decodeURIComponent(new URL(req.url, 'http://localhost').pathname)}`);
    if (path !== root && !path.startsWith(root + sep)) { res.writeHead(403).end(); return; }
    if ((await stat(path)).isDirectory()) path = resolve(path, 'index.html');
    res.setHeader('Content-Type', mime[extname(path)] ?? 'application/octet-stream');
    res.end(await readFile(path));
  } catch { res.writeHead(404).end(); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
const browser = await chromium.launch();
const contactOnly = process.argv.includes('--contact-only');
const paths = contactOnly ? ['/support', '/support/message-sent'] : ['/', '/privacy', '/terms', '/support'];
let count = 0;
try {
  for (const width of contactOnly ? [390, 1280] : [390, 600, 768, 900, 1100, 1280, 1600]) {
    for (const colorScheme of ['light', 'dark']) {
      const context = await browser.newContext({ viewport: { width, height: 900 }, colorScheme, javaScriptEnabled: false });
      const requests = [];
      context.on('request', request => requests.push(request.url()));
      const page = await context.newPage();
      for (const path of paths) {
        const response = await page.goto(origin + path);
        assert.equal(response.status(), 200, `${path} direct load`);
        await page.evaluate(() => document.fonts.ready);
        const state = await page.evaluate(() => ({
          h1: document.querySelectorAll('h1').length,
          scripts: document.querySelectorAll('script').length,
          overflow: document.documentElement.scrollWidth > innerWidth,
          images: [...document.images].every(image => image.hasAttribute('alt') && image.hasAttribute('width') && image.hasAttribute('height') && image.complete && image.naturalWidth > 0),
          headings: [...document.querySelectorAll('h1,h2,h3,h4,h5,h6')].map(el => Number(el.tagName[1])),
          anchors: [...document.querySelectorAll('a[href^="#"]')].every(el => document.getElementById(el.getAttribute('href').slice(1))),
          storage: localStorage.length + sessionStorage.length,
        }));
        assert.equal(state.h1, 1, `${path} one h1`);
        assert.equal(state.scripts, 0, `${path} no scripts`);
        assert.equal(state.overflow, false, `${path} no overflow at ${width}`);
        assert.equal(state.images, true, `${path} images loaded, sized and labelled`);
        assert.equal(state.anchors, true, `${path} section links resolve`);
        assert.equal(state.storage, 0, `${path} no browser storage`);
        if (path === '/') {
          const headline = await page.locator('h1').evaluate(el => ({ height: el.getBoundingClientRect().height, line: parseFloat(getComputedStyle(el).lineHeight) }));
          assert.ok(headline.height / headline.line <= 3.1, `Headline at most three lines at ${width}`);
          const geometry = await page.evaluate(() => {
            const frame = document.querySelector('.reader-illustration').getBoundingClientRect();
            const player = document.querySelector('.reader-player').getBoundingClientRect();
            const names = [...document.querySelectorAll('.works-inner strong')].map(el => el.getBoundingClientRect().top);
            return { playerInside: player.bottom <= frame.bottom - 8, namesTogether: Math.max(...names) - Math.min(...names) < 2 };
          });
          assert.ok(geometry.playerInside, `Full player inside phone at ${width}`);
          assert.ok(geometry.namesTogether, `Works-with names together at ${width}`);
        }
        for (let i = 1; i < state.headings.length; i++) assert.ok(state.headings[i] <= state.headings[i - 1] + 1, `${path} headings in order`);
        await page.keyboard.press('Tab');
        assert.equal(await page.locator('.skip-link').evaluate(el => el === document.activeElement), true, 'Keyboard skip link is first');
        await page.locator('.skip-link').evaluate(el => el.blur());
        if (path === '/' && colorScheme === 'dark' && [390, 768, 1280].includes(width)) {
          await page.screenshot({ path: resolve(results, `landing-${width}-${colorScheme}.png`), fullPage: true });
        }
        if (path === '/privacy') assert.equal(await page.locator('#recaps').count(), 1);
        if (path === '/terms') assert.equal(await page.locator('#your-books-and-notes').count(), 1);
        if (path === '/support') assert.equal(await page.locator('#delete-account').count(), 1);
        count++;
      }
      assert.ok(requests.every(url => new URL(url).origin === origin), `No third-party requests: ${requests.filter(url => new URL(url).origin !== origin)}`);
      assert.deepEqual(await context.cookies(), [], 'No cookies');
      await context.close();
      // Accessibility engine needs JS; the pages themselves still contain none.
      const axeContext = await browser.newContext({ viewport: { width, height: 900 }, colorScheme });
      const axePage = await axeContext.newPage();
      for (const path of paths) {
        await axePage.goto(origin + path);
        await axePage.evaluate(() => document.fonts.ready);
        const report = await new AxeBuilder({ page: axePage }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
        assert.deepEqual(report.violations.map(v => ({ id: v.id, elements: v.nodes.map(n => n.target) })), [], `${path}, ${width}px, ${colorScheme}: accessibility`);
      }
      await axeContext.close();
    }
  }
  console.log(`${count} JavaScript-disabled page checks and accessibility checks passed; no third-party requests, cookies or browser storage.`);
} finally {
  await browser.close();
  server.close();
}
