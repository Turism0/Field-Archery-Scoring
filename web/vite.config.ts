import { defineConfig } from 'vite'
import preact from '@preact/preset-vite'
import { VitePWA } from 'vite-plugin-pwa'

// Built straight into the API's wwwroot, so publishing the API publishes the web app at /app/ (2.5).
export default defineConfig({
  base: '/app/',
  build: {
    outDir: '../server/api/FieldArcheryApi/wwwroot/app', emptyOutDir: true,
    // 2.7.02: the same file names every build (assets/index.js, assets/index.css), so copying a new build over the old one
    // replaces them rather than leaving old hashed files behind. The service worker still updates (Workbox gives each file a
    // revision), and the server sends these with no-cache so a browser always checks for a newer copy.
    rollupOptions: { output: { entryFileNames: 'assets/[name].js', chunkFileNames: 'assets/[name].js', assetFileNames: 'assets/[name][extname]' } },
  },
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
        // 2.7.02: assets/ no longer has hashed names, so give every file a revision (the default treats assets/ as hashed and never updates it).
        dontCacheBustURLsMatching: /-[0-9a-f]{8}\./,
        navigateFallback: '/app/index.html',
        navigateFallbackAllowlist: [/^\/app\//],
      },
    }),
  ],
  test: { environment: 'node' },
})
