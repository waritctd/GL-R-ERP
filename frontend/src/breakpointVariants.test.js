import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it, expect } from 'vitest';

// Source guard (#exclusive-breakpoint-variant): jsdom never evaluates media queries, so a render test cannot
// catch an exclusive max-[Npx]: variant; scan the source instead.
const SRC = path.dirname(fileURLToPath(import.meta.url));
const INDEX_CSS = fs.readFileSync(path.join(SRC, 'index.css'), 'utf8');

// There is deliberately NO exemption list. Do not reintroduce an allowlist here — the assertion below is
// deliberately absolute, because "one temporary exception" is how a codebase-wide defect grows back.
const EXCLUSIVE_VARIANT = new RegExp(`max-\\[\\d+px\\]${':'}`);

function sourceFiles(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) sourceFiles(p, out);
    else if (/\.jsx?$/.test(entry.name)) out.push(p);
  }
  return out;
}

describe('inclusive breakpoint variants', () => {
  it('has no exclusive max-width variant anywhere in src', () => {
    const offenders = sourceFiles(SRC)
      .filter((f) => EXCLUSIVE_VARIANT.test(fs.readFileSync(f, 'utf8')))
      .map((f) => path.relative(SRC, f))
      .sort();

    expect(offenders).toEqual([]);
  });

  it('keeps the mobile variant byte-identical to useIsMobile.js MOBILE_QUERY', () => {
    // This equality IS the bug that was fixed: the CSS band and the JS band must resolve the same way at every
    // width, including the boundary. If one moves, the other has to move with it.
    const hook = fs.readFileSync(path.join(SRC, 'hooks/useIsMobile.js'), 'utf8');
    const jsQuery = /MOBILE_QUERY\s*=\s*'([^']+)'/.exec(hook)?.[1];

    expect(jsQuery).toBe('(max-width: 720px)');
    expect(INDEX_CSS).toMatch(
      new RegExp(`@custom-variant\\s+mobile\\s*\\{\\s*@media\\s*\\(max-width:\\s*720px\\)`),
    );
  });
});
