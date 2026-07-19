import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatThaiDate, pricingRequestStatusLabel } from '../../utils/format.js';
import {
  canCancelPricingRequest, canCreatePricingRequest, canRespondInformation,
  canSubmitPricingRequest, pricingRequestRecipientLabel, quantityTypeLabel,
} from './pricingRequestMeta.js';
import { PricingRequestCreateModal } from './PricingRequestCreateModal.jsx';

// Mirrors PricingRequestEventKind — labels for this request's own event log
// (rendered here, per-request, NOT merged into the deal's main timeline).
const EVENT_LABEL = {
  PRICING_REQUEST_CREATED: 'สร้างคำขอราคา (ร่าง)',
  PRICING_REQUEST_UPDATED: 'แก้ไขคำขอราคา',
  PRICING_REQUEST_SUBMITTED: 'ส่งให้ Import แล้ว',
  PRICING_REQUEST_PICKED_UP: 'Import รับเรื่องแล้ว',
  MORE_INFO_REQUESTED: 'Import ขอข้อมูลเพิ่มเติม',
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
 * create-button gate; this component does not know about ticket status/stage.
 */
export function PricingRequestPanel({ ticketId, deal, ticketItems = [], user }) {
  const queryClient = useQueryClient();
  const [createOpen, setCreateOpen] = useState(false);
  const [expandedId, setExpandedId] = useState(null);
  const [respondDraft, setRespondDraft] = useState(null); // { id, response }
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
  const respondMutation = useMutation({
    mutationFn: ({ id, response }) => api.pricingRequests.respondInformation(id, { response }),
    onSuccess: () => { invalidate(); setRespondDraft(null); },
  });

  const canCreate = canCreatePricingRequest(user, deal);

  return (
    <section className="table-panel">
      <div className="panel-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2>ใบขอราคา (Pricing Request)</h2>
        {canCreate ? (
          <button type="button" className="primary-button" onClick={() => setCreateOpen(true)}>
            <Icon name="plus" size={14} />
            สร้างใบขอราคา
          </button>
        ) : null}
      </div>

      {requests.length === 0 ? (
        <EmptyState
          icon="fileText"
          title="ยังไม่มีใบขอราคา"
          description={canCreate ? 'สร้างใบขอราคาเพื่อส่งให้ฝ่ายนำเข้าเสนอราคา' : 'ยังไม่มีใบขอราคาสำหรับดีลนี้'}
        />
      ) : (
        <div className="flex flex-col gap-2 p-3">
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
                    {pr.assignedImportName ? `Import: ${pr.assignedImportName}` : 'ยังไม่มีผู้รับเรื่อง'}
                  </span>
                </button>

                {(canSubmitPricingRequest(user, pr) || canRespondInformation(user, pr) || canCancelPricingRequest(user, pr)) ? (
                  <div className="flex flex-wrap items-center gap-2 border-t border-border px-3 py-2">
                    {canSubmitPricingRequest(user, pr) ? (
                      <button
                        type="button"
                        className="secondary-button"
                        disabled={submitMutation.isPending}
                        onClick={() => submitMutation.mutate(pr.id)}
                      >
                        ส่งให้ Import
                      </button>
                    ) : null}
                    {canRespondInformation(user, pr) ? (
                      <button type="button" className="secondary-button" onClick={() => setRespondDraft({ id: pr.id, response: '' })}>
                        ตอบข้อมูลเพิ่มเติม
                      </button>
                    ) : null}
                    {canCancelPricingRequest(user, pr) ? (
                      <button
                        type="button"
                        className="secondary-button"
                        style={{ color: 'var(--color-danger)', borderColor: 'var(--color-danger-border)' }}
                        onClick={() => setCancelDraft({ id: pr.id, reason: '' })}
                      >
                        ยกเลิก
                      </button>
                    ) : null}
                  </div>
                ) : null}

                {expanded ? (
                  <div className="border-t border-border px-3 py-3">
                    {detailQuery.isLoading ? (
                      <p className="text-xs text-text-muted">กำลังโหลด...</p>
                    ) : (
                      <>
                        <div className="flex flex-col gap-1.5">
                          <span className="text-2xs font-bold text-text-muted">รายการสินค้า</span>
                          {(detail?.items ?? []).map((item) => (
                            <div key={item.id} className="flex flex-wrap items-center gap-2 text-xs">
                              <strong>{item.brand} {item.model}</strong>
                              <span className="text-text-muted">{[item.color, item.texture, item.size].filter(Boolean).join(' · ')}</span>
                              <span className="text-text-muted">{item.requestedQty} {item.requestedUnit}</span>
                              <StatusBadge tone="neutral">{quantityTypeLabel(item.quantityType)}</StatusBadge>
                              {item.targetDeliveryDate ? (
                                <span className="text-text-muted">ส่งมอบ {formatThaiDate(item.targetDeliveryDate)}</span>
                              ) : null}
                            </div>
                          ))}
                        </div>

                        <div className="mt-3 flex flex-col gap-1.5 border-t border-border-subtle pt-3">
                          <span className="text-2xs font-bold text-text-muted">ประวัติ (เฉพาะใบขอราคานี้)</span>
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
          onClose={() => setCreateOpen(false)}
          onCreated={() => { setCreateOpen(false); invalidate(); }}
          createFn={(payload) => api.pricingRequests.create(ticketId, payload)}
          submitFn={(id) => api.pricingRequests.submit(id)}
        />
      ) : null}

      {respondDraft ? (
        <Modal
          title="ตอบข้อมูลเพิ่มเติม"
          onClose={() => setRespondDraft(null)}
          footer={(
            <>
              <button type="button" className="secondary-button" onClick={() => setRespondDraft(null)}>ยกเลิก</button>
              <button
                type="button"
                className="primary-button"
                disabled={!respondDraft.response.trim() || respondMutation.isPending}
                onClick={() => respondMutation.mutate({ id: respondDraft.id, response: respondDraft.response.trim() })}
              >
                บันทึก
              </button>
            </>
          )}
        >
          <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
            คำตอบ *
            <textarea
              className="min-h-24"
              value={respondDraft.response}
              onChange={(e) => setRespondDraft((d) => ({ ...d, response: e.target.value }))}
            />
          </label>
        </Modal>
      ) : null}

      {cancelDraft ? (
        <Modal
          title="ยกเลิกใบขอราคา"
          onClose={() => setCancelDraft(null)}
          footer={(
            <>
              <button type="button" className="secondary-button" onClick={() => setCancelDraft(null)}>ปิด</button>
              <button
                type="button"
                className="primary-button"
                disabled={!cancelDraft.reason.trim() || cancelMutation.isPending}
                onClick={() => cancelMutation.mutate({ id: cancelDraft.id, reason: cancelDraft.reason.trim() })}
              >
                ยืนยันยกเลิก
              </button>
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
        </Modal>
      ) : null}
    </section>
  );
}
