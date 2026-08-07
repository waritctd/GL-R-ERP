import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation, useNavigationType } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TaxAllowancePage } from './TaxAllowancePage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      payroll: {
        getTaxAllowanceCaps: vi.fn(),
        getMyTaxAllowanceDeclarations: vi.fn(),
        submitMyTaxAllowanceDeclaration: vi.fn(),
        withdrawMyTaxAllowanceDeclaration: vi.fn(),
        estimateMyTaxAllowanceDeclaration: vi.fn(),
        listTaxAllowanceAttachments: vi.fn(),
        uploadTaxAllowanceAttachment: vi.fn(),
      },
    },
  };
});

const user = { role: 'employee', name: 'สมชาย ใจดี', employeeId: 9 };
const currentYear = new Date().getFullYear();

function declaration(overrides = {}) {
  return {
    declarationId: 55,
    employeeId: 9,
    status: 'PENDING',
    submittedAt: `${currentYear}-03-01T00:00:00.000Z`,
    appliedAt: null,
    appliedEffectiveMonth: null,
    effectiveMonth: null,
    expiresOn: null,
    reviewerNote: null,
    allowances: {},
    ...overrides,
  };
}

// Same idiom as TaxAllowanceReviewPage.test.jsx's own `LocationProbe` -- exposes the router's
// current URL as text so a test can assert on it without reaching into react-router internals.
// `navigationType` additionally exposes react-router's own PUSH/REPLACE/POP classification of the
// most recent navigation (public API, `useNavigationType`) -- used to verify view changes push a
// real history entry while year changes replace, without reaching into MemoryRouter's own history
// stack.
function LocationProbe() {
  const location = useLocation();
  const navigationType = useNavigationType();
  return (
    <div data-testid="location" data-nav-type={navigationType}>
      {`${location.pathname}${location.search}`}
    </div>
  );
}

function renderPage({ entry = '/tax-allowance' } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[entry]}>
        <TaxAllowancePage user={user} showToast={vi.fn()} />
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('TaxAllowancePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.payroll.getTaxAllowanceCaps.mockResolvedValue({ caps: [] });
    api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [] });
    api.payroll.listTaxAllowanceAttachments.mockResolvedValue({ items: [] });
    api.payroll.withdrawMyTaxAllowanceDeclaration.mockResolvedValue(undefined);
  });

  describe('withdrawal — the way out of PENDING', () => {
    it('offers withdrawal while pending, explaining why the form is locked', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [declaration()] });
      renderPage();

      expect(await screen.findByRole('button', { name: 'ยกเลิกการยื่น' })).not.toBeNull();
      expect(screen.getByText(/แก้ไขไม่ได้ระหว่างรอ HR ตรวจสอบ/)).not.toBeNull();
      // Editing stays unavailable — withdrawal is the exit, not a direct edit.
      expect(screen.queryByRole('button', { name: 'แก้ไข / ยื่นฉบับใหม่' })).toBeNull();
    });

    it('withdraws the pending declaration after confirmation', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [declaration()] });
      renderPage();

      fireEvent.click(await screen.findByRole('button', { name: 'ยกเลิกการยื่น' }));
      // The confirm dialog repeats the label; the last match is the dialog's confirm button.
      const confirmButtons = await screen.findAllByRole('button', { name: 'ยกเลิกการยื่น' });
      fireEvent.click(confirmButtons[confirmButtons.length - 1]);

      await waitFor(() => {
        expect(api.payroll.withdrawMyTaxAllowanceDeclaration).toHaveBeenCalledWith(55);
      });
    });

    it('does not offer withdrawal once approved', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({
        items: [declaration({ status: 'APPROVED', appliedAt: `${currentYear}-04-01T00:00:00Z`, appliedEffectiveMonth: 4 })],
      });
      renderPage();

      await screen.findByText(/ใช้กับเงินเดือนแล้ว/);
      expect(screen.queryByRole('button', { name: 'ยกเลิกการยื่น' })).toBeNull();
    });
  });

  describe('tax-year history', () => {
    it('refetches when a past year is chosen', async () => {
      renderPage();
      await waitFor(() => {
        expect(api.payroll.getMyTaxAllowanceDeclarations).toHaveBeenCalledWith(currentYear);
      });

      fireEvent.change(screen.getByLabelText('ปีภาษี'), { target: { value: String(currentYear - 1) } });
      await waitFor(() => {
        expect(api.payroll.getMyTaxAllowanceDeclarations).toHaveBeenCalledWith(currentYear - 1);
      });
    });

    it('is read-only for a past year even when nothing was ever filed', async () => {
      renderPage();
      await screen.findByLabelText('ปีภาษี');

      fireEvent.change(screen.getByLabelText('ปีภาษี'), { target: { value: String(currentYear - 1) } });

      expect(await screen.findByText(new RegExp(`ยื่นหรือแก้ไขได้เฉพาะปีภาษี ${currentYear}`))).not.toBeNull();
      expect(screen.queryByRole('button', { name: 'แก้ไข / ยื่นฉบับใหม่' })).toBeNull();
    });
  });

  // The user's stated complaint (#tax-allowance-sections): "I couldn't attach a PDF while first
  // filling in the form" -- there was no declarationId yet for a brand-new declaration to attach
  // evidence to. This proves the fix end to end: a file picked mid-fill-in shows immediately
  // (staged, client-side) WITHOUT hitting the server, and is genuinely uploaded — tagged to the
  // section it was picked under — only once the declaration is actually submitted and a real
  // declarationId exists.
  describe('attaching evidence while first filling in a NOT-YET-SUBMITTED declaration', () => {
    it('stages a PDF picked while filling in a section, then uploads it against the real declarationId once submit succeeds', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [] }); // status NONE
      api.payroll.submitMyTaxAllowanceDeclaration.mockResolvedValue({ declarationId: 77, employeeId: 9, status: 'PENDING' });
      api.payroll.uploadTaxAllowanceAttachment.mockResolvedValue({ attachment: { attachmentId: 1 } });

      renderPage();

      fireEvent.click(await screen.findByText('ครอบครัว'));

      const file = new File(['x'], 'cert.pdf', { type: 'application/pdf' });
      const input = document.querySelector('input[type="file"]');
      fireEvent.change(input, { target: { files: [file] } });

      // Attached immediately, during fill-in -- no round trip needed to SEE it.
      expect(await screen.findByText('cert.pdf')).not.toBeNull();
      expect(screen.getByText('รอส่ง')).not.toBeNull();
      // But genuinely not sent yet -- there is no declarationId for a brand-new declaration.
      expect(api.payroll.uploadTaxAllowanceAttachment).not.toHaveBeenCalled();

      // Back to the hub, into review, then the ONE real submit control in the whole flow --
      // SECTION itself never carries one (#tax-allowance-ia-hub-review).
      fireEvent.click(screen.getByRole('button', { name: /กลับไปหน้ารวม/ }));
      fireEvent.click(screen.getByRole('button', { name: 'ตรวจทานและยื่น' }));
      fireEvent.click(screen.getByRole('button', { name: 'ยื่นแบบแจ้ง' }));

      await waitFor(() => expect(api.payroll.submitMyTaxAllowanceDeclaration).toHaveBeenCalled());
      // Flushed against the REAL declarationId the submit just returned, tagged with the section
      // it was picked under while filling in -- proves this is not "attach after a first submit",
      // it is the SAME pick from mid-fill-in finally reaching the server.
      await waitFor(() => expect(api.payroll.uploadTaxAllowanceAttachment).toHaveBeenCalledWith(77, file, 'family'));
    });
  });

  describe('?year=/?view= navigation via the URL (#tax-allowance-ia-hub-review)', () => {
    it('reads a valid ?view= from the URL straight into the matching section', async () => {
      renderPage({ entry: '/tax-allowance?view=insurance' });
      expect(await screen.findByLabelText('ประกันชีวิต')).not.toBeNull();
    });

    it('falls back to the hub for a garbage ?view=', async () => {
      renderPage({ entry: '/tax-allowance?view=not-a-real-section' });
      // Only the hub renders the declaration-level fields directly, with nothing else selected.
      expect(await screen.findByLabelText('มีผลตั้งแต่งวดเดือน')).not.toBeNull();
    });

    it('writes ?view= into the URL when a hub row is opened, and clears it again on the way back', async () => {
      renderPage();
      await screen.findByLabelText('มีผลตั้งแต่งวดเดือน');

      fireEvent.click(screen.getByRole('button', { name: /ครอบครัว/ }));
      await waitFor(() => expect(screen.getByTestId('location').textContent).toContain('view=family'));

      fireEvent.click(screen.getByRole('button', { name: 'กลับไปหน้ารวม' }));
      await waitFor(() => expect(screen.getByTestId('location').textContent).not.toContain('view='));
    });

    it('reads a valid ?year= from the URL and refetches for it', async () => {
      renderPage({ entry: `/tax-allowance?year=${currentYear - 1}` });
      await waitFor(() => {
        expect(api.payroll.getMyTaxAllowanceDeclarations).toHaveBeenCalledWith(currentYear - 1);
      });
      expect(screen.getByLabelText('ปีภาษี').value).toBe(String(currentYear - 1));
    });

    it('falls back to the current year for a garbage ?year=', async () => {
      renderPage({ entry: '/tax-allowance?year=not-a-number' });
      await waitFor(() => {
        expect(api.payroll.getMyTaxAllowanceDeclarations).toHaveBeenCalledWith(currentYear);
      });
      expect(screen.getByLabelText('ปีภาษี').value).toBe(String(currentYear));
    });

    // Review fix: HUB/SECTION/REVIEW are real, URL-addressable screens now that `?view=` exists at
    // all, so Back should step between them like any other page navigation -- not leave
    // `/tax-allowance` entirely and drop everything typed. A year switch is a filter on the same
    // screen, not a screen of its own, so it keeps the OLD `replace: true` behaviour instead.
    it('view changes push a new history entry; year changes replace instead', async () => {
      renderPage();
      await screen.findByLabelText('มีผลตั้งแต่งวดเดือน');

      fireEvent.click(screen.getByRole('button', { name: /ครอบครัว/ }));
      await waitFor(() => expect(screen.getByTestId('location').dataset.navType).toBe('PUSH'));

      fireEvent.change(screen.getByLabelText('ปีภาษี'), { target: { value: String(currentYear - 1) } });
      await waitFor(() => expect(screen.getByTestId('location').dataset.navType).toBe('REPLACE'));
    });
  });

  describe('REVIEW\'s staged-evidence manifest (review fix)', () => {
    it('includes the general/uncategorized bucket staged through the hub, not just the five sections', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [] }); // status NONE -> editing, mode 'staging'
      renderPage();

      // The picker only mounts once the declarations query has SETTLED and the page has concluded
      // this year is unfiled -- `editing` is deliberately not decided while the query is in flight
      // (see TaxAllowancePage's own comment on that effect), so querying for it synchronously
      // after render finds nothing.
      const picker = await waitFor(() => {
        const input = document.querySelector('input[type="file"]');
        expect(input).not.toBeNull();
        return input;
      });

      // Staged through the HUB's own general panel -- no section chosen, so this lands in the
      // UNCATEGORIZED_EVIDENCE_KEY bucket, which is not one of TAX_ALLOWANCE_GROUPS' own keys.
      const file = new File(['x'], 'general.pdf', { type: 'application/pdf' });
      fireEvent.change(picker, { target: { files: [file] } });
      await screen.findByText('general.pdf');

      fireEvent.click(screen.getByRole('button', { name: 'ตรวจทานและยื่น' }));

      const generalRow = screen.getByText('ทั่วไป (ไม่ได้ระบุหมวด)').closest('div');
      expect(within(generalRow).getByText('1 ไฟล์')).not.toBeNull();
    });

    it('does not render outside staging mode, where the staged-only count would misreport real stored attachments as zero', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [declaration()] }); // PENDING -> mode 'direct'
      renderPage({ entry: '/tax-allowance?view=review' });

      await screen.findByRole('heading', { name: 'ตรวจทานก่อนยื่น' });
      expect(screen.queryByText('ไฟล์ที่เตรียมไว้ — ยังไม่ได้ส่ง')).toBeNull();
    });
  });

  describe('year-switch confirmation guards unsaved work', () => {
    it('switches immediately when the form is clean and no evidence is staged', async () => {
      renderPage();
      await screen.findByLabelText('ปีภาษี');

      fireEvent.change(screen.getByLabelText('ปีภาษี'), { target: { value: String(currentYear - 1) } });

      expect(screen.queryByRole('heading', { name: 'เปลี่ยนปีภาษี' })).toBeNull();
      await waitFor(() => {
        expect(api.payroll.getMyTaxAllowanceDeclarations).toHaveBeenCalledWith(currentYear - 1);
      });
    });

    it('asks for confirmation before switching when the form has unsaved edits', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [] }); // NONE + current year -> already editable
      renderPage();

      fireEvent.change(await screen.findByLabelText('เลขที่เอกสารอ้างอิง (ถ้ามี)'), { target: { value: 'REF-1' } });
      fireEvent.change(screen.getByLabelText('ปีภาษี'), { target: { value: String(currentYear - 1) } });

      expect(await screen.findByRole('heading', { name: 'เปลี่ยนปีภาษี' })).not.toBeNull();
      expect(api.payroll.getMyTaxAllowanceDeclarations).not.toHaveBeenCalledWith(currentYear - 1);
    });

    it('asks for confirmation before switching when evidence is staged', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [] });
      renderPage();

      fireEvent.click(await screen.findByRole('button', { name: /ครอบครัว/ }));
      const file = new File(['x'], 'cert.pdf', { type: 'application/pdf' });
      fireEvent.change(document.querySelector('input[type="file"]'), { target: { files: [file] } });
      await screen.findByText('cert.pdf');

      fireEvent.change(screen.getByLabelText('ปีภาษี'), { target: { value: String(currentYear - 1) } });

      expect(await screen.findByRole('heading', { name: 'เปลี่ยนปีภาษี' })).not.toBeNull();
    });

    it('proceeds with the year switch once confirmed, and resets ?view= back to the hub', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [] });
      // Starts on the family SECTION (not the hub) so this also proves the reset-to-hub half of the
      // claim -- "เลขที่เอกสารอ้างอิง" (used by the other dirty-form test above) lives on the hub only,
      // so dirtying via this section's own field is what's available here.
      renderPage({ entry: '/tax-allowance?view=family' });

      fireEvent.change(await screen.findByLabelText('คู่สมรส (ไม่มีเงินได้)'), { target: { value: '1000' } });
      fireEvent.change(screen.getByLabelText('ปีภาษี'), { target: { value: String(currentYear - 1) } });
      fireEvent.click(await screen.findByRole('button', { name: 'ยืนยันเปลี่ยนปีภาษี' }));

      await waitFor(() => {
        expect(api.payroll.getMyTaxAllowanceDeclarations).toHaveBeenCalledWith(currentYear - 1);
      });
      expect(screen.getByTestId('location').textContent).not.toContain('view=');
    });
  });
});
