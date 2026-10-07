import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { parse } from 'yaml';
import { marked } from 'marked';

export function sectionId(text: string): string {
  return text.replace(/^\d+\.\s*/, '').toLowerCase().normalize('NFKD')
    .replace(/[’']/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
}

export function loadLegal(kind: 'privacy' | 'terms') {
  const path = resolve(process.cwd(), `../design/ember/website/legal/${kind}.md`);
  const source = readFileSync(path, 'utf8');
  return parseLegal(source, kind);
}

export function parseLegal(source: string, kind: 'privacy' | 'terms') {
  const frontMatter = source.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n/);
  const content = frontMatter ? source.slice(frontMatter[0].length) : source;
  const data = frontMatter ? (parse(frontMatter[1]) ?? {}) : {};
  const tokens = marked.lexer(content);
  const title = tokens.find(token => token.type === 'heading' && token.depth === 1);
  const shortIndex = tokens.findIndex(token => token.type === 'heading' && token.text === 'The short version');
  if (!title || title.type !== 'heading' || shortIndex < 0) throw new Error(`Invalid legal document: ${kind}`);
  const firstSection = tokens.findIndex((token, i) => i > shortIndex && token.type === 'heading' && token.depth === 2);
  if (firstSection < 0) throw new Error(`Missing numbered sections: ${kind}`);
  const intro = tokens.slice(1, shortIndex).filter(token => token.type !== 'space');
  const lead = intro.at(-1);
  const sections = tokens.slice(firstSection).filter(token => token.type === 'heading' && token.depth === 2)
    .map(token => ({ text: (token as { text: string }).text, id: sectionId((token as { text: string }).text) }));
  const renderer = new marked.Renderer();
  renderer.heading = ({ text, depth, tokens: inlineTokens }) => `<h${depth} id="${sectionId(text)}">${renderer.parser.parseInline(inlineTokens)}</h${depth}>\n`;
  const render = (items: typeof tokens) => marked.parser(items, { renderer });
  const lastUpdated = data.lastUpdated;
  if (lastUpdated !== undefined && (typeof lastUpdated !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(lastUpdated) || Number.isNaN(Date.parse(lastUpdated)) || new Date(lastUpdated).toISOString().slice(0, 10) !== lastUpdated)) {
    throw new Error(`${kind}: lastUpdated must be a quoted YYYY-MM-DD date`);
  }
  return {
    title: title.text,
    lead: lead ? render([lead]) : '',
    notices: render(intro.slice(0, -1)),
    short: render(tokens.slice(shortIndex + 1, firstSection)),
    body: render(tokens.slice(firstSection)),
    sections,
    lastUpdated,
  };
}
