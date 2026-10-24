import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    // 3000 exactly: it is the origin dashboard-service allows in its CORS config, so a silent
    // fallback to 3001 would turn every request into a CORS failure.
    port: 3000,
    strictPort: true,
  },
});
