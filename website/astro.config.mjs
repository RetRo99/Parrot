import { defineConfig } from 'astro/config';

export default defineConfig({
  output: 'static',
  trailingSlash: 'never',
  build: { format: 'directory' },
  vite: { build: { assetsInlineLimit: 0 } },
});
