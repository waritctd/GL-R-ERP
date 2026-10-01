import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { act, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DealDocumentPipeline } from './DealDocumentPipeline.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      storedRemainingInvoices: { listForTicket: vi.fn() },
      billingNotes: { listForCustomer: vi.fn() },
    },
  };
});

function baseSummary(overrides = {}) {
  return {
    customerId: 6,
    depositPolicy: 'REQUIRED',
    paymentStatus: null,
    ...overrides,
  };
}

function riRow(overrides = {}) {
  return { id: 900, status: 'ISSUED', docNumber: 'GLR6900001-1', ...overrides };
}

function noteRow(overrides = {}) {
  return {
    id: 1, status: 'DRAFT', docNumber: null,
    lines: [{ sourceType: 'REMAINING_INVOICE', sourceId: 900 }],
    ...overrides,
  };
}

// The pipeline always renders a <Link> for the ใบวางบิล step's action slot when eligible, so
// every render needs router context — wrapping unconditionally here rather than per-test avoids
// the crash-and-leak failure mode a bare <Link> outside a Router produces (an uncaught exception
// that can pollute a LATER test's DOM if cleanup doesn't run cleanly).
function renderPipeline({
  summary = baseSummary(),
  user = { role: 'sales' },
  remainingInvoices = [],
  billingNotes = [],
} = {}) {
  api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices });
  api.billingNotes.listForCustomer.mockResolvedValue({ billingNotes });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <DealDocumentPipeline ticketId={19} summary={summary} user={user} />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('DealDocumentPipeline — step 1 ใบแจ้งมัดจำ', () => {
  it('reads "ไม่ต้องมัดจำ" when the deposit policy bypasses the notice', async () => {
    renderPipeline({ summary: baseSummary({ depositPolicy: 'WAIVED' }) });
    expect(await screen.findByText('ไม่ต้องมัดจำ')).toBeTruthy();
  });

  it('reads "ยังไม่ออกใบแจ้ง" before any deposit notice exists', async () => {
    renderPipeline();
    expect(await screen.findByText('ยังไม่ออกใบแจ้ง')).toBeTruthy();
  });

  it('reads "รอชำระ" once the deposit notice is issued but unpaid', async () => {
    renderPipeline({ summary: baseSummary({ paymentStatus: 'DEPOSIT_NOTICE_ISSUED' }) });
    expect(await screen.findByText('รอชำระ')).toBeTruthy();
  });

  it('reads "ชำระแล้ว" once the deposit is paid', async () => {
    renderPipeline({ summary: baseSummary({ paymentStatus: 'DEPOSIT_PAID' }) });
    expect(await screen.findByText('ชำระแล้ว')).toBeTruthy();
  });
});

describe('DealDocumentPipeline — step 2 ใบแจ้งหนี้ส่วนที่เหลือ (blocked until step 1 is done)', () => {
  it('reads "รอขั้นตอนก่อนหน้า" while the deposit itself is still outstanding', async () => {
    renderPipeline({ summary: baseSummary({ paymentStatus: 'DEPOSIT_NOTICE_ISSUED' }) });
    expect(await screen.findByText('รอขั้นตอนก่อนหน้า')).toBeTruthy();
  });

  it('reads "ยังไม่ออก" once step 1 is done but no remaining invoice exists yet', async () => {
    renderPipeline({ summary: baseSummary({ paymentStatus: 'DEPOSIT_PAID' }) });
    expect(await screen.findByText('ยังไม่ออก')).toBeTruthy();
  });

  it('shows the DRAFT remaining invoice as "อยู่ระหว่างจัดทำ (ร่าง)"', async () => {
    renderPipeline({
      summary: baseSummary({ paymentStatus: 'DEPOSIT_PAID' }),
      remainingInvoices: [riRow({ status: 'DRAFT', docNumber: null })],
    });
    expect(await screen.findByText('อยู่ระหว่างจัดทำ (ร่าง)')).toBeTruthy();
  });

  it('shows the ISSUED remaining invoice by its own docNumber', async () => {
    renderPipeline({
      summary: baseSummary({ paymentStatus: 'DEPOSIT_PAID' }),
      remainingInvoices: [riRow()],
    });
    expect(await screen.findByText('ออกแล้ว (GLR6900001-1)')).toBeTruthy();
  });
});

describe('DealDocumentPipeline — step 3 ใบวางบิล (client-side join against the customer-scoped list)', () => {
  it('reads "รอใบแจ้งหนี้ส่วนที่เหลือ" when no remaining invoice has been issued', async () => {
    renderPipeline();
    expect(await screen.findByText('รอใบแจ้งหนี้ส่วนที่เหลือ')).toBeTruthy();
  });

  it('reads "ยังไม่ได้วางบิล" once the remaining invoice is issued but no note claims it', async () => {
    renderPipeline({ remainingInvoices: [riRow()] });
    expect(await screen.findByText('ยังไม่ได้วางบิล')).toBeTruthy();
  });

  it('matches a claiming note by sourceType+sourceId, not just by existing at all', async () => {
    // A note that references a DIFFERENT source must not be read as claiming this deal's RI.
    renderPipeline({
      remainingInvoices: [riRow()],
      billingNotes: [noteRow({ lines: [{ sourceType: 'REMAINING_INVOICE', sourceId: 999 }] })],
    });
    expect(await screen.findByText('ยังไม่ได้วางบิล')).toBeTruthy();
  });

  it('reads "อยู่ระหว่างจัดทำ (ร่าง)" when the claiming note is a DRAFT', async () => {
    renderPipeline({ remainingInvoices: [riRow()], billingNotes: [noteRow({ status: 'DRAFT' })] });
    expect(await screen.findByText('อยู่ระหว่างจัดทำ (ร่าง)')).toBeTruthy();
  });

  it('reads "วางบิลแล้ว (<docNumber>)" when the claiming note is ISSUED', async () => {
    renderPipeline({
      remainingInvoices: [riRow()],
      billingNotes: [noteRow({ status: 'ISSUED', docNumber: 'GLR6900002-1' })],
    });
    expect(await screen.findByText('วางบิลแล้ว (GLR6900002-1)')).toBeTruthy();
  });

  // Role-aware link — the point of this whole step's "role-aware actions" requirement.
  it('shows the ไปที่งานการเงิน link for account once a remaining invoice is issued, never for a plain sales rep', async () => {
    const { unmount } = renderPipeline({ remainingInvoices: [riRow()], user: { role: 'sales' } });
    await screen.findByText('ยังไม่ได้วางบิล');
    expect(screen.queryByText('ไปที่งานการเงิน')).toBeNull();
    unmount();

    renderPipeline({ remainingInvoices: [riRow()], user: { role: 'account' } });
    expect(await screen.findByText('ไปที่งานการเงิน')).toBeTruthy();
  });

  // Review round 2 (2026-09-23): deliberately NOT widened to canIssueBillingNote yet — see
  // DealDocumentPipeline.jsx's own comment on canSeeFinance. A can_issue_billing_note grant has no
  // role restriction (hr.employee.can_issue_billing_note is a plain boolean), so a REAL, reachable
  // role (sales_manager, unlike the seed's only current grant holder, employee — which never
  // reaches this tab at all) could hold it. Showing the link to that role before part 3 wires
  // isBillingNoteReleaseUser into /finance's own PATH_GUARDS would be a link a real user could
  // click and get refused — this pins that it does NOT happen.
  it('does not show the link for a can_issue_billing_note grant holder outside canConfirmPayments (part 3 not merged yet)', async () => {
    renderPipeline({
      remainingInvoices: [riRow()],
      user: { role: 'sales_manager', canIssueBillingNote: true },
    });
    await screen.findByText('ยังไม่ได้วางบิล');
    expect(screen.queryByText('ไปที่งานการเงิน')).toBeNull();
  });

  it('never shows the link before a remaining invoice is issued, even for account', async () => {
    renderPipeline({ user: { role: 'account' } });
    await screen.findByText('รอใบแจ้งหนี้ส่วนที่เหลือ');
    expect(screen.queryByText('ไปที่งานการเงิน')).toBeNull();
  });

  // Review round 1 (2026-09-23): a source's sourceId is not unique ACROSS types — REMAINING_INVOICE
  // and DEPOSIT_NOTICE are independent sequences, so id 900 can legitimately belong to each. Without
  // the sourceType half of the match, this would false-positive as "billed".
  it('does not match a line whose sourceId coincides but whose sourceType differs', async () => {
    renderPipeline({
      remainingInvoices: [riRow()],
      billingNotes: [noteRow({ lines: [{ sourceType: 'DEPOSIT_NOTICE', sourceId: 900 }] })],
    });
    expect(await screen.findByText('ยังไม่ได้วางบิล')).toBeTruthy();
  });

  // Review round 1: the billing note lifecycle has a FOURTH terminal status this step's match used
  // to miss — DRAFT -> ISSUED -> {SUPERSEDED | CANCELLED | SETTLED}. BillingNoteRepository
  // .findByCustomer runs the settlement reconcile before every read (including this one), so a
  // deal reaching full payment can self-trigger its own note into SETTLED on the very read this
  // component performs — regressing step 3 back to "not billed" at the exact moment billing
  // completed, unless SETTLED gets its own branch.
  it('reads "ชำระแล้ว (<docNumber>)" when the claiming note has settled', async () => {
    renderPipeline({
      remainingInvoices: [riRow()],
      billingNotes: [noteRow({ status: 'SETTLED', docNumber: 'GLR6900005-1' })],
    });
    expect(await screen.findByText('ชำระแล้ว (GLR6900005-1)')).toBeTruthy();
  });

  // Review round 1: revise() leaves the predecessor ISSUED/SETTLED (only its per-line note_status,
  // never serialized, flips) while the new correction DRAFT copies the same sourceId — and
  // findByCustomer's own ORDER BY COALESCE(base_number, ''), type, version puts the not-yet-numbered
  // DRAFT BEFORE its predecessor. A plain array .find() would therefore read a billed, paid deal as
  // "still a draft" while a correction is merely open. The live/settled note must win regardless of
  // array order.
  it('prefers an ISSUED claiming note over a DRAFT one for the same source, regardless of array order', async () => {
    renderPipeline({
      remainingInvoices: [riRow()],
      billingNotes: [
        noteRow({ id: 2, status: 'DRAFT', docNumber: null }), // the correction draft, listed FIRST
        noteRow({ id: 1, status: 'ISSUED', docNumber: 'GLR6900006-1' }), // the still-live predecessor
      ],
    });
    expect(await screen.findByText('วางบิลแล้ว (GLR6900006-1)')).toBeTruthy();
    expect(screen.queryByText('อยู่ระหว่างจัดทำ (ร่าง)')).toBeNull();
  });
});

describe('DealDocumentPipeline — step 4 รับชำระครบ', () => {
  it('reads "รอชำระให้ครบ" before FULLY_PAID', async () => {
    renderPipeline({ summary: baseSummary({ paymentStatus: 'AWAITING_FINAL_PAYMENT' }) });
    expect(await screen.findByText('รอชำระให้ครบ')).toBeTruthy();
  });

  it('reads "ชำระครบแล้ว" once paymentStatus is FULLY_PAID', async () => {
    renderPipeline({ summary: baseSummary({ paymentStatus: 'FULLY_PAID' }) });
    expect(await screen.findByText('ชำระครบแล้ว')).toBeTruthy();
  });
});

describe('DealDocumentPipeline — next-step highlighting', () => {
  it('marks step 1 as the current step on a brand-new deal', async () => {
    renderPipeline();
    await screen.findByText('ขั้นตอนถัดไป');
    expect(screen.getAllByText('ขั้นตอนถัดไป')).toHaveLength(1);
  });

  it('advances the marker to step 4 once everything else is done', async () => {
    renderPipeline({
      summary: baseSummary({ paymentStatus: 'AWAITING_FINAL_PAYMENT' }),
      remainingInvoices: [riRow()],
      billingNotes: [noteRow({ status: 'ISSUED', docNumber: 'GLR6900003-1' })],
    });
    await screen.findByText('วางบิลแล้ว (GLR6900003-1)');
    const marker = screen.getByText('ขั้นตอนถัดไป');
    const card = marker.closest('div.rounded-md');
    expect(card?.textContent).toContain('รับชำระครบ');
  });

  it('shows no current-step marker once every step is done', async () => {
    renderPipeline({
      summary: baseSummary({ paymentStatus: 'FULLY_PAID' }),
      remainingInvoices: [riRow()],
      billingNotes: [noteRow({ status: 'ISSUED', docNumber: 'GLR6900004-1' })],
    });
    // Wait for step 3's own final text (not just step 4's, which is derived purely from `summary`
    // and reads its final value before either query resolves) so this assertion runs against
    // settled data, not the loading-state render `stepsLoading` deliberately suppresses the marker
    // for.
    await screen.findByText('วางบิลแล้ว (GLR6900004-1)');
    expect(screen.queryByText('ขั้นตอนถัดไป')).toBeNull();
  });

  // Review round 1 (2026-09-23): step4Done is derived purely from `summary`, with no query
  // dependency, so it reads its FINAL value on the very first render while both queries are still
  // in flight. Without the stepsLoading guard, doneFlags would briefly read
  // [true, false, false, true] (step 2's own loading-state branch carries no `done` flag), landing
  // the marker on step 2 for one paint before the data-driven tests elsewhere in this file ever get
  // a chance to observe it — those tests all `await` a query-derived text FIRST, which is exactly
  // why they cannot catch this on their own. This test controls the query's resolution explicitly
  // to inspect the in-flight render directly.
  it('shows no premature marker while queries are still loading, even on a FULLY_PAID deal', async () => {
    // billingNotesQuery is gated on `issuedRemainingInvoice` (review round 2), so with
    // remainingInvoices resolving to [] it never even enables — no need to mock/resolve it here.
    let resolveRemainingInvoices;
    api.storedRemainingInvoices.listForTicket.mockReturnValue(
      new Promise((resolve) => { resolveRemainingInvoices = resolve; }),
    );
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <MemoryRouter>
        <QueryClientProvider client={queryClient}>
          <DealDocumentPipeline ticketId={19} summary={baseSummary({ paymentStatus: 'FULLY_PAID' })} user={{ role: 'sales' }} />
        </QueryClientProvider>
      </MemoryRouter>,
    );
    // step 4's final text is already on screen (it needs no query), proving this assertion runs
    // during the exact window the guard protects, not before the component has rendered at all.
    await screen.findByText('ชำระครบแล้ว');
    expect(screen.queryByText('ขั้นตอนถัดไป')).toBeNull();
    // Resolve and let the pending update flush inside act() before the test ends, so the state
    // update this triggers cannot land after teardown and warn.
    await act(async () => {
      resolveRemainingInvoices({ remainingInvoices: [] });
      await Promise.resolve();
    });
  });
});
