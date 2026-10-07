import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { loadLegal, parseLegal, sectionId } from '../src/lib/legal.ts';

test('Required legal app anchors are stable', () => {
  assert.equal(sectionId('5. Recaps'), 'recaps');
  assert.equal(sectionId('5. Your books and notes'), 'your-books-and-notes');
  assert.ok(loadLegal('privacy').sections.some(section => section.id === 'recaps'));
  assert.ok(loadLegal('terms').sections.some(section => section.id === 'your-books-and-notes'));
});
test('All numbered sections and draft notices are retained', () => {
  for (const [kind, count] of [['privacy', 14], ['terms', 14]]) {
    const document = loadLegal(kind);
    assert.equal(document.sections.length, count);
    assert.match(document.notices, /Draft · not yet in force/);
    assert.match(document.notices, /DRAFT for legal review/);
    assert.match(document.body, /\[LEGAL NAME\]/);
  }
});
test('Privacy covers website processing and optional content transfers', () => {
  const document = loadLegal('privacy');
  assert.ok(document.sections.some(section => section.id === 'this-website-and-support'));
  assert.match(document.short, /files you choose to back up/);
  assert.match(document.body, /website host/i);
});
test('Deletion and reporting pages stay accessible from the footer', () => {
  const footer = readFileSync(new URL('../src/layouts/Page.astro', import.meta.url), 'utf8');
  for (const route of ['delete-account', 'copyright', 'legal-notice']) {
    assert.ok(footer.includes(`href="/${route}"`));
    const source = readFileSync(new URL(`../src/pages/${route}.astro`, import.meta.url), 'utf8');
    assert.match(source, /Page title=/);
  }
  const deletion = readFileSync(new URL('../src/pages/delete-account.astro', import.meta.url), 'utf8');
  assert.match(deletion, /mailto:/);
  assert.match(deletion, /180 days/);
  assert.match(deletion, /Never send your password/);
});
test('Last updated is never fabricated', () => {
  const source = readFileSync(new URL('../../design/ember/website/legal/privacy.md', import.meta.url), 'utf8');
  assert.equal(parseLegal(source, 'privacy').lastUpdated, undefined);
  assert.equal(parseLegal(`---\nlastUpdated: '2026-10-07'\n---\n${source}`, 'privacy').lastUpdated, '2026-10-07');
  assert.throws(() => parseLegal(`---\nlastUpdated: '2026-02-30'\n---\n${source}`, 'privacy'));
});
