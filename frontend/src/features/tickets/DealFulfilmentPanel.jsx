import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ROLE_PERMISSIONS } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatThaiDate, fulfilmentStatusLabel } from '../../utils/format.js';
import { nextFulfilmentActionCode } from './importActions.js';
import { procurementPath } from './stageMeta.js';

const STEP_ROLE_TH = { import: 'ฝ่ายนำเข้า', ceo: 'CEO', sales: 'ฝ่ายขาย', account: 'ฝ่ายบัญชี' };

// Same small presentational helpers DealDepositPanel introduced (Phase 3
// Slice S3) — duplicated here rather than imported since neither panel
// exports them and this is the only other place that needs them so far.
function StepRoleTag({ owners, viewerRole }) {
  const mine = owners.includes(viewerRole);
  return (
    <span className={`rounded-full px-2 py-0.5 text-2xs font-bold ${
      mine ? 'bg-info-bg text-info' : 'bg-surface-subtle text-text-muted'
    }`}>
      {owners.map((o) => STEP_ROLE_TH[o] ?? o).join('/')}
    </span>
  );
}

function StepNumber({ no }) {
  return (
    <span className="grid h-6 w-6 shrink-0 place-items-center rounded-full bg-info text-2xs font-extrabold text-surface">
      {no}
    </span>
  );
}

// Compact glance strip reusing DealStagePanel's own PROCUREMENT_SUBSTEPS
// labels (stageMeta.js) so the two panels never drift on what each
// fulfillmentStatus code is called — DealStagePanel keeps its own copy of
// this rendering (read-only, inside its "inner journey" chip strip) per the
// same precedent Slice S3 set for the deposit-policy chip; this is the
// action-bearing version.
function SubstepChips({ currentCode, fromStock = null }) {
  // Issue #730: this used to walk the flat PROCUREMENT_SUBSTEPS list and mark everything before
  // the current code "done". That list is a lookup table, not a path — FROM_STOCK sits at index 4,
  // so a from-stock deal rendered IR-issued / ordered / shipping / goods-received in green, four
  // milestones it never performed and, per issueImportRequest's own guard, never could have.
  // procurementPath() returns the journey this deal is actually on, so index-as-progress is true
  // again. Same defect PR #715 fixed for PICKED_UP/CUSTOMS_CLEARANCE — but FROM_STOCK is written
  // by reserveStock, so this instance was live, and PR #706 made it more common.
  const steps = procurementPath(currentCode, fromStock);
  const currentIdx = steps.findIndex((s) => s.code === currentCode);
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      {steps.map((step, i) => {
        const done = currentIdx >= 0 && i < currentIdx;
        const current = i === currentIdx;
        return (
          <span
            key={step.code}
            className={`inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-2xs font-bold ${
              done ? 'bg-success-bg text-success-dark'
                : current ? 'bg-info-bg text-info'
                  : 'bg-surface-subtle text-text-muted'
            }`}
          >
            {step.label}
          </span>
        );
      })}
    </div>
  );
}

/**
 * "การส่งมอบ / นำเข้า" (Phase 3 Slice S4 — see
 * docs/agent-handoffs/105_feat-deal-deposit-fulfilment-unify.md): one
 * role-shaped section walking the deal-level fulfilment chain as two ordered
 * steps —
 *   1. นำเข้าสินค้า (import/CEO): Import Request → ส่งแล้ว → เดินทาง →
 *      รับสินค้าแล้ว, or the from-stock path via reserveStock.
 *   2. ส่งมอบสินค้า (import/CEO): record/complete delivery against the
 *      deal's own items, with the running progress bar + history.
 *
 * There used to be an optional third "ใบสั่งซื้อโรงงาน" block here (import/CEO
 * only, listing per-factory purchase orders for the deal's order-confirmed
 * PricingRequest). Removed 2026-08-11, owner ruling: factory purchase orders
 * are not part of the current business requirement, and the block had never
 * shown a row in production (0 POs ever created). The backend
 * ProcurementController and sales.factory_purchase_order survive, dormant and
 * with no frontend caller, for whenever the requirement does land.
 *
 * Replaces the "การส่งมอบสินค้า" panel, the docActions Import Request button,
 * and the delivery/stock-reservation modals that used to live directly on
 * TicketDetailPage. Every mutation here reuses an existing hrApi method
 * verbatim (tickets.issueImportRequest/markIrSent/markShipping/
 * markGoodsReceived/reserveStock/recordDelivery/completeDelivery) — none of
 * that surface changed shape or
 * gate for this slice; the `can.*` predicates below are moved out of
 * TicketDetailPage byte-for-byte.
 */
export function DealFulfilmentPanel({
  user, ticketId, summary, items = [], availableActions = [], showToast,
}) {
  const queryClient = useQueryClient();
  const role = user?.role;
  const isImport = ROLE_PERMISSIONS.canPickupTickets.includes(role);
  const isFulfilment = isImport || role === 'ceo';

  const hasAction = (action) => availableActions.some((item) => item.action === action);

  const [deliveryOpen, setDeliveryOpen] = useState(false);
  const [deliveryDraft, setDeliveryDraft] = useState({ source: 'WAREHOUSE', note: '', lines: {} });
  const [stockOpen, setStockOpen] = useState(false);
  const [stockDraft, setStockDraft] = useState({ note: '', lines: {} });
  // V148 (per-item stock-commission weighting): sales_manager/ceo only -- a separate control from
  // the stock-declaration modal above, matching the backend's separate authorization boundary
  // (TicketService#setItemWeightMultipliers, gated to a DIFFERENT role set than reserveStock).
  const [weightOpen, setWeightOpen] = useState(false);
  const [weightDraft, setWeightDraft] = useState({});

  const st = summary?.status;
  const fs = summary?.fulfillmentStatus ?? null;
  const fsLabel = fulfilmentStatusLabel(fs);

  const totalOrdered = items.reduce((sum, item) => sum + Number(item.qty || 0), 0);
  const totalDelivered = items.reduce((sum, item) => sum + Number(item.qtyDelivered || 0), 0);
  const totalFromStock = items.reduce((sum, item) => sum + Number(item.qtyFromStock || 0), 0);
  const deliveryProgress = totalOrdered > 0 ? Math.min(100, Math.round((totalDelivered / totalOrdered) * 100)) : 0;
  // Which journey this deal walked, for SubstepChips (issue #730). Once the deal reaches a
  // DELIVERY state its fulfillmentStatus no longer says, but the declaration itself survives on
  // the lines: reserveStock writes qtyFromStock, and only FULL coverage sets FROM_STOCK. So full
  // coverage => the from-stock branch; anything less => the import sequence really did run for
  // the remainder. `null` (no items loaded yet) means "don't guess", not "import".
  const fromStock = totalOrdered > 0 ? totalFromStock >= totalOrdered : null;

  const deliveriesQuery = useQuery({
    queryKey: queryKeys.ticketDeliveries(ticketId),
    queryFn: () => api.tickets.listDeliveries(ticketId).then((r) => r.items ?? []),
    enabled: !!ticketId,
  });
  const deliveryRecords = deliveriesQuery.data ?? [];

  function invalidateAfterFulfilmentChange() {
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketActions(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketDeliveries(ticketId) });
    queryClient.invalidateQueries({ queryKey: ['tickets', 'list'] });
    queryClient.invalidateQueries({ queryKey: queryKeys.dashboardSummary() });
    queryClient.invalidateQueries({ queryKey: queryKeys.notifications() });
  }

  function onError(err) {
    showToast?.('error', err.message || 'ดำเนินการไม่สำเร็จ');
  }

  const issueIrMutation = useMutation({
    mutationFn: () => api.tickets.issueImportRequest(ticketId),
    onSuccess: () => { showToast?.('success', 'ออกคำขอนำเข้าแล้ว'); invalidateAfterFulfilmentChange(); },
    onError,
  });
  const markIrSentMutation = useMutation({
    mutationFn: () => api.tickets.markIrSent(ticketId),
    onSuccess: () => { showToast?.('success', 'ส่งคำขอนำเข้าแล้ว'); invalidateAfterFulfilmentChange(); },
    onError,
  });
  const markShippingMutation = useMutation({
    mutationFn: () => api.tickets.markShipping(ticketId),
    onSuccess: () => { showToast?.('success', 'สินค้าอยู่ระหว่างขนส่ง'); invalidateAfterFulfilmentChange(); },
    onError,
  });
  const markGoodsReceivedMutation = useMutation({
    mutationFn: () => api.tickets.markGoodsReceived(ticketId),
    onSuccess: () => { showToast?.('success', 'รับสินค้าแล้ว'); invalidateAfterFulfilmentChange(); },
    onError,
  });
  const reserveStockMutation = useMutation({
    mutationFn: (payload) => api.tickets.reserveStock(ticketId, payload),
    onSuccess: () => { showToast?.('success', 'บันทึกสินค้าจากสต็อกแล้ว'); setStockOpen(false); invalidateAfterFulfilmentChange(); },
    onError,
  });
  const setItemWeightMutation = useMutation({
    mutationFn: (payload) => api.tickets.updateItemWeightMultipliers(ticketId, payload),
    onSuccess: () => { showToast?.('success', 'บันทึกน้ำหนักคอมมิชชั่นต่อรายการแล้ว'); setWeightOpen(false); invalidateAfterFulfilmentChange(); },
    onError,
  });
  const recordDeliveryMutation = useMutation({
    mutationFn: (payload) => api.tickets.recordDelivery(ticketId, payload),
    onSuccess: () => { showToast?.('success', 'บันทึกการส่งสินค้าแล้ว'); setDeliveryOpen(false); invalidateAfterFulfilmentChange(); },
    onError,
  });
  const completeDeliveryMutation = useMutation({
    mutationFn: () => api.tickets.completeDelivery(ticketId, { note: 'ส่งมอบครบจากหน้าดีล' }),
    onSuccess: () => { showToast?.('success', 'ส่งมอบครบแล้ว'); invalidateAfterFulfilmentChange(); },
    onError,
  });

  // Moved out of TicketDetailPage byte-for-byte (Phase 3 Slice S4) — same
  // status+role permission checks, just no longer routed through this
  // page's shared actionMutation/doAction.
  //
  // Role-scoped views (Import build): the status/fulfillmentStatus matching
  // for the four linear fulfilment-chain steps is now factored into
  // importActions.js's nextFulfilmentActionCode, shared with ImportOverview's
  // worklist CTA and ProcurementFulfilmentPage — so the two surfaces can
  // never disagree with this panel about which stage a deal is at.
  // hasAction(...) && isFulfilment still gate whether THIS viewer may act on
  // it (list rows don't carry availableActions, so that check stays local).
  const fulfilmentActionCode = nextFulfilmentActionCode({ status: st, fulfillmentStatus: fs });
  const can = {
    issueImportRequest: hasAction('ISSUE_IMPORT_REQUEST') && fulfilmentActionCode === 'issueImportRequest' && isFulfilment,
    markIrSent:         hasAction('IR_SENT') && fulfilmentActionCode === 'markIrSent' && isFulfilment,
    markShipping:       hasAction('SHIPPING') && fulfilmentActionCode === 'markShipping' && isFulfilment,
    markGoodsReceived:  hasAction('GOODS_RECEIVED') && fulfilmentActionCode === 'markGoodsReceived' && isFulfilment,
    // The ONE entry here with no local role check, deliberately (issue #732). PR #706 widened the
    // backend gate to the deal owner — TicketService.canDeclareStockCoverage is
    // FULFILMENT_ROLES ∪ (SALES_ROLES ∧ createdById == actor.id) — and actions() advertises
    // RESERVE_STOCK off that same predicate. ANDing the server's answer with the pre-#706 role set
    // meant the server offered the action and the frontend threw it away, so the one role the
    // workflow was built for could not start it. TicketService's own Javadoc names this failure
    // mode: gate and advertisement are one predicate so they "cannot drift into offering an action
    // that immediately 403s", and applying it unevenly leaves a capability "live but invisible".
    //
    // hasAction('RESERVE_STOCK') already carries the ownership rule, the S10 stage floor and the
    // remaining-delivery check, because the server computed all three. Re-deriving any of them
    // here is what rots. The other six entries KEEP isFulfilment — their backend gates really are
    // FULFILMENT_ROLES-only, so dropping it there would offer buttons that 403.
    reserveStock:       hasAction('RESERVE_STOCK'),
    recordDelivery:     hasAction('RECORD_PARTIAL_DELIVERY') && isFulfilment,
    completeDelivery:   hasAction('COMPLETE_DELIVERY') && isFulfilment,
    // V148 (per-item stock-commission weighting): sales_manager/ceo only -- unlike reserveStock
    // above, this DOES keep a local role check alongside hasAction(), matching every other entry
    // in this object except reserveStock's own documented exception. The backend gate
    // (TicketService#ITEM_WEIGHT_ROLES) is sales_manager/ceo, never isFulfilment (import/ceo).
    setItemWeight:      hasAction('SET_ITEM_WEIGHT_MULTIPLIER') && (role === 'sales_manager' || role === 'ceo'),
  };

  function openDeliveryModal() {
    const source = fs === 'FROM_STOCK' ? 'STOCK' : 'WAREHOUSE';
    const lines = {};
    items.forEach((item) => {
      lines[item.id] = String(Math.max(0, Number(item.qty || 0) - Number(item.qtyDelivered || 0)));
    });
    setDeliveryDraft({ source, note: '', lines });
    setDeliveryOpen(true);
  }

  function openStockModal() {
    const lines = {};
    items.forEach((item) => { lines[item.id] = String(item.qtyFromStock ?? 0); });
    setStockDraft({ note: '', lines });
    setStockOpen(true);
  }

  function openWeightModal() {
    const lines = {};
    items.forEach((item) => { lines[item.id] = String(item.weightMultiplier ?? 1); });
    setWeightDraft(lines);
    setWeightOpen(true);
  }

  function handleRecordDelivery() {
    const lines = items
      .map((item) => ({ itemId: item.id, qty: Number(deliveryDraft.lines[item.id] || 0) }))
      .filter((line) => line.qty > 0);
    if (lines.length === 0) {
      showToast?.('error', 'กรุณาระบุจำนวนส่งมอบอย่างน้อย 1 รายการ');
      return;
    }
    recordDeliveryMutation.mutate({ source: deliveryDraft.source, note: deliveryDraft.note.trim() || null, lines });
  }

  function handleReserveStock() {
    const lines = items.map((item) => ({
      itemId: item.id,
      qtyFromStock: Number(stockDraft.lines[item.id] || 0),
      note: stockDraft.note.trim() || null,
    }));
    reserveStockMutation.mutate({ lines });
  }

  function handleSetItemWeight() {
    const lines = items.map((item) => ({
      itemId: item.id,
      weightMultiplier: Number(weightDraft[item.id] || 1),
    }));
    setItemWeightMutation.mutate({ lines });
  }

  return (
    <Panel flush title="การส่งมอบ / นำเข้า" data-testid="deal-fulfilment-panel">
      <div className="flex flex-col gap-3 p-4">
        {/* Step 1: นำเข้าสินค้า (Import Request → รับสินค้า, or from-stock) */}
        <div className="flex flex-col gap-2 rounded-md border border-border bg-surface p-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-2">
              <StepNumber no={1} />
              <strong className="text-sm">นำเข้าสินค้า</strong>
              <StepRoleTag owners={['import', 'ceo']} viewerRole={role} />
            </div>
            {fs ? <StatusBadge tone={fsLabel.tone}>{fsLabel.label}</StatusBadge> : null}
          </div>

          <SubstepChips currentCode={fs} fromStock={fromStock} />

          <div className="flex flex-wrap gap-2">
            {can.issueImportRequest ? (
              <Button type="button" variant="primary" disabled={issueIrMutation.isPending}
                onClick={() => issueIrMutation.mutate()} data-testid="deal-fulfilment-issue-ir">
                ออกคำขอนำเข้า (IR)
              </Button>
            ) : can.markIrSent ? (
              <Button type="button" variant="primary" disabled={markIrSentMutation.isPending}
                onClick={() => markIrSentMutation.mutate()} data-testid="deal-fulfilment-mark-ir-sent">
                ส่งคำขอนำเข้าแล้ว
              </Button>
            ) : can.markShipping ? (
              <Button type="button" variant="primary" disabled={markShippingMutation.isPending}
                onClick={() => markShippingMutation.mutate()} data-testid="deal-fulfilment-mark-shipping">
                สินค้าออกเดินทาง
              </Button>
            ) : can.markGoodsReceived ? (
              <Button type="button" variant="primary" disabled={markGoodsReceivedMutation.isPending}
                onClick={() => markGoodsReceivedMutation.mutate()} data-testid="deal-fulfilment-mark-goods-received">
                รับสินค้าแล้ว
              </Button>
            ) : fs == null ? (
              <p className="text-xs text-text-muted">ยังไม่ออกคำขอนำเข้า</p>
            ) : null}
            {can.reserveStock ? (
              <Button type="button" variant="secondary" disabled={reserveStockMutation.isPending}
                onClick={openStockModal} data-testid="deal-fulfilment-reserve-stock">
                จองสินค้าจากสต็อก
              </Button>
            ) : null}
            {can.setItemWeight ? (
              <Button type="button" variant="secondary" disabled={setItemWeightMutation.isPending}
                onClick={openWeightModal} data-testid="deal-fulfilment-set-item-weight">
                ตั้งน้ำหนักคอมมิชชั่นต่อรายการ
              </Button>
            ) : null}
          </div>
        </div>

        {/* Step 2: ส่งมอบสินค้า */}
        <div className="flex flex-col gap-2 rounded-md border border-border bg-surface p-3">
          <div className="flex items-center gap-2">
            <StepNumber no={2} />
            <strong className="text-sm">ส่งมอบสินค้า</strong>
            <StepRoleTag owners={['import', 'ceo']} viewerRole={role} />
          </div>

          <div className="flex flex-wrap items-center gap-3">
            <div className="min-w-40 flex-1">
              <div className="mb-1 flex items-center justify-between text-xs">
                <strong>{totalDelivered.toLocaleString('en-US')} / {totalOrdered.toLocaleString('en-US')}</strong>
                <span className="text-text-muted">{deliveryProgress}%</span>
              </div>
              <div className="h-2 overflow-hidden rounded-full bg-surface-subtle">
                <div className="h-full bg-success" style={{ width: `${deliveryProgress}%` }} />
              </div>
            </div>
            <div className="flex flex-wrap gap-2">
              {can.recordDelivery ? (
                <Button type="button" variant="primary" disabled={recordDeliveryMutation.isPending}
                  onClick={openDeliveryModal} data-testid="deal-fulfilment-record-delivery">
                  บันทึกการส่งสินค้า
                </Button>
              ) : null}
              {can.completeDelivery ? (
                <Button type="button" variant="secondary" disabled={completeDeliveryMutation.isPending}
                  onClick={() => completeDeliveryMutation.mutate()} data-testid="deal-fulfilment-complete">
                  ส่งมอบครบ
                </Button>
              ) : null}
            </div>
          </div>

          <div className="flex flex-col gap-1.5">
            {items.map((item) => {
              const ordered = Number(item.qty || 0);
              const delivered = Number(item.qtyDelivered || 0);
              const remaining = Math.max(0, ordered - delivered);
              return (
                <div key={item.id} className="flex items-center justify-between gap-2 rounded border border-border-subtle px-2.5 py-1.5 text-xs">
                  <div className="min-w-0">
                    <strong className="block truncate">{item.brand} {item.model || ''}</strong>
                    <span className="text-2xs text-text-muted">
                      จากสต็อก {Number(item.qtyFromStock || 0).toLocaleString('en-US')} · คงเหลือ {remaining.toLocaleString('en-US')}
                    </span>
                  </div>
                  <strong className="shrink-0">{delivered.toLocaleString('en-US')} / {ordered.toLocaleString('en-US')}</strong>
                </div>
              );
            })}
          </div>

          <div className="flex flex-col gap-1.5 border-t border-border-subtle pt-2.5">
            <span className="text-2xs font-bold text-text-muted">ประวัติส่งมอบ</span>
            {deliveriesQuery.isLoading ? (
              <p className="text-xs text-text-muted">กำลังโหลดประวัติส่งมอบ…</p>
            ) : deliveryRecords.length === 0 ? (
              <p className="text-xs text-text-muted">ยังไม่มีรายการส่งมอบ</p>
            ) : (
              deliveryRecords.map((record) => (
                <div key={record.deliveryId} className="flex flex-wrap items-baseline gap-2 text-xs">
                  <span className="text-text-muted">{formatThaiDate(record.deliveredAt)}</span>
                  <strong>{record.source}</strong>
                  <span className="text-text-muted">
                    {(record.items ?? []).map((line) => `${line.itemId}: ${Number(line.qty).toLocaleString('en-US')}`).join(', ')}
                    {record.deliveredByName ? ` · ${record.deliveredByName}` : ''}
                    {record.note ? ` · ${record.note}` : ''}
                  </span>
                </div>
              ))
            )}
          </div>
        </div>
      </div>

      {deliveryOpen ? (
        <Modal
          title="บันทึกการส่งสินค้า"
          onClose={() => setDeliveryOpen(false)}
          footer={(
            <>
              <Button type="button" variant="secondary" onClick={() => setDeliveryOpen(false)}>ยกเลิก</Button>
              <Button type="button" variant="primary" disabled={recordDeliveryMutation.isPending}
                onClick={handleRecordDelivery} data-testid="deal-fulfilment-record-delivery-submit">
                บันทึก
              </Button>
            </>
          )}
        >
          <div className="flex flex-col gap-3">
            <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
              แหล่งสินค้า
              <select value={deliveryDraft.source}
                onChange={(e) => setDeliveryDraft((draft) => ({ ...draft, source: e.target.value }))}>
                <option value="WAREHOUSE">WAREHOUSE</option>
                <option value="STOCK">STOCK</option>
              </select>
            </label>
            <div className="flex flex-col gap-2">
              {items.map((item) => {
                const remaining = Math.max(0, Number(item.qty || 0) - Number(item.qtyDelivered || 0));
                return (
                  <label key={item.id} className="grid grid-cols-[1fr_120px] items-center gap-2.5 text-sm">
                    <span>
                      <span className="sr-only">จำนวนส่งมอบ</span>
                      <strong>{item.brand} {item.model || ''}</strong>
                      <small className="block text-text-muted">
                        คงเหลือ {remaining.toLocaleString('en-US')} · ส่งแล้ว {Number(item.qtyDelivered || 0).toLocaleString('en-US')}
                      </small>
                    </span>
                    <input type="number" min="0" max={remaining} step="0.01"
                      aria-label={`จำนวนส่งมอบ ${item.brand} ${item.model || ''}`}
                      value={deliveryDraft.lines[item.id] ?? ''}
                      onChange={(e) => setDeliveryDraft((draft) => ({
                        ...draft,
                        lines: { ...draft.lines, [item.id]: e.target.value },
                      }))} />
                  </label>
                );
              })}
            </div>
            <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
              หมายเหตุ
              <textarea className="min-h-16" value={deliveryDraft.note}
                onChange={(e) => setDeliveryDraft((draft) => ({ ...draft, note: e.target.value }))} />
            </label>
          </div>
        </Modal>
      ) : null}

      {stockOpen ? (
        <Modal
          title="จองสินค้าจากสต็อก"
          onClose={() => setStockOpen(false)}
          footer={(
            <>
              <Button type="button" variant="secondary" onClick={() => setStockOpen(false)}>ยกเลิก</Button>
              <Button type="button" variant="primary" disabled={reserveStockMutation.isPending}
                onClick={handleReserveStock}>
                บันทึก
              </Button>
            </>
          )}
        >
          <div className="flex flex-col gap-3">
            {items.map((item) => (
              <label key={item.id} className="grid grid-cols-[1fr_120px] items-center gap-2.5 text-sm">
                <span>
                  <span className="sr-only">จำนวนจากสต็อก</span>
                  <strong>{item.brand} {item.model || ''}</strong>
                  <small className="block text-text-muted">
                    สั่ง {Number(item.qty || 0).toLocaleString('en-US')} · จากสต็อกเดิม {Number(item.qtyFromStock || 0).toLocaleString('en-US')}
                  </small>
                </span>
                <input type="number" min="0" max={Number(item.qty || 0)} step="0.01"
                  aria-label={`จำนวนจากสต็อก ${item.brand} ${item.model || ''}`}
                  value={stockDraft.lines[item.id] ?? ''}
                  onChange={(e) => setStockDraft((draft) => ({
                    ...draft,
                    lines: { ...draft.lines, [item.id]: e.target.value },
                  }))} />
              </label>
            ))}
            <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
              เหตุผล / หมายเหตุ
              <textarea className="min-h-16" value={stockDraft.note}
                onChange={(e) => setStockDraft((draft) => ({ ...draft, note: e.target.value }))} />
            </label>
          </div>
        </Modal>
      ) : null}

      {weightOpen ? (
        <Modal
          title="ตั้งน้ำหนักคอมมิชชั่นต่อรายการ"
          onClose={() => setWeightOpen(false)}
          footer={(
            <>
              <Button type="button" variant="secondary" onClick={() => setWeightOpen(false)}>ยกเลิก</Button>
              <Button type="button" variant="primary" disabled={setItemWeightMutation.isPending}
                onClick={handleSetItemWeight} data-testid="deal-fulfilment-set-item-weight-submit">
                บันทึก
              </Button>
            </>
          )}
        >
          <div className="flex flex-col gap-3">
            <p className="text-xs text-text-muted">
              น้ำหนัก 2 หรือ 3 เท่ามีผลเฉพาะสัดส่วนของแต่ละรายการที่มาจากสต็อกเท่านั้น ส่วนที่สั่งนำเข้ายังคงนับ 1 เท่าเสมอ
              การตั้งค่านี้จะมีผลกับค่าคอมมิชชั่นครั้งถัดไปที่บันทึกสำหรับดีลนี้เท่านั้น ไม่กระทบรายการที่บันทึกไปแล้ว
            </p>
            {items.map((item) => (
              <label key={item.id} className="grid grid-cols-[1fr_110px] items-center gap-2.5 text-sm">
                <span>
                  <span className="sr-only">น้ำหนักคอมมิชชั่น</span>
                  <strong>{item.brand} {item.model || ''}</strong>
                  <small className="block text-text-muted">
                    จากสต็อก {Number(item.qtyFromStock || 0).toLocaleString('en-US')} / สั่ง {Number(item.qty || 0).toLocaleString('en-US')}
                  </small>
                </span>
                <select
                  aria-label={`น้ำหนักคอมมิชชั่น ${item.brand} ${item.model || ''}`}
                  value={weightDraft[item.id] ?? 1}
                  onChange={(e) => setWeightDraft((draft) => ({ ...draft, [item.id]: e.target.value }))}
                >
                  <option value={1}>1 เท่า (ปกติ)</option>
                  <option value={2}>2 เท่า</option>
                  <option value={3}>3 เท่า</option>
                </select>
              </label>
            ))}
          </div>
        </Modal>
      ) : null}
    </Panel>
  );
}
