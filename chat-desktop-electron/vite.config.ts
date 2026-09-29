import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// A packaged renderer is loaded from a file:// URL, so every emitted asset path must be relative.
export default defineConfig({
  root: 'src/renderer',
  base: './',
  plugins: [react()],
  build: {
    outDir: '../../dist/renderer',
    emptyOutDir: true,
  },
});
