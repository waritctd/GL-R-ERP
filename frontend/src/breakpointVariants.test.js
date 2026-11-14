import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it, expect } from 'vitest';

// Guard for #exclusive-breakpoint-variant (2026-08-08). WHY A SOURCE SCAN AND NOT A RENDER TEST: jsdom has no
// layout engine and never evaluates a media query, so no amount of `render()` in this suite can observe a
// breakpoint at all.

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

  it('declares the pure max-width variants widest-first, so the narrower band wins', () => {
    // Declare custom max-width variants widest first so the narrower mobile rules win where bands overlap.
    const declared = [...INDEX_CSS.matchAll(/@custom-variant\s+([\w-]+)\s*\{\s*@media\s*([^{]+)\{/g)]
      .map(([, name, query]) => ({ name, query: query.trim() }));

    expect(declared.length).toBeGreaterThan(0);

    const pureMax = declared
      .map(({ name, query }) => {
        const m = /^\(max-width:\s*(\d+)px\)$/.exec(query);
        return m ? { name, width: Number(m[1]) } : null;
      })
      .filter(Boolean);

    expect(pureMax.map((v) => v.name)).toEqual([
      'nav-drawer',
      'attendance-scroll',
      'mobile',
      'mobile-sm',
      'mobile-xs',
    ]);

    const widths = pureMax.map((v) => v.width);
    expect(widths).toEqual([...widths].sort((a, b) => b - a));
    expect(new Set(widths).size).toBe(widths.length);
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
