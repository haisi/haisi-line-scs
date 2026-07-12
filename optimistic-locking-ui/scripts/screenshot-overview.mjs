#!/usr/bin/env node
// Screenshots the Lines overview page against a running instance of the app (see
// optimistic-locking-web/pom.xml, which boots the app under the "local" Spring profile -- for the
// seed data and /dev/login this needs -- takes this screenshot, then stops it again, all bound to
// the same Maven phase that renders the API guide). Run manually via `npm run screenshot` against
// any already-running instance for a quick local check.
//
// Usage: node scripts/screenshot-overview.mjs --out=<path> [--base-url=http://localhost:8080]

import { chromium } from 'playwright';
import { mkdir } from 'node:fs/promises';
import { dirname } from 'node:path';

function readArg(name, fallback) {
  const prefix = `--${name}=`;
  const found = process.argv.find((arg) => arg.startsWith(prefix));
  return found ? found.slice(prefix.length) : fallback;
}

const outPath = readArg('out');
const baseUrl = readArg('base-url', 'http://localhost:8080');

if (!outPath) {
  console.error('Usage: screenshot-overview.mjs --out=<path> [--base-url=http://localhost:8080]');
  process.exit(1);
}

async function main() {
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
    await page.goto(baseUrl, { waitUntil: 'networkidle' });

    // Some identity is required, or the page correctly shows the permission-denied view instead
    // of the table -- "administrator" vs "viewer" makes no visual difference on this page.
    await page.selectOption('#demo-identity', 'administrator');
    await page.waitForSelector('qd-table tbody tr, qd-table [role=row]', { timeout: 15_000 });
    await page.waitForTimeout(300); // let the table's own render/animation settle

    await mkdir(dirname(outPath), { recursive: true });
    await page.screenshot({ path: outPath });
  } finally {
    await browser.close();
  }
}

main().catch((error) => {
  console.error('Failed to take the overview screenshot:', error);
  process.exit(1);
});
