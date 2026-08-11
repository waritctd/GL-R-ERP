import { forwardRef, useImperativeHandle, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatThaiDate, pricingRequestStatusLabel } from '../../utils/format.js';
import {
  canCancelPricingRequest, canCreatePricingRequest,
  canSubmitPricingRequest, canUpdatePricingRequest, pricingRequestRecipientLabel,
} from './pricingRequestMeta.js';
import { PricingRequestCreateModal } from './PricingRequestCreateModal.jsx';

/**
 * Every action in this panel is a react-query mutation whose failure used to go NOWHERE: none of
 * them had an onError or rendered `.error`, so a rejected call left the button looking like it
 * simply did nothing, with the reason visible only in the browser console. That is how a real backend 422 ("ต้องเลือกสินค้าจาก Price Catalog…") reached a UAT user
 * as silence. The service already returns a Thai, user-facing message for every one of these
 * failures, so showing it is all that was missing.
 */
function MutationError({ mutation, match }) {
  // One mutation instance serves every row, so `variables` is what says WHICH row the in-flight
  // call belonged to — without this check a failure would print under all of them at once.
  if (!mutation.isError || (match !== undefined && !match)) return null;
  return (
    <p role="alert" className="basis-full text-2xs font-bold text-danger-dark">
      {mutation.error?.message || 'ทำรายการไม่สำเร็จ'}
    </p>
  );
}

// Mirrors PricingRequestEventKind — labels for this request's own event log
// (rendered here, per-request, NOT merged into the deal's main timeline).
const EVENT_LABEL = {
  PRICING_REQUEST_CREATED: 'สร้างคำขอราคา (ร่าง)',
  PRICING_REQUEST_UPDATED: 'แก้ไขคำขอราคา',
  PRICING_REQUEST_SUBMITTED: 'ส่งให้ฝ่ายนำเข้าแล้ว',
  PRICING_REQUEST_PICKED_UP: 'ฝ่ายนำเข้ารับเรื่องแล้ว',
  MORE_INFO_REQUESTED: 'ฝ่ายนำเข้าขอข้อมูลเพิ่มเติม',
  MORE_INFO_RESPONDED: 'ตอบข้อมูลเพิ่มเติมแล้ว',
  PRICING_REQUEST_CANCELLED: 'ยกเลิกคำขอราคา',
};

/**
 * Per-deal PricingRequest section (commit 6): the deal's own pricing requests,
 * with per-row expansion showing that request's items + its own event log.
 * Mounted on TicketDetailPage right after the items table — a deal's product
 * lines here are preliminary; nothing reaches Import until a PricingRequest is
 * created and submitted (see TicketService.create/submit, commit 5).
 *
 * `deal` is the ticket's summary (createdById + lifecycle) — used only for the
 * create gate; this component does not know about ticket status/stage.
 *
 * Ticket-detail IA rebuild Phase 1 clutter follow-up: this panel no longer
 * renders its own "สร้างคำขอราคา" button — the sticky header's primary CTA
 * (TicketDetailPage's `workState`-derived `CREATE_PCR` action) owns that
 * action outright now, and opens THIS panel's create modal via the forwardRef
 * below (same "parent triggers, panel stays the sole gate + mutation owner"
 * convention DealStagePanel already uses for openEditStage/openHold/...).
 * Before this, the same "สร้างคำขอราคา" label rendered twice on one page (the
 * sticky bar's own copy, plus this panel's) — the Phase-1 follow-up audit's
 * FIX 1.
 */
export const PricingRequestPanel = forwardRef(function PricingRequestPanel({ ticketId, deal, ticketItems = [], user }, ref) {
  const queryClient = useQueryClient();
  const [createOpen, setCreateOpen] = useState(false);
  const [expandedId, setExpandedId] = useState(null);
  const [editingId, setEditingId] = useState(null); // pricing request id being edited (Fix 2), or null
  const [cancelDraft, setCancelDraft] = useState(null); // { id, reason }

  const listQuery = useQuery({
    queryKey: queryKeys.pricingRequestsByTicket(ticketId),
    queryFn: () => api.pricingRequests.listForTicket(ticketId).then((r) => r.items ?? []),
    enabled: !!ticketId,
  });
  const requests = listQuery.data ?? [];

  const detailQuery = useQuery({
    queryKey: queryKeys.pricingRequestDetail(expandedId),
    queryFn: () => api.pricingRequests.get(expandedId).then((r) => r.pricingRequest),
    enabled: expandedId != null,
  });

  // Shares its query key with detailQuery above when expandedId === editingId
  // (both key off queryKeys.pricingRequestDetail(id)), so expanding a row and
  // then editing it reuses the same cached fetch rather than doubling up.
  const editDetailQuery = useQuery({
    queryKey: queryKeys.pricingRequestDetail(editingId),
    queryFn: () => api.pricingRequests.get(editingId).then((r) => r.pricingRequest),
    enabled: editingId != null,
  });

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingRequestsByTicket(ticketId) });
    queryClient.invalidateQueries({ queryKey: ['pricingRequests', 'detail'] });
    queryClient.invalidateQueries({ queryKey: ['pricingRequests', 'queue'] });
  }

  const submitMutation = useMutation({
    mutationFn: (id) => api.pricingRequests.submit(id),
    onSuccess: invalidate,
  });
  const cancelMutation = useMutation({
    mutationFn: ({ id, reason }) => api.pricingRequests.cancel(id, { reason }),
    onSuccess: () => { invalidate(); setCancelDraft(null); },
  });

  const canCreate = canCreatePricingRequest(user, deal);

  // Defensive re-check before acting (same convention as DealStagePanel's
  // ref-exposed openers): a stale or over-eager caller cannot force the
  // create modal open past the real gate.
  useImperativeHandle(ref, () => ({
    openCreate: () => { if (canCreate) setCreateOpen(true); },
  }));

  return (
    <Panel flush title="คำขอราคา">
      {requests.length === 0 ? (
        <EmptyState
          icon="fileText"
          title="ยังไม่มีคำขอราคา"
          description={canCreate
            ? 'คำขอราคาส่งรายละเอียดสินค้าให้ฝ่ายนำเข้าเสนอราคา — สร้างได้จากปุ่ม “สร้างคำขอราคา” บนแถบด้านบนของหน้า'
            : 'ยังไม่มีคำขอราคาสำหรับดีลนี้'}
        />
      ) : (
        <div className="flex flex-col gap-2 p-4">
          {requests.map((pr) => {
            const status = pricingRequestStatusLabel(pr.status);
            const expanded = expandedId === pr.id;
            const detail = expanded ? detailQuery.data : null;
            return (
              <div key={pr.id} className="overflow-hidden rounded-lg border border-border bg-surface">
                <button
                  type="button"
                  className="flex w-full flex-wrap items-center gap-2 px-3 py-2.5 text-left"
                  onClick={() => setExpandedId(expanded ? null : pr.id)}
                  aria-expanded={expanded}
                >
                  <Icon name={expanded ? 'chevronUp' : 'chevronDown'} size={14} className="shrink-0 text-text-muted" />
                  <code className="text-xs text-text-muted">{pr.requestCode}</code>
                  <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
                  <span className="text-xs text-text-muted">
                    {pricingRequestRecipientLabel(pr.recipientType)}
                    {pr.recipientLabel ? ` · ${pr.recipientLabel}` : ''}
                  </span>
                  <span className="text-xs text-text-muted">{pr.itemCount} รายการ</span>
                  {pr.requiredDate ? (
                    <span className="text-xs text-text-muted">ต้องการภายใน {formatThaiDate(pr.requiredDate)}</span>
                  ) : null}
                  <span className="ml-auto text-xs text-text-muted">
                    {pr.assignedImportName ? `ฝ่ายนำเข้า: ${pr.assignedImportName}` : 'ยังไม่มีผู้รับเรื่อง'}
                  </span>
                </button>

                {(canUpdatePricingRequest(user, pr) || canSubmitPricingRequest(user, pr) || canCancelPricingRequest(user, pr)) ? (
                  <div className="flex flex-wrap items-center gap-2 border-t border-border px-3 py-2">
                    {canUpdatePricingRequest(user, pr) ? (
                      <Button type="button" variant="secondary" onClick={() => setEditingId(pr.id)}>
                        แก้ไขร่าง
                      </Button>
                    ) : null}
                    {canSubmitPricingRequest(user, pr) ? (
                      <Button
                        type="button"
                        variant="secondary"
                        disabled={submitMutation.isPending}
                        onClick={() => submitMutation.mutate(pr.id)}
                      >
                        ส่งให้ฝ่ายนำเข้า
                      </Button>
                    ) : null}
                    {canCancelPricingRequest(user, pr) ? (
                      <Button
                        type="button"
                        variant="secondary"
                        style={{ color: 'var(--color-danger)', borderColor: 'var(--color-danger-border)' }}
                        onClick={() => setCancelDraft({ id: pr.id, reason: '' })}
                      >
                        ยกเลิก
                      </Button>
                    ) : null}
                    {/* Cancel renders its own error inside the modal that stays open on failure;
                        submit is the only action fired straight from this row, so it is the only
                        one that reports here. */}
                    <MutationError mutation={submitMutation} match={submitMutation.variables === pr.id} />
                  </div>
                ) : null}

                {expanded ? (
                  <div className="border-t border-border px-3 py-3">
                    {detailQuery.isLoading ? (
                      <p className="text-xs text-text-muted">กำลังโหลดรายละเอียดคำขอราคา…</p>
                    ) : (
                      <>
                        <div className="flex flex-col gap-1.5">
                          <span className="text-2xs font-bold text-text-muted">รายการสินค้า</span>
                          {(detail?.items ?? []).map((item) => {
                            const productName = [item.brand, item.model].filter(Boolean).join(' ');
                            return (
                              <div key={item.id} className="flex flex-wrap items-center gap-2 text-xs">
                                <strong>{productName || item.productDescription || 'ไม่ระบุชื่อสินค้า'}</strong>
                                {productName && item.productDescription ? (
                                  <span className="text-text-muted">{item.productDescription}</span>
                                ) : null}
                                <span className="text-text-muted">{[item.color, item.texture, item.size].filter(Boolean).join(' · ')}</span>
                                <span className="text-text-muted">{item.requestedQty} {item.requestedUnit}</span>
                              </div>
                            );
                          })}
                        </div>

                        <div className="mt-3 flex flex-col gap-1.5 border-t border-border-subtle pt-3">
                          <span className="text-2xs font-bold text-text-muted">ประวัติ (เฉพาะคำขอราคานี้)</span>
                          {(detail?.events ?? []).map((event) => (
                            <div key={event.id} className="flex flex-wrap items-baseline gap-2 text-xs">
                              <span className="text-text-muted">{formatThaiDate(event.createdAt)}</span>
                              <strong>{EVENT_LABEL[event.eventKind] ?? event.eventKind}</strong>
                              <span className="text-text-muted">{event.actorName}</span>
                              {event.message ? <span className="text-text-muted">— {event.message}</span> : null}
                            </div>
                          ))}
                        </div>
                      </>
                    )}
                  </div>
                ) : null}
              </div>
            );
          })}
        </div>
      )}

      {createOpen ? (
        <PricingRequestCreateModal
          ticketItems={ticketItems}
          deal={deal}
          onClose={() => setCreateOpen(false)}
          onCreated={() => { setCreateOpen(false); invalidate(); }}
          createFn={(payload) => api.pricingRequests.create(ticketId, payload)}
          submitFn={(id) => api.pricingRequests.submit(id)}
          // Wired even in create mode so the modal's duplicate-draft retry
          // guard (Fix 1) can push a retry's edits onto an already-created
          // draft via PUT instead of calling createFn a second time.
          updateFn={(id, payload) => api.pricingRequests.update(id, payload)}
        />
      ) : null}

      {editingId != null ? (
        editDetailQuery.data ? (
          <PricingRequestCreateModal
            mode="edit"
            initialValue={editDetailQuery.data}
            onClose={() => setEditingId(null)}
            onCreated={() => { setEditingId(null); invalidate(); }}
            updateFn={(id, payload) => api.pricingRequests.update(id, payload)}
          />
        ) : (
          <Modal title="แก้ไขร่างคำขอราคา" onClose={() => setEditingId(null)}>
            <p className="text-xs text-text-muted">กำลังโหลดร่างคำขอราคา…</p>
          </Modal>
        )
      ) : null}

      {cancelDraft ? (
        <Modal
          title="ยกเลิกคำขอราคา"
          onClose={() => setCancelDraft(null)}
          footer={(
            <>
              <Button type="button" variant="secondary" onClick={() => setCancelDraft(null)}>ปิด</Button>
              <Button
                type="button"
                variant="primary"
                disabled={!cancelDraft.reason.trim() || cancelMutation.isPending}
                onClick={() => cancelMutation.mutate({ id: cancelDraft.id, reason: cancelDraft.reason.trim() })}
              >
                ยืนยันยกเลิก
              </Button>
            </>
          )}
        >
          <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
            เหตุผล *
            <textarea
              className="min-h-24"
              value={cancelDraft.reason}
              onChange={(e) => setCancelDraft((d) => ({ ...d, reason: e.target.value }))}
            />
          </label>
          <MutationError mutation={cancelMutation} />
        </Modal>
      ) : null}
    </Panel>
  );
});
