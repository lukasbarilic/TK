import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

const backend = 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': { target: backend, changeOrigin: false },
      '/actuator': { target: backend, changeOrigin: false },
    },
  },
});
