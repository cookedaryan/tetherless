import { readFileSync } from 'node:fs';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// One source for the version, so About cannot drift from package.json.
const { version } = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')) as {
  version: string;
};

// A packaged renderer is loaded from a file:// URL, so every emitted asset path must be relative.
export default defineConfig({
  root: 'src/renderer',
  base: './',
  plugins: [react()],
  define: {
    __APP_VERSION__: JSON.stringify(version),
  },
  build: {
    outDir: '../../dist/renderer',
    emptyOutDir: true,
  },
});
