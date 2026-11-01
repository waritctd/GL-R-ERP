import { useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { api, ROLE_PERMISSIONS } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { CompactStatRow } from '../../components/common/CompactStatRow.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { FormGrid, formGridSpan2, Panel } from '../../components/common/Layout.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import { InvoiceFromDealForm } from './InvoiceFromDealForm.jsx';
import { emptyInvoiceForm, prepareInvoiceAttachment, toCreateFromDealPayload } from './invoiceFromDeal.js';
import { commissionStatusLabel as statusInfo, dealStageLabel, formatMoney, formatThaiMonthYearFromMonthInputValue } from '../../utils/format.js';
import { ApprovalBoard } from './ApprovalBoard.jsx';
import { CommissionStatement } from './CommissionStatement.jsx';
import { MANUAL_KIND_LABELS, MANUAL_KINDS, isManualKind, monthOf } from './commissionRecord.js';
import { ReceiptLedger } from './ReceiptLedger.jsx';
import { WeightSegmented } from './WeightSegmented.jsx';

/* Hallmark · redesign · system: design.md (Operations Control Desk) · pre-emit critique: P4 H4 E3 S4 R4 V3 (V: design.md locks the system, so variety is deliberately low; E3: the stock-line segmented states are proven by tests, but only the locked import-line state was seen in a browser) */
const thisMonth = new Date().toISOString().slice(0, 7);

// The sales_manager / ceo page opens on the current PAYROLL month in Bangkok time (UTC+7, no DST).
// `thisMonth` above is a UTC slice: between 00:00 and 07:00 on the 1st it is still last month.
const bangkokMonth = () => new Date(Date.now() + 7 * 3600 * 1000).toISOString().slice(0, 7);
const APPROVAL_STATUSES = ['SUBMITTED', 'MANAGER_APPROVED', 'APPROVED'];

const emptyCreateForm = emptyInvoiceForm();

const emptyDeductionDraft = {
  grossAmount: 0,
  bankFees: 0,
  suspenseVat: 0,
  transportFee: 0,
  cutFee: 0,
  shortfall: 0,
  withholdingTax: 0,
  overpayment: 0,
  weightMultiplier: 1,
  reason: '',
};

const emptyManualForm = {
  salesRepId: '',
  kind: 'ADJUSTMENT',
  amount: '',
  payrollMonth: '',
  reason: '',
};

// Debounces `value` by `delayMs` -- mirrors OvertimePanel.jsx/LeaveRequestPage.jsx's identically
// named private helper (same reason: don't fire one request per keystroke). Kept as its own small
// local copy rather than a new shared hook module for a four-line function.
function useDebouncedValue(value, delayMs) {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}

// The subset of a ticket summary the create-from-deal form reads, taken from FinanceDealDto.
function summaryFromFinanceDeal(deal) {
  if (!deal) return null;
  return {
    id: deal.id,
    code: deal.code,
    customerName: deal.customerName,
    salesStage: deal.salesStage,
    amountPayable: deal.money?.amountPayable ?? null,
    // Pre-VAT payable: the invoice form's default ยอดรวม (older payloads lack it -> amountPayable).
    amountPayableExVat: deal.money?.amountPayableExVat ?? null,
  };
}

export function CommissionPage({ user, showToast }) {
  const [searchParams, setSearchParams] = useSearchParams();
  const queryClient = useQueryClient();
  const [month, setMonth] = useState(() => (ROLE_PERMISSIONS.canApproveCommissions.includes(user.role) ? bangkokMonth() : thisMonth));
  const [records, setRecords] = useState([]);
  const [summary, setSummary] = useState(null);
  // Sales-only monthly tier summary — server-computed (CommissionService#monthlySummary), fetched
  // alongside `records` by the same load()/month effect below. Never re-derived client-side; a
  // load failure leaves this null and the panel simply doesn't render (see load()).
  const [monthlyTierSummary, setMonthlyTierSummary] = useState(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  // sales_manager/ceo queue: the caller's own-stage sale records across ALL payroll months
  // (GET /api/commissions/pending-approval: manager -> SUBMITTED, ceo -> MANAGER_APPROVED); the board
  // filters it to the shown month client-side.
  const [pending, setPending] = useState(null); // null = not loaded yet

  // sales_manager / ceo "สรุปรายคน": every rep's monthly summary for the shown month (the estimated
  // commission column) and the rep whose full statement is open. The request counter drops a slow
  // response for a previous month.
  const [pickedRepId, setPickedRepId] = useState('');
  const [repSummaries, setRepSummaries] = useState({});
  const repSummaryRequestId = useRef(0);

  // account: "record invoice" / create-from-deal flow
  const [ticketIdInput, setTicketIdInput] = useState(searchParams.get('ticketId') || '');
  const [ticketLookupLoading, setTicketLookupLoading] = useState(false);
  const [ticketLookupError, setTicketLookupError] = useState('');
  const [loadedTicket, setLoadedTicket] = useState(null);
  const [eligibleTickets, setEligibleTickets] = useState([]);
  const [createForm, setCreateForm] = useState(emptyCreateForm);
  const [fileInputKey, setFileInputKey] = useState(0);
  const [recentlyCreated, setRecentlyCreated] = useState([]);

  // sales_manager/ceo: review-queue inline input edit
  const [editingId, setEditingId] = useState(null);
  const [deductionDraft, setDeductionDraft] = useState(emptyDeductionDraft);
  // Live preview of the edited invoice's recompute — server-computed (CommissionService#simulate)
  // rather than client math; see the debounced effect below. Seeded from the record's own
  // last-persisted figures on beginEdit (no math, just a field read) so the panel shows a sensible
  // value immediately instead of stale zeros.
  const [deductionPreview, setDeductionPreview] = useState({ actualReceived: 0, commissionableBase: 0 });
  const deductionPreviewRequestId = useRef(0);

  const [clawbackId, setClawbackId] = useState(null); // record id pending clawback reason, or null
  const [rejectId, setRejectId] = useState(null);
  const [approveId, setApproveId] = useState(null); // record id pending approve confirmation, or null

  // Manual commission entries (feat/commission-manual-adjustments): sales_manager/ceo only.
  const [showManualForm, setShowManualForm] = useState(false);
  const [manualForm, setManualForm] = useState(emptyManualForm);
  const [manualSaving, setManualSaving] = useState(false);
  const [repOptions, setRepOptions] = useState([]);

  // Sales is read-only (Slice A3, AUTHZ CHANGE): commission creation moved entirely to the
  // accountant's createFromDeal trigger. Mirrors ROLE_PERMISSIONS exactly — see api/routes.js
  // for the "why" comments tying each key back to a CommissionController/Service role gate.
  // Issue #422 B5 fix: every mutation below that changes a payroll-visible figure
  // (line.commissionPay) must also invalidate the ['payroll'] prefix so an open PayrollPage tab
  // picks the change up on its own next poll/focus refetch (issue #422 B1), not only on a manual
  // reload. CommissionPage itself stays imperative (`load()`) -- a full react-query migration
  // here is out of scope for this fix; invalidation alone is sufficient now that payroll is a
  // query. payrollCurrent is ['payroll', 'current', month], so ['payroll'] is the correct prefix
  // and no other queryKeys.js entry starts with 'payroll'.
  function invalidatePayrollUpstream() {
    return queryClient.invalidateQueries({ queryKey: ['payroll'] });
  }

  const canReview = ROLE_PERMISSIONS.canApproveCommissions.includes(user.role); // sales_manager, ceo
  const canListRecords = ROLE_PERMISSIONS.canListCommissionRecords.includes(user.role); // sales, sales_manager, ceo
  const canCreateFromDeal = ROLE_PERMISSIONS.canCreateCommissionFromDeal.includes(user.role); // account
  const canCreateManual = ROLE_PERMISSIONS.canCreateManualCommission.includes(user.role); // sales_manager, ceo
  const payrollOnly = ROLE_PERMISSIONS.canViewPayrollCommissions.includes(user.role); // hr
  const isSales = user.role === 'sales';
  const loadRequestId = useRef(0);
  const viewParam = searchParams.get('view');
  async function loadPending({ jumpToEarliest = false } = {}) {
    try {
      const response = await api.commissions.pendingApproval();
      const entries = response.commissions ?? [];
      setPending(entries);
      // The notification link (?view=pending) opens on the EARLIEST round that holds her own
      // pending items (carry-over first, else current, else upcoming); none -> the current round.
      if (jumpToEarliest) {
        const months = entries.map((entry) => monthOf(entry.commission)).filter(Boolean).sort();
        if (months.length) setMonth(months[0]);
      }
    } catch (error) {
      showToast('error', error.message || 'โหลดรายการรออนุมัติไม่สำเร็จ');
    }
  }

  useEffect(() => {
    if (!canReview || viewParam === 'pending') return; // the effect below loads it instead
    loadPending();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- fetch once on mount; showToast is stable enough.
  }, [canReview]);

  // ?view=pending is acted on whenever it APPEARS, not only on mount: the notification bell navigates
  // in-app, often while this page is already open. The param is then dropped so the next click on a
  // notification changes the URL again and jumps again.
  useEffect(() => {
    if (!canReview || viewParam !== 'pending') return;
    loadPending({ jumpToEarliest: true });
    const params = new URLSearchParams(searchParams);
    params.delete('view');
    setSearchParams(params, { replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- keyed on the param itself.
  }, [canReview, viewParam]);

  // WeightLines owns the optimistic state; this only performs the write and keeps payroll fresh. The
  // server recomputes effectiveWeight / weightedCommissionableBase / estimatedCommission and the
  // entry is replaced with its answer, never patched locally.
  async function adjustWeights(recordId, payload) {
    const response = await api.commissions.adjustItemWeights(recordId, payload);
    invalidatePayrollUpstream();
    return response;
  }

  // A weight change moves the month's base: reload the records table and the picked rep's statement
  // so "สรุปรายคน" and the table are not left showing the old figures.
  function reloadAfterWeightChange(recordId) {
    load();
    refreshRep(recordId);
  }

  function replacePendingEntry(updated) {
    setPending((current) => (current ?? []).map((entry) => (entry.commission.id === updated.commission.id ? updated : entry)));
  }

  async function load() {
    if (payrollOnly) {
      setLoading(true);
      try {
        const response = await api.commissions.payrollReady({ payrollMonth: month });
        setSummary(response.summary);
      } catch (error) {
        showToast('error', error.message || 'โหลดข้อมูลค่าคอมไม่สำเร็จ');
      } finally {
        setLoading(false);
      }
      return;
    }
    if (canListRecords) {
      setLoading(true);
      // A slower response for an earlier month must not overwrite the newer month's rows.
      const requestId = (loadRequestId.current += 1);
      try {
        const response = await api.commissions.list({ payrollMonth: month });
        if (loadRequestId.current === requestId) setRecords(response.commissions ?? []);
      } catch (error) {
        if (loadRequestId.current === requestId) showToast('error', error.message || 'โหลดข้อมูลค่าคอมไม่สำเร็จ');
      } finally {
        if (loadRequestId.current === requestId) setLoading(false);
      }
      if (isSales) {
        // Server-computed monthly tier summary (CommissionService#monthlySummary) — never
        // re-derived client-side. A failure here must not take down the records table above: it
        // is a secondary, informational panel, so on error the panel just stays hidden.
        try {
          const summaryResponse = await api.commissions.monthlySummary({ payrollMonth: month });
          setMonthlyTierSummary(summaryResponse.summary);
        } catch {
          setMonthlyTierSummary(null);
        }
      }
      return;
    }
    // account: GET /api/commissions has no route for this role on the real backend (see
    // routes.js's canListCommissionRecords comment) — there is nothing to load here.
    setLoading(false);
  }

  // `load` reads `user`-derived role flags that don't change while this page is mounted, so it
  // is intentionally omitted from the dependency array (re-running per render would be a no-op).
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { load(); }, [month]);

  // "สรุปรายคน": fetch every listed rep's summary when the month or the SET of reps changes; a
  // change to one record (approve / reject / deductions / weights) refetches only that record's rep.
  const repIdsKey = [...new Set(records.filter((record) => APPROVAL_STATUSES.includes(record.status)).map((record) => record.salesRepId))].sort((a, b) => a - b).join(',');
  const monthRef = useRef(month);
  monthRef.current = month;
  useEffect(() => { setRepSummaries({}); }, [month]); // nothing from the previous month while the new one loads
  useEffect(() => {
    if (!canReview) return;
    const requestId = (repSummaryRequestId.current += 1);
    Promise.all((repIdsKey ? repIdsKey.split(',') : []).map(Number).map((salesRepId) => api.commissions.monthlySummary({ payrollMonth: month, salesRepId })
      .then((response) => [salesRepId, response.summary ?? null])
      .catch(() => [salesRepId, null])))
      .then((entries) => {
        if (repSummaryRequestId.current === requestId) setRepSummaries(Object.fromEntries(entries.filter(([, summary]) => summary)));
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- canReview is fixed for the page's lifetime.
  }, [repIdsKey, month]);

  function refreshRep(recordId) {
    const salesRepId = findRecord(recordId)?.salesRepId;
    if (!canReview || salesRepId == null) return;
    const forMonth = month;
    api.commissions.monthlySummary({ payrollMonth: forMonth, salesRepId })
      .then((response) => {
        if (monthRef.current === forMonth && response.summary) setRepSummaries((current) => ({ ...current, [salesRepId]: response.summary }));
      })
      .catch(() => {});
  }

  // Best-effort convenience picker for account: the deals TicketRepository's account-role list
  // scoping surfaces are money-PENDING deals, so a CLOSED_PAID deal (already fully paid) often
  // will not appear here — see createFromDeal's NOTE above the mock implementation. Manual
  // ticket-id lookup (below) always works regardless; this list is a nice-to-have on top.
  //
  // Also drops any ticket whose commissionRecorded is already true (TicketSummaryDto
  // .commissionRecorded, issue #736): a deal that already has a live commission is not actually
  // "eligible" any more, and this mirrors the same flag accountActions.js#nextAccountAction now
  // checks for its worklist CTA, so the picker and the worklist can't disagree about which
  // CLOSED_PAID deals still need one recorded.
  useEffect(() => {
    if (!canCreateFromDeal) return;
    let cancelled = false;
    api.tickets.list({ salesStage: 'CLOSED_PAID' })
      .then((response) => {
        if (cancelled) return;
        setEligibleTickets((response.tickets ?? []).filter((ticket) => !ticket.commissionRecorded));
      })
      .catch(() => { if (!cancelled) setEligibleTickets([]); });
    return () => { cancelled = true; };
  }, [canCreateFromDeal]);

  // Deep-link support: a future "record invoice" call-to-action elsewhere (e.g. the ticket
  // detail page, once it grows one) can send account straight here via /commissions?ticketId=NN.
  useEffect(() => {
    if (!canCreateFromDeal) return;
    const paramTicketId = searchParams.get('ticketId');
    if (paramTicketId) lookupTicket(paramTicketId);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- run once on mount, driven by the URL.
  }, [canCreateFromDeal]);

  // Manual commission entry (feat/commission-manual-adjustments, issue #737): sales_manager/ceo
  // has no /api/employees access (that's hr-only, see ROLE_PERMISSIONS.canViewEmployees), so the
  // rep picker is served by a purpose-built read endpoint, GET /api/commissions/reps
  // (CommissionController#reps), gated to the same sales_manager/ceo roles as this form.
  //
  // Owner ruling 2026-08-14 (2nd ruling on this endpoint, after per-caller division scoping):
  // ceo and sales_manager get the IDENTICAL list -- every ACTIVE employee in ฝ่ายขาย, i.e. the
  // people who actually earn commission (CommissionService#listManualCommissionRepOptions).
  // Division MEMBERSHIP, not "has a commission record" -- a brand-new rep, or a manager never
  // yet paid one, still appears. Still narrower than what POST /api/commissions/manual itself
  // accepts (that write path's only real restriction is still the sales_rep_id foreign key --
  // ANY hr.employee id, any division): the numeric Employee ID field below is how to reach
  // someone the picker excludes on purpose (inactive, or outside ฝ่ายขาย entirely) -- see
  // ManualCommissionForm's picker label, which says so rather than leaving the limit silent.
  useEffect(() => {
    if (!canCreateManual) return;
    let cancelled = false;
    api.commissions.reps().then((response) => {
      if (cancelled) return;
      // Do NOT re-sort here. `reps` already arrives ordered by the backend's own ORDER BY
      // (display name, then id -- CommissionRepository#findActiveSalesRepOptions); a second,
      // client-side sort could silently disagree with it (e.g. a different locale collation)
      // rather than being the harmless no-op it looks like.
      setRepOptions(response.reps ?? []);
    }).catch(() => { if (!cancelled) setRepOptions([]); });
    return () => { cancelled = true; };
  }, [canCreateManual]);

  // A record by id from the month's records, or from the queue (same record, any round).
  function findRecord(id) {
    return records.find((record) => record.id === id) ?? (pending ?? []).map((entry) => entry.commission).find((record) => record.id === id);
  }

  function canCeoReview(record) {
    return record.status === 'MANAGER_APPROVED' && user.role === 'ceo';
  }

  function updateCreateForm(field, value) {
    setCreateForm((current) => ({ ...current, [field]: value }));
  }

  // Ticket-detail lookup is unaffected by the account-role list scoping (see the NOTE above
  // mockApi's createFromDeal) — this is the flow's real primary path, not a fallback.
  async function lookupTicket(rawTicketId) {
    const ticketId = Number(rawTicketId);
    if (!ticketId || Number.isNaN(ticketId)) {
      setTicketLookupError('กรุณาระบุเลขที่ Ticket ID ที่ถูกต้อง');
      setLoadedTicket(null);
      return;
    }
    setTicketLookupLoading(true);
    setTicketLookupError('');
    try {
      // H1 lockdown: account is refused on api.tickets.get and reads the deal through the finance
      // view (api.finance.getDeal, FinanceDealDto). Only the fields this form uses are mapped —
      // id, code, customerName, salesStage and the amount, which lives under `money` there. Every
      // other role keeps the ticket read unchanged.
      const summary = user.role === 'account'
        ? summaryFromFinanceDeal((await api.finance.getDeal(ticketId)).deal)
        : (await api.tickets.get(ticketId)).ticket?.summary;
      if (!summary) {
        setLoadedTicket(null);
        setTicketLookupError('ไม่พบดีลนี้');
        return;
      }
      if (summary.salesStage !== 'CLOSED_PAID') {
        setLoadedTicket(null);
        setTicketLookupError(`ดีลนี้ยังไม่ถึงขั้นตอนปิดงาน/รับเงินครบ (สถานะปัจจุบัน: ${dealStageLabel(summary.salesStage).label}) จึงยังบันทึกค่าคอมไม่ได้`);
        return;
      }
      setLoadedTicket(summary);
      const defaultGross = summary.amountPayable;
      updateCreateForm('grossAmount', defaultGross != null ? String(defaultGross) : '');
    } catch (error) {
      setLoadedTicket(null);
      setTicketLookupError(error.message || 'ไม่พบดีลนี้');
    } finally {
      setTicketLookupLoading(false);
    }
  }

  function selectEligibleTicket(ticketId) {
    setTicketIdInput(ticketId);
    if (ticketId) lookupTicket(ticketId);
    else setLoadedTicket(null);
  }

  async function submitFromDeal(event) {
    event.preventDefault();
    if (!loadedTicket) {
      showToast('error', 'กรุณาโหลดข้อมูลดีลที่ปิดงานแล้วก่อนบันทึก');
      return;
    }
    setSaving(true);
    try {
      const invoiceAttachment = await prepareInvoiceAttachment(createForm.invoiceAttachment);
      const response = await api.commissions.createFromDeal(toCreateFromDealPayload(loadedTicket.id, createForm, invoiceAttachment));
      setRecentlyCreated((current) => [response.commission, ...current]);
      // createFromDeal is not commission-only: it dual-writes the tax invoice as an
      // AttachType.INVOICE attachment on the ticket, which flips that ticket's
      // invoiceOnFile and so unlocks CONFIRM_CLOSE. Without this the ticket's cached
      // detail/actions stay stale for staleTime (30s, api/queryClient.js), so account
      // returns to the deal — usually straight from this page — and still sees
      // "บันทึกใบกำกับ" instead of "ยืนยันพร้อมปิดงาน".
      //
      // Invalidated by the ['tickets'] PREFIX rather than the exact
      // queryKeys.ticketDetail(id)/ticketActions(id)/ticketAttachments(id) triple, on
      // purpose: TicketDetailPage builds those keys from useParams().id, a STRING,
      // while loadedTicket.id here is a NUMBER — and TanStack compares keys
      // structurally, so ['tickets','detail',16] would not match
      // ['tickets','detail','16'] and the invalidation would silently no-op. The
      // prefix also correctly refreshes the ticket lists, whose stage/scope changed.
      queryClient.invalidateQueries({ queryKey: ['tickets'] });
      // Account reads the deal through the finance view (['finance','deal',id]); its
      // commissionRecorded flag drives the "บันทึกใบกำกับ" CTA, so it must go stale too.
      queryClient.invalidateQueries({ queryKey: ['finance'] });
      invalidatePayrollUpstream();
      setCreateForm(emptyCreateForm);
      setFileInputKey((key) => key + 1);
      setLoadedTicket(null);
      setTicketIdInput('');
      showToast('success', 'บันทึกใบกำกับและสร้างคำขอค่าคอมแล้ว');
    } catch (error) {
      showToast('error', error.message || 'บันทึกไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  function updateManualForm(field, value) {
    setManualForm((current) => ({ ...current, [field]: value }));
  }

  function openManualForm() {
    setManualForm({ ...emptyManualForm, payrollMonth: month });
    setShowManualForm(true);
  }

  // Manual commission entries (feat/commission-manual-adjustments): NO auto-computation anywhere
  // here — every amount is hand-typed by sales_manager/ceo, submitted verbatim to
  // POST /api/commissions/manual. (A future CEO-confirmed auto-config may prefill a suggested
  // amount for specific kinds, e.g. STOCK_BONUS — not implemented; owner decision is manual
  // across the UI for now.)
  async function submitManual(event) {
    event.preventDefault();
    if (!manualForm.salesRepId) {
      showToast('error', 'กรุณาระบุพนักงานขาย');
      return;
    }
    if (manualForm.amount === '' || Number.isNaN(Number(manualForm.amount))) {
      showToast('error', 'กรุณาระบุจำนวนเงิน');
      return;
    }
    if (manualForm.kind !== 'ADJUSTMENT' && Number(manualForm.amount) < 0) {
      showToast('error', 'จำนวนเงินสำหรับประเภทนี้ต้องไม่ติดลบ');
      return;
    }
    if (!manualForm.reason.trim()) {
      showToast('error', 'กรุณาระบุเหตุผล');
      return;
    }
    setManualSaving(true);
    try {
      await api.commissions.createManualCommission({
        salesRepId: Number(manualForm.salesRepId),
        kind: manualForm.kind,
        amount: Number(manualForm.amount),
        reason: manualForm.reason.trim(),
        payrollMonth: manualForm.payrollMonth ? `${manualForm.payrollMonth}-01` : undefined,
      });
      showToast('success', 'เพิ่มค่าคอมด้วยตนเองแล้ว');
      setManualForm(emptyManualForm);
      setShowManualForm(false);
      invalidatePayrollUpstream();
      await load();
    } catch (error) {
      showToast('error', error.message || 'เพิ่มค่าคอมไม่สำเร็จ');
    } finally {
      setManualSaving(false);
    }
  }

  function beginEdit(record) {
    setEditingId(record.id);
    setDeductionDraft({
      grossAmount: Number(record.invoiceDetails.grossAmount || 0),
      bankFees: Number(record.invoiceDetails.bankFees || 0),
      suspenseVat: Number(record.invoiceDetails.suspenseVat || 0),
      transportFee: Number(record.invoiceDetails.transportFee || 0),
      cutFee: Number(record.invoiceDetails.cutFee || 0),
      shortfall: Number(record.invoiceDetails.shortfall || 0),
      withholdingTax: Number(record.invoiceDetails.withholdingTax || 0),
      overpayment: Number(record.invoiceDetails.overpayment || 0),
      weightMultiplier: Number(record.weightMultiplier || 1),
      reason: '',
    });
    // Seed the preview with the record's own last-persisted figures (a plain field read, no
    // math) so the panel shows a sensible value immediately, before the debounced simulate()
    // call below has had a chance to run.
    setDeductionPreview({
      actualReceived: Number(record.actualReceived || 0),
      commissionableBase: Number(record.commissionableBase || 0),
    });
  }

  function updateDeductionDraft(field, value) {
    setDeductionDraft((current) => ({ ...current, [field]: value }));
  }

  const debouncedDeductionDraft = useDebouncedValue(deductionDraft, 300);

  // Live preview only — server-computed (CommissionService#simulate) as the manager edits, so it
  // can never drift from the real calculation; this draft never gets sent as-is, only the
  // individual input fields do (see saveDeductions). Debounced ~300ms so editing doesn't fire one
  // request per keystroke. A request counter drops a stale, out-of-order response so a slow
  // earlier reply can never clobber a newer one. Only runs while a row is actually being edited.
  useEffect(() => {
    if (editingId == null) return;
    // The debounced copy lags the live draft by one debounce interval, and `editingId` changing
    // re-fires this effect immediately — without this guard, opening a row would first simulate
    // the PREVIOUS row's (or the empty draft's) numbers and flash them into the money preview
    // before the real values arrive. Wait until the debounce has caught up with the live draft.
    if (debouncedDeductionDraft !== deductionDraft) return;
    const editingRecord = findRecord(editingId);
    if (!editingRecord) return;
    const requestId = (deductionPreviewRequestId.current += 1);
    api.commissions.simulate({
      salesRepId: editingRecord.salesRepId,
      // CommissionSimulatorRequest.payrollMonth is a Java LocalDate deserialised by Jackson's
      // JavaTimeModule (see config/JacksonConfig.java), which requires a full ISO yyyy-MM-dd —
      // a bare "yyyy-MM" 400s. `month` is a <input type="month"> value, hence the `-01`; this is
      // the same conversion createManualCommission already does a few hundred lines below.
      payrollMonth: `${month}-01`,
      grossAmount: debouncedDeductionDraft.grossAmount,
      bankFees: debouncedDeductionDraft.bankFees,
      suspenseVat: debouncedDeductionDraft.suspenseVat,
      transportFee: debouncedDeductionDraft.transportFee,
      cutFee: debouncedDeductionDraft.cutFee,
      shortfall: debouncedDeductionDraft.shortfall,
      withholdingTax: debouncedDeductionDraft.withholdingTax,
      overpayment: debouncedDeductionDraft.overpayment,
    }).then((response) => {
      if (deductionPreviewRequestId.current !== requestId) return; // superseded by a newer request
      setDeductionPreview(response.simulation);
    }).catch(() => {
      // Keep showing the previous preview values; do not toast on every keystroke.
    });
    // `records` is read for the lookup only; re-running per records change (e.g. after an
    // unrelated save) is unnecessary noise, so it is intentionally omitted below.
    // `deductionDraft` IS in the deps (the guard above compares against it): each keystroke
    // re-runs this effect, hits the guard, and returns without a request — the debounce still
    // does the throttling.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [editingId, debouncedDeductionDraft, deductionDraft, month]);

  async function saveDeductions(id) {
    if (!deductionDraft.reason.trim()) {
      showToast('error', 'กรุณาระบุเหตุผลในการแก้ไข');
      return;
    }
    setSaving(true);
    try {
      await api.commissions.updateDeductions(id, {
        grossAmount: deductionDraft.grossAmount,
        bankFees: deductionDraft.bankFees,
        suspenseVat: deductionDraft.suspenseVat,
        transportFee: deductionDraft.transportFee,
        cutFee: deductionDraft.cutFee,
        shortfall: deductionDraft.shortfall,
        withholdingTax: deductionDraft.withholdingTax,
        overpayment: deductionDraft.overpayment,
        weightMultiplier: deductionDraft.weightMultiplier,
        reason: deductionDraft.reason.trim(),
      });
      setEditingId(null);
      showToast('success', 'อัปเดตค่าคอมแล้ว');
      invalidatePayrollUpstream();
      refreshRep(id);
      await load();
      if (canReview) await loadPending();
    } catch (error) {
      showToast('error', error.message || 'อัปเดตไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  async function confirmApprove() {
    setSaving(true);
    try {
      await api.commissions.approve(approveId);
      showToast('success', 'อนุมัติค่าคอมแล้ว');
      invalidatePayrollUpstream();
      refreshRep(approveId);
      await load();
      if (canReview) await loadPending();
    } catch (error) {
      showToast('error', error.message || 'อนุมัติไม่สำเร็จ');
    } finally {
      setSaving(false);
      setApproveId(null);
    }
  }

  function reject(id) {
    setRejectId(id);
  }

  async function confirmReject(reviewerNote) {
    setSaving(true);
    try {
      await api.commissions.reject(rejectId, { reviewerNote });
      showToast('success', 'ปฏิเสธค่าคอมแล้ว');
      invalidatePayrollUpstream();
      refreshRep(rejectId);
      await load();
      if (canReview) await loadPending();
    } catch (error) {
      showToast('error', error.message || 'ปฏิเสธไม่สำเร็จ');
    } finally {
      setSaving(false);
      setRejectId(null);
    }
  }

  function clawback(id) {
    setClawbackId(id);
  }

  async function confirmClawback(reason) {
    setSaving(true);
    try {
      await api.commissions.clawback(clawbackId, { reason });
      showToast('success', 'บันทึกรายการหักคืนแล้ว');
      invalidatePayrollUpstream();
      await load();
    } catch (error) {
      showToast('error', error.message || 'บันทึกหักคืนไม่สำเร็จ');
    } finally {
      setSaving(false);
      setClawbackId(null);
    }
  }

  // Commission paid in payroll month N comes from money RECEIVED in month N-1 — the one rule every
  // figure on this page hangs on, so it is stated at the top of every month-scoped view.
  const receiptMonth = (() => {
    const match = /^(\d{4})-(\d{2})$/.exec(month);
    if (!match) return null;
    const [year, monthNumber] = [Number(match[1]), Number(match[2])];
    return monthNumber === 1 ? `${year - 1}-12` : `${year}-${String(monthNumber - 1).padStart(2, '0')}`;
  })();
  const monthScoped = !canCreateFromDeal && !canReview; // sales + hr keep the free month input

  return (
    <div className="grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px]">
      <PageHeader title="ค่าคอมมิชชัน" subtitle={canReview ? 'อนุมัติค่าคอมของฝ่ายขายสำหรับรอบจ่ายเงินเดือน' : 'ตรวจสอบ อนุมัติ และติดตามค่าคอมมิชชันของฝ่ายขาย'} />

      {monthScoped ? (
        <div className="grid gap-2 border-b border-border pb-4">
          <div className="flex flex-wrap items-end gap-x-3 gap-y-3">
            <label className="grid w-[220px] gap-1.5 text-sm font-bold mobile:w-full">
              รอบเดือน
              <input type="month" value={month} onChange={(event) => setMonth(event.target.value)} className="hover:border-border-muted pointer-coarse:min-h-11" />
            </label>
            {/* A tax invoice recorded in month M lands in payroll month M+1, so a rep who closes a
                deal and checks this page on the same day sees nothing — and the table's empty state
                cannot warn them, because the CURRENT month usually has other rows and so is not
                empty. Measured 2026-08-28: an invoice dated 27 Aug produced payroll_month 2026-09.
                Hence a standing hint plus a one-click jump, rather than an empty-state message. */}
            <Button
              type="button"
              variant="secondary"
              className="whitespace-nowrap pointer-coarse:min-h-11 mobile:flex-1"
              data-testid="commission-next-cycle"
              title="ค่าคอมจากใบกำกับที่บันทึกเดือนนี้ จะอยู่ในรอบเดือนถัดไป"
              onClick={() => {
                const [y, m] = month.split('-').map(Number);
                const next = new Date(Date.UTC(y, m, 1));
                setMonth(next.toISOString().slice(0, 7));
              }}
            >
              รอบถัดไป
              <Icon name="chevronRight" size={14} />
            </Button>
          </div>
          {receiptMonth ? (
            <p className="m-0 text-md font-bold text-text-secondary [overflow-wrap:anywhere]">
              {`รอบจ่าย ${formatThaiMonthYearFromMonthInputValue(month)} · จากยอดรับเงินเดือน ${formatThaiMonthYearFromMonthInputValue(receiptMonth)}`}
            </p>
          ) : null}
        </div>
      ) : null}

      {canReview ? (
        <ApprovalBoard
          user={user}
          month={month}
          currentMonth={bangkokMonth()}
          onMonth={setMonth}
          records={records}
          loading={loading}
          pending={pending}
          summaries={repSummaries}
          pickedRepId={pickedRepId}
          onPickRep={setPickedRepId}
          saving={saving}
          canCreateManual={canCreateManual}
          onManual={() => (showManualForm ? setShowManualForm(false) : openManualForm())}
          manualForm={canCreateManual && showManualForm ? (
            <ManualCommissionForm
              form={manualForm}
              onChange={updateManualForm}
              repOptions={repOptions}
              onSubmit={submitManual}
              onCancel={() => setShowManualForm(false)}
              saving={manualSaving}
            />
          ) : null}
          onApprove={setApproveId}
          onReject={reject}
          onClawback={clawback}
          onEdit={beginEdit}
          editor={(record) => (editingId === record.id ? (
            <ManagerReviewEditPanel
              record={record}
              draft={deductionDraft}
              onChange={updateDeductionDraft}
              preview={deductionPreview}
              saving={saving}
              onSave={() => saveDeductions(record.id)}
              onCancel={() => setEditingId(null)}
            />
          ) : null)}
          onAdjust={adjustWeights}
          onEntryUpdate={replacePendingEntry}
          onSaved={reloadAfterWeightChange}
        />
      ) : payrollOnly ? (
        <PayrollSummary summary={summary} loading={loading} />
      ) : canCreateFromDeal ? (
        <AccountCreateFromDeal
          ticketIdInput={ticketIdInput}
          setTicketIdInput={setTicketIdInput}
          ticketLookupLoading={ticketLookupLoading}
          ticketLookupError={ticketLookupError}
          loadedTicket={loadedTicket}
          eligibleTickets={eligibleTickets}
          onLookup={() => lookupTicket(ticketIdInput)}
          onSelectEligible={selectEligibleTicket}
          createForm={createForm}
          updateCreateForm={updateCreateForm}
          fileInputKey={fileInputKey}
          onSubmit={submitFromDeal}
          saving={saving}
          recentlyCreated={recentlyCreated}
        />
      ) : isSales ? (
        <>
          {monthlyTierSummary ? <CommissionStatement summary={monthlyTierSummary} /> : null}
          <ReceiptLedger records={records} loading={loading} />
        </>
      ) : null}

      <ConfirmDialog
        open={approveId != null}
        title="ยืนยันการอนุมัติค่าคอม"
        message={(() => {
          const record = [...records, ...(pending ?? []).map((entry) => entry.commission)].find((item) => item.id === approveId);
          if (!record) return 'ยืนยันการอนุมัติค่าคอมมิชชันรายการนี้?';
          // Status-transition copy quoted from api.commissions.approve
          // (src/api/mockApi.js): SUBMITTED -> MANAGER_APPROVED when a
          // sales_manager approves; MANAGER_APPROVED -> APPROVED when the CEO
          // approves a manager-approved record.
          const nextStep = canCeoReview(record)
            ? 'สถานะจะเปลี่ยนเป็น "อนุมัติแล้ว" และพร้อมเข้ารอบ Payroll'
            : 'สถานะจะเปลี่ยนเป็น "รอ CEO" เพื่อรออนุมัติขั้นสุดท้าย';
          if (isManualKind(record.kind)) {
            return (
              <div className="flex flex-col gap-2">
                <p className="confirm-dialog-message text-text-secondary leading-normal m-0">
                  ตรวจสอบก่อนอนุมัติค่าคอมแบบ <strong>{MANUAL_KIND_LABELS[record.kind] || record.kind}</strong> ของ <strong>{record.salesRepName || record.salesRepId}</strong>
                </p>
                <div className="flex justify-between text-md font-bold border-t border-border pt-2">
                  <span>จำนวนเงิน</span>
                  <code className={cn('font-mono', Number(record.manualAmount || 0) < 0 && 'text-danger')}>
                    {formatMoney(record.manualAmount)}
                  </code>
                </div>
                <p className="m-0 text-xs text-text-muted">เหตุผล: {record.manualReason || '-'}</p>
                <p className="m-0 text-xs text-text-muted">{nextStep}</p>
              </div>
            );
          }
          return (
            <div className="flex flex-col gap-2">
              <p className="confirm-dialog-message text-text-secondary leading-normal m-0">
                ตรวจสอบยอดก่อนอนุมัติใบกำกับ <strong>{record.invoiceDetails.invoiceNumber}</strong> ของ <strong>{record.salesRepName || record.salesRepId}</strong>
              </p>
              {record.dealAmountMismatch && (
                // Step 9 cross-check: grossAmount diverged >5% from the linked deal's actual
                // payableAmount at submission time. Never blocked submission — surfaced here so
                // the approver sees it before signing off, which is the entire point of the flag.
                <StatusBadge tone="warning">ยอดต่างจากยอดที่เรียกเก็บ — โปรดตรวจสอบก่อนอนุมัติ</StatusBadge>
              )}
              <div className="flex justify-between text-sm border-t border-border pt-2">
                {/* #475569 has no exact --color-text-muted match (#5c6b80); --color-icon-muted is the
                    literal token value, so it's used here to keep the rendered color identical. */}
                <span className="text-icon-muted">ยอดรับจริง</span>
                <code className="font-mono">{formatMoney(record.actualReceived)}</code>
              </div>
              <div className="flex justify-between text-md font-bold">
                <span>ฐานค่าคอม</span>
                <code className="font-mono">{formatMoney(record.commissionableBase)}</code>
              </div>
              <p className="m-0 text-xs text-text-muted">{nextStep}</p>
            </div>
          );
        })()}
        confirmLabel="อนุมัติ"
        busy={saving}
        onCancel={() => setApproveId(null)}
        onConfirm={confirmApprove}
      />
      <ConfirmDialog
        open={clawbackId != null}
        tone="danger"
        title="บันทึกหักคืน"
        message="ยืนยันการยกเลิก/คืนเงินค่าคอมมิชชันรายการนี้?"
        requireReason
        reasonLabel="เหตุผลการยกเลิก/คืนเงิน"
        busy={saving}
        onCancel={() => setClawbackId(null)}
        onConfirm={confirmClawback}
      />
      <ConfirmDialog
        open={rejectId != null}
        tone="danger"
        title="ปฏิเสธค่าคอม"
        message="ยืนยันการปฏิเสธรายการค่าคอมนี้?"
        requireReason
        reasonLabel="เหตุผลการปฏิเสธ"
        busy={saving}
        onCancel={() => setRejectId(null)}
        onConfirm={confirmReject}
      />
    </div>
  );
}

/** account role: pick/lookup a CLOSED_PAID deal, upload the tax invoice, submit. */
function AccountCreateFromDeal({
  ticketIdInput, setTicketIdInput, ticketLookupLoading, ticketLookupError, loadedTicket,
  eligibleTickets, onLookup, onSelectEligible, createForm, updateCreateForm, fileInputKey,
  onSubmit, saving, recentlyCreated,
}) {
  return (
    <>
      <Panel flush title="บันทึกใบกำกับภาษี / สร้างคำขอค่าคอมจากดีล">
        <div className="grid min-w-0 grid-cols-[minmax(0,1fr)_minmax(0,1.8fr)] nav-drawer:grid-cols-[minmax(0,1fr)]">
          {/* Left half: which deal this invoice belongs to. */}
          <div className="grid min-w-0 content-start gap-3 bg-surface-muted p-[18px] mobile:p-4 min-[1041px]:border-r min-[1041px]:border-border nav-drawer:border-b nav-drawer:border-border">
            <h3 className="m-0 text-md font-extrabold text-text">ดีลที่จะบันทึกใบกำกับ</h3>
            <div className="grid gap-2">
              <label htmlFor="commission-ticket-lookup" className="text-sm font-bold">
                เลขที่ Ticket ID ของดีลที่ปิดงาน/รับเงินครบแล้ว (CLOSED_PAID)
              </label>
              <div className="flex flex-wrap items-center gap-2">
                <input
                  id="commission-ticket-lookup"
                  type="number"
                  min="1"
                  value={ticketIdInput}
                  onChange={(event) => setTicketIdInput(event.target.value)}
                  placeholder="เช่น 42"
                  className="max-w-[160px] hover:border-border-muted pointer-coarse:min-h-11"
                />
                <Button type="button" variant="secondary" className="whitespace-nowrap pointer-coarse:min-h-11" disabled={ticketLookupLoading || !ticketIdInput} onClick={onLookup}>
                  <Icon name="search" size={14} />
                  {ticketLookupLoading ? 'กำลังโหลดข้อมูลดีล…' : 'โหลดข้อมูลดีล'}
                </Button>
              </div>
              {eligibleTickets.length > 0 ? (
                <label className="grid gap-1.5 text-sm">
                  หรือเลือกจากดีลที่ปรากฏในรายการ
                  <select value="" className="hover:border-border-muted pointer-coarse:min-h-11" onChange={(event) => onSelectEligible(event.target.value)}>
                    <option value="">— เลือกดีล —</option>
                    {eligibleTickets.map((ticket) => (
                      <option key={ticket.id} value={ticket.id}>
                        {ticket.code} · {ticket.customerName || 'ไม่ระบุลูกค้า'}
                      </option>
                    ))}
                  </select>
                </label>
              ) : (
                <p className="m-0 text-xs text-text-muted">
                  ดีลที่ปิดงาน/รับเงินครบแล้วอาจไม่ปรากฏในรายการอัตโนมัติ (รายการนี้แสดงเฉพาะดีลที่ยังรอดำเนินการด้านการเงิน) — กรอกเลขที่ Ticket ID ด้วยตนเองด้านบนได้เสมอ
                </p>
              )}
              {ticketLookupError ? <StatusBadge tone="danger">{ticketLookupError}</StatusBadge> : null}
              {loadedTicket ? (
                <div className="rounded-md border border-success-border bg-surface p-3 text-sm">
                  <strong>{loadedTicket.code}</strong> · {loadedTicket.customerName || 'ไม่ระบุลูกค้า'}
                  <div className="mt-1 flex items-center justify-between gap-3">
                    <span className="text-text-muted">ยอดที่ต้องชำระของดีล</span>
                    <code className="font-mono">{formatMoney(loadedTicket.amountPayable)}</code>
                  </div>
                </div>
              ) : null}
            </div>
          </div>

          {/* Right half: the invoice itself. */}
          <div className="grid min-w-0 content-start gap-4 p-[18px] mobile:p-4">
            {!loadedTicket ? (
              <p className="m-0 flex items-start gap-2 text-sm text-text-muted">
                <Icon name="info" size={16} className="mt-0.5 shrink-0" />
                <span>โหลดข้อมูลดีลก่อน จึงจะบันทึกใบกำกับได้ — กรอกฟอร์มไว้ล่วงหน้าได้</span>
              </p>
            ) : null}
            <InvoiceFromDealForm
              form={createForm}
              onChange={updateCreateForm}
              onSubmit={onSubmit}
              saving={saving}
              disabled={!loadedTicket}
              fileInputKey={fileInputKey}
              fileInputId="commission-invoice-file"
              submitLabel="บันทึกและสร้างคำขอค่าคอม"
            />
          </div>
        </div>
      </Panel>

      {recentlyCreated.length > 0 ? (
        <Panel title="บันทึกล่าสุดในเซสชันนี้">
          <div className="grid gap-2">
            {recentlyCreated.map((record) => {
              const status = statusInfo(record.status);
              return (
                <div key={record.id} className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border bg-surface-subtle p-3 text-sm">
                  <span>
                    <strong>{record.invoiceDetails.invoiceNumber}</strong>
                    <span className="ml-2 text-text-muted">{record.salesRepName || record.salesRepId}</span>
                  </span>
                  <span className="flex items-center gap-2">
                    <code className="font-mono">{formatMoney(record.commissionableBase)}</code>
                    <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
                  </span>
                </div>
              );
            })}
          </div>
        </Panel>
      ) : null}
    </>
  );
}

/**
 * sales_manager/ceo manual commission entry (feat/commission-manual-adjustments): every amount
 * here is hand-typed and submitted verbatim — there is NO auto-computation anywhere in this
 * form. A future CEO-confirmed auto-config may prefill a suggested amount for specific kinds
 * (e.g. STOCK_BONUS) later; owner decision for now is manual across the UI, so this form stays a
 * plain number input with no formula behind it.
 *
 * Created by sales_manager lands MANAGER_APPROVED (needs CEO sign-off); created by ceo lands
 * APPROVED immediately — see CommissionService#createManualCommission. Either way it then flows
 * through the exact same approve/reject controls as a SALE commission (no parallel UI here).
 */
// Issue #737 scope (owner ruling 2026-08-14, 2nd ruling): ceo and sales_manager now see the
// IDENTICAL ฝ่ายขาย list, so one label serves both roles -- a role-keyed map would be dead
// branching now that there is nothing left to branch on. Ploy asked for this copy to be concise;
// it still has to say the list is scoped (not "everyone") and still has to point at the numeric
// field above as the way to reach anyone outside ฝ่ายขาย -- dropping either would either overclaim
// completeness or silently strand a caller with no way to find someone the picker excludes.
const REP_PICKER_LABEL = 'หรือเลือกจากพนักงานขาย (นอกฝ่ายขายให้กรอกรหัสพนักงานด้านบน)';

function ManualCommissionForm({ form, onChange, repOptions, onSubmit, onCancel, saving }) {
  const isAdjustment = form.kind === 'ADJUSTMENT';
  return (
    <Panel flush title="เพิ่มค่าคอมด้วยตนเอง">
      <SafeForm className="p-[18px]" onSubmit={onSubmit}>
        <FormGrid>
        <label>
          ประเภท *
          <select value={form.kind} onChange={(event) => onChange('kind', event.target.value)}>
            {MANUAL_KINDS.map((kind) => (
              <option key={kind} value={kind}>{MANUAL_KIND_LABELS[kind]}</option>
            ))}
          </select>
        </label>
        <label>
          รหัสพนักงาน (Employee ID) ของพนักงานขาย *
          <input
            type="number"
            min="1"
            value={form.salesRepId}
            onChange={(event) => onChange('salesRepId', event.target.value)}
            placeholder="เช่น 42"
            required
          />
        </label>
        {repOptions.length > 0 && (
          <label className={formGridSpan2}>
            {REP_PICKER_LABEL}
            <select value="" onChange={(event) => { if (event.target.value) onChange('salesRepId', event.target.value); }}>
              <option value="">— เลือกพนักงานขาย —</option>
              {repOptions.map((rep) => (
                <option key={rep.id} value={rep.id}>{rep.name}</option>
              ))}
            </select>
          </label>
        )}
        <label>
          จำนวนเงิน (บาท) *
          <input
            type="number"
            step="0.01"
            min={isAdjustment ? undefined : 0}
            value={form.amount}
            onChange={(event) => onChange('amount', event.target.value)}
            required
          />
          <small className="text-text-muted">
            {isAdjustment
              ? 'ใส่ค่าลบเพื่อหักออกจากค่าคอมของพนักงาน (เช่น -2000)'
              : 'ต้องไม่ติดลบ'}
          </small>
        </label>
        <label>
          รอบเดือน
          <input type="month" value={form.payrollMonth} onChange={(event) => onChange('payrollMonth', event.target.value)} />
        </label>
        <label className={formGridSpan2}>
          เหตุผล *
          <textarea
            rows={2}
            value={form.reason}
            onChange={(event) => onChange('reason', event.target.value)}
            required
          />
        </label>

        <div className={`${formGridSpan2} flex flex-wrap justify-end gap-[10px] mobile:flex-col-reverse`}>
          <Button type="button" variant="secondary" className="mobile:!min-h-11 mobile:!w-full" disabled={saving} onClick={onCancel}>
            ยกเลิก
          </Button>
          <Button type="submit" variant="primary" className="mobile:!min-h-11 mobile:!w-full" disabled={saving}>
            <Icon name="check" size={14} />
            บันทึกค่าคอม
          </Button>
        </div>
        </FormGrid>
      </SafeForm>
    </Panel>
  );
}

/**
 * sales_manager/ceo review-queue edit panel: every input field (not the final amount) is
 * editable, each save requires a reason, and the recomputed actualReceived/commissionableBase
 * previews live as the reviewer types — the final commission itself is never hand-entered.
 */
function ManagerReviewEditPanel({ record, draft, onChange, preview, saving, onSave, onCancel }) {
  const fields = [
    ['grossAmount', 'Gross Amount'],
    ['bankFees', 'Bank Fees'],
    ['suspenseVat', 'Suspense VAT'],
    ['transportFee', 'ค่าขนส่ง'],
    ['cutFee', 'ค่าตัด'],
    ['shortfall', 'รับเงินขาด'],
    ['withholdingTax', 'หัก ณ ที่จ่าย (WHT)'],
    ['overpayment', 'รับเงินเกิน'],
  ];
  return (
    <Panel className="commission-row-wrap" title={`แก้ไขข้อมูลใบกำกับ ${record.invoiceDetails.invoiceNumber}`}>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        {fields.map(([key, label]) => (
          <label key={key} className="text-xs font-bold">
            {label}
            <input
              type="number"
              min="0"
              step="0.01"
              value={draft[key]}
              onChange={(event) => onChange(key, Number(event.target.value))}
              className="mt-1"
            />
          </label>
        ))}
        <div className="grid content-start gap-1.5 text-xs font-bold">
          <span id="record-weight-label">น้ำหนักฐานคอมของรายการนี้</span>
          <WeightSegmented
            label="น้ำหนักฐานคอม"
            labelledBy="record-weight-label"
            disabled={saving}
            busy={saving}
            value={Number(draft.weightMultiplier)}
            onChange={(weight) => onChange('weightMultiplier', weight)}
            describedBy="record-weight-hint"
          />
          <span id="record-weight-hint" className="text-sm font-normal text-text-muted">×2 และ ×3 ใช้เมื่อยืนยันนโยบายแล้วเท่านั้น</span>
        </div>
      </div>
      {record.effectiveWeightMultiplier !== null && record.effectiveWeightMultiplier !== undefined ? (
        // V148 (per-item stock-commission weighting): this record already carries a FROZEN,
        // blended weight computed from its ticket's items at creation time — it wins over the
        // dropdown above via CommissionRepository#sumActiveWeightedActualReceived's COALESCE, so
        // changing the selection below would silently do nothing to this record's money. Said
        // here rather than disabling the control, so the fallback stays editable for audit/record
        // purposes without misleading the reviewer about which number actually counts.
        <p className="mt-2">
          <StatusBadge tone="info">
            รายการนี้คำนวณน้ำหนักจากรายการสินค้าอัตโนมัติแล้ว ({Number(record.effectiveWeightMultiplier).toFixed(2)} เท่า) —
            การเลือกด้านบนจะไม่มีผลกับยอดเงินของรายการนี้
          </StatusBadge>
        </p>
      ) : null}

      <div className="mt-3 grid grid-cols-2 gap-3 rounded-md border border-border bg-surface-subtle p-3 text-sm sm:grid-cols-4">
        <span>
          <small className="block text-text-muted">ยอดรับจริง (คำนวณใหม่)</small>
          <code className="font-mono">{formatMoney(preview.actualReceived)}</code>
        </span>
        <span>
          <small className="block text-text-muted">ฐานค่าคอม (คำนวณใหม่)</small>
          <code className="font-mono">{formatMoney(preview.commissionableBase)}</code>
        </span>
      </div>

      <label className="mt-3 block text-xs font-bold">
        เหตุผลในการแก้ไข *
        <textarea
          rows={2}
          value={draft.reason}
          onChange={(event) => onChange('reason', event.target.value)}
          className="mt-1 w-full"
          required
        />
      </label>

      <div className="mt-3 flex gap-2">
        <Button type="button" variant="primary" disabled={saving || !draft.reason.trim()} onClick={onSave}>บันทึก</Button>
        <Button type="button" variant="secondary" disabled={saving} onClick={onCancel}>ยกเลิก</Button>
      </div>
    </Panel>
  );
}

// The legacy `.commission-payroll-table` rule in styles.css is 5 columns; a Tailwind utility beats it
// (layer order), so the 6-column variant (ค่าคอมทีม shown) lives here.
const PAYROLL_COLUMNS_BASE = 'grid-cols-[minmax(0,1.8fr)_repeat(4,minmax(0,1fr))]';
const PAYROLL_COLUMNS_WITH_TEAM = 'grid-cols-[minmax(0,1.8fr)_repeat(5,minmax(0,1fr))]';

function PayrollSummary({ summary, loading }) {
  if (loading) {
    return <CompactStatRow items={[]} loading loadingCount={3} aria-label="กำลังโหลดสรุปค่าคอมมิชชัน Payroll" />;
  }
  if (!summary) {
    return <EmptyState icon="badge" title="ยังไม่มีข้อมูลค่าคอม" description="เลือกรอบเดือนอื่นเพื่อตรวจสอบ" />;
  }
  // The backend sends companyCommissionableBase = null when no override applies in the month, but
  // ALWAYS sends totalTeamOverrideAmount (0.00) -- so only a non-null company base or a non-zero
  // total means the override exists this month. Otherwise (every pre-October month) the ค่าคอมทีม
  // column and footer would be all zeros and are left out.
  const showTeam = summary.companyCommissionableBase != null
    || (summary.totalTeamOverrideAmount != null && Number(summary.totalTeamOverrideAmount) !== 0);
  const PAYROLL_TABLE_COLUMNS = showTeam ? PAYROLL_COLUMNS_WITH_TEAM : PAYROLL_COLUMNS_BASE;
  return (
    <>
      <CompactStatRow
        items={[
          { key: 'base', label: 'ฐานค่าคอมรวม', value: formatMoney(summary.totalCommissionableBase), helper: 'Payroll-ready base' },
          { key: 'amount', label: 'ค่าคอมที่ต้องจ่าย', value: formatMoney(summary.totalCommissionAmount), helper: summary.status },
          { key: 'reps', label: 'จำนวน Sales', value: summary.salesReps?.length ?? 0, helper: 'Sales reps' },
        ]}
      />
      <Panel flush>
        <div className={cn('commission-payroll-table table-head', PAYROLL_TABLE_COLUMNS)}>
          <span>Sales Rep</span>
          <span>ฐานค่าคอม</span>
          {/* Issue #405: the auto-computed INCENTIVE ladder + STOCK_BONUS, already folded into
              the "ค่าคอม" total column, surfaced separately so HR can see the breakdown. The
              stock-bonus column stays visible (not conditionally hidden) even though it reads
              all-zero until the CEO enables sales.stock_bonus_config -- that is expected. */}
          <span>อินเซนทีฟ</span>
          <span>โบนัสขายของในสต๊อค</span>
          {/* The team override (ค่าคอมทีม) is already folded into the "ค่าคอม" total column — only
              the override recipients carry a non-zero amount, so the column is mostly empty by
              design rather than broken. */}
          {showTeam ? <span>ค่าคอมทีม</span> : null}
          <span>ค่าคอม</span>
        </div>
        {(summary.salesReps ?? []).length === 0 ? (
          <EmptyState icon="badge" title="ไม่มีรายการอนุมัติ" description="ยังไม่มีค่าคอมที่พร้อมเข้า Payroll ในเดือนนี้" />
        ) : summary.salesReps.map((rep) => (
          <div key={rep.salesRepId} className={cn('commission-payroll-table data-row', PAYROLL_TABLE_COLUMNS)}>
            <strong>{rep.salesRepName || rep.salesRepId}</strong>
            <code>{formatMoney(rep.commissionableBase)}</code>
            <code>{formatMoney(rep.incentiveAmount)}</code>
            <code>{formatMoney(rep.stockBonusAmount)}</code>
            {showTeam ? <code>{formatMoney(rep.teamOverrideAmount)}</code> : null}
            <code>{formatMoney(rep.commissionAmount)}</code>
          </div>
        ))}
        {showTeam ? (
          <div className="flex flex-wrap items-baseline justify-between gap-x-6 gap-y-1 border-t border-border-subtle bg-surface-muted px-5 py-3 text-md mobile:px-4">
            {summary.companyCommissionableBase != null ? (
              <span className="text-text-secondary">
                ยอดรับทั้งบริษัท (ไม่รวม VAT) <strong className="tabular-nums text-text">{formatMoney(summary.companyCommissionableBase)}</strong>
              </span>
            ) : <span />}
            {summary.totalTeamOverrideAmount != null ? (
              <span data-testid="payroll-team-override-total" className="text-text-secondary">
                รวมค่าคอมทีม <strong className="tabular-nums text-text">{formatMoney(summary.totalTeamOverrideAmount)}</strong>
              </span>
            ) : null}
          </div>
        ) : null}
      </Panel>
    </>
  );
}
