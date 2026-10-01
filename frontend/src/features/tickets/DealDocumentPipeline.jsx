import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { hasPermission } from '../../app/permissions.js';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { depositStatus } from './dealMoneyStatus.js';

// Ranks a billing note's "live" statuses for the step 3 join below — see that call site's own
// comment. Module-level (not rebuilt every render, unlike its two sibling constants right there).
const CLAIMING_NOTE_RANK = { SETTLED: 3, ISSUED: 2, DRAFT: 1 };

// Step-card shape copied from DealDepositPanel's own StepNumber/card wrapper (module-private
// there too) — see this feature's INFORMATION_ARCHITECTURE.md Component Reuse Map.
function StepNumber({ no }) {
  return (
    <span className="grid h-6 w-6 shrink-0 place-items-center rounded-full bg-info text-2xs font-extrabold text-surface">
      {no}
    </span>
  );
}

function PipelineStep({ no, title, status, tone, detail, current, action }) {
  return (
    <div className={`flex flex-col gap-1.5 rounded-md border p-3 ${
      current ? 'border-info bg-info-bg/40' : 'border-border bg-surface'
    }`}
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <StepNumber no={no} />
          <strong className="text-sm">{title}</strong>
          {current ? (
            <span className="rounded-full bg-info-bg px-2 py-0.5 text-2xs font-bold text-info">ขั้นตอนถัดไป</span>
          ) : null}
        </div>
        <StatusBadge tone={tone}>{status}</StatusBadge>
      </div>
      {detail ? <p className="text-xs text-text-muted">{detail}</p> : null}
      {action}
    </div>
  );
}

/**
 * GLA-129: a read-only map of the four money documents in sequence — ใบแจ้งมัดจำ → ใบแจ้งหนี้
 * ส่วนที่เหลือ → ใบวางบิล → รับชำระครบ — with the next actionable step highlighted. This does not
 * itself carry any document's controls (those stay on `DealDepositPanel` and the remaining-invoice
 * card below it in the money tab); it only tells the viewer where to look next.
 *
 * The ใบวางบิล step needs a customer-scoped billing-note read
 * (`api.billingNotes.listForCustomer`) to know whether this deal's remaining invoice has been
 * billed — joined client-side against the deal's own ISSUED remaining invoice, matched by
 * `sourceType === 'REMAINING_INVOICE' && sourceId`. No new backend endpoint: both reads already
 * exist (GLA-129 step 4 part 1).
 */
export function DealDocumentPipeline({ ticketId, summary, user }) {
  const remainingInvoicesQuery = useQuery({
    queryKey: queryKeys.storedRemainingInvoices(ticketId),
    queryFn: () => api.storedRemainingInvoices.listForTicket(ticketId).then((r) => r.remainingInvoices ?? []),
    enabled: !!ticketId,
  });
  const remainingInvoices = remainingInvoicesQuery.data ?? [];
  const issuedRemainingInvoice = remainingInvoices.find((ri) => ri.status === 'ISSUED') ?? null;

  // account/sales_manager/ceo read unconditionally; a `sales` caller is filtered server-side to
  // notes referencing a deal they own (never 403s outright) — see BillingNoteService#list. Safe to
  // call for every role that can render this pipeline at all (import/hr/employee never reach the
  // money tab — ticketDetailTabs.js's own gate).
  // Gated on `issuedRemainingInvoice` too (review round 2, 2026-09-23), not just customerId — this
  // read's own findByCustomer runs BillingNoteRepository's settlement reconcile as its first
  // statement, so calling it before step 2 has even produced a document to bill would trigger a
  // customer-wide settlement recompute on every money-tab open for zero possible effect on this
  // render (step 3 reads "รอใบแจ้งหนี้ส่วนที่เหลือ" whenever `issuedRemainingInvoice` is null,
  // regardless of what this query would return).
  const billingNotesQuery = useQuery({
    queryKey: queryKeys.billingNotesForCustomer(summary.customerId),
    queryFn: () => api.billingNotes.listForCustomer(summary.customerId).then((r) => r.billingNotes ?? []),
    enabled: !!summary.customerId && !!issuedRemainingInvoice,
  });
  const billingNotes = billingNotesQuery.data ?? [];

  // Review round 1 (2026-09-23) caught two real gaps here:
  // 1. SETTLED was missing from the matched statuses — a billing note's own lifecycle is DRAFT ->
  //    ISSUED -> {SUPERSEDED | CANCELLED | SETTLED}, and BillingNoteRepository#findByCustomer runs
  //    the settlement reconcile BEFORE every read (including this one), so a deal that just
  //    finished paying would self-trigger its own note into SETTLED on the very read this pipeline
  //    performs — silently regressing step 3 back to "ยังไม่ได้วางบิล" at the exact moment billing
  //    completed.
  // 2. A plain .find() picked whichever status happened to be first in findByCustomer's own ORDER
  //    BY COALESCE(base_number, ''), type, version — which puts a not-yet-numbered correction DRAFT
  //    BEFORE its still-live ISSUED/SETTLED predecessor. Ranked explicitly instead (CLAIMING_NOTE_RANK
  //    above), so a live correction-in-progress never hides a billed deal's real status.
  const claimingNote = issuedRemainingInvoice
    ? billingNotes
        .filter((note) => CLAIMING_NOTE_RANK[note.status]
          && note.lines?.some((line) => line.sourceType === 'REMAINING_INVOICE' && line.sourceId === issuedRemainingInvoice.id))
        .sort((a, b) => CLAIMING_NOTE_RANK[b.status] - CLAIMING_NOTE_RANK[a.status])[0] ?? null
    : null;

  // Step 1: ใบแจ้งมัดจำ
  const { bypassed: depositBypassedByPolicy, paid: depositPaid, noticeIssued: depositNoticeIssued } = depositStatus(summary);
  let step1 = { status: 'ยังไม่ออกใบแจ้ง', tone: 'neutral' };
  if (depositBypassedByPolicy) step1 = { status: 'ไม่ต้องมัดจำ', tone: 'neutral', done: true };
  else if (depositPaid) step1 = { status: 'ชำระแล้ว', tone: 'success', done: true };
  else if (depositNoticeIssued) step1 = { status: 'รอชำระ', tone: 'warning' };
  const step1Done = step1.done ?? false;

  // Step 2: ใบแจ้งหนี้ส่วนที่เหลือ
  const draftRemainingInvoice = remainingInvoices.find((ri) => ri.status === 'DRAFT') ?? null;
  let step2 = { status: 'รอขั้นตอนก่อนหน้า', tone: 'neutral' };
  if (remainingInvoicesQuery.isLoading) step2 = { status: 'กำลังโหลด…', tone: 'neutral' };
  else if (issuedRemainingInvoice) step2 = { status: `ออกแล้ว (${issuedRemainingInvoice.docNumber})`, tone: 'success', done: true };
  else if (draftRemainingInvoice) step2 = { status: 'อยู่ระหว่างจัดทำ (ร่าง)', tone: 'warning' };
  else if (step1Done) step2 = { status: 'ยังไม่ออก', tone: 'warning' };
  const step2Done = step2.done ?? false;

  // Step 3: ใบวางบิล — see this component's own doc comment above for the client-side join.
  // Review round 2 (2026-09-23): deliberately NOT OR'd with canIssueBillingNote yet, even though
  // that grant is the eventual audience per the design brief. permissions.js's own
  // isBillingNoteReleaseUser is NOT wired into /finance's PATH_GUARDS until part 3 merges — and
  // unlike the grant-only holders in today's seed (all role `employee`, which never reaches this
  // tab), a grant CAN be issued to a role that DOES reach the money tab (sales_manager, sales — the
  // grant has no role restriction, hr.employee.can_issue_billing_note is a plain boolean). Widening
  // this before part 3 lands would show a real, reachable user a link to a route that refuses them
  // today. Widen this in the SAME change that wires isBillingNoteReleaseUser into PATH_GUARDS.
  const canSeeFinance = hasPermission(user?.role, 'canConfirmPayments');
  let step3 = { status: 'รอใบแจ้งหนี้ส่วนที่เหลือ', tone: 'neutral' };
  if (issuedRemainingInvoice && billingNotesQuery.isLoading) step3 = { status: 'กำลังโหลด…', tone: 'neutral' };
  else if (claimingNote?.status === 'SETTLED') step3 = { status: `ชำระแล้ว (${claimingNote.docNumber})`, tone: 'success', done: true };
  else if (claimingNote?.status === 'ISSUED') step3 = { status: `วางบิลแล้ว (${claimingNote.docNumber})`, tone: 'success', done: true };
  else if (claimingNote?.status === 'DRAFT') step3 = { status: 'อยู่ระหว่างจัดทำ (ร่าง)', tone: 'warning' };
  else if (issuedRemainingInvoice) step3 = { status: 'ยังไม่ได้วางบิล', tone: 'warning' };
  const step3Done = step3.done ?? false;
  const step3Action = canSeeFinance && issuedRemainingInvoice
    ? <Link to="/finance" className="self-start text-xs font-bold text-link hover:underline">ไปที่งานการเงิน</Link>
    : null;

  // Step 4: รับชำระครบ
  const step4Done = summary.paymentStatus === 'FULLY_PAID';
  const step4 = step4Done
    ? { status: 'ชำระครบแล้ว', tone: 'success' }
    : { status: 'รอชำระให้ครบ', tone: 'neutral' };

  const doneFlags = [step1Done, step2Done, step3Done, step4Done];
  // step4Done is derived purely from `summary` (no query), so it reads its final value on the
  // FIRST render while the two queries above are still loading — without this guard, doneFlags
  // would briefly read e.g. [true, false, false, true] before data arrives (step 2/3's own
  // "กำลังโหลด…" branches carry no `done` flag), landing the marker on a step for one paint that
  // is not actually where the next action is. Suppressed entirely while either query is loading,
  // rather than showing a marker that would immediately jump once data settles.
  const stepsLoading = remainingInvoicesQuery.isLoading || (!!summary.customerId && billingNotesQuery.isLoading);
  const currentIndex = stepsLoading ? -1 : doneFlags.indexOf(false); // first not-yet-done step, or -1 if all done

  return (
    <div className="flex flex-col gap-2">
      <PipelineStep no={1} title="ใบแจ้งมัดจำ" status={step1.status} tone={step1.tone} current={currentIndex === 0} />
      <PipelineStep no={2} title="ใบแจ้งหนี้ส่วนที่เหลือ" status={step2.status} tone={step2.tone} current={currentIndex === 1} />
      <PipelineStep no={3} title="ใบวางบิล" status={step3.status} tone={step3.tone} current={currentIndex === 2} action={step3Action} />
      <PipelineStep no={4} title="รับชำระครบ" status={step4.status} tone={step4.tone} current={currentIndex === 3} />
    </div>
  );
}
