import fs from 'node:fs';
// `URL` explicitly from node:url, NOT the global one. This suite runs in the jsdom environment,
// whose global URL is jsdom's browser implementation -- it resolves a relative path against the
// document base rather than a `file:` base, so `fileURLToPath` on the result throws
// "The URL must be of scheme file". Measured, not guessed.
import { fileURLToPath, URL as NodeUrl } from 'node:url';
import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { LeavePolicyBar, POLICY_PDF_HREF } from './LeavePolicyBar.jsx';

globalThis.React = React;

// The bar went from a three-state API probe (available / unavailable / errored) to a plain link at
// a bundled asset on 2026-08-11, so what is worth pinning changed with it. Not the states -- there
// are none left -- but the two things that can now silently break: the asset going missing, and
// the link losing the attributes that make it open safely and save under a recognisable name.
describe('LeavePolicyBar', () => {
  // THE test in this file. Every other assertion here is about markup jsdom can see; this one is
  // about a file on disk, which is the only failure mode that renders perfectly and 404s on click.
  // Rename the PDF, move public/policy/, or drop the asset from the build and nothing else in the
  // suite goes red -- the component still renders a link, the page still lays out, and the first
  // person to find out is a user who wanted the announcement.
  it('the bundled PDF the href points at actually exists in public/', () => {
    // Resolved from THIS file, not `process.cwd()` -- `process` is not a global the browser-target
    // eslint config allows in src/, and a cwd-relative path would also break under any runner
    // invoked from the repo root rather than frontend/.
    const assetPath = fileURLToPath(new NodeUrl(`../../../public${POLICY_PDF_HREF}`, import.meta.url));
    expect(fs.existsSync(assetPath)).toBe(true);

    // Non-trivially sized and a real PDF, not a stub or an LFS pointer that would download as
    // something unopenable.
    const bytes = fs.readFileSync(assetPath);
    expect(bytes.length).toBeGreaterThan(10_000);
    expect(bytes.subarray(0, 5).toString('latin1')).toBe('%PDF-');
  });

  it('renders a download link to the announcement, opened safely and saved under the source document\'s own name', () => {
    render(<LeavePolicyBar />);

    const link = screen.getByRole('link', { name: /ประกาศวันลา \(PDF\)/ });
    expect(link.getAttribute('href')).toBe(POLICY_PDF_HREF);
    // Mirrors the source document's own filename, not the URL slug -- same convention as
    // SpecialMoneyPanel.jsx's welfare bar.
    expect(link.getAttribute('download')).toBe('วันเวลาทำงาน และการหยุดงาน_1_10_67.pdf');
    expect(link.getAttribute('target')).toBe('_blank');
    // `noopener` is the load-bearing half: without it the opened tab gets a handle on this one.
    expect(link.getAttribute('rel')).toContain('noopener');
  });

  it('no longer tells anyone the announcement is missing', () => {
    render(<LeavePolicyBar />);

    // The old unavailable branch. It was honest while no file had been uploaded; with the document
    // bundled it would be a plain lie, and it is the copy a stale revert would bring back.
    expect(screen.queryByText(/ยังไม่มีไฟล์ประกาศ/)).toBeNull();
    expect(screen.queryByText(/กรุณาติดต่อฝ่ายบุคคล/)).toBeNull();
  });
});
