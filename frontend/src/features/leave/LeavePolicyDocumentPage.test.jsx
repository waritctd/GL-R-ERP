import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { LeavePolicyDocumentPage } from './LeavePolicyDocumentPage.jsx';
import { api } from '../../api/index.js';
import { bangkokTodayIso } from '../../utils/format.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      leave: {
        policyDocumentAvailable: vi.fn(),
        downloadPolicyDocument: vi.fn(),
        uploadPolicyDocument: vi.fn(),
      },
    },
  };
});

function pdf(name = 'announcement.pdf', size = 1024) {
  const file = new File(['%PDF-1.4'], name, { type: 'application/pdf' });
  // jsdom computes size from the blob parts; override so a size-cap test does not need a real 10 MB
  // buffer.
  Object.defineProperty(file, 'size', { value: size });
  return file;
}

function renderPage({ showToast } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/settings/leave-policy']}>
        <LeavePolicyDocumentPage showToast={showToast} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

async function fillAndSubmit({ file = pdf(), date = '2026-09-01' } = {}) {
  fireEvent.change(document.getElementById('leave-policy-file'), { target: { files: [file] } });
  fireEvent.change(screen.getByLabelText(/วันที่มีผลบังคับใช้/), { target: { value: date } });
  fireEvent.click(screen.getByRole('button', { name: /อัปโหลดประกาศ/ }));
}

describe('LeavePolicyDocumentPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.leave.policyDocumentAvailable.mockResolvedValue('absent');
    api.leave.uploadPolicyDocument.mockResolvedValue({
      document: {
        documentId: 1,
        fileName: 'announcement.pdf',
        mimeType: 'application/pdf',
        fileSize: 1024,
        effectiveFrom: '2026-09-01',
        uploadedAt: '2026-08-14T10:00:00Z',
      },
    });
  });

  // The state production, UAT and a fresh mock session are all in: V133 says rows reach the table
  // only through this endpoint, and it has never had a client. Phrased as a starting point, not a
  // fault.
  it('reads an empty store as the normal starting point rather than a fault', async () => {
    renderPage();

    expect(await screen.findByText(/ยังไม่เคยมีการอัปโหลดไฟล์เข้ามาเลย/)).not.toBeNull();
    expect(screen.queryByRole('button', { name: /เปิดเอกสารในระบบ/ })).toBeNull();
  });

  it('offers the stored document when one is current', async () => {
    api.leave.policyDocumentAvailable.mockResolvedValue('available');
    renderPage();

    expect(await screen.findByRole('button', { name: /เปิดเอกสารในระบบ/ })).not.toBeNull();
  });

  // ── The honesty requirement, reversed 2026-08-14 ──────────────────────────
  // Until issue #774 shipped this page, uploading here did NOT change the PDF at /leave. Now it
  // does, once the version's effective date arrives and LeavePolicyBar.jsx's probe confirms it --
  // see that file's own tests for the reader side of this.
  it('says outright that the leave-page reader now prefers the uploaded file once it is in force', async () => {
    renderPage();

    expect(await screen.findByText(/จะเปลี่ยนตามฉบับนี้เมื่อถึงวันมีผลบังคับใช้/)).not.toBeNull();
    // The old claim -- "the file employees see has NOT changed" -- would be a lie now; guard
    // against a stale revert bringing it back.
    expect(screen.queryByText(/ยังไม่เปลี่ยนไฟล์ที่พนักงานเห็นในหน้า/)).toBeNull();
  });

  // 'check-failed' shares the retry branch with a genuine query error: with no confirmed answer,
  // "never uploaded" is a claim this page cannot support, and it has no bundled fallback to show
  // in its place the way LeavePolicyBar.jsx does.
  it('renders the retry affordance for a failed check, not the "never uploaded" copy', async () => {
    api.leave.policyDocumentAvailable.mockResolvedValue('check-failed');
    renderPage();

    expect(await screen.findByText('ตรวจสอบเอกสารในระบบไม่สำเร็จ')).not.toBeNull();
    expect(screen.getByRole('button', { name: /ลองใหม่/ })).not.toBeNull();
    expect(screen.queryByText(/ยังไม่เคยมีการอัปโหลดไฟล์เข้ามาเลย/)).toBeNull();
  });

  // The server checks MultipartFile#getContentType — the type the CLIENT declares — so nothing
  // verifies the bytes really are a PDF. The copy must state the rule without claiming a check.
  it('states the PDF rule without claiming the file is verified', async () => {
    renderPage();

    expect(await screen.findByText('ต้องเป็นไฟล์ PDF ขนาดไม่เกิน 10 MB')).not.toBeNull();
    expect(screen.queryByText(/ตรวจสอบว่าเป็นไฟล์ PDF จริง/)).toBeNull();
  });

  it('uploads the chosen file with its effective date', async () => {
    renderPage();
    await screen.findByText(/ยังไม่เคยมีการอัปโหลดไฟล์เข้ามาเลย/);

    const file = pdf('policy-2568.pdf');
    await fillAndSubmit({ file, date: '2026-08-01' });

    await waitFor(() => expect(api.leave.uploadPolicyDocument).toHaveBeenCalledWith(file, '2026-08-01'));
  });

  // ── The trap this page exists to defuse ───────────────────────────────────
  // "Current" is the latest effective_from <= today. A future-dated upload is stored and returns
  // 200 while the availability probe keeps reporting the previous state — which reads as "my upload
  // did nothing" unless the page says otherwise.
  it('explains that a future-dated version is stored but not yet in force', async () => {
    const future = '2099-01-01';
    api.leave.uploadPolicyDocument.mockResolvedValue({
      document: { documentId: 2, fileName: 'next-year.pdf', fileSize: 2048, effectiveFrom: future },
    });
    renderPage();
    await screen.findByText(/ยังไม่เคยมีการอัปโหลดไฟล์เข้ามาเลย/);

    await fillAndSubmit({ date: future });

    expect(await screen.findByText(/ยังไม่ถือเป็นฉบับปัจจุบัน/)).not.toBeNull();
    expect(screen.getByText(/ไม่ใช่ข้อผิดพลาด/)).not.toBeNull();
  });

  it('does not show the not-yet-in-force note for a version effective today', async () => {
    const today = bangkokTodayIso();
    api.leave.uploadPolicyDocument.mockResolvedValue({
      document: { documentId: 3, fileName: 'now.pdf', fileSize: 2048, effectiveFrom: today },
    });
    renderPage();
    await screen.findByText(/ยังไม่เคยมีการอัปโหลดไฟล์เข้ามาเลย/);

    await fillAndSubmit({ date: today });

    expect(await screen.findByText('now.pdf')).not.toBeNull();
    expect(screen.queryByText(/ยังไม่ถือเป็นฉบับปัจจุบัน/)).toBeNull();
  });

  // Found by clicking through the real app, not by a failing test: clearing the input's `value`
  // empties the real <input type="file"> but not FileUploadField's own display state, so the
  // control kept naming a file it had already consumed while the input behind it was empty — and
  // the next submit would complain about a missing file the user could still see on screen.
  it('clears the file field after a successful upload, name and input together', async () => {
    renderPage();
    await screen.findByText(/ยังไม่เคยมีการอัปโหลดไฟล์เข้ามาเลย/);

    await fillAndSubmit({ file: pdf('policy-2568.pdf'), date: '2026-08-01' });
    await screen.findByText('announcement.pdf'); // the success panel, i.e. the upload resolved

    expect(screen.queryByText('policy-2568.pdf')).toBeNull();
    expect(screen.getByText('ยังไม่ได้เลือกไฟล์')).not.toBeNull();
    expect(document.getElementById('leave-policy-file').files.length).toBe(0);
  });

  it('surfaces the backend refusal verbatim and keeps the form filled in', async () => {
    api.leave.uploadPolicyDocument.mockRejectedValue(new Error('รองรับเฉพาะไฟล์ PDF'));
    renderPage();
    await screen.findByText(/ยังไม่เคยมีการอัปโหลดไฟล์เข้ามาเลย/);

    await fillAndSubmit();

    expect(await screen.findByRole('alert')).not.toBeNull();
    expect(screen.getByText('รองรับเฉพาะไฟล์ PDF')).not.toBeNull();
  });

  it('refuses to submit without a file or a date', async () => {
    renderPage();
    await screen.findByText(/ยังไม่เคยมีการอัปโหลดไฟล์เข้ามาเลย/);

    fireEvent.click(screen.getByRole('button', { name: /อัปโหลดประกาศ/ }));

    expect(await screen.findByText('กรุณาเลือกไฟล์ PDF')).not.toBeNull();
    expect(screen.getByText('กรุณาระบุวันที่มีผลบังคับใช้')).not.toBeNull();
    expect(api.leave.uploadPolicyDocument).not.toHaveBeenCalled();
  });

  // Mirrors LeaveController.MAX_POLICY_DOCUMENT_BYTES. Size is a real client-side fact (unlike the
  // declared content type), so checking it before the round trip adds something without implying a
  // verification nobody performs.
  it('catches an oversized file before sending it', async () => {
    renderPage();
    await screen.findByText(/ยังไม่เคยมีการอัปโหลดไฟล์เข้ามาเลย/);

    await fillAndSubmit({ file: pdf('huge.pdf', 11 * 1024 * 1024) });

    expect(await screen.findByText('ไฟล์มีขนาดใหญ่เกิน 10 MB')).not.toBeNull();
    expect(api.leave.uploadPolicyDocument).not.toHaveBeenCalled();
  });
});
