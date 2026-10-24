// @ts-check
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  // Vitest owns the *.spec.ts files under src/, so Playwright only looks here.
  testDir: 'e2e',
  forbidOnly: !!process.env.CI,
  reporter: 'list',
  use: { ...devices['Desktop Chrome'], viewport: { width: 800, height: 600 }, trace: 'retain-on-failure' },
});
