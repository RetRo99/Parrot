const MAX_BYTES = 20000;
const RECIPIENT = 'rok@parrotapp.dev';
const SENDER = 'Parrot Support <rok@parrotapp.dev>';
const headers = {
  'Content-Type': 'text/html; charset=utf-8',
  'Cache-Control': 'no-store',
  'Content-Security-Policy': "default-src 'none'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
  'Referrer-Policy': 'no-referrer',
  'X-Content-Type-Options': 'nosniff',
};

function error(status, message) {
  return new Response(`<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Message not sent · Parrot</title><link rel="stylesheet" href="/email-links.css"></head><body><!--email_off--><main><h1>Message not sent</h1><p>${message}</p><p>Use your browser’s Back button to return to your message, or <a href="/support#contact">return to support</a>. You can also email <a href="mailto:${RECIPIENT}">${RECIPIENT}</a>.</p></main><!--/email_off--></body></html>`, { status, headers });
}

function accepted() {
  return new Response(null, { status: 303, headers: { Location: '/support/message-sent', 'Cache-Control': 'no-store' } });
}

async function readLimited(request) {
  const reader = request.body?.getReader();
  if (!reader) return '';
  let length = 0;
  const chunks = [];
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    length += value.length;
    if (length > MAX_BYTES) { await reader.cancel(); throw new Error('oversize'); }
    chunks.push(value);
  }
  const buffer = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { buffer.set(chunk, offset); offset += chunk.length; }
  return new TextDecoder().decode(buffer);
}

export async function handleContact({ request, env }, send = fetch, now = Date.now()) {
  if (request.method !== 'POST') return new Response('Method not allowed', { status: 405, headers: { ...headers, Allow: 'POST' } });
  const url = new URL(request.url);
  if (request.headers.get('origin') !== url.origin) return error(403, 'Please submit the form from this website.');
  if (request.headers.get('sec-fetch-site') && !['same-origin', 'none'].includes(request.headers.get('sec-fetch-site'))) return error(403, 'Please submit the form from this website.');
  if (request.headers.get('content-type')?.split(';')[0].trim() !== 'application/x-www-form-urlencoded') return error(415, 'Unsupported form format.');
  if (Number(request.headers.get('content-length')) > MAX_BYTES) return error(413, 'Your message is too large.');
  let form;
  try { form = new URLSearchParams(await readLimited(request)); }
  catch { return error(413, 'Your message is too large.'); }
  if (form.get('website')) return accepted();
  if (['email', 'message', 'website'].some(key => form.getAll(key).length > 1)) return error(400, 'Please submit one email address and one message.');
  const email = (form.get('email') ?? '').trim();
  const message = (form.get('message') ?? '').trim();
  if (email.length > 254 || !/^[^\s@<>\x00-\x1f\x7f]+@[^\s@<>\x00-\x1f\x7f]+\.[^\s@<>\x00-\x1f\x7f]+$/.test(email)) return error(400, 'Enter a valid email address.');
  if (message.length < 10 || message.length > 5000 || message.includes('\0')) return error(400, 'Your message must contain between 10 and 5,000 characters.');
  if (!env.RESEND_API_KEY || !env.CONTACT_RATE_SALT || !env.CONTACT_DB) return error(503, 'The contact form is not configured yet. Please email us instead.');
  const ip = request.headers.get('cf-connecting-ip');
  if (!ip) return error(503, 'The contact form is temporarily unavailable. Please email us instead.');
  try {
    const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(env.CONTACT_RATE_SALT), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
    const bucket = Math.floor(now / 3600000);
    const digest = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(`${bucket}:${ip}`));
    const identifier = [...new Uint8Array(digest)].map(byte => byte.toString(16).padStart(2, '0')).join('');
    await env.CONTACT_DB.prepare('DELETE FROM contact_limits WHERE expires_at <= ?').bind(now).run();
    const row = await env.CONTACT_DB.prepare('INSERT INTO contact_limits (key, count, expires_at) VALUES (?, 1, ?) ON CONFLICT(key) DO UPDATE SET count = count + 1 WHERE count < 5 RETURNING count').bind(identifier, (bucket + 1) * 3600000).first();
    if (!row) return error(429, 'Too many messages. Please try again in an hour or email us.');
    const response = await send('https://api.resend.com/emails', {
      method: 'POST',
      headers: { Authorization: `Bearer ${env.RESEND_API_KEY}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ from: SENDER, to: [RECIPIENT], reply_to: email, subject: 'Parrot website support request', text: `Reply address: ${email}\n\n${message}` }),
      signal: AbortSignal.timeout(10000),
    });
    if (!response.ok) return error(502, 'We could not send your message. Please try later or email us.');
    return accepted();
  } catch { return error(503, 'We could not send your message. Please try later or email us.'); }
}

export const onRequest = context => handleContact(context);
