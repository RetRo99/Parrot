import { readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const requiredMarkers = /\[(?:CONFIRM|DECIDE|LAWYER CHECK|LEGAL NAME|ADDRESS|CONTACT EMAIL|AI PROVIDER)/i;
export function inspectOutput(files, allowDrafts = false) {
  const errors = [];
  const drafts = [];
  for (const [path, text] of files) {
    if (/\.(?:m?js)$/i.test(path)) errors.push(`${path}: client JavaScript is forbidden`);
    if (!/\.(html|css)$/i.test(path)) continue;
    // Decode numeric references so escaped Markdown markers cannot bypass the gate.
    const decoded = text.replace(/&#(?:x([\da-f]+)|(\d+));/gi, (_, hex, dec) => String.fromCodePoint(parseInt(hex ?? dec, hex ? 16 : 10)));
    if (requiredMarkers.test(decoded) || (path.endsWith('.html') && /\[[^\]\n]+\]/.test(decoded.replace(/<[^>]*>/g, '')))) drafts.push(`${path}: unresolved bracketed placeholder`);
    if (/<script\b/i.test(text)) errors.push(`${path}: script tag is forbidden`);
    if (/(?:src|srcset|poster)\s*=\s*["']\s*(?:https?:)?\/\//i.test(text) || /url\(\s*["']?(?:https?:)?\/\//i.test(text) || /@import/i.test(text)) errors.push(`${path}: remote resource is forbidden`);
    if (/<link\b[^>]*\bhref=["'](?:https?:)?\/\//i.test(text)) errors.push(`${path}: remote link resource is forbidden`);
  }
  if (!allowDrafts) errors.push(...drafts);
  return { errors, drafts };
}

function collect(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    const path = resolve(dir, entry.name);
    return entry.isDirectory() ? collect(path) : [[path, /\.(html|css|m?js)$/i.test(path) ? readFileSync(path, 'utf8') : '']];
  });
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const allowDrafts = process.argv.includes('--allow-drafts');
  const { errors, drafts } = inspectOutput(collect(fileURLToPath(new URL('../dist', import.meta.url))), allowDrafts);
  if (!allowDrafts) {
    const config = readFileSync(new URL('../src/config.ts', import.meta.url), 'utf8');
    if (/\[[^\]]+\]/.test(config)) errors.push('src/config.ts: launch details remain unresolved');
  }
  if (errors.length) { console.error(`Production safety check failed:\n${errors.join('\n')}`); process.exitCode = 1; }
  else console.log(allowDrafts ? `PREVIEW ONLY: ${drafts.length} files contain unresolved placeholders. Do not deploy this output to the public domain.` : 'Production safety check passed: no drafts, scripts or remote resources.');
}
