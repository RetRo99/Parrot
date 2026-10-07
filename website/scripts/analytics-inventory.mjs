// Report-only source inventory. Never included in public site output.
import { readFileSync, readdirSync, mkdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
const root = fileURLToPath(new URL('../../', import.meta.url));
const directory = resolve(root, 'lib/analytics/api/src/commonMain/kotlin/com/retro99/analytics/api');
const events = [];
for (const file of readdirSync(directory).filter(name => name.endsWith('.kt'))) {
  const source = readFileSync(resolve(directory, file), 'utf8');
  for (const match of source.matchAll(/(?:override val name(?:\s*:\s*String)?|(?:attempted|failed|cancelled)EventName)\s*=\s*"([a-z0-9_]+)"/g)) {
    events.push({ name: match[1], source: `lib/analytics/api/src/commonMain/kotlin/com/retro99/analytics/api/${file}`, line: source.slice(0, match.index).split('\n').length });
  }
  if (file === 'FeatureUsageAnalytics.kt') {
    const body = source.match(/enum class UsageOperation[\s\S]*?\n\}/)?.[0] ?? '';
    for (const match of body.matchAll(/\("([a-z0-9_]+)"\)/g)) events.push({ name: match[1], source: `lib/analytics/api/src/commonMain/kotlin/com/retro99/analytics/api/${file}`, line: source.slice(0, source.indexOf(match[0])).split('\n').length });
  }
}
events.sort((a, b) => a.name.localeCompare(b.name));
const report = {
  scope: 'Code-defined custom event names, not proof that every schema is reached or received by Firebase. SDK automatic events and identifiers are additional.',
  uniqueEventNames: [...new Set(events.map(event => event.name))],
  declarations: events,
  providerBoundary: 'lib/analytics/implementation/src/commonMain/kotlin/com/retro99/analytics/implementation/AnalyticsParameterSanitizer.kt',
  identity: 'No non-null setUserId call found in production app sources; login clears identity.',
};
const output = fileURLToPath(new URL('../reports/', import.meta.url));
mkdirSync(output, { recursive: true });
writeFileSync(resolve(output, 'analytics-inventory.json'), JSON.stringify(report, null, 2) + '\n');
console.log(`Inventoried ${report.uniqueEventNames.length} distinct custom event names.`);
