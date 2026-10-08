import { test } from 'node:test';
import assert from 'node:assert/strict';
import { handleContact } from '../functions/api/contact.js';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { chromium } from 'playwright';

function setup() {
  let calls = 0;
  let payload;
  let count = 0;
  const env = { RESEND_API_KEY: 'test-only', CONTACT_RATE_SALT: 'test-only-salt', CONTACT_DB: {
    prepare(sql) { return { bind(...values) {
      assert.ok(!values.some(value => String(value).includes('192.0.2.1')), 'Raw IP is never stored');
      return { run: async () => ({}), first: async () => count < 5 ? { count: ++count } : null };
    } }; },
  } };
  const send = async (url, options) => { calls++; payload = JSON.parse(options.body); return new Response('{}', { status: 200 }); };
  const request = (data = {}, headers = {}) => new Request('https://parrotapp.dev/api/contact', { method: 'POST', headers: { origin: 'https://parrotapp.dev', 'content-type': 'application/x-www-form-urlencoded', 'cf-connecting-ip': '192.0.2.1', ...headers }, body: new URLSearchParams({ email: 'reader@example.com', message: 'Please help with my library.', website: '', ...data }) });
  return { env, send, request, calls: () => calls, payload: () => payload };
}
test('Valid form sends plain text to fixed recipient, then redirects', async () => {
  const s = setup();
  const result = await handleContact({ request: s.request({ message: '<script>example</script>' }), env: s.env }, s.send);
  assert.equal(result.status, 303);
  assert.equal(result.headers.get('location'), '/support/message-sent');
  assert.deepEqual(s.payload().to, ['rok@parrotapp.dev']);
  assert.equal(s.payload().reply_to, 'reader@example.com');
  assert.equal(s.payload().html, undefined);
});
test('Browser form preserves same-origin POST origin under the shipped referrer policy', async () => {
  const policy = readFileSync(new URL('../public/_headers', import.meta.url), 'utf8')
    .match(/Referrer-Policy:\s*(\S+)/)[1];
  assert.equal(policy, 'same-origin');
  let postedOrigin;
  const server = createServer((req, res) => {
    if (req.method === 'POST') {
      postedOrigin = req.headers.origin;
      req.resume();
      res.end('Accepted');
    } else {
      res.setHeader('Referrer-Policy', policy);
      res.setHeader('Content-Type', 'text/html');
      res.end('<form action="/api/contact" method="post"><button>Send</button></form>');
    }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let browser;
  try {
    browser = await chromium.launch();
    const page = await browser.newPage();
    const origin = `http://127.0.0.1:${server.address().port}`;
    await page.goto(origin);
    await Promise.all([page.waitForURL(`${origin}/api/contact`), page.getByRole('button', { name: 'Send' }).click()]);
    assert.equal(postedOrigin, origin);
  } finally {
    await browser?.close();
    await new Promise(resolve => server.close(resolve));
  }
});
test('Validation, header injection, cross-origin and oversized bodies never send', async () => {
  const s = setup();
  for (const [req, status] of [
    [s.request({ email: 'bad' }), 400],
    [s.request({ email: 'a@example.com\r\nBcc:x@y.com' }), 400],
    [s.request({ message: 'short' }), 400],
    [s.request({ message: 'x'.repeat(5001) }), 400],
    [s.request({}, { origin: 'https://evil.example' }), 403],
    [s.request({}, { 'content-type': 'application/json' }), 415],
    [s.request({ message: 'x'.repeat(21000) }), 413],
  ]) assert.equal((await handleContact({ request: req, env: s.env }, s.send)).status, status);
  assert.equal(s.calls(), 0);
});
test('Honeypot pretends success without sending', async () => {
  const s = setup();
  assert.equal((await handleContact({ request: s.request({ website: 'spam' }), env: s.env }, s.send)).status, 303);
  assert.equal(s.calls(), 0);
});
test('Rate limit allows five attempts per bucket then blocks', async () => {
  const s = setup();
  for (let i = 0; i < 5; i++) assert.equal((await handleContact({ request: s.request(), env: s.env }, s.send)).status, 303);
  assert.equal((await handleContact({ request: s.request(), env: s.env }, s.send)).status, 429);
  assert.equal(s.calls(), 5);
});
test('Unconfigured form, DB failures and provider failures fail safely', async () => {
  const s = setup();
  assert.equal((await handleContact({ request: s.request(), env: {} }, s.send)).status, 503);
  assert.equal((await handleContact({ request: s.request(), env: s.env }, async () => new Response('private provider details', { status: 500 }))).status, 502);
  const result = await handleContact({ request: s.request(), env: s.env }, async () => { throw new Error('private details'); });
  assert.equal(result.status, 503);
  assert.ok(!(await result.text()).includes('private details'));
});
test('GET rejected', async () => {
  assert.equal((await handleContact({ request: new Request('https://parrotapp.dev/api/contact'), env: {} })).status, 405);
});
test('Error email uses shared script-free link styling and bypasses email obfuscation', async () => {
  const s = setup();
  const response = await handleContact({ request: s.request({ email: 'bad' }), env: s.env }, s.send);
  assert.match(response.headers.get('content-security-policy'), /style-src 'self'/);
  const html = await response.text();
  assert.match(html, /href="\/email-links\.css"/);
  assert.match(html, /<!--email_off-->[\s\S]*href="mailto:rok@parrotapp\.dev"[\s\S]*<!--\/email_off-->/);
  assert.doesNotMatch(html, /<script|email-protection/);
});
test('Real SQLite schema enforces quota, rotating buckets and expired-counter cleanup', async () => {
  const database = new DatabaseSync(':memory:');
  database.exec(readFileSync(new URL('../functions-schema.sql', import.meta.url), 'utf8'));
  const s = setup();
  s.env.CONTACT_DB = { prepare(sql) { return { bind(...values) {
    const statement = database.prepare(sql);
    return { run: async () => statement.run(...values), first: async () => statement.get(...values) ?? null };
  } }; } };
  try {
    const start = 3600000;
    for (let i = 0; i < 5; i++) assert.equal((await handleContact({ request: s.request(), env: s.env }, s.send, start)).status, 303);
    assert.equal((await handleContact({ request: s.request(), env: s.env }, s.send, start)).status, 429);
    const previous = database.prepare('SELECT * FROM contact_limits').get();
    assert.equal(previous.count, 5);
    assert.match(previous.key, /^[a-f0-9]{64}$/);
    assert.equal((await handleContact({ request: s.request(), env: s.env }, s.send, start + 3600000)).status, 303);
    const rows = database.prepare('SELECT * FROM contact_limits').all();
    assert.equal(rows.length, 1);
    assert.notEqual(rows[0].key, previous.key);
    assert.equal(rows[0].count, 1);
  } finally { database.close(); }
});
