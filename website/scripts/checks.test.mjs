import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { checkTokens } from './check-tokens.mjs';
import { inspectOutput } from './check-output.mjs';

const kotlin = readFileSync(new URL('../../base-ui/src/commonMain/kotlin/com/retro99/base/ui/compose/EmberTokens.kt', import.meta.url), 'utf8');
const css = readFileSync(new URL('../src/styles/tokens.css', import.meta.url), 'utf8');
test('Both theme token sets match Kotlin', () => assert.deepEqual(checkTokens(kotlin, css), []));
test('A changed colour fails the check', () => assert.ok(checkTokens(kotlin, css.replace('#F7F1E8', '#FFFFFF')).length));
test('A removed or invented token fails', () => {
  assert.ok(checkTokens(kotlin, css.replace('--bg: #F7F1E8;', '')).length);
  assert.ok(checkTokens(kotlin, css.replace('--bg:', '--invented:')).length);
});
test('Hard-coded CSS colours outside tokens fail', () => assert.ok(checkTokens(kotlin, css, 'body{color:#123456}').length));
test('All required markers fail production', () => {
  for (const marker of ['CONFIRM', 'DECIDE', 'LAWYER CHECK', 'LEGAL NAME', 'ADDRESS', 'CONTACT EMAIL', 'AI PROVIDER', 'DOMAIN', '200 MB', '60', 'LAST UPDATED']) {
    assert.ok(inspectOutput([['index.html', `<p>[${marker}]</p>`]]).errors.length, marker);
  }
});
test('Encoded markers cannot bypass production gate', () => assert.ok(inspectOutput([['index.html', '<p>&#91;CONFIRM&#93;</p>']]).errors.length));
test('Preview permits drafts but not scripts or remote resources', () => {
  assert.deepEqual(inspectOutput([['index.html', '[CONFIRM]']], true).errors, []);
  assert.ok(inspectOutput([['index.html', '<script></script>']], true).errors.length);
  assert.ok(inspectOutput([['index.html', '<img src="https://example.com/a">']], true).errors.length);
  assert.ok(inspectOutput([['site.css', '@import "https://example.com/a.css";']], true).errors.length);
  assert.ok(inspectOutput([['assets/app.js', '']], true).errors.length);
});
test('Local fonts and normal external document links are permitted', () => assert.deepEqual(inspectOutput([
  ['index.html', '<a href="mailto:hello@example.com">Email</a><a href="https://example.com">Reference</a>'],
  ['site.css', '@font-face{src:url(/fonts/font.ttf)}'],
]).errors, []));
