// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 IT Beratung Hermann GmbH

// Runs axe-core (WCAG 2.2 A/AA rules) against HTML files in jsdom.
// Usage: node axe-check.mjs <file.html|file.xhtml>...
// JSDOM_MODULE and AXE_SCRIPT point at the mise-installed packages; see
// scripts/check-formats.sh. jsdom does not lay out pages, so the colour
// contrast rule is disabled here.
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';

const require = createRequire(import.meta.url);
const { JSDOM } = require(process.env.JSDOM_MODULE);
const axeSource = readFileSync(process.env.AXE_SCRIPT, 'utf8');

let failed = false;
for (const file of process.argv.slice(2)) {
  const xhtml = file.endsWith('.xhtml');
  const dom = new JSDOM(readFileSync(file, 'utf8'), {
    contentType: xhtml ? 'application/xhtml+xml' : 'text/html',
    runScripts: 'outside-only',
    pretendToBeVisual: true,
  });
  dom.window.eval(axeSource);
  const results = await dom.window.axe.run(dom.window.document, {
    runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice'] },
    // Letters and emails need no landmarks; 'region' is a best practice, not a WCAG rule.
    rules: { 'color-contrast': { enabled: false }, region: { enabled: false } },
    resultTypes: ['violations'],
  });
  if (results.violations.length === 0) {
    console.log(`${file}: no accessibility violations`);
  }
  for (const v of results.violations) {
    failed = true;
    for (const node of v.nodes) {
      console.error(`error: ${file}: ${v.id}: ${v.help} (${node.target.join(' ')})`);
    }
  }
}
process.exit(failed ? 1 : 0);
