// Stable stylesheet URL for Pages Function error responses, using the same
// tokens and email-link rules as Astro pages. No client script is needed.
import tokens from '../styles/tokens.css?raw';
import emailLinks from '../styles/email-links.css?raw';

export function GET() {
  return new Response(`${tokens}\n${emailLinks}`, {
    headers: { 'Content-Type': 'text/css; charset=utf-8' },
  });
}
