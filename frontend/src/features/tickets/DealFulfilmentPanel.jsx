import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api, ROLE_PERMISSIONS } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import {
  factoryPurchaseOrderStatusLabel, formatMoney, formatThaiDate, fulfilmentStatusLabel,
} from '../../utils/format.js';
import { nextFulfilmentActionCode } from './importActions.js';
import { PROCUREMENT_SUBSTEPS } from './stageMeta.js';

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
function SubstepChips({ currentCode }) {
  const currentIdx = PROCUREMENT_SUBSTEPS.findIndex((s) => s.code === currentCode);
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      {PROCUREMENT_SUBSTEPS.map((step, i) => {
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

// Most recently created pricing request that reached order confirmation —
// mirrors DealDepositPanel's pickAcceptedPricingRequest (same reasoning: the
// deal may carry several PricingRequests, and QUOTATION_ACCEPTED is also the
// only status ProcurementService.create accepts — see api/mockApi.js
// procurement.create's own status gate). This is the request a factory
// purchase order, if any exists for this deal, was created against.
function pickAcceptedPricingRequest(pricingRequests = []) {
  return pricingRequests
    .filter((pr) => pr.status === 'QUOTATION_ACCEPTED')
    .sort((a, b) => b.id - a.id)[0] ?? null;
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
 * — plus an OPTIONAL third "ใบสั่งซื้อโรงงาน (Factory PO)" block, shown to
 * import/CEO only (the same roles ProcurementService.RAW_PO_ROLES allows —
 * see api/mockApi.js's own `hasRole('import', 'ceo')` guard on
 * procurement.listForPricingRequest), summarizing any per-factory purchase
 * orders that exist for the deal's order-confirmed PricingRequest, with a
 * link to ProcurementDetailPage for full editing. This is unused in
 * production today (0 factory purchase orders exist yet — see handoff 105's
 * S4 section), so it renders a plain empty state rather than an empty table.
 *
 * Replaces the "การส่งมอบสินค้า" panel, the docActions Import Request button,
 * and the delivery/stock-reservation modals that used to live directly on
 * TicketDetailPage. Every mutation here reuses an existing hrApi method
 * verbatim (tickets.issueImportRequest/markIrSent/markShipping/
 * markGoodsReceived/reserveStock/recordDelivery/completeDelivery,
 * procurement.listForPricingRequest) — none of that surface changed shape or
 * gate for this slice; the `can.*` predicates below are moved out of
 * TicketDetailPage byte-for-byte.
 */
export function DealFulfilmentPanel({
  user, ticketId, summary, items = [], availableActions = [], pricingRequests = [], showToast,
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

  const st = summary?.status;
  const fs = summary?.fulfillmentStatus ?? null;
  const fsLabel = fulfilmentStatusLabel(fs);

  const totalOrdered = items.reduce((sum, item) => sum + Number(item.qty || 0), 0);
  const totalDelivered = items.reduce((sum, item) => sum + Number(item.qtyDelivered || 0), 0);
  const deliveryProgress = totalOrdered > 0 ? Math.min(100, Math.round((totalDelivered / totalOrdered) * 100)) : 0;

  const deliveriesQuery = useQuery({
    queryKey: queryKeys.ticketDeliveries(ticketId),
    queryFn: () => api.tickets.listDeliveries(ticketId).then((r) => r.items ?? []),
    enabled: !!ticketId,
  });
  const deliveryRecords = deliveriesQuery.data ?? [];

  const pr = useMemo(() => pickAcceptedPricingRequest(pricingRequests), [pricingRequests]);
  // procurement.listForPricingRequest is import/CEO only (mirrors
  // ProcurementService.RAW_PO_ROLES) — sales/account see this panel's other
  // two steps read-only, but never call this query (it would 403 in the
  // mock, same reasoning noted on DealDepositPanel's canCreateNotice gate).
  const canViewProcurement = pr != null && ['import', 'ceo'].includes(role);
  const procurementQuery = useQuery({
    queryKey: queryKeys.factoryPurchaseOrdersForPricingRequest(pr?.id),
    queryFn: () => api.procurement.listForPricingRequest(pr.id).then((r) => r.factoryPurchaseOrders ?? []),
    enabled: canViewProcurement,
  });
  const purchaseOrders = procurementQuery.data ?? [];

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
    onSuccess: () => { showToast?.('success', 'ออก IR แล้ว'); invalidateAfterFulfilmentChange(); },
    onError,
  });
  const markIrSentMutation = useMutation({
    mutationFn: () => api.tickets.markIrSent(ticketId),
    onSuccess: () => { showToast?.('success', 'ส่ง IR แล้ว'); invalidateAfterFulfilmentChange(); },
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
    reserveStock:       hasAction('RESERVE_STOCK') && isFulfilment,
    recordDelivery:     hasAction('RECORD_PARTIAL_DELIVERY') && isFulfilment,
    completeDelivery:   hasAction('COMPLETE_DELIVERY') && isFulfilment,
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

  return (
    <section className="table-panel">
      <div className="panel-header">
        <h2>การส่งมอบ / นำเข้า</h2>
      </div>

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

          <SubstepChips currentCode={fs} />

          <div className="flex flex-wrap gap-2">
            {can.issueImportRequest ? (
              <button type="button" className="primary-button" disabled={issueIrMutation.isPending}
                onClick={() => issueIrMutation.mutate()}>
                ออก Import Request (IR)
              </button>
            ) : can.markIrSent ? (
              <button type="button" className="primary-button" disabled={markIrSentMutation.isPending}
                onClick={() => markIrSentMutation.mutate()}>
                ส่ง IR แล้ว
              </button>
            ) : can.markShipping ? (
              <button type="button" className="primary-button" disabled={markShippingMutation.isPending}
                onClick={() => markShippingMutation.mutate()}>
                สินค้าออกเดินทาง (Shipping)
              </button>
            ) : can.markGoodsReceived ? (
              <button type="button" className="primary-button" disabled={markGoodsReceivedMutation.isPending}
                onClick={() => markGoodsReceivedMutation.mutate()}>
                รับสินค้าแล้ว (Goods Received)
              </button>
            ) : fs == null ? (
              <p className="text-xs text-text-muted">ยังไม่ออก Import Request</p>
            ) : null}
            {can.reserveStock ? (
              <button type="button" className="secondary-button" disabled={reserveStockMutation.isPending}
                onClick={openStockModal}>
                จองสินค้าจากสต็อก
              </button>
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
                <button type="button" className="primary-button" disabled={recordDeliveryMutation.isPending}
                  onClick={openDeliveryModal}>
                  บันทึกการส่งสินค้า
                </button>
              ) : null}
              {can.completeDelivery ? (
                <button type="button" className="secondary-button" disabled={completeDeliveryMutation.isPending}
                  onClick={() => completeDeliveryMutation.mutate()}>
                  ส่งมอบครบ
                </button>
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
              <p className="text-xs text-text-muted">กำลังโหลด...</p>
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

        {/* Step 3 (optional): per-factory purchase orders — import/CEO only,
            unused in production today (0 POs — see handoff 105's S4 section). */}
        {['import', 'ceo'].includes(role) ? (
          <div className="flex flex-col gap-2 rounded-md border border-border bg-surface p-3">
            <div className="flex items-center gap-2">
              <StepNumber no={3} />
              <strong className="text-sm">ใบสั่งซื้อโรงงาน (Factory PO)</strong>
              <StepRoleTag owners={['import', 'ceo']} viewerRole={role} />
            </div>

            {pr == null ? (
              <p className="text-xs text-text-muted">
                ยังไม่มีใบขอราคาที่ลูกค้ายืนยันคำสั่งซื้อสำหรับดีลนี้ — ยังสร้างใบสั่งซื้อโรงงานไม่ได้
              </p>
            ) : procurementQuery.isLoading ? (
              <p className="text-xs text-text-muted">กำลังโหลด...</p>
            ) : purchaseOrders.length === 0 ? (
              <EmptyState
                icon="fileText"
                title="ยังไม่มีใบสั่งซื้อโรงงาน"
                description="ใบสั่งซื้อโรงงาน (แยกตามโรงงาน) จะปรากฏที่นี่เมื่อถูกสร้างขึ้นสำหรับใบขอราคานี้"
              />
            ) : (
              <div className="flex flex-col gap-1.5">
                {purchaseOrders.map((po) => {
                  const status = factoryPurchaseOrderStatusLabel(po.status);
                  return (
                    <div key={po.id} className="flex flex-wrap items-center justify-between gap-2 rounded border border-border-subtle bg-surface-muted px-2.5 py-2">
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="font-mono text-xs font-bold text-text">{po.poNumber}</span>
                          <span className="text-2xs text-text-muted">{po.factoryName}</span>
                        </div>
                        <span className="text-2xs text-text-muted">
                          {formatMoney(po.totalAmount)} {po.currency}
                          {' · '}Proforma: {po.supplierProformaRef || '-'}
                          {' · '}ขนส่ง: {po.containerRef || '-'} ({formatThaiDate(po.etd)} → {formatThaiDate(po.eta)})
                          {' · '}ต้นทุนจริง: {po.actualLandedCostThb != null ? formatMoney(po.actualLandedCostThb) : '-'}
                        </span>
                      </div>
                      <div className="flex shrink-0 items-center gap-2">
                        <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
                        <Link to={`/factory-purchase-orders/${po.id}`} className="text-xs font-bold text-link">
                          รายละเอียด →
                        </Link>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        ) : null}
      </div>

      {deliveryOpen ? (
        <Modal
          title="บันทึกการส่งสินค้า"
          onClose={() => setDeliveryOpen(false)}
          footer={(
            <>
              <button type="button" className="secondary-button" onClick={() => setDeliveryOpen(false)}>ยกเลิก</button>
              <button type="button" className="primary-button" disabled={recordDeliveryMutation.isPending}
                onClick={handleRecordDelivery}>
                บันทึก
              </button>
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
              <button type="button" className="secondary-button" onClick={() => setStockOpen(false)}>ยกเลิก</button>
              <button type="button" className="primary-button" disabled={reserveStockMutation.isPending}
                onClick={handleReserveStock}>
                บันทึก
              </button>
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
    </section>
  );
}
