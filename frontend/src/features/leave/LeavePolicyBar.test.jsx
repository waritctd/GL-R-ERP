// `URL` explicitly from node:url, NOT the global one. This suite runs in the jsdom environment,
// whose global URL is jsdom's browser implementation -- it resolves a relative path against the
// document base rather than a `file:` base, so `fileURLToPath` on the result throws
// "The URL must be of scheme file". Measured, not guessed.
import fs from 'node:fs';
import { fileURLToPath, URL as NodeUrl } from 'node:url';
import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { LeavePolicyBar, POLICY_PDF_HREF } from './LeavePolicyBar.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// Mocked the way LeavePolicyDocumentPage.test.jsx mocks the same module -- only the two methods
// this bar actually calls.
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      leave: {
        policyDocumentAvailable: vi.fn(),
        downloadPolicyDocument: vi.fn(),
      },
    },
  };
});

function renderBar() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <LeavePolicyBar />
    </QueryClientProvider>,
  );
}

// Quoted once so the copy and the assertion pinning it cannot silently drift apart.
const STRONG_WARNING_TEXT = /อาจไม่ใช่ฉบับปัจจุบัน/;

// The bar went from a three-state API probe (2026-08-10) to a plain bundled link with no states at
// all (2026-08-11) to FOUR states again (2026-08-14, reversed) -- see LeavePolicyBar.jsx's own
// header comment for the full history and the table these tests pin. What's worth testing changed
// each time: this suite's job now is the property the header comment calls out explicitly --
// every state still OFFERS a document, and the states differ only in the strength of the warning
// beside it, never in whether the link/button is there at all. That is why every state below is
// tested in a "what IS shown" and "what is NOT shown" pair, not just the positive half.
describe('LeavePolicyBar', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.open = vi.fn();
    URL.createObjectURL = vi.fn(() => 'blob:leave-policy');
    URL.revokeObjectURL = vi.fn();
  });

  // THE test that predates this whole reversal and is unrelated to it: the only failure mode that
  // renders perfectly and 404s on click. Rename the PDF, move public/policy/, or drop the asset
  // from the build and nothing else in the suite goes red -- the component still renders a link
  // (or a button, in the 'available' state), the bar still lays out, and the first person to find
  // out is a user who wanted the announcement.
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

  it('renders a muted, non-interactive placeholder while the probe is in flight -- never a clickable link', async () => {
    api.leave.policyDocumentAvailable.mockReturnValue(new Promise(() => {})); // never settles
    renderBar();

    expect(await screen.findByText(/กำลังตรวจสอบเอกสารล่าสุด/)).not.toBeNull();
    // Deliberately not a link and not a button: something clickable before the answer arrives can
    // be clicked before the answer arrives, which is the exact silent wrong-serve this whole
    // change exists to prevent.
    expect(screen.queryByRole('link')).toBeNull();
    expect(screen.queryByRole('button')).toBeNull();
  });

  describe("'absent' — the state every environment is in today", () => {
    beforeEach(() => {
      api.leave.policyDocumentAvailable.mockResolvedValue('absent');
    });

    it('renders the bundled link, opened safely and saved under the source document\'s own name', async () => {
      renderBar();

      const link = await screen.findByRole('link', { name: /ประกาศวันลา \(PDF\)/ });
      expect(link.getAttribute('href')).toBe(POLICY_PDF_HREF);
      // Mirrors the source document's own filename, not the URL slug -- same convention as
      // SpecialMoneyPanel.jsx's welfare bar.
      expect(link.getAttribute('download')).toBe('วันเวลาทำงาน และการหยุดงาน_1_10_67.pdf');
      expect(link.getAttribute('target')).toBe('_blank');
      // `noopener` is the load-bearing half: without it the opened tab gets a handle on this one.
      expect(link.getAttribute('rel')).toContain('noopener');
    });

    // No warning of any kind: a 404 is the server CONFIRMING nothing is stored, which makes the
    // bundled copy current, not stale. THIS is the property that must never regress: if 'absent'
    // and 'check-failed' below ever render the same warning (or lack of one), a reader can no
    // longer tell a confirmed-current document from an unchecked one. See the 'check-failed'
    // describe block for the other half of this pair.
    it('shows no warning of any kind', async () => {
      renderBar();
      await screen.findByRole('link', { name: /ประกาศวันลา \(PDF\)/ });

      expect(screen.queryByText(/ตรวจสอบกับเซิร์ฟเวอร์ไม่ได้/)).toBeNull();
      expect(screen.queryByText(STRONG_WARNING_TEXT)).toBeNull();
      expect(screen.queryByRole('button', { name: /ลองใหม่/ })).toBeNull();
      expect(screen.queryByRole('status')).toBeNull();
    });

    it('no longer tells anyone the announcement is missing', async () => {
      renderBar();
      await screen.findByRole('link', { name: /ประกาศวันลา \(PDF\)/ });

      // The old unavailable branch, from before the document was ever bundled. It was honest
      // while no file had been uploaded; rendering it here now would be a plain lie, and it is
      // the copy a stale revert would bring back.
      expect(screen.queryByText(/ยังไม่มีไฟล์ประกาศ/)).toBeNull();
      expect(screen.queryByText(/กรุณาติดต่อฝ่ายบุคคล/)).toBeNull();
    });
  });

  describe("'available' — a confirmed-current uploaded document", () => {
    beforeEach(() => {
      api.leave.policyDocumentAvailable.mockResolvedValue('available');
    });

    it('renders the uploaded-document affordance instead of the bundled link, with no warning', async () => {
      renderBar();

      expect(await screen.findByRole('button', { name: /ประกาศวันลา \(PDF\)/ })).not.toBeNull();
      expect(screen.queryByRole('link', { name: /ประกาศวันลา \(PDF\)/ })).toBeNull();
      expect(document.querySelector(`a[href="${POLICY_PDF_HREF}"]`)).toBeNull();
      expect(screen.queryByText(/ตรวจสอบกับเซิร์ฟเวอร์ไม่ได้/)).toBeNull();
      expect(screen.queryByText(STRONG_WARNING_TEXT)).toBeNull();
    });

    it('downloads and opens the uploaded document on click', async () => {
      const blob = new Blob(['%PDF-1.4'], { type: 'application/pdf' });
      api.leave.downloadPolicyDocument.mockResolvedValue(blob);
      renderBar();

      fireEvent.click(await screen.findByRole('button', { name: /ประกาศวันลา \(PDF\)/ }));

      await waitFor(() => {
        expect(api.leave.downloadPolicyDocument).toHaveBeenCalledTimes(1);
        expect(URL.createObjectURL).toHaveBeenCalledWith(blob);
        expect(window.open).toHaveBeenCalledWith('blob:leave-policy', '_blank', 'noopener');
      });
    });

    // The bar has no `showToast` prop and must not gain one just for this -- the failure renders
    // inline, in the same trailing slot the affordance itself sits in.
    it('shows the failure inline when the download itself fails', async () => {
      api.leave.downloadPolicyDocument.mockRejectedValue(new Error('ไฟล์เสียหาย'));
      renderBar();

      fireEvent.click(await screen.findByRole('button', { name: /ประกาศวันลา \(PDF\)/ }));

      expect(await screen.findByText('ไฟล์เสียหาย')).not.toBeNull();
    });
  });

  describe("'unverified' — learned nothing about whether a real backend exists", () => {
    beforeEach(() => {
      api.leave.policyDocumentAvailable.mockResolvedValue('unverified');
    });

    it('renders the bundled link plus a mild note, and no retry control', async () => {
      renderBar();

      const link = await screen.findByRole('link', { name: /ประกาศวันลา \(PDF\)/ });
      expect(link.getAttribute('href')).toBe(POLICY_PDF_HREF);
      expect(screen.getByText(/ตรวจสอบกับเซิร์ฟเวอร์ไม่ได้/)).not.toBeNull();
      // Mild, not strong: a non-PDF 200 or a rejected fetch proves nothing about whether a real
      // backend exists at all, so there is nothing more specific to retry against.
      expect(screen.queryByText(STRONG_WARNING_TEXT)).toBeNull();
      expect(screen.queryByRole('button', { name: /ลองใหม่/ })).toBeNull();
    });
  });

  describe("'check-failed' — positive evidence a backend exists and refused to answer", () => {
    beforeEach(() => {
      api.leave.policyDocumentAvailable.mockResolvedValue('check-failed');
    });

    // The other half of the pair declared in the 'absent' describe block above: 'absent' and
    // 'check-failed' render the IDENTICAL bundled link (same href/download/target/rel) and must
    // NEVER be told apart by anything other than this warning.
    it('renders the bundled link plus a strong warning and a retry control', async () => {
      renderBar();

      const link = await screen.findByRole('link', { name: /ประกาศวันลา \(PDF\)/ });
      expect(link.getAttribute('href')).toBe(POLICY_PDF_HREF);
      expect(link.getAttribute('download')).toBe('วันเวลาทำงาน และการหยุดงาน_1_10_67.pdf');
      expect(link.getAttribute('target')).toBe('_blank');
      expect(link.getAttribute('rel')).toContain('noopener');

      // Amber, not red -- the document IS available (the link above proves it), it may just be
      // stale. role="status": a page-load state the reader can act on in their own time, not an
      // interruption.
      const warning = screen.getByRole('status');
      expect(warning.textContent).toMatch(STRONG_WARNING_TEXT);
      expect(screen.getByRole('button', { name: /ลองใหม่/ })).not.toBeNull();
    });

    // The probe THROWING is a different path from it resolving 'check-failed', and it lands on the
    // component's fall-through default -- which is the unwarned bundled link. hrApi's probe never
    // throws for an HTTP reason, but mockApi's does (`requireSession()` raises a 401). Without the
    // isError coalesce in the component this renders as 'absent': a document we never managed to
    // check, shown with no warning at all, which is precisely the silent wrong-serve this whole
    // change exists to prevent.
    it('treats a probe that throws outright as check-failed, not as a confirmed-current document', async () => {
      api.leave.policyDocumentAvailable.mockRejectedValue(new Error('boom'));
      renderBar();

      const link = await screen.findByRole('link', { name: /ประกาศวันลา \(PDF\)/ });
      expect(link.getAttribute('href')).toBe(POLICY_PDF_HREF);
      expect(screen.getByRole('status').textContent).toMatch(STRONG_WARNING_TEXT);
    });

    it('retries the probe when the retry control is clicked', async () => {
      renderBar();
      await screen.findByRole('button', { name: /ลองใหม่/ });
      expect(api.leave.policyDocumentAvailable).toHaveBeenCalledTimes(1);

      api.leave.policyDocumentAvailable.mockResolvedValue('available');
      fireEvent.click(screen.getByRole('button', { name: /ลองใหม่/ }));

      await waitFor(() => expect(api.leave.policyDocumentAvailable).toHaveBeenCalledTimes(2));
      expect(await screen.findByRole('button', { name: /ประกาศวันลา \(PDF\)/ })).not.toBeNull();
    });
  });
});
