// Built-site UI review with JavaScript disabled; never sends real email.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFileSync, existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { resolve, extname } from 'node:path';
import { homedir } from 'node:os';
import { chromium } from 'playwright';
import AxeBuilder from '@axe-core/playwright';

const output = resolve(homedir(), 'Downloads/Parrot Website Screenshots/UI-only review/Round 2');
mkdirSync(output, { recursive: true });
const root = resolve('dist');
const types = { '.html': 'text/html', '.css': 'text/css', '.svg': 'image/svg+xml', '.webp': 'image/webp', '.ttf': 'font/ttf' };
let lastPost;
const server = createServer((req, res) => {
  if (req.method === 'POST') {
    lastPost = { url: req.url, origin: req.headers.origin };
    req.resume(); res.writeHead(200, { 'Content-Type': 'text/html' }); res.end('Test submission intercepted locally'); return;
  }
  let path = resolve(root, '.' + new URL(req.url, 'http://localhost').pathname);
  if (!extname(path)) path = resolve(path, 'index.html');
  if (!path.startsWith(root + '/') || !existsSync(path)) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'Content-Type': types[extname(path)] ?? 'application/octet-stream', 'Referrer-Policy': 'same-origin' });
  res.end(readFileSync(path));
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
const browser = await chromium.launch();
const auditContext = await browser.newContext({ colorScheme: 'dark' });
const auditPage = await auditContext.newPage();
const routes = ['/', '/support', '/delete-account', '/copyright', '/legal-notice', '/privacy', '/terms'];
const results = [];
try {
  for (const width of [390, 768, 1280]) {
    const context = await browser.newContext({ viewport: { width, height: 900 }, colorScheme: 'dark', javaScriptEnabled: false, deviceScaleFactor: 1 });
    const page = await context.newPage();
    for (const route of routes) {
      await page.goto(origin + route);
      await page.evaluate(() => document.fonts.ready);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `${route} overflows at ${width}`);
      if (route === '/') {
        assert.ok(await page.locator('.server-illustration').evaluate(panel => {
          const style = getComputedStyle(panel);
          const bounds = panel.getBoundingClientRect();
          const left = bounds.left + parseFloat(style.paddingLeft);
          const right = bounds.right - parseFloat(style.paddingRight);
          return [...panel.querySelectorAll('.server-row')].every(row => {
            const rect = row.getBoundingClientRect();
            return rect.left >= left - 1 && rect.right <= right + 1 && row.scrollWidth <= row.clientWidth + 1;
          });
        }), `Server cards overflow their padded container at ${width}`);
      }
      const footer = await page.locator('.site-footer nav a').evaluateAll(links => {
        const rows = {};
        for (const link of links) {
          const top = Math.round(link.getBoundingClientRect().top);
          rows[top] = (rows[top] ?? 0) + 1;
        }
        return Object.values(rows);
      });
      assert.ok(footer.every(count => count > 1), `${route} has a footer orphan at ${width}`);
      const images = await page.locator('img').evaluateAll(images => images.map(img => ({ decorative: !!img.closest('.brand'), alt: img.getAttribute('alt') })));
      assert.ok(images.every(img => img.decorative ? img.alt === '' : !!img.alt?.trim()), `${route} image alt`);
      assert.equal(await page.locator('.site-header .brand img').evaluate(img => img.getBoundingClientRect().width), 36);
      assert.equal(await page.locator('.site-footer .brand img').evaluate(img => img.getBoundingClientRect().width), 28);
      assert.ok(await page.locator('.site-header .brand img').isVisible());
      const toc = page.locator('.legal-toc');
      if (await toc.count()) assert.equal(await toc.isVisible(), width >= 1100);
      if (await page.locator('.legal-page').count()) {
        const headings = await page.locator('.legal-prose h2').allTextContents();
        assert.equal(await toc.count(), headings.length >= 4 ? 1 : 0, `${route} contents threshold`);
        assert.ok(headings.every(text => !/^\d+\./.test(text)), `${route} duplicate heading numbers`);
        assert.ok(await page.locator('.legal-prose h2').evaluateAll(headings => headings.every(h => getComputedStyle(h, '::before').content.includes('counter(legal-section)'))));
        if (headings.length >= 4) {
          assert.deepEqual(await toc.locator('li a').allTextContents(), headings);
          assert.ok(await toc.locator('li a').evaluateAll(links => links.every(a => getComputedStyle(a, '::before').content.includes('counter(contents-section)'))));
        }
      }
      assert.equal(await page.locator('a[href*="email-protection"], .__cf_email__').count(), 0);
      assert.ok(await page.evaluate(() => {
        const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
        for (let node = walker.nextNode(); node; node = walker.nextNode()) {
          if (/[^\s]+@[^\s]+\.[^\s]+/.test(node.textContent) && !node.parentElement.closest('a[href^="mailto:"]')) return false;
        }
        return true;
      }), `${route} unlinked email address`);
      for (const mail of await page.locator('a[href^="mailto:"]').all()) {
        assert.ok(await mail.isVisible());
        await mail.focus();
        assert.equal(await mail.evaluate(el => getComputedStyle(el).outlineStyle), 'solid');
        const label = await mail.innerText();
        if (label.includes('@')) {
          assert.ok(label.includes('rok@parrotapp.dev'));
          assert.ok(await mail.evaluate(el => {
            const style = getComputedStyle(el);
            const probe = document.createElement('span');
            probe.style.color = 'var(--accent-text)'; el.append(probe);
            const accent = getComputedStyle(probe).color; probe.remove();
            return style.color === accent && style.textDecorationLine.includes('underline');
          }));
        }
      }
      if (route === '/support') {
        assert.equal(await page.locator('#contact-email').evaluate(el => el.getBoundingClientRect().height), 52);
        assert.equal(await page.locator('#contact-email').evaluate(el => getComputedStyle(el).borderRadius), '14px');
        assert.equal(await page.locator('#contact-website').isVisible(), false);
        assert.ok(!(await page.locator('body').innerText()).includes('Leave this field empty'));
        assert.ok(!(await page.locator('form').ariaSnapshot()).includes('Leave this field empty'));
        await page.locator('#contact-website').focus();
        assert.equal(await page.locator('#contact-website').evaluate(el => document.activeElement === el), false);
        await page.locator('#contact-email').focus();
        await page.keyboard.press('Tab');
        assert.equal(await page.locator('#contact-message').evaluate(el => document.activeElement === el), true);
        await page.keyboard.press('Tab');
        assert.equal(await page.locator('button[type="submit"]').evaluate(el => document.activeElement === el), false);
        assert.equal(await page.locator('#contact-website').evaluate(el => document.activeElement === el), false);
      }
      await page.locator('h1').click(); // Clear test focus rings before capture.
      if (width !== 768) {
        // Separate tooling-only page permits axe injection; captures and form
        // interaction above remain in the JavaScript-disabled context.
        await auditPage.setViewportSize({ width, height: 900 });
        await auditPage.goto(origin + route);
        assert.equal(await auditPage.locator('script').count(), 0);
        const accessibility = await new AxeBuilder({ page: auditPage }).analyze();
        assert.deepEqual(accessibility.violations.map(v => ({ id: v.id, targets: v.nodes.map(n => n.target) })), [], `${route} accessibility at ${width}`);
        const name = route === '/' ? 'home' : route.slice(1);
        await page.screenshot({ path: resolve(output, `${name}-dark-${width}.png`), fullPage: true });
      }
      results.push({ route, width, footerRows: footer, result: 'pass' });
    }
    if (width === 390) {
      await page.goto(origin + '/support');
      await page.locator('button[type="submit"]').click();
      assert.ok(await page.locator('#contact-email-error').isVisible(), 'Inline email error');
      await page.locator('#contact-email').fill('reader@example.com');
      await page.locator('#contact-message').fill('This is a local UI test; no real email is sent.');
      await Promise.all([page.waitForURL('**/api/contact'), page.locator('button[type="submit"]').click()]);
      assert.deepEqual(lastPost, { url: '/api/contact', origin });
    }
    await context.close();
  }
  writeFileSync(resolve(output, 'checks.json'), JSON.stringify(results, null, 2));
  console.log(`PASS: 21 page/width checks; 14 dark screenshots; scripts disabled; no email sent.\n${output}`);
} finally {
  await browser.close();
  await new Promise(resolve => server.close(resolve));
}
