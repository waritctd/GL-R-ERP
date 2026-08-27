import { useCallback, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { FilterBar, PageStack, Panel } from '../../components/common/Layout.jsx';
import { SkeletonText } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import { formatThaiDate, fulfilmentStatusLabel } from '../../utils/format.js';
import {
  FULFILMENT_WORKSPACE_CODES, IMPORT_ACTION_LABELS, nextFulfilmentActionCode,
} from '../tickets/importActions.js';
import { PAYMENT_SUBSTEPS } from '../tickets/stageMeta.js';

/**
 * งานนำเข้า — Import's stream-2 workspace: the cross-deal fulfilment worklist
 * that ACTS IN PLACE, rather than launching into one deal at a time.
 * See .design/import-fulfilment/INFORMATION_ARCHITECTURE.md.
 *
 * This is deliberately NOT a revival of the deleted /procurement page (commit
 * ebaf6888). That page's fulfilment half was a verbatim duplicate of the
 * ImportOverview worklist — same helper, same rows, same labels, same
 * `navigate('/tickets/' + id)` destination — and was correctly removed because
 * deleting it lost no capability. The difference here is the only one that
 * matters: this page performs the four transitions itself. Delete it and Import
 * loses the ability to advance N deals without opening N deal pages.
 *
 * SCOPE — stage 12 (DealStage.PROCUREMENT) ONLY. The four statuses on
 * sales.ticket.fulfillment_status that Import owns:
 *
 *     (null) --ออกคำขอนำเข้า--> IR_ISSUED --ส่งแล้ว--> IR_SENT
 *            --ออกเดินทาง--> SHIPPING --รับเข้าคลัง--> GOODS_RECEIVED
 *
 * Delivery (PARTIALLY_DELIVERED / FULLY_DELIVERED, stages 13-14) is EXCLUDED —
 * owner ruling: it is being reassigned to Sales. `recordDelivery` rows are
 * filtered out below and there is no delivery control anywhere on this page.
 *
 * Note the consequence, which is load-bearing for the UI: a deal LEAVES this
 * workspace the moment goods are received, because nextFulfilmentActionCode
 * returns `recordDelivery` for GOODS_RECEIVED and that code is out of scope.
 * The ยืนยันรับเข้าคลัง toast says so, or the disappearing row reads as a bug.
 */

// The fulfilment-chain action codes this workspace owns, in chain order —
// IMPORTED from importActions.js, never redeclared, so the list this page
// FILTERS on and the list nextImportAction ROUTES to /fulfilment on are provably
// the same four. Anything not in it (i.e. `recordDelivery`, or null) is dropped
// from the worklist entirely.
//
// Chip and button copy likewise comes from IMPORT_ACTION_LABELS rather than
// being retyped, so a chip can never drift from the button it filters to — the
// same trick PricingRequestQueuePage plays with pricingRequestStatusLabel.
const FULFILMENT_ACTION_CODES = FULFILMENT_WORKSPACE_CODES;

// The one place a code maps to the api.tickets method that performs it. All four
// already exist in hrApi.js (and are mirrored in mockApi.js); this branch adds no
// API method and changes no endpoint.
const ACTION_MUTATIONS = {
  issueImportRequest: (id) => api.tickets.issueImportRequest(id),
  markIrSent: (id) => api.tickets.markIrSent(id),
  markShipping: (id) => api.tickets.markShipping(id),
  markGoodsReceived: (id) => api.tickets.markGoodsReceived(id),
};

// Success copy per transition. ยืนยันรับเข้าคลัง names the HANDOFF, not just the
// write, because confirming it is what removes the row from this page (see the
// header) — "done, and it has left your workspace" is the honest message.
const ACTION_SUCCESS_TOAST = {
  issueImportRequest: 'ออกคำขอนำเข้าแล้ว',
  markIrSent: 'ส่งคำขอนำเข้าแล้ว',
  markShipping: 'บันทึกว่าสินค้าออกเดินทางแล้ว',
  markGoodsReceived: 'รับเข้าคลังแล้ว — ดีลนี้ส่งต่อไปยังขั้นตอนส่งมอบ',
};

// What the deal's fulfillmentStatus becomes once the action succeeds. Shown in
// the confirmation strip so the operator reads the actual state change, not just
// a verb. Presentation only — the SERVER decides whether the move is legal.
const ACTION_RESULT_STATUS = {
  issueImportRequest: 'IR_ISSUED',
  markIrSent: 'IR_SENT',
  markShipping: 'SHIPPING',
  markGoodsReceived: 'GOODS_RECEIVED',
};

const PAYMENT_LABELS = Object.fromEntries(PAYMENT_SUBSTEPS.map((s) => [s.code, s.label]));

function fulfilmentStageText(status) {
  return status == null ? 'ยังไม่ออกคำขอนำเข้า' : fulfilmentStatusLabel(status).label;
}

/**
 * One worklist row. Two states:
 *   idle  — deal facts + a single action button
 *   armed — the button is replaced by a confirmation strip (§4.2 of the IA)
 *
 * Why a confirmation at all: all four transitions are irreversible and monotonic
 * server-side (re-issuing an IR 409s — TicketService.issueImportRequest: "Never
 * restart an in-flight fulfillment track"). A bare one-click button on a list row
 * is a misfire waiting to happen, and there is nothing to undo afterwards.
 *
 * Why not a modal: one modal per click is disproportionate for a page whose whole
 * purpose is advancing several deals in a row — it would give back the context
 * switch this page exists to remove.
 *
 * The strip renders BELOW the row content rather than in the button's own place,
 * so the confirm control never lands on the pixels the trigger just occupied. A
 * double-tap or double-click cannot reach it.
 */
function FulfilmentRow({ ticket, code, armed, pending, onArm, onDisarm, onConfirm }) {
  const stage = fulfilmentStatusLabel(ticket.fulfillmentStatus);
  const resultStage = fulfilmentStatusLabel(ACTION_RESULT_STATUS[code]);
  const label = IMPORT_ACTION_LABELS[code];
  const stripId = `fulfilment-confirm-${ticket.id}`;
  // Escape lives on the two buttons, not on the strip that wraps them: a
  // role="group" is non-interactive, and hanging key handlers off one is both a
  // lint error (jsx-a11y/no-noninteractive-element-interactions) and a real a11y
  // problem — the listener would only ever fire for a pointer user who happened
  // to focus a descendant anyway. Focus is inside the strip whenever it is open
  // (it opens onto ยกเลิก), so this covers every keyboard path that can reach it.
  const dismissOnEscape = (event) => { if (event.key === 'Escape') onDisarm(); };

  return (
    <div
      className={cn(
        'flex flex-col gap-2 border-t border-border-subtle px-4 py-3 first:border-t-0',
        armed && 'bg-warning-bg-soft',
      )}
      data-testid="fulfilment-row"
      data-ticket-id={ticket.id}
    >
      <div className="flex flex-wrap items-center justify-between gap-3 mobile:flex-col mobile:items-stretch">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <strong className="min-w-0 truncate text-sm font-extrabold text-text">
              {ticket.customerName || ticket.title}
            </strong>
            {ticket.overdue ? <StatusBadge tone="danger">เกินกำหนด</StatusBadge> : null}
          </div>
          <span className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-2xs text-text-muted">
            {/* The escape hatch. Everything this page deliberately cannot do —
                stock reservation, delivery, item weights, documents, the event
                history — still lives on the deal, and import holds
                canViewTickets, so /tickets/:id is reachable for them. */}
            <Link to={`/tickets/${ticket.id}`} className="text-info underline">
              <code>{ticket.code}</code>
            </Link>
            {ticket.projectName ? <span className="truncate">{ticket.projectName}</span> : null}
            {ticket.dueDate ? <span>กำหนด {formatThaiDate(ticket.dueDate)}</span> : null}
          </span>
        </div>

        <StatusBadge tone={ticket.fulfillmentStatus ? stage.tone : 'neutral'}>
          {fulfilmentStageText(ticket.fulfillmentStatus)}
        </StatusBadge>

        {armed ? (
          // Holds the trigger's slot while armed so the row does not reflow
          // sideways underneath the pointer as the strip opens.
          <span className="text-xs font-bold text-warning-dark mobile:text-center" aria-hidden="true">
            รอยืนยัน…
          </span>
        ) : (
          <Button
            type="button"
            size="sm"
            variant="primary"
            className="shrink-0 mobile:w-full"
            aria-expanded={false}
            aria-controls={stripId}
            disabled={pending}
            onClick={() => onArm(ticket.id)}
            data-testid="fulfilment-action"
          >
            {label}
          </Button>
        )}
      </div>

      {armed ? (
        <div
          id={stripId}
          role="group"
          aria-label={`ยืนยัน${label} — ${ticket.customerName || ticket.title}`}
          className="flex flex-wrap items-center justify-between gap-3 rounded-md border border-warning-border bg-surface px-3 py-2.5 mobile:flex-col mobile:items-stretch"
          data-testid="fulfilment-confirm"
        >
          <span className="flex min-w-0 flex-col gap-0.5">
            <span className="flex flex-wrap items-center gap-1.5 text-xs font-bold text-text">
              <Icon name="triangleAlert" size={14} className="text-warning" />
              ยืนยัน{label}?
              <span className="font-normal text-text-muted">
                {fulfilmentStageText(ticket.fulfillmentStatus)} → {resultStage.label}
              </span>
            </span>
            <span className="text-2xs text-text-muted">
              ขั้นตอนนี้ย้อนกลับไม่ได้
              {/* The deal's own payment state, shown because the backend's
                  issueImportRequest ALSO requires deposit readiness and this page
                  cannot see availableActions on a list row. Displayed as a fact,
                  never re-derived as a rule — see the page footer note. */}
              {ticket.paymentStatus
                ? ` · สถานะการเงิน: ${PAYMENT_LABELS[ticket.paymentStatus] ?? ticket.paymentStatus}`
                : ''}
            </span>
          </span>

          {/* ยกเลิก first in the DOM so it takes focus and the first Tab stop:
              the safe option is the default for an irreversible action. */}
          <span className="flex shrink-0 gap-2 mobile:flex-col">
            <Button
              type="button"
              size="sm"
              variant="secondary"
              onClick={onDisarm}
              onKeyDown={dismissOnEscape}
              ref={(node) => node?.focus()}
              data-testid="fulfilment-cancel"
            >
              ยกเลิก
            </Button>
            <Button
              type="button"
              size="sm"
              variant="primary"
              disabled={pending}
              onClick={() => onConfirm(ticket.id, code)}
              onKeyDown={dismissOnEscape}
              data-testid="fulfilment-confirm-submit"
            >
              ยืนยัน
            </Button>
          </span>
        </div>
      ) : null}
    </div>
  );
}

export function ImportFulfilmentPage({ showToast }) {
  const queryClient = useQueryClient();
  const [filterKey, setFilterKey] = useState('ALL');
  const [armedId, setArmedId] = useState(null);
  const [search, setSearch] = useState('');

  // Same call and the SAME query key ImportOverview uses, so the dashboard and
  // this page share one cache entry and can never show different rows. The list
  // is already role-scoped server-side (TicketRepository.appendRoleScope), so
  // every row here is one this viewer is allowed to see.
  const ticketsQuery = useQuery({
    queryKey: queryKeys.ticketList(''),
    queryFn: () => api.tickets.list({}).then((r) => r?.tickets ?? []),
  });

  /**
   * The SAME invalidation set DealFulfilmentPanel.invalidateAfterFulfilmentChange
   * fires. Copied deliberately and kept in lockstep: the two surfaces write the
   * same column through the same endpoints, so if this page invalidated a
   * narrower set, a deal advanced here would still render its old stage on
   * /tickets/:id (and vice versa) until a hard reload.
   */
  const invalidateAfterFulfilmentChange = useCallback((ticketId) => {
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketActions(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketDeliveries(ticketId) });
    queryClient.invalidateQueries({ queryKey: ['tickets', 'list'] });
    queryClient.invalidateQueries({ queryKey: queryKeys.dashboardSummary() });
    queryClient.invalidateQueries({ queryKey: queryKeys.notifications() });
  }, [queryClient]);

  // One mutation for all four transitions, mirroring PricingRequestQueuePage's
  // pickupMutation shape (mutate from the row, invalidate on success, surface the
  // server's own message on error).
  //
  // That error path is not a formality. This page's button is an AFFORDANCE, not
  // an authorization: nextFulfilmentActionCode matches on status/fulfillmentStatus
  // only, while the backend additionally requires deposit readiness for
  // issueImportRequest and refuses markShipping/markGoodsReceived on a PO-tracked
  // deal. api.tickets.list rows carry no `availableActions` (importActions.js says
  // so in its header, which is why DealFulfilmentPanel keeps hasAction() local),
  // and re-deriving those rules here would be a second copy of a backend rule that
  // can drift. So the server stays the authority and its Thai 409 message is what
  // the operator sees.
  const advanceMutation = useMutation({
    mutationFn: ({ id, code }) => ACTION_MUTATIONS[code](id),
    onSuccess: (_data, { id, code }) => {
      setArmedId(null);
      showToast?.('success', ACTION_SUCCESS_TOAST[code]);
      invalidateAfterFulfilmentChange(id);
    },
    onError: (error) => {
      setArmedId(null);
      showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ');
    },
  });

  const tickets = useMemo(() => ticketsQuery.data ?? [], [ticketsQuery.data]);

  // One row per deal sitting on the import axis. The stage decision is
  // nextFulfilmentActionCode's alone — never re-implemented here — and rows whose
  // code is not one of this workspace's four (i.e. `recordDelivery`, or null) are
  // dropped.
  const rows = useMemo(() => {
    const stageOrder = (code) => FULFILMENT_ACTION_CODES.indexOf(code);
    return tickets
      .map((ticket) => ({ ticket, code: nextFulfilmentActionCode(ticket) }))
      .filter((row) => FULFILMENT_ACTION_CODES.includes(row.code))
      .sort((a, b) => {
        if (Boolean(a.ticket.overdue) !== Boolean(b.ticket.overdue)) return a.ticket.overdue ? -1 : 1;
        const stageDiff = stageOrder(a.code) - stageOrder(b.code);
        if (stageDiff !== 0) return stageDiff;
        return new Date(a.ticket.updatedAt || 0) - new Date(b.ticket.updatedAt || 0);
      });
  }, [tickets]);

  const counts = useMemo(() => {
    const next = { ALL: rows.length };
    FULFILMENT_ACTION_CODES.forEach((code) => { next[code] = 0; });
    rows.forEach(({ code }) => { next[code] += 1; });
    return next;
  }, [rows]);

  const visibleRows = useMemo(() => {
    const term = search.trim().toLowerCase();
    return rows
      .filter(({ code }) => filterKey === 'ALL' || code === filterKey)
      .filter(({ ticket }) => !term || [ticket.customerName, ticket.code, ticket.title, ticket.projectName]
        .some((field) => (field ?? '').toLowerCase().includes(term)));
  }, [rows, filterKey, search]);

  function selectFilter(key) {
    setFilterKey(key);
    // A row armed under one filter must not stay armed into another: the
    // confirmation names a deal that may no longer be on screen.
    setArmedId(null);
  }

  return (
    <PageStack>
      <PageHeader
        title="งานนำเข้า"
        subtitle="เดินงานนำเข้าทีละขั้นได้จากหน้านี้ — ออกคำขอนำเข้า → ส่งคำขอ → ออกเดินทาง → รับเข้าคลัง"
        actions={(
          <Button
            type="button"
            variant="icon"
            onClick={() => queryClient.invalidateQueries({ queryKey: ['tickets', 'list'] })}
            title="รีเฟรช"
            aria-label="รีเฟรช"
          >
            <Icon name="refresh" />
          </Button>
        )}
      />

      <FilterBar>
        <span className="text-2xs font-extrabold uppercase tracking-wide text-text-muted">ขั้นตอน</span>
        {[{ key: 'ALL', label: 'ทั้งหมด' },
          ...FULFILMENT_ACTION_CODES.map((code) => ({ key: code, label: IMPORT_ACTION_LABELS[code] }))]
          .map((option) => {
            const active = filterKey === option.key;
            return (
              <button
                key={option.key}
                type="button"
                aria-pressed={active}
                className={cn(
                  'inline-flex min-h-8 items-center gap-1.5 rounded-full border px-3 text-xs font-bold',
                  active ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface hover:bg-surface-hover',
                )}
                onClick={() => selectFilter(option.key)}
              >
                {option.label}
                <span className="tabular-nums opacity-70">{counts[option.key] ?? 0}</span>
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

      {ticketsQuery.isLoading ? (
        <Panel aria-busy="true" aria-label="กำลังโหลดงานนำเข้า">
          <SkeletonText lines={6} />
        </Panel>
      ) : visibleRows.length === 0 ? (
        <EmptyState
          icon="check"
          title="ไม่มีงานนำเข้าในขั้นตอนนี้"
          description={filterKey === 'ALL' && !search.trim()
            ? 'งานนำเข้าทั้งหมดดำเนินการครบแล้ว'
            : 'ลองล้างคำค้นหรือเลือกขั้นตอน "ทั้งหมด"'}
        />
      ) : (
        <Panel flush>
          {visibleRows.map(({ ticket, code }) => (
            <FulfilmentRow
              key={ticket.id}
              ticket={ticket}
              code={code}
              armed={armedId === ticket.id}
              pending={advanceMutation.isPending}
              onArm={setArmedId}
              onDisarm={() => setArmedId(null)}
              onConfirm={(id, actionCode) => advanceMutation.mutate({ id, code: actionCode })}
            />
          ))}
        </Panel>
      )}

      <p className="text-2xs text-text-muted">
        การส่งมอบสินค้าให้ลูกค้าไม่ได้อยู่ในหน้านี้ — บันทึกที่แท็บ จัดซื้อ-ส่งมอบ ของดีลนั้น
      </p>
    </PageStack>
  );
}
