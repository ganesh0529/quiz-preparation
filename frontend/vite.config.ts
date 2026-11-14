import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In dev, Vite proxies API + WebSocket calls to the Spring Boot backend,
// so the browser only ever talks to one origin (no CORS needed).
const backend = process.env.BACKEND_URL ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: backend, changeOrigin: true },
      '/ws': { target: backend.replace(/^http/, 'ws'), ws: true },
    },
  },
});
