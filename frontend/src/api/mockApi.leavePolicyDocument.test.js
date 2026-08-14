import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';
import { bangkokTodayIso } from '../utils/format.js';

// Guards the §5 announcement PDF endpoints against LeaveController#policyDocument /
// #uploadPolicyDocument and LeavePolicyDocumentRepository#findCurrent (issue #744).
//
// ⚠️ mockApi's `db` is module-level and shared by every test in this file, with no reset hook. Once
// any test below uploads a document the store is no longer pristine, so every later assertion is
// written to tolerate that — scoped, or compared relatively. The "starts empty" test cannot
// tolerate residue and must stay FIRST.

function pdf(name = 'a.pdf', size = 1024, type = 'application/pdf', content = '%PDF-1.4') {
  const file = new File([content], name, { type });
  // The declared size drives the mock's cap check; the real byte length is what a served Blob
  // reports, so the two are deliberately independent here.
  Object.defineProperty(file, 'size', { value: size });
  return file;
}

const YESTERDAY = '2000-01-01';
const FUTURE = '2099-01-01';

describe('mock leave policy document', () => {
  // V133 is explicit: rows reach hr.leave_policy_document only through POST
  // /api/leave/policy-document, which had no client until #744. The table is therefore empty in
  // EVERY environment, production included — seeding one here would make the fixture more populated
  // than production and hide the only state a real deployment actually has.
  it('starts with no document, exactly as every real environment does', async () => {
    await api.auth.login({ role: 'hr' });
    await expect(api.leave.policyDocumentAvailable()).resolves.toBe('absent');
    await expect(api.leave.downloadPolicyDocument())
      .rejects.toThrow('ยังไม่มีการอัปโหลดเอกสารประกาศฉบับนี้ กรุณาติดต่อฝ่ายบุคคล');
  });

  // requireAnyRole(user, "hr", "ceo") on the upload — NARROWER than the read, which is
  // sessions.requireUser only (every authenticated employee may read the rules that bind them).
  it('lets hr and ceo upload', async () => {
    for (const role of ['hr', 'ceo']) {
      await api.auth.login({ role });
      const { document } = await api.leave.uploadPolicyDocument(pdf(`${role}.pdf`), YESTERDAY);
      expect(document.fileName).toBe(`${role}.pdf`);
    }
  });

  it('refuses every role that is not hr or ceo', async () => {
    for (const role of ['employee', 'sales', 'sales_manager', 'import', 'account']) {
      await api.auth.login({ role });
      await expect(api.leave.uploadPolicyDocument(pdf(), YESTERDAY))
        .rejects.toThrow('ไม่มีสิทธิ์เข้าถึงรายการนี้');
    }
  });

  // ...while READING stays open to any authenticated user, which is the asymmetry that matters.
  it('still lets a plain employee read the document', async () => {
    await api.auth.login({ role: 'employee' });
    await expect(api.leave.policyDocumentAvailable()).resolves.toBe('available');
  });

  it('rejects a non-PDF, an empty file, and an oversized file with the controller messages', async () => {
    await api.auth.login({ role: 'hr' });
    await expect(api.leave.uploadPolicyDocument(pdf('x.png', 512, 'image/png'), YESTERDAY))
      .rejects.toThrow('รองรับเฉพาะไฟล์ PDF');
    await expect(api.leave.uploadPolicyDocument(pdf('empty.pdf', 0), YESTERDAY))
      .rejects.toThrow('ไฟล์ว่างเปล่า');
    await expect(api.leave.uploadPolicyDocument(pdf('huge.pdf', 10 * 1024 * 1024 + 1), YESTERDAY))
      .rejects.toThrow('ไฟล์มีขนาดใหญ่เกินไป');
  });

  // The content-type check reads the CLIENT-DECLARED type on the real backend too, so the mock must
  // accept a file that merely CLAIMS to be a PDF. Being stricter here would hide a request
  // production accepts — the opposite of the usual mock hazard, and just as misleading.
  it('accepts a file that only declares application/pdf, exactly as the server does', async () => {
    await api.auth.login({ role: 'hr' });
    const notReallyAPdf = new File(['this is plain text'], 'liar.pdf', { type: 'application/pdf' });
    await expect(api.leave.uploadPolicyDocument(notReallyAPdf, YESTERDAY)).resolves.toBeTruthy();
  });

  it('returns metadata only, never the bytes', async () => {
    await api.auth.login({ role: 'hr' });
    const { document } = await api.leave.uploadPolicyDocument(pdf('meta.pdf', 2048), YESTERDAY);

    expect(Object.keys(document).sort()).toEqual(
      ['documentId', 'effectiveFrom', 'fileName', 'fileSize', 'mimeType', 'uploadedAt'],
    );
    expect(document.content).toBeUndefined();
  });

  // ── findCurrent's rule, which is the whole point ──────────────────────────
  // "Current" is the latest effective_from that is <= today — NOT "most recently uploaded". A
  // future-dated upload is stored and succeeds while the previous version keeps being served, and
  // that outcome is exactly what a user would misread as a failed upload.
  it('does not treat a future-dated upload as current', async () => {
    await api.auth.login({ role: 'hr' });
    // Distinguishable byte lengths, so the served Blob identifies WHICH version findCurrent chose —
    // asserting only "a Blob came back" would pass even if the future version were served.
    const inForce = 'IN-FORCE-BYTES';
    await api.leave.uploadPolicyDocument(pdf('in-force.pdf', 1024, 'application/pdf', inForce), YESTERDAY);
    const { document } = await api.leave.uploadPolicyDocument(
      pdf('next-year.pdf', 1024, 'application/pdf', 'NEXT'), FUTURE,
    );

    // Stored and acknowledged...
    expect(document.effectiveFrom).toBe(FUTURE);
    // ...but the bytes served are still the version already in force, not the newest upload.
    const blob = await api.leave.downloadPolicyDocument();
    expect(blob.size).toBe(inForce.length);
    await expect(api.leave.policyDocumentAvailable()).resolves.toBe('available');
  });

  it('treats a version effective today as current', async () => {
    await api.auth.login({ role: 'hr' });
    await api.leave.uploadPolicyDocument(pdf('today.pdf'), bangkokTodayIso());
    await expect(api.leave.policyDocumentAvailable()).resolves.toBe('available');
  });

  // LeavePolicyDocumentRepository#insert never updates — a superseded version stays retrievable, so
  // each upload must APPEND a row with its own id rather than replacing one.
  it('appends a new version instead of overwriting the previous one', async () => {
    await api.auth.login({ role: 'hr' });
    const first = await api.leave.uploadPolicyDocument(pdf('v1.pdf'), YESTERDAY);
    const second = await api.leave.uploadPolicyDocument(pdf('v2.pdf'), YESTERDAY);

    expect(second.document.documentId).toBeGreaterThan(first.document.documentId);
  });
});
