import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

const kebab = name => name.replace(/[A-Z]/g, letter => `-${letter.toLowerCase()}`);

export function checkTokens(kotlin, css, otherCss = '') {
  const errors = [];
  const blocks = [...css.matchAll(/:root\s*\{([^}]+)\}/g)].map(match => match[1]);
  if (blocks.length !== 2) errors.push('Expected exactly two token blocks (Day and Night).');
  for (const [index, mode] of ['Day', 'Night'].entries()) {
    const source = kotlin.match(new RegExp(`val Ember${mode}Colors = EmberColors\\(([\\s\\S]*?)\\n\\)`))?.[1];
    if (!source) { errors.push(`Missing ${mode} Kotlin colours`); continue; }
    const expected = new Map([...source.matchAll(/(\w+)\s*=\s*Color(?:\(0xFF([A-Fa-f0-9]{6})\)|\.(White|Black|Transparent))/g)]
      .map(([, name, hex, constant]) => [kebab(name), hex ? `#${hex}` : { White: '#FFFFFF', Black: '#000000', Transparent: 'transparent' }[constant]]));
    expected.set('surface-selected', 'color-mix(in srgb, var(--accent) 6%, var(--surface))');
    const actual = new Map([...((blocks[index] ?? '').matchAll(/--([\w-]+)\s*:\s*([^;]+);/g))].map(([, name, value]) => [name, value.trim()]));
    for (const [name, value] of expected) {
      if (actual.get(name)?.toLowerCase() !== value.toLowerCase()) errors.push(`${mode} --${name}: expected ${value}, got ${actual.get(name)}`);
    }
    for (const name of actual.keys()) if (!expected.has(name)) errors.push(`${mode}: unknown colour token --${name}`);
  }
  if (/#(?:[a-f\d]{3,8})\b|\b(?:rgb|hsl|oklch|lab)a?\(/i.test(otherCss)) errors.push('Colours outside tokens.css are not allowed.');
  return errors;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const kotlin = readFileSync(new URL('../../base-ui/src/commonMain/kotlin/com/retro99/base/ui/compose/EmberTokens.kt', import.meta.url), 'utf8');
  const css = readFileSync(new URL('../src/styles/tokens.css', import.meta.url), 'utf8');
  const otherCss = readFileSync(new URL('../src/styles/site.css', import.meta.url), 'utf8');
  const errors = checkTokens(kotlin, css, otherCss);
  if (errors.length) { console.error(errors.join('\n')); process.exitCode = 1; }
  else console.log('Ember Day/Night colour tokens match Kotlin.');
}
