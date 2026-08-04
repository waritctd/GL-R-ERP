import {
  forwardRef, useEffect, useImperativeHandle, useMemo, useRef, useState,
} from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import {
  formatMoney,
  formatThaiDate,
  pricingRequestStatusLabel,
  quotationStatusLabel,
} from '../../utils/format.js';
import { downloadBlob } from '../../utils/download.js';
import {
  canConfirmOrder, canCreateCustomerQuotation,
  canManageCustomerQuotation, canRecordCustomerQuotationOutcome, canViewCustomerQuotation,
  isCustomerQuotationEditable, pricingRequestRecipientLabel,
} from '../pricingRequests/pricingRequestMeta.js';

function generateClientRequestId() {
  return crypto.randomUUID?.()
    ?? '00000000-0000-4000-8000-' + String(Date.now()).slice(-12).padStart(12, '0');
}

// The customer-quotation tail only ever exists once a PricingRequest reaches
// APPROVED_FOR_QUOTATION (canCreateCustomerQuotation's own gate) — earlier
// statuses have nothing to surface here, only on PricingRequestDetailPage.
// Among the requests that DO qualify, the most recently created one is the
// one this deal is currently working — ties broken by id (creation order).
const QUOTATION_TAIL_STATUSES = new Set(['APPROVED_FOR_QUOTATION', 'QUOTATION_ISSUED', 'QUOTATION_ACCEPTED']);

function pickRelevantPricingRequest(pricingRequests = []) {
  return pricingRequests
    .filter((pr) => QUOTATION_TAIL_STATUSES.has(pr.status))
    .sort((a, b) => b.id - a.id)[0] ?? null;
}

/**
 * "ราคาและใบเสนอราคา" (Phase 2 Slice S2 — see
 * docs/agent-handoffs/104_feat-deal-workspace-unification.md): pulls the
 * customer-facing tail of the PricingRequest/CustomerQuotation chain (issue /
 * outcome) plus the order-confirm + deposit-notice bridge onto the deal
 * workspace, so a rep no longer has to leave the deal page to move a priced
 * PCR to a signed order. The factory/costing/CEO-price steps that precede
 * this stay on PricingRequestDetailPage — see the "ดูรายละเอียดเต็ม" link
 * below — this panel only covers Steps 4-6 of that chain
 * (CustomerQuotationService / OrderConfirmationService), reusing the exact
 * predicates and hrApi methods PricingRequestDetailPage itself uses.
 *
 * Renders nothing when the deal has no pricing request at or past
 * APPROVED_FOR_QUOTATION yet (there is nothing customer-facing to show) —
 * the caller should keep showing PricingRequestPanel above this for the
 * earlier-stage view.
 *
 * Terminal action is confirm-order only (Phase 3 Slice S4 de-duplication —
 * see docs/agent-handoffs/105_feat-deal-deposit-fulfilment-unify.md): the
 * create-deposit-notice-from-quotation control that used to live here too
 * (a real, documented overlap flagged in Slice S3's handoff) now lives
 * solely in DealDepositPanel's own "ใบแจ้งยอดมัดจำ" step — see this
 * component's git history for the removed `createDepositNotice`
 * mutation/`depositPercentInput` state if that overlap ever needs undoing.
 *
 * Ticket-detail IA rebuild Phase 1 clutter follow-up round 2 (FIX 2): this
 * panel no longer renders its own "ออกใบเสนอราคา" / "ยืนยันคำสั่งซื้อ"
 * buttons — the sticky header's primary CTA (TicketDetailPage's
 * `workState`-derived ISSUE_QUOTATION/CONFIRM_ORDER action) owns both
 * outright now, and triggers them here via the forwardRef below (same
 * "parent triggers, panel stays the sole gate + mutation owner" convention
 * DealStagePanel/PricingRequestPanel already use for their own openers).
 * Before this, "ออกใบเสนอราคา" and "ยืนยันคำสั่งซื้อ" each rendered twice on
 * one page (the sticky bar's own scroll-to-here copy, plus this panel's own
 * button) — since the sticky bar is `sticky top-0`, both copies of each
 * label were visible on screen simultaneously.
 *
 * Handoff 117 follow-up ("A second, real bug found while making the specs
 * actually pass"): FIX 2 above made `openIssueQuotation` fire
 * `issueQuotation` straight through the ref, but `nextSalesAction`'s
 * ISSUE_QUOTATION bucket (salesActions.js) only checks
 * `pr.status === 'APPROVED_FOR_QUOTATION'` — it has no visibility into
 * whether a draft customer quotation exists yet, because that only comes
 * from THIS panel's own per-PR `listCustomerQuotations` fetch
 * (salesActions.js is deliberately built from the ticket-list row + PR queue
 * alone and documents that it never triggers a per-ticket detail fetch — see
 * its own module doc comment — so widening it to know "a draft exists" was
 * rejected as the fix). That mismatch meant the sticky "ออกใบเสนอราคา" button
 * appeared the instant CEO approved the price, but silently did nothing
 * until the rep separately clicked "สร้างร่างใบเสนอราคาลูกค้า" below to
 * create the draft `openIssueQuotation` requires.
 *
 * Fixed here (not in salesActions.js) by making `openIssueQuotation` smart:
 * when no draft exists yet but the PR/permissions genuinely support
 * creating one, it now creates the draft and issues it in one chained
 * mutation (`createAndIssueQuotation`) instead of no-op'ing. This is a
 * deliberate sales-workflow change (CLAUDE.md's "Sales flow redesign" —
 * authorised, not a side effect): issuing a quotation used to always take
 * two separate clicks (create, then issue) so a rep could review/discount
 * the draft first; now the sticky button can collapse that into one click
 * with default (no-discount) terms. The in-panel "สร้างร่างใบเสนอราคาลูกค้า"
 * button below is left in place specifically so a rep who wants to edit
 * discounts before issuing still has that path — see its updated copy. Every
 * branch that still can't do anything (query not yet settled, gates fail,
 * etc.) now shows a real `showToast?.('error', ...)` instead of doing
 * nothing — see `openIssueQuotation`'s own decision-tree comment below for
 * the exact branches.
 */
export const DealQuotationPanel = forwardRef(function DealQuotationPanel({ ticketId, pricingRequests = [], user, showToast }, ref) {
  const queryClient = useQueryClient();
  const [outcomeNote, setOutcomeNote] = useState('');
  const [downloadingFormat, setDownloadingFormat] = useState(null);

  const pr = useMemo(() => pickRelevantPricingRequest(pricingRequests), [pricingRequests]);
  const canView = pr != null && canViewCustomerQuotation(user, pr);

  const quotationsQuery = useQuery({
    queryKey: queryKeys.customerQuotations(pr?.id),
    queryFn: () => api.pricingRequests.listCustomerQuotations(pr.id).then((r) => r.items ?? []),
    enabled: canView,
  });
  const quotations = useMemo(
    () => [...(quotationsQuery.data ?? [])].sort((a, b) => a.quotationRevisionNo - b.quotationRevisionNo),
    [quotationsQuery.data],
  );
  const current = useMemo(
    () => quotations.find((q) => isCustomerQuotationEditable(q)) ?? [...quotations].reverse()[0] ?? null,
    [quotations],
  );

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: queryKeys.customerQuotations(pr?.id) });
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingRequestsByTicket(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingRequestDetail(pr?.id) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
  }

  const createQuotation = useMutation({
    mutationFn: () => api.pricingRequests.createCustomerQuotation(pr.id, { clientRequestId: generateClientRequestId() }),
    onSuccess: () => { showToast?.('success', 'สร้างร่างใบเสนอราคาลูกค้าแล้ว'); invalidate(); },
    onError: (err) => showToast?.('error', err.message || 'ดำเนินการไม่สำเร็จ'),
  });
  const issueQuotation = useMutation({
    mutationFn: (quotationId) => api.pricingRequests.issueCustomerQuotation(quotationId, { clientRequestId: generateClientRequestId() }),
    onSuccess: () => { showToast?.('success', 'ออกใบเสนอราคาลูกค้าแล้ว'); invalidate(); },
    onError: (err) => showToast?.('error', err.message || 'ดำเนินการไม่สำเร็จ'),
  });
  // Silent-no-op fix (handoff 117, "A second, real bug found while making the
  // specs actually pass"): salesActions.js's ISSUE_QUOTATION bucket fires the
  // sticky primary CTA off `pr.status === 'APPROVED_FOR_QUOTATION'` alone —
  // it has no way to know whether a draft customer quotation already exists,
  // because that only comes from this panel's own per-PR
  // `listCustomerQuotations` fetch (salesActions.js's own doc comment states
  // it never triggers a per-ticket detail fetch, so it can't be widened to
  // know this — see the handoff for why that option was rejected). So when
  // the sticky button is clicked before anyone has pressed "สร้างร่างใบเสนอ
  // ราคาลูกค้า" below, `current` is still null and the click used to do
  // nothing at all. This mutation closes that gap by creating the draft and
  // issuing it in one chained action when the PR/permissions genuinely
  // support it — see openIssueQuotation below for the full decision tree.
  const createAndIssueQuotation = useMutation({
    mutationFn: async () => {
      const created = await api.pricingRequests.createCustomerQuotation(pr.id, { clientRequestId: generateClientRequestId() });
      const draft = created?.quotation;
      if (draft?.id == null) throw new Error('สร้างร่างใบเสนอราคาไม่สำเร็จ');
      return api.pricingRequests.issueCustomerQuotation(draft.id, { clientRequestId: generateClientRequestId() });
    },
    onSuccess: () => { showToast?.('success', 'สร้างร่างและออกใบเสนอราคาลูกค้าแล้ว'); invalidate(); },
    onError: (err) => showToast?.('error', err.message || 'ดำเนินการไม่สำเร็จ'),
  });
  const recordOutcome = useMutation({
    mutationFn: (outcome) => api.pricingRequests.recordCustomerQuotationOutcome(current.id, {
      outcome, customerNote: outcomeNote || null, clientRequestId: generateClientRequestId(),
    }),
    onSuccess: () => { setOutcomeNote(''); showToast?.('success', 'บันทึกผลใบเสนอราคาแล้ว'); invalidate(); },
    onError: (err) => showToast?.('error', err.message || 'ดำเนินการไม่สำเร็จ'),
  });
  const confirmOrder = useMutation({
    mutationFn: () => api.pricingRequests.confirmOrder(pr.id, { clientRequestId: generateClientRequestId() }),
    onSuccess: () => { showToast?.('success', 'ยืนยันคำสั่งซื้อแล้ว'); invalidate(); },
    onError: (err) => showToast?.('error', err.message || 'ดำเนินการไม่สำเร็จ'),
  });

  // Defensive re-check before acting (same convention as DealStagePanel's
  // openAdvance/openEditStage/... and PricingRequestPanel's openCreate): a
  // stale or over-eager caller — the sticky header's primary CTA is derived
  // from a snapshot of `pricingRequests`/`summary` that could in principle
  // race a fresher render — cannot force either mutation past the real gate.
  // `current`/`pr` are computed above via useMemo and may be null (no
  // qualifying pricing request / no editable quotation yet); both guards
  // are null-safe through their own predicates.
  //
  // openIssueQuotation's decision tree (handoff 117 fix for the "silent
  // no-op" bug — see createAndIssueQuotation's own comment above for the
  // root cause):
  //   1. A mutation is already in flight → do nothing (a double-click must
  //      never fire two mutations, and must never create two drafts).
  //   2. An editable draft already exists → issue THAT draft (unchanged
  //      original behaviour — this is still the common path once the rep
  //      has used the in-panel "สร้างร่างใบเสนอราคาลูกค้า" button first).
  //   3. No draft yet, but we genuinely don't know that for sure —
  //      `quotationsQuery` hasn't resolved (it's `enabled: canView`, so also
  //      gate on `canView` or a not-yet-enabled query reads as "not
  //      success" forever). FIX 3 (Opus review — cross-tab dead end): queue
  //      a silent retry instead of telling the rep to click again — see the
  //      effect below.
  //   4. No draft, the query has genuinely settled empty, and the PR/user
  //      still pass the create gate → create the draft and issue it in one
  //      chained mutation. This is the actual bug fix: the sticky button
  //      now always does SOMETHING when nextSalesAction says ISSUE_QUOTATION.
  //   5. None of the above → the click cannot do anything (e.g. the only
  //      quotation is already ISSUED/ACCEPTED/etc). Never fail silently —
  //      tell the rep where to look.
  //
  // FIX 3 (Opus review, cross-tab `issue_quotation` first-click dead end):
  // TicketDetailPage's `runOnTab('quotations', () =>
  // dealQuotationPanelRef.current?.openIssueQuotation())` switches to this
  // tab and calls this ref in the SAME commit the tab mounts — this panel's
  // own `quotationsQuery` has only just started fetching, so on a rep's
  // FIRST click from a different tab (e.g. ภาพรวม), branch 3 above used to
  // fire unconditionally: the rep saw "กำลังโหลดข้อมูลใบเสนอราคา — กรุณาลองอีกครั้ง"
  // and nothing happened, even though the very next tick would have had
  // everything needed to issue for real. A second click (now with a warm,
  // already-`isSuccess` query) always "fixed" it, which is exactly what made
  // this easy to miss — it only reproduces on a session's first visit to
  // this deal. Fixed WITHOUT pre-mounting every panel (that reinstates the
  // 4,000px page this rebuild retires): `attemptIssueQuotation` is factored
  // out so it can run twice — once immediately (branch 3 now just queues a
  // retry instead of toasting) and once more, automatically, the moment
  // `quotationsQuery` actually settles (the effect below, keyed on
  // `quotationsQuery.isSuccess`). No polling, no fixed delay — the retry is
  // driven by the query's own real state transition.
  const pendingIssueIntentRef = useRef(false);

  function attemptIssueQuotation() {
    if (issueQuotation.isPending || createQuotation.isPending || createAndIssueQuotation.isPending) return;
    if (current && isCustomerQuotationEditable(current) && canManageCustomerQuotation(user, pr)) {
      pendingIssueIntentRef.current = false;
      issueQuotation.mutate(current.id);
    } else if (!current && canView && !quotationsQuery.isSuccess) {
      // Not settled yet — queue the retry (see the effect below) instead of
      // telling the rep to click again; this is the exact race FIX 3 closes.
      pendingIssueIntentRef.current = true;
    } else if (!current && quotationsQuery.isSuccess && canCreateCustomerQuotation(user, pr)) {
      pendingIssueIntentRef.current = false;
      createAndIssueQuotation.mutate();
    } else {
      pendingIssueIntentRef.current = false;
      showToast?.('error', 'ยังออกใบเสนอราคาไม่ได้ — ตรวจสอบสถานะคำขอราคาในส่วน "ราคาและใบเสนอราคา" ด้านล่าง');
    }
  }

  // Fires the queued retry exactly once, the moment `quotationsQuery`
  // actually resolves — `attemptIssueQuotation` re-reads `current`/
  // `quotationsQuery.isSuccess` fresh (closures over this render's state),
  // both already updated by the time this effect runs (the query settling
  // is what triggered this very re-render). Re-entrant/idempotent by
  // construction (the in-flight guard at the top of `attemptIssueQuotation`),
  // so calling it again here is safe even if something else already resolved
  // the intent in between.
  useEffect(() => {
    if (pendingIssueIntentRef.current && quotationsQuery.isSuccess) {
      attemptIssueQuotation();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [quotationsQuery.isSuccess]);

  useImperativeHandle(ref, () => ({
    openIssueQuotation: attemptIssueQuotation,
    openConfirmOrder: () => {
      if (confirmOrder.isPending) return;
      if (pr && canConfirmOrder(user, pr)) {
        confirmOrder.mutate();
      } else {
        showToast?.('error', 'ยังยืนยันคำสั่งซื้อไม่ได้ — ตรวจสอบสถานะคำขอราคาในส่วน "ราคาและใบเสนอราคา" ด้านล่าง');
      }
    },
  }));

  async function handleDownload(format) {
    setDownloadingFormat(format);
    try {
      const blob = format === 'pdf'
        ? await api.pricingRequests.downloadCustomerQuotationPdf(current.id)
        : await api.pricingRequests.downloadCustomerQuotationXlsx(current.id);
      downloadBlob(blob, current.number ?? 'customer-quotation', format);
    } catch (err) {
      showToast?.('error', err.message || 'ดาวน์โหลดไม่สำเร็จ');
    } finally {
      setDownloadingFormat(null);
    }
  }

  if (!pr || !canView) return null;

  const status = pricingRequestStatusLabel(pr.status);

  return (
    <Panel flush data-testid="deal-quotation-panel">
      {/* Explicit Panel.Header, not title/actions: this header's gap is 8px,
          not Panel's default 14px, and title/actions has no way to pass a
          className through to the header row it builds. */}
      <Panel.Header bordered className="gap-2">
        <h2 className="m-0 min-w-0 text-lg break-words">ราคาและใบเสนอราคา</h2>
        <div className="flex items-center gap-2">
          <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
          <Link to={`/pricing-requests/${pr.id}`} className="text-xs font-bold text-link">
            ดูรายละเอียดเต็ม (ราคาโรงงาน/ต้นทุน/CEO) →
          </Link>
        </div>
      </Panel.Header>

      <div className="flex flex-col gap-3 p-4">
        <div className="flex flex-wrap items-center gap-2 text-xs text-text-muted">
          <span>{pr.requestCode}</span>
          <span>·</span>
          <span>{pricingRequestRecipientLabel(pr.recipientType)}{pr.recipientLabel ? ` · ${pr.recipientLabel}` : ''}</span>
        </div>

        {quotationsQuery.isLoading ? (
          <p className="text-sm text-text-muted">กำลังโหลดใบเสนอราคาลูกค้า…</p>
        ) : !current ? (
          canCreateCustomerQuotation(user, pr) ? (
            <div className="flex flex-col gap-2 rounded-md border border-border bg-surface p-3">
              <p className="text-sm text-text-muted">
                CEO อนุมัติราคาขายแล้ว — สร้างร่างใบเสนอราคาให้ลูกค้าได้เลย
                หรือกดปุ่ม “ออกใบเสนอราคา” บนแถบด้านบนของหน้าเพื่อสร้างร่างและออกใบเสนอราคาในขั้นตอนเดียว
                (ใช้ปุ่มด้านล่างนี้แทนหากต้องการแก้ไขส่วนลด/รายละเอียดก่อนออกจริง)
              </p>
              <Button type="button" variant="primary" className="self-start" disabled={createQuotation.isPending}
                onClick={() => createQuotation.mutate()} data-testid="deal-quotation-create">
                <Icon name="fileText" size={14} />
                สร้างร่างใบเสนอราคาลูกค้า
              </Button>
            </div>
          ) : (
            <p className="text-sm text-text-muted">ยังไม่มีใบเสนอราคาลูกค้าสำหรับคำขอราคานี้</p>
          )
        ) : (
          <div className="flex flex-col gap-3 rounded-md border border-border bg-surface p-3">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-mono text-sm font-bold">{current.number ?? `ครั้งที่ ${current.quotationRevisionNo}`}</span>
              <StatusBadge tone={
                ['ISSUED', 'SENT', 'ACCEPTED'].includes(current.docStatus) ? 'success'
                  : ['REJECTED', 'CANCELLED', 'EXPIRED'].includes(current.docStatus) ? 'danger'
                    : current.docStatus === 'REVISION_REQUESTED' ? 'warning' : 'neutral'
              }>
                {quotationStatusLabel(current.docStatus).label}
              </StatusBadge>
              <span className="text-sm text-text-muted">รวมทั้งสิ้น {formatMoney(current.grandTotal)}</span>
              {current.validityDate ? (
                <span className="text-xs text-text-muted">ยืนราคาถึง {formatThaiDate(current.validityDate)}</span>
              ) : null}
            </div>

            <div className="flex flex-wrap gap-2">
              <Button type="button" variant="secondary" disabled={downloadingFormat === 'pdf'} onClick={() => handleDownload('pdf')}>
                <Icon name="fileText" size={12} /> {downloadingFormat === 'pdf' ? 'กำลังดาวน์โหลด…' : 'PDF'}
              </Button>
              <Button type="button" variant="secondary" disabled={downloadingFormat === 'xlsx'} onClick={() => handleDownload('xlsx')}>
                <Icon name="fileText" size={12} /> {downloadingFormat === 'xlsx' ? 'กำลังดาวน์โหลด…' : 'Excel'}
              </Button>
              {isCustomerQuotationEditable(current) && canManageCustomerQuotation(user, pr) ? (
                // Not a <Button>: react-router's <Link> renders an <a> for
                // client-side navigation, which Button (a <button>) can't do.
                <Link to={`/pricing-requests/${pr.id}`} className="secondary-button">
                  แก้ไขรายละเอียด/ส่วนลด →
                </Link>
              ) : null}
            </div>

            {isCustomerQuotationEditable(current) && canManageCustomerQuotation(user, pr) ? (
              // The "ออกใบเสนอราคา" button itself lives solely on the sticky
              // header now (FIX 2, see this component's own doc comment) —
              // this explains the section instead of duplicating the CTA.
              <p className="text-xs text-text-muted">
                พร้อมออกใบเสนอราคาแล้ว — กดปุ่ม “ออกใบเสนอราคา” บนแถบด้านบนของหน้า
                หรือแก้ไขรายละเอียด/ส่วนลดก่อนได้ที่ลิงก์ด้านบน
              </p>
            ) : null}

            {canRecordCustomerQuotationOutcome(user, pr, current) ? (
              <div className="flex flex-col gap-2 border-t border-border-subtle pt-3">
                <strong className="text-sm">บันทึกผลจากลูกค้า</strong>
                <textarea
                  className="rounded border border-border p-2 text-sm"
                  rows={2}
                  placeholder="หมายเหตุจากลูกค้า (ถ้ามี)"
                  value={outcomeNote}
                  onChange={(e) => setOutcomeNote(e.target.value)}
                />
                <div className="flex flex-wrap gap-2">
                  <Button type="button" variant="primary" disabled={recordOutcome.isPending}
                    onClick={() => recordOutcome.mutate('ACCEPTED')} data-testid="deal-quotation-accept">
                    ลูกค้ายอมรับ
                  </Button>
                  <Button type="button" variant="secondary" style={{ color: 'var(--color-danger)', borderColor: 'var(--color-danger-border)' }}
                    disabled={recordOutcome.isPending} onClick={() => recordOutcome.mutate('REJECTED')} data-testid="deal-quotation-reject">
                    ลูกค้าปฏิเสธ
                  </Button>
                  <Button type="button" variant="secondary" disabled={recordOutcome.isPending}
                    onClick={() => recordOutcome.mutate('REVISION_REQUESTED')} data-testid="deal-quotation-revision">
                    ลูกค้าขอแก้ไข
                  </Button>
                </div>
              </div>
            ) : null}

            {['ACCEPTED', 'REJECTED', 'REVISION_REQUESTED', 'EXPIRED', 'SUPERSEDED'].includes(current.docStatus) ? (
              <p className="text-xs text-text-muted">
                ผลใบเสนอราคา: <strong>{quotationStatusLabel(current.docStatus).label}</strong>
                {current.outcomeNote ? ` — ${current.outcomeNote}` : ''}
              </p>
            ) : null}
          </div>
        )}

        {pr.status === 'QUOTATION_ACCEPTED' ? (
          <div className="flex flex-col gap-2 rounded-md border border-border bg-surface p-3">
            <strong className="text-sm">ยืนยันคำสั่งซื้อ</strong>
            {canConfirmOrder(user, pr) ? (
              // The "ยืนยันคำสั่งซื้อ" button itself lives solely on the
              // sticky header now (FIX 2, see this component's own doc
              // comment) — this explains the section instead of duplicating
              // the CTA.
              <p className="text-sm text-text-muted">
                ลูกค้ายอมรับใบเสนอราคาแล้ว — กดปุ่ม “ยืนยันคำสั่งซื้อ” บนแถบด้านบนของหน้าเพื่อเริ่มขั้นตอนรับมัดจำและนำเข้าสินค้า
              </p>
            ) : (
              <p className="text-sm text-text-muted">
                {pr.orderConfirmedAt
                  // Deposit-notice creation moved to DealDepositPanel's own
                  // "ใบแจ้งยอดมัดจำ" step (Phase 3 Slice S4 de-duplication —
                  // see docs/agent-handoffs/105_feat-deal-deposit-fulfilment-unify.md)
                  // — pointed at here rather than duplicated.
                  ? `ยืนยันคำสั่งซื้อแล้วเมื่อ ${formatThaiDate(pr.orderConfirmedAt)} — ออกใบแจ้งยอดเงินรับมัดจำได้ที่ส่วน "มัดจำ" ด้านล่าง`
                  : 'ยืนยันคำสั่งซื้อได้เฉพาะเจ้าของดีล (sales)'}
              </p>
            )}
          </div>
        ) : null}
      </div>
    </Panel>
  );
});
