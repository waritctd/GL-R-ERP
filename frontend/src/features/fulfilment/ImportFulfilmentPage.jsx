import { useCallback, useMemo, useState } from 'react';
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { FilterBar, PageStack, Panel } from '../../components/common/Layout.jsx';
import { SkeletonText } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import { formatThaiDate, fulfilmentStatusLabel } from '../../utils/format.js';
import { FactoryProgressBar } from '../importProgress/FactoryProgressBar.jsx';
import { IMPORT_STEPS } from '../importProgress/importSteps.js';

/**
 * งานนำเข้า — Import's cross-deal fulfilment worklist, REDESIGNED for per-factory tracking
 * (revision 2026-09-19, PR-B — see .design/import-fulfilment/INFORMATION_ARCHITECTURE.md §11).
 *
 * Ported from Yang.Pongburit's origin/feat/per-factory-import-tracking (commit 65dfe171), which
 * built this exact per-factory redesign against a `api.importProgress` namespace his branch's own
 * (not-merged) backend supplied. PR-A (#1008) rebuilt the backend on the existing V154
 * `sales.import_request` aggregate instead (one row per (deal, FACTORY), not a second table), so
 * this port re-wires the same UI onto `api.storedImportRequests` and PR-A's own six step codes
 * (CONTACTED/ORDERED/PICKED_UP/IN_TRANSIT/AWAITING_CUSTOMS/RECEIVED — importSteps.js) instead of
 * Yang's original set. See importSteps.js's header for why the codes differ.
 *
 * The original page (2026-08-17, pre-redesign) bucketed each DEAL into one of four deal-level
 * `fulfillment_status` transitions and performed them in place. Per-factory import tracking broke
 * that premise: a tracked deal's fulfillment_status stays `IR_ISSUED` from its first factory's
 * issue until every factory reaches RECEIVED, so a tracked deal sat mislabelled in "ออกคำขอนำเข้า"
 * / never advanced on the old page, and then vanished the moment the rollup set GOODS_RECEIVED.
 * Both were reported as bugs; this page (and importActions.js's own irTracked awareness) is the
 * fix.
 *
 * The core principle is unchanged — this is "the room", not "a door": Import advances work here
 * without opening N deal pages. Only the UNIT changed, from a deal at one of four statuses to a
 * FACTORY SHIPMENT (one stored ใบขอซื้อ row) at one of six steps. Each shipment renders as the
 * same {@link FactoryProgressBar} used on the deal page's per-factory cards, so the two surfaces
 * read identically; import/ceo advance a step, edit lead time, and manage the order email in
 * place.
 *
 * A deal stays here while ANY of its ISSUED factory rows is < RECEIVED. When every factory is
 * received the deal rolls up to GOODS_RECEIVED (server-side) and drops into a collapsed "รับครบ
 * แล้ว · รอส่งมอบ" group at the foot, rather than disappearing, so the handoff to Sales's delivery
 * step reads as a handoff, never as a lost row. Delivery itself is still NOT here (owner ruling):
 * it is Sales's, on the deal's จัดซื้อ-ส่งมอบ tab.
 *
 * NO CROSS-DEAL LIST ENDPOINT EXISTS for the stored aggregate (only per-ticket
 * `GET /tickets/{id}/import-requests` and per-row `GET /import-requests/{id}`) — a real backend
 * gap noted rather than worked around with a backend change. This page compensates client-side:
 * it reads the deal list (already role-scoped server-side) and fetches each CANDIDATE deal's
 * stored rows in parallel (`useQueries`), where a candidate is any deal whose deal-level
 * `fulfillmentStatus` is non-null and not FROM_STOCK/FULLY_DELIVERED (see the `candidateIds`
 * filter below). The FETCH is wider than what actually gets SHOWN: once each candidate's rows
 * come back, PR-B REVIEW ROUND 2, X3 classifies the result —
 *
 *   - has ISSUED rows           -> tracked (an active card, or the done group once every
 *                                  required factory is RECEIVED — see `allReceived` below)
 *   - IR_ISSUED/IR_SENT/SHIPPING with NO rows -> the legacy section (pre-V184 chain still
 *                                  reachable at the deal page)
 *   - GOODS_RECEIVED/PARTIALLY_DELIVERED with NO rows -> dropped entirely. Reaching either
 *     status with zero stored rows means pure stock (FROM_STOCK, or a stock-only mixed deal)
 *     — there is no per-factory import work here to show or link to, so it must not read as a
 *     legacy import-chain deal the way an IR_ISSUED/IR_SENT/SHIPPING candidate does.
 *
 * This is an N+1 fetch bounded by the deal list's own role scope, acceptable for import's
 * worklist size; it is not a substitute for a real cross-deal endpoint, which would be a
 * backend change out of this PR's scope.
 */

function receivedCount(rows) {
  return rows.filter((r) => r.importStep === 'RECEIVED').length;
}

function LeadTimeInline({ row, editable, onSave, saving, showToast }) {
  const [open, setOpen] = useState(false);
  const [min, setMin] = useState(row.leadTimeMinDays ?? '');
  const [max, setMax] = useState(row.leadTimeMaxDays ?? '');
  if (!open) {
    return (
      <span className="flex flex-wrap items-center gap-1.5 text-2xs text-text-muted">
        {row.leadTimeMinDays != null ? `ระยะเวลานำเข้า ${row.leadTimeMinDays}–${row.leadTimeMaxDays} วัน` : 'ยังไม่ระบุระยะเวลานำเข้า'}
        {row.expectedArrivalFrom ? ` · คาดถึง ${formatThaiDate(row.expectedArrivalFrom)} – ${formatThaiDate(row.expectedArrivalTo)}` : ''}
        {editable ? (
          <Button type="button" variant="text" onClick={() => setOpen(true)} data-testid={`lead-time-edit-${row.id}`}>แก้ไข</Button>
        ) : null}
      </span>
    );
  }
  // Nit: a blank/invalid value must show a VALIDATION message, not silently submit a 0/NaN that
  // the server would 400 on (SetLeadTimeRequest requires both fields, 1-365).
  function handleSave() {
    if (min === '' || max === '') {
      showToast?.('error', 'กรุณาระบุระยะเวลานำเข้า (วัน) ทั้งค่าต่ำสุดและสูงสุด');
      return;
    }
    const minN = Number(min);
    const maxN = Number(max);
    if (!Number.isInteger(minN) || !Number.isInteger(maxN) || minN < 1 || maxN < 1 || minN > maxN) {
      showToast?.('error', 'ระยะเวลานำเข้าไม่ถูกต้อง — ต่ำสุดต้องไม่เกินสูงสุด และอยู่ระหว่าง 1-365 วัน');
      return;
    }
    // PR-B REVIEW ROUND 1, nit: stay OPEN on a 400 (or any failure) — onSave returns the
    // mutation's own promise (mutateAsync), so this only closes once the server actually accepted
    // it. It used to close unconditionally right after firing the mutation, which hid a rejected
    // save behind an editor that had already vanished.
    Promise.resolve(onSave(row.id, { leadTimeMinDays: minN, leadTimeMaxDays: maxN }))
      .then(() => setOpen(false))
      .catch(() => {});
  }
  return (
    <span className="flex flex-wrap items-center gap-1.5 text-2xs">
      <input type="number" min="1" max="365" className="w-16" value={min} onChange={(e) => setMin(e.target.value)} aria-label="ระยะเวลานำเข้าต่ำสุด (วัน)" />
      <span>–</span>
      <input type="number" min="1" max="365" className="w-16" value={max} onChange={(e) => setMax(e.target.value)} aria-label="ระยะเวลานำเข้าสูงสุด (วัน)" />
      <span>วัน</span>
      <Button type="button" size="sm" variant="secondary" disabled={saving}
        onClick={handleSave}
        data-testid={`lead-time-save-${row.id}`}>
        บันทึก
      </Button>
      <Button type="button" size="sm" variant="text" onClick={() => setOpen(false)}>ยกเลิก</Button>
    </span>
  );
}

export function ImportFulfilmentPage({ user, showToast }) {
  const queryClient = useQueryClient();
  // Nav-gated to import/ceo; anyone here may advance. A read-only viewer (should one ever reach
  // it) still gets the bars, just without the controls.
  const editable = user?.role === 'import' || user?.role === 'ceo';
  const [stepFilter, setStepFilter] = useState('ALL');
  const [search, setSearch] = useState('');
  const [emailRow, setEmailRow] = useState(null);
  // PR-B REVIEW ROUND 1, B1: field names MUST be emailTo/emailSubject/emailBody, matching Java's
  // UpdateEmailDraftRequest exactly — see ImportRequestFactoryCard.jsx's own comment on this same
  // fix for the failure mode (`to`/`subject`/`body` silently dropped, old value kept, 200 OK).
  const [emailDraft, setEmailDraft] = useState({ emailTo: '', emailSubject: '', emailBody: '' });

  const ticketsQuery = useQuery({
    queryKey: queryKeys.ticketList(''),
    queryFn: () => api.tickets.list({}).then((r) => r?.tickets ?? []),
  });
  const tickets = useMemo(() => ticketsQuery.data ?? [], [ticketsQuery.data]);
  // See this file's header for why this is a bounded N+1 rather than one cross-deal call.
  //
  // PR-B REVIEW ROUND 1, B4: used to be IR_ISSUED/GOODS_RECEIVED only, which dropped a
  // PARTIALLY_DELIVERED mixed stock+import deal (still has factories mid-transit) and every
  // legacy IR_SENT/SHIPPING deal (the pre-V184 chain, still reachable via the deal page's own
  // legacy buttons) from this worklist entirely — Import lost visibility into work it still owns.
  // Widened to any non-null status except FROM_STOCK (never touches import at all) and
  // FULLY_DELIVERED (delivery is done; see this file's header for why delivery itself stays off
  // this page regardless). A candidate with no stored ISSUED row renders in the `deals` builder's
  // `legacyDeals` branch ONLY when it is still on the pre-V184 IR_ISSUED/IR_SENT/SHIPPING chain
  // (PR-B REVIEW ROUND 2, X3) — a GOODS_RECEIVED/PARTIALLY_DELIVERED candidate with no rows
  // reached that status through pure stock and is dropped there instead, see that branch's own
  // comment and this file's header table.
  const candidateIds = useMemo(() => tickets
    .filter((t) => t.fulfillmentStatus != null
      && t.fulfillmentStatus !== 'FROM_STOCK'
      && t.fulfillmentStatus !== 'FULLY_DELIVERED')
    .map((t) => t.id), [tickets]);
  const ticketsById = useMemo(() => new Map(tickets.map((t) => [t.id, t])), [tickets]);

  const rowsQueries = useQueries({
    queries: candidateIds.map((ticketId) => ({
      queryKey: queryKeys.storedImportRequests(ticketId),
      queryFn: () => api.storedImportRequests.listForTicket(ticketId).then((r) => r?.importRequests ?? []),
    })),
  });
  const rowsLoading = ticketsQuery.isLoading || (candidateIds.length > 0 && rowsQueries.some((q) => q.isLoading));

  const invalidateAfterAdvance = useCallback((ticketId) => {
    queryClient.invalidateQueries({ queryKey: queryKeys.storedImportRequests(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketActions(ticketId) });
    queryClient.invalidateQueries({ queryKey: ['tickets', 'list'] });
    queryClient.invalidateQueries({ queryKey: queryKeys.dashboardSummary() });
    queryClient.invalidateQueries({ queryKey: queryKeys.notifications() });
  }, [queryClient]);

  const advance = useMutation({
    mutationFn: ({ rowId, targetStep }) => api.storedImportRequests.advanceStep(rowId, { targetStep }),
    // PR-B REVIEW ROUND 2, X4: the completion toast used to be a CLIENT-side guess —
    // `deal.rows.every(...)` against rows this page already had loaded, computed from state that
    // predates the advance's own server-side rollup. That is wrong whenever the rollup declines to
    // complete (a required factory never issued at all, so not even in the loaded row set —
    // mirrors mockApi's own S3 fix in mockApi.storedImportRequestRollup.test.js), and it can NEVER
    // be right for a PARTIALLY_DELIVERED deal: TicketService#applyImportRequestRollup
    // (backend/.../TicketService.java ~942-948, Owner decision 3) writes only the GOODS_RECEIVED
    // EVENT for that status, never fulfillment_status itself. So the toast is now keyed on a FRESH
    // read taken after the advance actually lands — only worth taking when the row could possibly
    // have completed the deal (targetStep RECEIVED); any other step is never a completion.
    onSuccess: async (_data, { ticketId, targetStep }) => {
      invalidateAfterAdvance(ticketId);
      let completesDeal = false;
      if (targetStep === 'RECEIVED') {
        try {
          const [{ ticket: freshTicket }, { importRequests: freshRows }] = await Promise.all([
            api.tickets.get(ticketId),
            api.storedImportRequests.listForTicket(ticketId),
          ]);
          const fs = freshTicket?.summary?.fulfillmentStatus;
          if (fs === 'GOODS_RECEIVED') {
            completesDeal = true;
          } else if (fs === 'PARTIALLY_DELIVERED') {
            // A factory still in DRAFT means the server's allFactoriesReceived declined the rollup.
            const all = freshRows ?? [];
            const issued = all.filter((r) => r.status === 'ISSUED');
            completesDeal = issued.length > 0 && issued.every((r) => r.importStep === 'RECEIVED')
              && !all.some((r) => r.status === 'DRAFT');
          }
        } catch {
          // A failed confirmation read must not claim completion — fall through to the ordinary
          // toast, same as "not GOODS_RECEIVED" would.
          completesDeal = false;
        }
      }
      showToast?.('success', completesDeal
        ? 'รับครบทุกโรงงาน — ส่งต่อฝ่ายขายเพื่อส่งมอบ'
        : 'อัปเดตสถานะรายโรงงานแล้ว');
    },
    onError: (e) => showToast?.('error', e?.message ?? 'อัปเดตไม่สำเร็จ'),
  });
  const setLeadTime = useMutation({
    mutationFn: ({ rowId, payload }) => api.storedImportRequests.setLeadTime(rowId, payload),
    onSuccess: (_d, { ticketId }) => { invalidateAfterAdvance(ticketId); showToast?.('success', 'บันทึกระยะเวลานำเข้าแล้ว'); },
    onError: (e) => showToast?.('error', e?.message ?? 'บันทึกไม่สำเร็จ'),
  });
  const saveEmail = useMutation({
    mutationFn: ({ rowId, payload }) => api.storedImportRequests.updateEmailDraft(rowId, payload),
    onSuccess: (_d, { ticketId }) => { invalidateAfterAdvance(ticketId); showToast?.('success', 'บันทึกอีเมลแล้ว'); setEmailRow(null); },
    onError: (e) => showToast?.('error', e?.message ?? 'บันทึกไม่สำเร็จ'),
  });
  const markSent = useMutation({
    mutationFn: ({ rowId, ticketId }) => api.storedImportRequests.markEmailSent(rowId).then((res) => ({ res, ticketId })),
    onSuccess: ({ ticketId }) => { invalidateAfterAdvance(ticketId); showToast?.('success', 'บันทึกว่าส่งอีเมลแล้ว'); setEmailRow(null); },
    onError: (e) => showToast?.('error', e?.message ?? 'บันทึกไม่สำเร็จ'),
  });
  const downloadPdf = useMutation({
    mutationFn: ({ rowId, copy, row }) => api.storedImportRequests.download(rowId, copy).then((blob) => ({ blob, copy, row })),
    onSuccess: ({ blob, copy, row }) => {
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `IR-${row.docNumber ?? `draft-${row.id}`}-${row.factoryName}${copy === 'factory' ? '' : '-internal'}.pdf`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    },
    onError: (e) => showToast?.('error', e?.message ?? 'ดาวน์โหลดไม่สำเร็จ'),
  });

  // Group the flat rows (per candidate ticket) into one entry per deal.
  //
  // PR-B REVIEW ROUND 1, B4 + S5: three outcomes per candidate now, not two — a fetch ERROR is
  // its own bucket (erroredDeals), never silently collapsed into "nothing to track" the way an
  // empty `?? []` used to read. See B4's own comment above the candidateIds filter for why a
  // candidate with zero ISSUED rows (legacyDeals) is shown rather than dropped.
  const { deals, legacyDeals, erroredDeals } = useMemo(() => {
    const tracked = [];
    const legacy = [];
    const errored = [];
    candidateIds.forEach((ticketId, i) => {
      const query = rowsQueries[i];
      const ticket = ticketsById.get(ticketId);
      if (query?.isError) {
        errored.push({
          ticketId,
          ticketCode: ticket?.code,
          customerName: ticket?.customerName ?? null,
          title: ticket?.title ?? null,
          message: query.error?.message ?? 'โหลดใบขอซื้อของดีลนี้ไม่สำเร็จ',
        });
        return;
      }
      const factoryRows = query?.data ?? [];
      // Only ISSUED rows are shipments this page tracks — a stray DRAFT (e.g. mid-creation on the
      // deal page) is not this worklist's concern.
      const issuedRows = factoryRows.filter((r) => r.status === 'ISSUED');
      if (issuedRows.length === 0) {
        // PR-B REVIEW ROUND 2, X3: the legacy section is for the pre-V184 chain only —
        // IR_ISSUED/IR_SENT/SHIPPING candidates with nothing stored yet, still actionable at the
        // deal page. A GOODS_RECEIVED/PARTIALLY_DELIVERED candidate with zero stored rows reached
        // that status through pure stock (FROM_STOCK, or a stock-only mixed deal) or finished the pre-V184
        // chain via the deal-page buttons — either way there is no
        // per-factory import work to show or link to, so it must be dropped, not shown as if it
        // were still on the legacy import chain (see this file's header for the full table).
        const fs = ticket?.fulfillmentStatus ?? null;
        if (fs === 'IR_ISSUED' || fs === 'IR_SENT' || fs === 'SHIPPING') {
          legacy.push({
            ticketId,
            ticketCode: ticket?.code,
            customerName: ticket?.customerName ?? null,
            projectName: ticket?.projectName ?? null,
            title: ticket?.title ?? null,
            fulfillmentStatus: fs,
            overdue: Boolean(ticket?.overdue),
          });
        }
        return;
      }
      const rows = [...issuedRows].sort((a, b) => (a.factoryName ?? '').localeCompare(b.factoryName ?? '', 'th'));
      const received = receivedCount(rows);
      tracked.push({
        ticketId,
        ticketCode: ticket?.code ?? rows[0].ticketCode,
        customerName: ticket?.customerName ?? null,
        projectName: ticket?.projectName ?? null,
        title: ticket?.title ?? null,
        dueDate: ticket?.dueDate ?? null,
        overdue: Boolean(ticket?.overdue),
        rows,
        received,
        total: rows.length,
        // PR-B REVIEW ROUND 1, S2: for GOODS_RECEIVED, keyed on the DEAL's own server-computed
        // rollup (ticket.fulfillmentStatus), NOT derived from "every ISSUED row I can currently
        // see is RECEIVED" — a deal can have a factory still in DRAFT (not counted in `rows` at
        // all), so a row-only check would collapse an incomplete deal into the done group the
        // moment its ISSUED subset finished, ahead of ImportRequestService#applyImportRequestRollup
        // actually saying so server-side.
        //
        // PR-B REVIEW ROUND 2, X2: PARTIALLY_DELIVERED (Owner decision 3, mixed stock+import
        // Case 8) is the one status that check cannot cover — TicketService
        // #applyImportRequestRollup (backend/.../TicketService.java ~942-948) deliberately writes
        // ONLY the GOODS_RECEIVED event for it, never fulfillment_status itself, so a delivery
        // already under way is not disturbed. There is no server rollup on THIS status to key on,
        // so it falls back to the row-only check S2 avoids for GOODS_RECEIVED — every ISSUED row
        // here is RECEIVED (`received === rows.length`, both computed just above).
        allReceived: ticket?.fulfillmentStatus === 'GOODS_RECEIVED'
          || (ticket?.fulfillmentStatus === 'PARTIALLY_DELIVERED' && received === rows.length
            // A factory still in DRAFT is not received: Java's allFactoriesReceived requires every
            // required factory to have an ISSUED row. (A required factory with no row at all is not
            // visible to this list — the deal page shows it.)
            && !factoryRows.some((r) => r.status === 'DRAFT')),
      });
    });
    return { deals: tracked, legacyDeals: legacy, erroredDeals: errored };
  }, [candidateIds, rowsQueries, ticketsById]);

  const activeDeals = useMemo(() => deals.filter((d) => !d.allReceived), [deals]);
  const doneDeals = useMemo(() => deals.filter((d) => d.allReceived), [deals]);

  const stepCounts = useMemo(() => {
    const counts = { ALL: 0 };
    IMPORT_STEPS.forEach((s) => { counts[s.code] = 0; });
    activeDeals.forEach((deal) => deal.rows.forEach((row) => {
      counts.ALL += 1;
      if (counts[row.importStep] != null) counts[row.importStep] += 1;
    }));
    return counts;
  }, [activeDeals]);

  const visibleActive = useMemo(() => {
    const term = search.trim().toLowerCase();
    return activeDeals
      .filter((d) => !term || [d.customerName, d.ticketCode, d.projectName, d.title]
        .some((f) => (f ?? '').toLowerCase().includes(term)))
      .filter((d) => stepFilter === 'ALL' || d.rows.some((r) => r.importStep === stepFilter))
      .sort((a, b) => {
        if (a.overdue !== b.overdue) return a.overdue ? -1 : 1;
        return (a.customerName ?? a.ticketCode ?? '').localeCompare(b.customerName ?? b.ticketCode ?? '', 'th');
      });
  }, [activeDeals, stepFilter, search]);

  const visibleLegacy = useMemo(() => {
    const term = search.trim().toLowerCase();
    return legacyDeals
      .filter((d) => !term || [d.customerName, d.ticketCode, d.projectName, d.title]
        .some((f) => (f ?? '').toLowerCase().includes(term)))
      .sort((a, b) => {
        if (a.overdue !== b.overdue) return a.overdue ? -1 : 1;
        return (a.customerName ?? a.ticketCode ?? '').localeCompare(b.customerName ?? b.ticketCode ?? '', 'th');
      });
  }, [legacyDeals, search]);

  const isLoading = rowsLoading;
  const nothingAtAll = !isLoading && !ticketsQuery.isError
    && deals.length === 0 && legacyDeals.length === 0 && erroredDeals.length === 0;

  const filterOptions = [{ key: 'ALL', label: 'ทั้งหมด' },
    ...IMPORT_STEPS.map((s) => ({ key: s.code, label: s.label }))];

  function openEmail(row) {
    setEmailRow(row);
    setEmailDraft({ emailTo: row.emailTo ?? '', emailSubject: row.emailSubject ?? '', emailBody: row.emailBody ?? '' });
  }

  return (
    <PageStack>
      <PageHeader
        title="งานนำเข้า"
        subtitle="ติดตามและเลื่อนสถานะนำเข้ารายโรงงาน — ติดต่อโรงงาน → สั่งซื้อ → รับสินค้า → ขนส่ง → ศุลกากร → ถึงโกดัง"
        actions={(
          <Button
            type="button"
            variant="icon"
            onClick={() => {
              candidateIds.forEach((id) => queryClient.invalidateQueries({ queryKey: queryKeys.storedImportRequests(id) }));
              queryClient.invalidateQueries({ queryKey: ['tickets', 'list'] });
            }}
            title="รีเฟรช"
            aria-label="รีเฟรช"
          >
            <Icon name="refresh" />
          </Button>
        )}
      />

      <FilterBar>
        <span className="text-2xs font-extrabold uppercase tracking-wide text-text-muted">ขั้นตอน</span>
        {filterOptions.map((option) => {
          const active = stepFilter === option.key;
          return (
            <button
              key={option.key}
              type="button"
              aria-pressed={active}
              className={cn(
                'inline-flex min-h-8 items-center gap-1.5 rounded-full border px-3 text-xs font-bold',
                active ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface hover:bg-surface-hover',
              )}
              onClick={() => setStepFilter(option.key)}
              data-testid={`step-chip-${option.key}`}
            >
              {option.label}
              <span className="tabular-nums opacity-70">{stepCounts[option.key] ?? 0}</span>
            </button>
          );
        })}
        <label className="ml-auto flex items-center gap-2 mobile:ml-0 mobile:w-full">
          <span className="sr-only">ค้นหาดีล</span>
          <input
            type="search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="ค้นหาลูกค้า / เลขที่ดีล"
            className="min-h-9 w-52 rounded-md border border-border-input px-3 text-sm mobile:w-full"
            data-testid="fulfilment-search"
          />
        </label>
      </FilterBar>

      {/* PR-B REVIEW ROUND 1, S5: a fetch failure must render as an error, not read as "nothing to
          do" — the deal-list query failing used to fall straight into the nothingAtAll EmptyState,
          which told Import their worklist was empty when it had simply not loaded. */}
      {ticketsQuery.isError ? (
        <Panel className="border-danger-border bg-danger-bg" data-testid="fulfilment-tickets-error">
          <p className="text-sm text-danger-dark">
            โหลดรายชื่อดีลไม่สำเร็จ — {ticketsQuery.error?.message || 'ลองรีเฟรชหน้านี้อีกครั้ง'}
          </p>
        </Panel>
      ) : null}
      {erroredDeals.length > 0 ? (
        <Panel className="border-danger-border bg-danger-bg" data-testid="fulfilment-rows-error">
          <p className="mb-1.5 text-sm font-bold text-danger-dark">โหลดใบขอซื้อของดีลต่อไปนี้ไม่สำเร็จ</p>
          <ul className="m-0 list-disc pl-5 text-xs text-danger-dark">
            {erroredDeals.map((d) => (
              <li key={d.ticketId}>
                <Link to={`/tickets/${d.ticketId}`} className="underline">
                  {d.customerName || d.title || d.ticketCode}
                </Link>
                {' — '}{d.message}
              </li>
            ))}
          </ul>
        </Panel>
      ) : null}

      {isLoading ? (
        <Panel aria-busy="true" aria-label="กำลังโหลดงานนำเข้า">
          <SkeletonText lines={6} />
        </Panel>
      ) : nothingAtAll ? (
        <EmptyState
          icon="check"
          title="ยังไม่มีงานนำเข้ารายโรงงาน"
          description="เมื่อมีการออกใบขอซื้อของโรงงานในดีลใดดีลหนึ่ง แต่ละโรงงานจะขึ้นที่นี่ให้เลื่อนสถานะ"
        />
      ) : visibleActive.length === 0 && doneDeals.length === 0 && visibleLegacy.length === 0 ? (
        <EmptyState
          icon="check"
          title="ไม่มีงานนำเข้าในขั้นตอนนี้"
          description={'ลองล้างคำค้นหรือเลือกขั้นตอน "ทั้งหมด"'}
        />
      ) : (
        <div className="flex flex-col gap-3">
          {visibleActive.map((deal) => (
            <section
              key={deal.ticketId}
              className="rounded-xl border border-border bg-surface-subtle p-3"
              data-testid="fulfilment-deal"
              data-ticket-id={deal.ticketId}
            >
              <div className="mb-2 flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <strong className="min-w-0 truncate text-sm font-extrabold text-text">
                      {deal.customerName || deal.title || deal.ticketCode}
                    </strong>
                    {deal.overdue ? <StatusBadge tone="danger">เกินกำหนด</StatusBadge> : null}
                  </div>
                  <span className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-2xs text-text-muted">
                    <Link to={`/tickets/${deal.ticketId}`} className="text-info underline">
                      <code>{deal.ticketCode}</code>
                    </Link>
                    {deal.projectName ? <span className="truncate">{deal.projectName}</span> : null}
                    {deal.dueDate ? <span>กำหนด {formatThaiDate(deal.dueDate)}</span> : null}
                  </span>
                </div>
                <span className="shrink-0 rounded-full bg-info-bg px-2.5 py-0.5 text-2xs font-bold text-info">
                  ถึงโกดัง {deal.received}/{deal.total} โรงงาน
                </span>
              </div>

              <div className="flex flex-col gap-2">
                {deal.rows.map((row) => {
                  const dim = stepFilter !== 'ALL' && row.importStep !== stepFilter;
                  return (
                    <div key={row.id} className={cn('flex flex-col gap-1.5 transition-opacity', dim && 'opacity-40')}>
                      <FactoryProgressBar
                        row={row}
                        editable={editable}
                        advancing={advance.isPending}
                        onAdvance={(r, targetStep) => advance.mutate({
                          rowId: r.id,
                          targetStep,
                          ticketId: deal.ticketId,
                        })}
                        onOpenEmail={editable ? openEmail : undefined}
                      />
                      <div className="flex flex-wrap items-center gap-3 px-1">
                        <LeadTimeInline
                          row={row}
                          editable={editable}
                          saving={setLeadTime.isPending}
                          showToast={showToast}
                          onSave={(rowId, payload) => setLeadTime.mutateAsync({ rowId, payload, ticketId: deal.ticketId })}
                        />
                        <span className="flex items-center gap-1.5 text-2xs">
                          {row.emailSentAt ? (
                            <StatusBadge tone="green">ส่งอีเมลแล้ว</StatusBadge>
                          ) : (
                            <StatusBadge tone="blue">ยังไม่ส่งอีเมล</StatusBadge>
                          )}
                          <Button type="button" variant="text" disabled={downloadPdf.isPending}
                            onClick={() => downloadPdf.mutate({ rowId: row.id, copy: undefined, row })}
                            data-testid={`ir-download-internal-${row.id}`}>
                            PDF (ภายใน)
                          </Button>
                          <Button type="button" variant="text" disabled={downloadPdf.isPending}
                            onClick={() => downloadPdf.mutate({ rowId: row.id, copy: 'factory', row })}
                            data-testid={`ir-download-factory-${row.id}`}>
                            PDF (ให้โรงงาน)
                          </Button>
                        </span>
                      </div>
                    </div>
                  );
                })}
              </div>
            </section>
          ))}

          {visibleActive.length === 0 ? (
            <p className="rounded-lg border border-dashed border-border px-4 py-6 text-center text-xs text-text-muted">
              ไม่มีงานนำเข้าที่ตรงกับตัวกรองนี้ — ดูดีลที่รับครบแล้วด้านล่าง
            </p>
          ) : null}

          {doneDeals.length > 0 ? (
            <details className="rounded-xl border border-border bg-surface" data-testid="fulfilment-done">
              <summary className="cursor-pointer list-none px-4 py-3 text-xs font-bold text-text-muted marker:hidden">
                <span className="inline-flex items-center gap-2">
                  <Icon name="check" size={14} className="text-success" />
                  รับครบแล้ว · รอฝ่ายขายส่งมอบ ({doneDeals.length})
                </span>
              </summary>
              <div className="border-t border-border-subtle">
                {doneDeals.map((deal) => (
                  <div
                    key={deal.ticketId}
                    className="flex flex-wrap items-center justify-between gap-2 border-t border-border-subtle px-4 py-2.5 first:border-t-0"
                  >
                    <span className="flex flex-wrap items-center gap-x-2 text-xs">
                      <strong className="text-text">{deal.customerName || deal.title || deal.ticketCode}</strong>
                      <Link to={`/tickets/${deal.ticketId}`} className="text-info underline">
                        <code>{deal.ticketCode}</code>
                      </Link>
                    </span>
                    <span className="text-2xs font-bold text-success-dark">
                      ครบทุกโรงงาน ({deal.total}) → ส่งต่อส่งมอบ
                    </span>
                  </div>
                ))}
              </div>
            </details>
          ) : null}

          {/* PR-B REVIEW ROUND 1, B4: deals whose worklist candidacy comes from a ticket-level
              fulfillmentStatus (IR_SENT/SHIPPING — the pre-V184 chain — or a deal with nothing
              ISSUED here yet) but that carry no stored per-factory row this page can act on. Shown
              with a link out rather than dropped, so Import never loses visibility into a deal
              their own worklist filter would otherwise silently exclude. */}
          {visibleLegacy.length > 0 ? (
            <section className="rounded-xl border border-dashed border-border bg-surface p-3" data-testid="fulfilment-legacy">
              <p className="mb-2 text-xs font-bold text-text-muted">
                ดีลอื่นที่ยังไม่ติดตามแบบรายโรงงาน — เปิดที่หน้าดีลเพื่อดำเนินการ
              </p>
              <div className="flex flex-col gap-1.5">
                {visibleLegacy.map((deal) => (
                  <div key={deal.ticketId}
                    className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border-subtle px-3 py-2 text-xs"
                    data-testid="fulfilment-legacy-deal">
                    <span className="flex flex-wrap items-center gap-2">
                      <strong className="text-text">{deal.customerName || deal.title || deal.ticketCode}</strong>
                      {deal.overdue ? <StatusBadge tone="danger">เกินกำหนด</StatusBadge> : null}
                      {deal.fulfillmentStatus ? (
                        <span className="text-2xs text-text-muted">{fulfilmentStatusLabel(deal.fulfillmentStatus).label}</span>
                      ) : null}
                    </span>
                    <Link to={`/tickets/${deal.ticketId}`} className="text-info underline">
                      ไปที่หน้าดีล <code>{deal.ticketCode}</code>
                    </Link>
                  </div>
                ))}
              </div>
            </section>
          ) : null}
        </div>
      )}

      <p className="text-2xs text-text-muted">
        การส่งมอบสินค้าให้ลูกค้าไม่ได้อยู่ในหน้านี้ — บันทึกที่แท็บ จัดซื้อ-ส่งมอบ ของดีลนั้น
      </p>

      {emailRow ? (
        <Modal
          title="อีเมลสั่งซื้อ (ร่าง)"
          subtitle={emailRow.factoryName}
          onClose={() => setEmailRow(null)}
          testId="order-email-modal"
          footer={(
            <>
              <Button type="button" variant="secondary" onClick={() => setEmailRow(null)}>ปิด</Button>
              {/* Nit: hide "บันทึก" once sent — mirrors ImportRequestFactoryCard.jsx's own fix. */}
              {!emailRow.emailSentAt ? (
                <Button type="button" variant="secondary" disabled={saveEmail.isPending}
                  onClick={() => saveEmail.mutate({ rowId: emailRow.id, payload: emailDraft, ticketId: emailRow.ticketId })}>
                  บันทึก
                </Button>
              ) : null}
              <Button
                type="button"
                variant="secondary"
                onClick={async () => {
                  try {
                    await navigator.clipboard.writeText(emailDraft.emailBody ?? '');
                    showToast?.('success', 'คัดลอกข้อความอีเมลแล้ว');
                  } catch {
                    showToast?.('error', 'คัดลอกไม่สำเร็จ — เลือกข้อความแล้วคัดลอกเอง');
                  }
                }}
              >
                คัดลอกข้อความ
              </Button>
              {!emailRow.emailSentAt ? (
                <Button type="button" variant="primary" disabled={markSent.isPending}
                  onClick={() => markSent.mutate({ rowId: emailRow.id, ticketId: emailRow.ticketId })}
                  data-testid="ir-email-mark-sent">
                  ส่งแล้ว
                </Button>
              ) : null}
            </>
          )}
        >
          <div className="grid gap-2">
            <ol className="m-0 list-decimal pl-5 text-xs text-text-muted">
              <li>คัดลอกข้อความด้านล่าง (แก้ไขได้ก่อนคัดลอก)</li>
              <li>วางในอีเมล/ช่องทางที่ใช้ส่งโรงงานเอง แล้วส่ง</li>
              <li>กลับมากด &ldquo;ส่งแล้ว&rdquo;</li>
            </ol>
            {emailRow.emailSentAt ? (
              <p className="text-xs font-bold text-success-dark">
                ส่งแล้วเมื่อ {formatThaiDate(emailRow.emailSentAt)}{emailRow.emailSentByName ? ` โดย ${emailRow.emailSentByName}` : ''}
              </p>
            ) : null}
            <label className="flex flex-col gap-1 text-xs font-bold text-text-secondary">
              ถึง
              <input type="email" value={emailDraft.emailTo} disabled={Boolean(emailRow.emailSentAt)}
                onChange={(e) => setEmailDraft((d) => ({ ...d, emailTo: e.target.value }))} />
            </label>
            <label className="flex flex-col gap-1 text-xs font-bold text-text-secondary">
              หัวข้อ
              <input type="text" value={emailDraft.emailSubject} disabled={Boolean(emailRow.emailSentAt)}
                onChange={(e) => setEmailDraft((d) => ({ ...d, emailSubject: e.target.value }))} />
            </label>
            <textarea
              className="form-input min-h-64 font-mono text-xs"
              readOnly={Boolean(emailRow.emailSentAt)}
              value={emailDraft.emailBody}
              onChange={(e) => setEmailDraft((d) => ({ ...d, emailBody: e.target.value }))}
            />
          </div>
        </Modal>
      ) : null}
    </PageStack>
  );
}
