import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests',
  outputDir: '../../reports/browser-tests',
  fullyParallel: false,
  workers: 1,
  timeout: 30_000,
  use: {
    baseURL: 'http://127.0.0.1:4179',
    channel: process.env.TEAMTALK_ADMIN_BROWSER_CHANNEL,
    viewport: { width: 1440, height: 960 },
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'npm exec -- vite preview --host 127.0.0.1 --port 4179 --strictPort --outDir ../../dist',
    url: 'http://127.0.0.1:4179/admin/',
    reuseExistingServer: false,
  },
})
