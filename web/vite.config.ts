import { defineConfig } from 'vite'
import preact from '@preact/preset-vite'
import { VitePWA } from 'vite-plugin-pwa'

// Built straight into the API's wwwroot, so publishing the API publishes the web app at /app/ (2.5).
export default defineConfig({
  base: '/app/',
  build: { outDir: '../server/api/FieldArcheryApi/wwwroot/app', emptyOutDir: true },
  plugins: [
    preact(),
    VitePWA({
      registerType: 'autoUpdate',
      injectRegister: 'auto',
      includeAssets: ['icon.svg', 'apple-touch-icon.png'],
      manifest: {
        name: 'Field Archery Scoring',
        short_name: 'Field Archery',
        description: 'Score field archery rounds, with or without signal.',
        start_url: '/app/',
        scope: '/app/',
        display: 'standalone',
        background_color: '#f4f7f1',
        theme_color: '#2e5d34',
        icons: [
          { src: 'icon-192.png', sizes: '192x192', type: 'image/png' },
          { src: 'icon-512.png', sizes: '512x512', type: 'image/png' },
          { src: 'icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      workbox: {
        // The app itself works offline; the API is never cached (sync handles no signal).
        globPatterns: ['**/*.{js,css,html,svg,png,webmanifest}'],
        navigateFallback: '/app/index.html',
        navigateFallbackAllowlist: [/^\/app\//],
      },
    }),
  ],
  test: { environment: 'node' },
})
