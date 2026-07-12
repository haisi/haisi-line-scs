#!/usr/bin/env node
// Screenshots individual pages/components in isolation, mocking whatever HTTP calls they make
// (via Playwright's own request interception) instead of needing a real running backend --
// unlike screenshot-overview.mjs, which deliberately exercises the real, running app to prove the
// integration actually works. This script never starts optimistic-locking-web at all: it just
// serves the already-built static assets (optimistic-locking-ui/target/classes/static) itself,
// so there's nothing beyond this one Node process to start or tear down.
//
// To screenshot another component/page, add an entry to `specs` below: a hash route to visit, the
// HTTP responses to fake for whatever that route fetches, and a selector that's only present once
// the page has actually rendered its (mocked) data.
//
// Usage: node scripts/screenshot-components.mjs --out-dir=<path>

import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { mkdir, readFile } from 'node:fs/promises';
import { dirname, extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const staticRoot = join(scriptDir, '..', 'target', 'classes', 'static');
const port = 4310;

const mimeTypes = {
  '.html': 'text/html',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.json': 'application/json',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.svg': 'image/svg+xml',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.txt': 'text/plain',
};

const fakeLine = {
  id: '00000000-0000-0000-0000-000000000001',
  left: 2,
  right: 8,
  businessPartnerId: 'acme',
  _links: {
    self: { href: 'http://localhost/lines/00000000-0000-0000-0000-000000000001' },
    delete: { href: 'http://localhost/lines/00000000-0000-0000-0000-000000000001/delete' },
  },
};

const specs = [
  {
    name: 'line-detail',
    hashPath: `/#/lines/${fakeLine.id}`,
    mocks: [{ url: `**/lines/${fakeLine.id}`, json: fakeLine }],
    waitForSelector: 'qd-horizontal-pairs',
  },
];

function readArg(name, fallback) {
  const prefix = `--${name}=`;
  const found = process.argv.find((arg) => arg.startsWith(prefix));
  return found ? found.slice(prefix.length) : fallback;
}

function startStaticServer(rootDir) {
  return new Promise((resolve, reject) => {
    const server = createServer(async (req, res) => {
      const { pathname } = new URL(req.url, 'http://localhost');
      const filePath = join(rootDir, pathname === '/' ? 'index.html' : pathname);
      try {
        const content = await readFile(filePath);
        res.writeHead(200, { 'Content-Type': mimeTypes[extname(filePath)] ?? 'application/octet-stream' });
        res.end(content);
      } catch {
        res.writeHead(404);
        res.end();
      }
    });
    server.once('error', reject);
    server.listen(port, () => resolve(server));
  });
}

async function screenshotSpec(browser, baseUrl, outDir, spec) {
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  for (const mock of spec.mocks) {
    await page.route(mock.url, (route) =>
      route.fulfill({
        contentType: 'application/json',
        headers: { ETag: '"1"' },
        body: JSON.stringify(mock.json),
      }),
    );
  }
  await page.goto(baseUrl + spec.hashPath, { waitUntil: 'networkidle' });
  await page.waitForSelector(spec.waitForSelector, { timeout: 15_000 });
  await page.waitForTimeout(300); // let the component's own render/animation settle
  await page.screenshot({ path: join(outDir, `${spec.name}.png`) });
  await page.close();
}

async function main() {
  const outDir = readArg('out-dir');
  if (!outDir) {
    console.error('Usage: screenshot-components.mjs --out-dir=<path>');
    process.exit(1);
  }
  await mkdir(outDir, { recursive: true });

  const server = await startStaticServer(staticRoot);
  const browser = await chromium.launch();
  try {
    for (const spec of specs) {
      await screenshotSpec(browser, `http://localhost:${port}`, outDir, spec);
    }
  } finally {
    await browser.close();
    server.close();
  }
}

main().catch((error) => {
  console.error('Failed to take component screenshots:', error);
  process.exit(1);
});
