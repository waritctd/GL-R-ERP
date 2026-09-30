import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button, buttonVariants } from '../../components/common/Button.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { SkeletonText } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import {
  dealLifecycleLabel, dealStageLabel, formatThaiDate, fulfilmentStatusLabel,
} from '../../utils/format.js';
import { cn } from '../../utils/cn.js';
import { ImportStatusStrip } from '../importProgress/ImportStatusStrip.jsx';
import { importStepIndex } from '../importProgress/importSteps.js';
import { DealAttachmentsPanel } from '../tickets/DealAttachmentsPanel.jsx';
import { ImportRequestFactoryCard } from '../tickets/ImportRequestFactoryCard.jsx';
import { IMPORT_ACTION_LABELS, nextImportAction } from '../tickets/importActions.js';

const NOOP = () => {};

/**
 * The three LEGACY deal-level transitions import performs on this page. `nextImportAction` routes
 * them here (importActions.js) because DealFulfilmentPanel — where they used to live — is closed to
 * import. `markIrSent` deliberately is NOT here (it routes to /fulfilment) and neither is
 * `recordDelivery` (delivery is Sales's; this page stays read-only for it).
 */
const LEGACY_ACTIONS = {
  issueImportRequest: {
    call: (ticketId) => api.tickets.issueImportRequest(ticketId),
    toast: 'ออกคำขอนำเข้าแล้ว',
  },
  markShipping: {
    call: (ticketId) => api.tickets.markShipping(ticketId),
    toast: 'สินค้าอยู่ระหว่างขนส่ง',
  },
  markGoodsReceived: {
    call: (ticketId) => api.tickets.markGoodsReceived(ticketId),
    toast: 'รับสินค้าแล้ว',
  },
};

function formatQty(value) {
  if (value == null || value === '') return '-';
  const n = Number(value);
  return Number.isFinite(n) ? n.toLocaleString('en-US') : String(value);
}

function itemTitle(item) {
  return [item.brand, item.model].filter(Boolean).join(' ') || item.code || `รายการ ${item.id}`;
}

function itemSpec(item) {
  return [item.color, item.texture, item.size].filter(Boolean).join(' · ');
}

/**
 * ดีลนำเข้า — import's OWN per-deal page (`/import/deals/:ticketId`).
 *
 * Import is refused the whole-deal read (GET /api/tickets/{id} carries the customer price and the
 * quotation chain), so this page never touches it: everything comes from
 * GET /api/import/deals/{id} (ImportDealController), an import-only projection that has NO price,
 * cost or margin field on any line. This component therefore renders only the fields it names —
 * it never spreads a DTO — so a money field could not reach the screen even if one appeared.
 *
 * Sections, top to bottom: header → at-a-glance status strip → per-factory ใบขอซื้อ tracking (the
 * focal section — the SAME `ImportRequestFactoryCard` the deal tab's "ใบขอซื้อรายโรงงาน" section
 * renders; a LEGACY deal with no per-factory rows shows the deal-level import action here instead)
 * → delivery status (READ-ONLY: delivery is Sales's) → items → comments → attachments.
 *
 * LEGACY deals: a deal that never started per-factory tracking still advances through the old
 * deal-level chain (issue IR → shipping → goods received). `nextImportAction` routes those three
 * here; the page performs them, gated on "no live per-factory rows" because the backend 409s them
 * once a deal is IR-tracked (TicketService.markShipping/markGoodsReceived).
 *
 * SYNC with the deal tab and /fulfilment: the per-factory mutations live in
 * ImportRequestFactoryCard, whose `invalidate()` refreshes `queryKeys.importDeal` alongside the
 * storedImportRequests / ticketDetail caches, so a step advanced here shows on the other surfaces
 * and vice versa.
 *
 * The role props handed to the card mirror DealFulfilmentPanel's matrix exactly
 * (ImportRequestService): import advances steps, sets lead time after issue and works the order
 * email; issue/revise/delete and the printed footer stay with the owning rep / CEO. Import is
 * neither, so it gets none of those controls.
 */
export function ImportDealPage({ user, showToast }) {
  const { ticketId } = useParams();
  const queryClient = useQueryClient();
  const role = user?.role;
  const [commentText, setCommentText] = useState('');

  const dealQuery = useQuery({
    queryKey: queryKeys.importDeal(ticketId),
    queryFn: () => api.importDeals.get(ticketId).then((r) => r.deal),
    enabled: !!ticketId,
    // A 403/404 is an answer, not a blip — retrying it only delays the message.
    retry: false,
  });
  const deal = dealQuery.data;

  // Documents: import may now READ every type on an in-scope deal (TicketAccessPolicy). Fetched
  // only once the deal itself resolved, so an out-of-scope deal never fires a second 403.
  const attachmentsQuery = useQuery({
    queryKey: queryKeys.ticketAttachments(ticketId),
    queryFn: () => api.attachments.list(ticketId).then((r) => r.attachments ?? []),
    enabled: !!ticketId && dealQuery.isSuccess,
  });

  const commentMutation = useMutation({
    mutationFn: (message) => api.tickets.comment(ticketId, { message }),
    onSuccess: () => {
      showToast?.('success', 'เพิ่มความคิดเห็นแล้ว');
      setCommentText('');
      queryClient.invalidateQueries({ queryKey: queryKeys.importDeal(ticketId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
    },
    onError: (err) => showToast?.('error', err.message || 'ดำเนินการไม่สำเร็จ'),
  });

  // Legacy deal-level transition (issue IR / shipping / goods received). Same cache set as the
  // per-factory card's invalidate() so the deal tab, /fulfilment and the worklist all move together.
  const legacyMutation = useMutation({
    mutationFn: (code) => LEGACY_ACTIONS[code].call(ticketId),
    onSuccess: (_data, code) => {
      showToast?.('success', LEGACY_ACTIONS[code].toast);
      queryClient.invalidateQueries({ queryKey: queryKeys.importDeal(ticketId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketActions(ticketId) });
      queryClient.invalidateQueries({ queryKey: ['tickets', 'list'] });
      queryClient.invalidateQueries({ queryKey: queryKeys.dashboardSummary() });
      queryClient.invalidateQueries({ queryKey: queryKeys.notifications() });
    },
    onError: (err) => showToast?.('error', err.message || 'ดำเนินการไม่สำเร็จ'),
  });

  function submitComment() {
    const message = commentText.trim();
    if (!message) return;
    commentMutation.mutate(message);
  }

  if (dealQuery.isLoading) {
    return (
      <PageStack>
        <Panel aria-busy="true" aria-label="กำลังโหลดดีล">
          <SkeletonText lines={6} />
        </Panel>
      </PageStack>
    );
  }

  if (dealQuery.isError) {
    const status = dealQuery.error?.status;
    return (
      <PageStack>
        <Panel data-testid={status === 403 ? 'import-deal-forbidden' : status === 404 ? 'import-deal-not-found' : 'import-deal-error'}>
          <EmptyState
            icon="search"
            title={status === 403 ? 'ดีลนี้ไม่อยู่ในขอบเขตงานนำเข้าของคุณ'
              : status === 404 ? 'ไม่พบดีลนี้'
                : 'โหลดดีลไม่สำเร็จ'}
            description={status === 403 ? 'ดีลที่ยังไม่ถึงขั้นจัดซื้อและนำเข้าจะยังไม่ปรากฏในงานนำเข้า — กลับไปเลือกจากรายการงานนำเข้า'
              : status === 404 ? 'ตรวจสอบลิงก์อีกครั้ง หรือกลับไปเลือกจากรายการงานนำเข้า'
                : (dealQuery.error?.message || 'ลองใหม่อีกครั้ง')}
          />
          <div className="mt-2 flex flex-wrap justify-center gap-2">
            {/* A real button-shaped link (not a bare underlined anchor) so it clears the 44px touch floor. */}
            <Link to="/fulfilment" className={cn(buttonVariants({ variant: 'secondary', size: 'sm' }), 'no-underline')}>กลับไปงานนำเข้า</Link>
            {status !== 403 && status !== 404 ? (
              <Button type="button" size="sm" variant="secondary" onClick={() => dealQuery.refetch()}>ลองใหม่</Button>
            ) : null}
          </div>
        </Panel>
      </PageStack>
    );
  }

  // ── per-factory tracking (same derivation as DealFulfilmentPanel's) ───────────────────────────
  const liveRows = (deal.importRequests ?? [])
    .filter((r) => r.status !== 'SUPERSEDED')
    .sort((a, b) => (a.factoryName ?? '').localeCompare(b.factoryName ?? '', 'th') || a.version - b.version);
  // N = issued rows (they carry an importStep); X = those at AWAITING_CUSTOMS (S16) or later.
  const issuedRows = liveRows.filter((r) => r.importStep != null);
  const arrivedCount = issuedRows
    .filter((r) => importStepIndex(r.importStep) >= importStepIndex('AWAITING_CUSTOMS')).length;

  const isCeo = role === 'ceo';
  const canAdvance = role === 'import' || isCeo;
  const canEmailWrite = isCeo || role === 'import';

  // Legacy = no LIVE per-factory rows AND the deal-level chain has a step import performs here.
  const nextAction = nextImportAction(deal);
  const legacyCode = canAdvance && liveRows.length === 0 && nextAction && LEGACY_ACTIONS[nextAction.code]
    ? nextAction.code
    : null;

  const stage = dealStageLabel(deal.salesStage);
  const lifecycle = dealLifecycleLabel(deal.lifecycle ?? 'ACTIVE');
  const fulfilment = fulfilmentStatusLabel(deal.fulfillmentStatus);
  const items = deal.items ?? [];
  const comments = deal.comments ?? [];
  const attachments = attachmentsQuery.data ?? [];

  // Overall delivery = the mean of the per-item bars below (each item's delivered/ordered, capped at
  // 100%). Deliberately NOT a sum of quantities: lines are in different units (ตร.ม. / กล่อง).
  const deliveryPct = items.length > 0
    ? Math.round(items.reduce((sum, item) => {
      const ordered = Number(item.qty) || 0;
      const delivered = Number(item.qtyDelivered) || 0;
      return sum + (ordered > 0 ? Math.min(100, (delivered / ordered) * 100) : 0);
    }, 0) / items.length)
    : null;

  return (
    <PageStack className="gap-5">
      <PageHeader
        breadcrumbs={[{ label: 'งานนำเข้า', to: '/fulfilment' }, { label: deal.code }]}
        eyebrow={deal.code}
        title={deal.customerName || deal.title}
        subtitle={deal.projectName || deal.title}
        context={(
          <>
            <StatusBadge tone={stage.tone}>{stage.label}</StatusBadge>
            {deal.lifecycle && deal.lifecycle !== 'ACTIVE' ? (
              <StatusBadge tone={lifecycle.tone}>{lifecycle.label}</StatusBadge>
            ) : null}
            {deal.createdByName ? (
              <span className="self-center text-xs text-text-muted">ฝ่ายขาย: {deal.createdByName}</span>
            ) : null}
          </>
        )}
      />

      {/* At-a-glance: the SAME strip the deal tab's Step-1 header shows (fulfilment status → ถึงไทย
          X/N โรงงาน → ส่งมอบ N%) — shared so the two surfaces read as one system. */}
      <ImportStatusStrip
        className="-mt-1"
        testIdPrefix="import-deal"
        fulfilment={fulfilment}
        arrivedCount={arrivedCount}
        issuedCount={issuedRows.length}
        deliveryPct={deliveryPct}
      />

      {/* The focal section: per-factory tracking. Same padding as the supporting panels below, but a
          stronger rule and the full-size title (theirs are quieted), so the eye lands here first. */}
      <Panel
        title="ใบขอซื้อรายโรงงาน"
        data-testid="import-deal-tracking"
        className="border-border-strong p-4 mobile:p-3"
      >
        {liveRows.length === 0 ? (
          <div className="grid gap-4">
            <p className="m-0 text-sm text-text-muted" data-testid="import-deal-no-ir">
              ดีลนี้ยังไม่มีใบขอซื้อรายโรงงาน — ฝ่ายขายเป็นผู้สร้างและออกเลขใบขอซื้อ แล้วฝ่ายนำเข้าจึงเลื่อนสถานะรายโรงงานได้ที่นี่
            </p>
            {legacyCode ? (
              <div
                className="flex flex-wrap items-center justify-between gap-x-4 gap-y-3 rounded-md border border-dashed border-border-muted bg-surface-muted p-4"
                data-testid="import-deal-legacy-action"
              >
                <div className="min-w-0 flex-1 basis-56">
                  <h3 className="m-0 text-sm font-bold text-text">ดำเนินการนำเข้า (ดีลรูปแบบเดิม)</h3>
                  <p className="m-0 mt-1 text-xs text-text-muted">
                    ดีลนี้ยังไม่ใช้ใบขอซื้อรายโรงงาน — เลื่อนสถานะทั้งดีลทีละขั้นด้วยปุ่มนี้
                  </p>
                </div>
                <Button
                  type="button"
                  variant="primary"
                  className="whitespace-nowrap"
                  loading={legacyMutation.isPending}
                  onClick={() => legacyMutation.mutate(legacyCode)}
                  data-testid="import-deal-legacy-action-btn"
                >
                  {IMPORT_ACTION_LABELS[legacyCode]}
                </Button>
              </div>
            ) : null}
          </div>
        ) : (
          <div className="flex flex-col gap-3">
            {liveRows.map((row) => (
              <ImportRequestFactoryCard
                key={row.id}
                row={row}
                ticketId={ticketId}
                canFullWrite={isCeo}
                canFooterWrite={isCeo}
                canAdvance={canAdvance}
                canEmailWrite={canEmailWrite}
                showToast={showToast}
                showInlineDownload
              />
            ))}
          </div>
        )}
      </Panel>

      {/* Supporting sections (delivery → items → comments → attachments): one wrapper so every
          panel title — including DealAttachmentsPanel's, which this page does not own — steps down
          to the same quiet size (and every panel to the same 16px/12px padding) and the tracker above is the only thing with a full-size heading. */}
      <div className="grid min-w-0 gap-5 [&>section]:p-4 mobile:[&>section]:p-3 [&_h2]:text-md [&_h2]:text-text-secondary" data-testid="import-deal-supporting">
        {/* Delivery is Sales's (owner ruling 2026-08-17): status only, no write control of any kind.
            The status badge stays in the header — the delivery panel is read on its own, and its own
            test pins the label here. */}
        <Panel
          title="สถานะการส่งมอบ"
          data-testid="import-deal-delivery"
          className="p-4 mobile:p-3"
          actions={<StatusBadge tone={fulfilment.tone}>{fulfilment.label}</StatusBadge>}
        >
          {items.length === 0 ? (
            <p className="m-0 text-sm text-text-muted">ยังไม่มีรายการสินค้า</p>
          ) : (
            <ul className="m-0 grid list-none gap-2 p-0">
              {items.map((item) => {
                const ordered = Number(item.qty) || 0;
                const delivered = Number(item.qtyDelivered) || 0;
                const pct = ordered > 0 ? Math.min(100, Math.round((delivered / ordered) * 100)) : 0;
                return (
                  <li
                    key={item.id}
                    className="grid gap-1.5 rounded-md border border-border-subtle p-3"
                    data-testid={`import-deal-delivery-item-${item.id}`}
                  >
                    <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5">
                      <strong className="min-w-0 text-sm">{itemTitle(item)}</strong>
                      <span className="text-xs font-bold tabular-nums text-text-secondary">
                        ส่งแล้ว {formatQty(item.qtyDelivered ?? 0)} / {formatQty(item.qty)} {item.unit ?? ''}
                      </span>
                    </div>
                    <div className="h-1.5 overflow-hidden rounded-full bg-surface-subtle" aria-hidden="true">
                      <div className="h-full rounded-full bg-success" style={{ width: `${pct}%` }} />
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </Panel>

        <Panel title="รายการสินค้า" data-testid="import-deal-items" className="p-4 mobile:p-3">
          {items.length === 0 ? (
            <p className="m-0 text-sm text-text-muted">ยังไม่มีรายการสินค้า</p>
          ) : (
            <ul className="m-0 grid list-none gap-2 p-0">
              {items.map((item) => (
                <li
                  key={item.id}
                  className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5 border-t border-border-subtle pt-2 first:border-t-0 first:pt-0"
                >
                  <span className="min-w-0">
                    <strong className="block text-sm">{itemTitle(item)}</strong>
                    <span className="block text-xs text-text-muted">
                      {itemSpec(item) || '-'}
                      {item.code ? <> · <code>{item.code}</code></> : null}
                    </span>
                  </span>
                  <span className="text-sm font-bold tabular-nums">
                    {formatQty(item.qty)} {item.unit ?? ''}
                    {item.qtySqm != null ? (
                      <span className="ml-1 text-xs font-normal text-text-muted">({formatQty(item.qtySqm)} ตร.ม.)</span>
                    ) : null}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Panel>

        <Panel title="ความคิดเห็น" data-testid="import-deal-comments" className="p-4 mobile:p-3">
          {comments.length === 0 ? (
            <p className="m-0 mb-3 text-sm text-text-muted">ยังไม่มีความคิดเห็น</p>
          ) : (
            <ul className="m-0 mb-3 grid list-none gap-2 p-0">
              {comments.map((c) => (
                <li key={c.id} className="rounded-md bg-surface-subtle p-3">
                  <div className="flex flex-wrap items-baseline gap-x-2 text-2xs text-text-muted">
                    <strong className="text-xs text-text">{c.actorName}</strong>
                    <span>{formatThaiDate(c.createdAt)}</span>
                  </div>
                  <p className="m-0 mt-1 whitespace-pre-wrap text-sm [overflow-wrap:anywhere]">{c.message}</p>
                </li>
              ))}
            </ul>
          )}
          <label className="grid gap-1.5 text-xs font-bold text-text-secondary">
            เพิ่มความคิดเห็น
            <textarea
              className="form-input min-h-20 text-sm font-normal"
              value={commentText}
              onChange={(e) => setCommentText(e.target.value)}
              data-testid="import-deal-comment-input"
            />
          </label>
          <div className="mt-2 flex justify-end">
            <Button
              type="button"
              variant="primary"
              disabled={commentMutation.isPending || !commentText.trim()}
              onClick={submitComment}
              data-testid="import-deal-comment-submit"
            >
              ส่งความคิดเห็น
            </Button>
          </div>
        </Panel>

        {/* Read-only: import lists and downloads; upload/delete stay with the deal's participants. */}
        <DealAttachmentsPanel
          attachments={attachments}
          attachLoading={attachmentsQuery.isLoading}
          canManageDocuments={false}
          uploadingFile={false}
          onUploadAttachment={NOOP}
          onDeleteAttachment={NOOP}
          canUpload={false}
          notTerminal={false}
          user={user}
          emptyDescription="ยังไม่มีไฟล์แนบสำหรับดีลนี้"
        />
      </div>
    </PageStack>
  );
}
