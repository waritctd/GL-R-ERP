import { useMemo, useState } from 'react';
import { useQueries, useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { downloadBlob } from '../../utils/download.js';
import { formatMoney, formatThaiDate, quotationStatusLabel } from '../../utils/format.js';
import { canViewCustomerQuotation } from '../pricingRequests/pricingRequestMeta.js';

/**
 * Slice D ("the เอกสาร document register"): one read-only roll-up of every document a deal can
 * produce — customer quotations (both the PricingRequest/CustomerQuotation chain and the legacy
 * ticket-native rows), the deposit notice, the remaining-invoice download, and formal attachments
 * (PO / signed quotation / tax invoice / other) — rendered together in the เอกสาร tab.
 *
 * Deliberately a ROLL-UP, not a replacement: DealQuotationPanel/DealLegacyQuotations (also in this
 * same tab), DealDepositPanel (การเงิน), the docActions remaining-invoice button (DealStagePanel),
 * and DealAttachmentsPanel (ประวัติ) all keep their existing create/issue/upload/delete affordances
 * exactly where they are — this component only ever reads and downloads. Do not add a mutation
 * here; if a document needs an action, it belongs on the panel that already owns that document's
 * lifecycle.
 *
 * THE AUTHORIZATION SHAPE (the reason this file exists, not just a UI convenience): a single
 * "can this role see the เอกสาร tab" gate cannot express who may see WHICH row, because the three
 * document families are governed by three different real backend gates that do not coincide:
 *
 *   - quotation rows (customer-quotation chain + legacy embedded ticket.quotations): the existing
 *     pricing-and-quotation role gate this tab already used pre-Slice-D — `canViewQuotations`
 *     below, unchanged from the tab's old `Boolean(sections.dealQuotation || sections.quotation) &&
 *     PRICING_AND_QUOTATION_ROLES.has(role)` predicate (ticketDetailTabs.js used to enforce this at
 *     the TAB level; Slice D moves it in here, per row family, verbatim). Mirrors
 *     CustomerQuotationService.VIEW_ROLES (quotation_salesManagerCeoImportCanAllList /
 *     quotation_accountCannotListCustomerQuotations) for the new chain, and
 *     TicketService#projectForRole's unconditional (role-only, NOT participant-scoped) stripping
 *     of import's embedded `ticket.quotations` for the legacy rows.
 *
 *     KNOWN GAP, preserved (see ticketDetailTabs.js's own header comment, unchanged by this
 *     slice): `sections.quotation`/`sections.dealQuotation` are `false` for `import` regardless of
 *     participation, even though `CustomerQuotationService.VIEW_ROLES` genuinely includes `import`
 *     — so an import rep who has picked this deal up (is its assignee) sees ZERO quotation rows
 *     here even though the raw service call would not 403 them. That is a deliberate, pre-existing
 *     frontend choice (salesViewScope.js is out of bounds for this branch), not something this
 *     slice fixes or should quietly "correct" by widening the gate — doing so would hand an
 *     assignee-only import rep the approved customer price. The embedded-legacy-quotations half of
 *     this IS backend-enforced unconditionally (`projectForRole`), independent of participation.
 *
 *   - the deposit notice AND the remaining invoice: BOTH are backed by the exact same
 *     `DepositNoticeService#requireTicketViewer` gate (`listByTicket`/`getRemainingInvoiceXlsx`
 *     both call it first, before any status check) — sales scoped to the deal it owns, ceo/
 *     account/sales_manager unconditionally, and `import` refused OUTRIGHT, even as this deal's
 *     assignee. This is `sections.depositNotice` exactly (salesViewScope.js already encodes the
 *     same role set) — reused here rather than re-derived, since re-deriving it would risk drifting
 *     from the money tab's own gate for the exact same data.
 *
 *     THIS DELIBERATELY DIVERGES FROM THE OTHER ROW FAMILY'S SHAPE: unlike attachments below,
 *     import is refused here even when it IS a participant (assignedToId). Grouping the remaining
 *     invoice with attachments under one "participant OR canViewTicketDocuments" predicate — the
 *     shape this branch's brief originally sketched — would have been WRONG: it would let an
 *     import assignee download the remaining-invoice xlsx, which the real endpoint 403s
 *     unconditionally. See this branch's PR body for the full note; this is reported, not silently
 *     patched over.
 *
 *   - attachments (PO / signed quotation / tax invoice / other): `canViewDocumentsTab`, passed down
 *     unchanged from TicketDetailPage — participant (createdById/assignedToId) OR
 *     ROLE_PERMISSIONS.canViewTicketDocuments (ceo/account/sales_manager), mirroring
 *     TicketAccessPolicy.canViewDocuments exactly. This is the ONE row family where a participant
 *     import rep (this deal's assignee) genuinely gains access under this slice — reusing the
 *     `attachments` the parent already fetched (query already gated at its own `enabled`, so it
 *     never fires for a viewer who would 403) rather than re-fetching here.
 *
 * A role/section combination admitted to NONE of the three families renders one honest
 * "ไม่มีเอกสารให้แสดงในมุมมองนี้" empty state instead of three silently-omitted sections — the
 * brief's own warning: a swallowed 403 rendering an empty register reads as "no documents exist",
 * which is worse than an honest "not available in this view".
 */
export function DealDocumentRegister({
  ticketId,
  user,
  summary,
  sections,
  canViewPricingRequests,
  canViewDocumentsTab,
  pricingRequests = [],
  legacyQuotations = [],
  attachments = [],
  attachLoading = false,
}) {
  const [busyKey, setBusyKey] = useState(null);

  // Unchanged from the pre-Slice-D `documents` tab gate (ticketDetailTabs.js) — see this file's
  // own header comment for why it stays a two-part role+section check rather than collapsing to
  // just `canViewPricingRequests`.
  const canViewQuotations = canViewPricingRequests && Boolean(sections?.dealQuotation || sections?.quotation);
  // Mirrors DepositNoticeService#requireTicketViewer via salesViewScope's own `depositNotice`
  // section id (see header comment) — governs BOTH the deposit notice and the remaining invoice.
  const canViewDepositAndInvoice = Boolean(sections?.depositNotice);

  const eligiblePricingRequests = useMemo(
    () => (canViewQuotations ? pricingRequests.filter((pr) => canViewCustomerQuotation(user, pr)) : []),
    [canViewQuotations, pricingRequests, user],
  );

  // One listCustomerQuotations call per eligible pricing request — there is no ticket-level "all
  // customer quotations for this deal" endpoint (only the per-pricing-request one DealQuotationPanel
  // already uses), so a complete register necessarily fans out across every request this viewer may
  // read, not just the single "currently relevant" one that panel narrows to.
  const customerQuotationQueries = useQueries({
    queries: eligiblePricingRequests.map((pr) => ({
      queryKey: queryKeys.customerQuotations(pr.id),
      queryFn: () => api.pricingRequests.listCustomerQuotations(pr.id).then((r) => r.items ?? []),
      enabled: canViewQuotations,
    })),
  });
  const customerQuotationsLoading = canViewQuotations && customerQuotationQueries.some((q) => q.isLoading);
  const customerQuotationRows = useMemo(() => customerQuotationQueries.flatMap((q, idx) => (
    (q.data ?? []).map((doc) => ({ ...doc, pricingRequestId: eligiblePricingRequests[idx]?.id }))
  )), [customerQuotationQueries, eligiblePricingRequests]);

  const depositNoticesQuery = useQuery({
    queryKey: queryKeys.depositNotices(ticketId),
    queryFn: () => api.depositNotices.listByTicket(ticketId).then((r) => r.depositNotices ?? []),
    // Never fire this for a viewer DepositNoticeService#requireTicketViewer would 403 — same
    // reasoning as TicketDetailPage's own `attachmentsQuery`/`activitiesQuery` `enabled` gates.
    enabled: Boolean(ticketId) && canViewDepositAndInvoice,
  });
  const depositNotices = depositNoticesQuery.data ?? [];

  const remainingInvoiceReady = summary?.status === 'quotation_issued' && summary?.fulfillmentStatus === 'GOODS_RECEIVED';

  async function handleDownload(key, run) {
    setBusyKey(key);
    try {
      const blob = await run();
      return blob;
    } finally {
      setBusyKey(null);
    }
  }

  async function downloadLegacyQuotation(q, format) {
    const key = `legacy-${q.id}-${format}`;
    const blob = await handleDownload(key, () => (format === 'pdf'
      ? api.tickets.downloadQuotationPdf(ticketId, q.id)
      : api.tickets.downloadQuotationXlsx(ticketId, q.id)));
    downloadBlob(blob, q.number ?? `quotation-${q.id}`, format);
  }

  async function downloadCustomerQuotation(q, format) {
    const key = `chain-${q.id}-${format}`;
    const blob = await handleDownload(key, () => (format === 'pdf'
      ? api.pricingRequests.downloadCustomerQuotationPdf(q.id)
      : api.pricingRequests.downloadCustomerQuotationXlsx(q.id)));
    downloadBlob(blob, q.number ?? `quotation-${q.id}`, format);
  }

  async function downloadDepositNotice(doc, format) {
    const key = `deposit-${doc.id}-${format}`;
    const blob = await handleDownload(key, () => (format === 'pdf'
      ? api.depositNotices.downloadPdf(doc.id)
      : api.depositNotices.downloadXlsx(doc.id)));
    downloadBlob(blob, doc.docNumber ?? 'deposit-notice', format);
  }

  async function downloadRemainingInvoice() {
    const key = 'remaining-invoice';
    const blob = await handleDownload(key, () => api.tickets.downloadRemainingInvoice(ticketId));
    downloadBlob(blob, `remaining-invoice-${ticketId}`, 'xlsx');
  }

  const hasAnyVisibleSection = canViewQuotations || canViewDepositAndInvoice || canViewDocumentsTab;

  if (!hasAnyVisibleSection) {
    return (
      <Panel title="เอกสารของดีล" data-testid="deal-document-register">
        <div className="p-4">
          <EmptyState icon="fileText" title="ไม่มีเอกสารให้แสดงในมุมมองนี้" description="สิทธิ์การเข้าถึงของบทบาทนี้ไม่ครอบคลุมเอกสารของดีลนี้" />
        </div>
      </Panel>
    );
  }

  return (
    <Panel flush className="flex flex-col gap-4" title="เอกสารของดีล" data-testid="deal-document-register">
      {canViewQuotations ? (
        <div className="flex flex-col gap-2 rounded-md border border-border bg-surface p-3" data-testid="register-quotations">
          <strong className="text-sm">ใบเสนอราคา</strong>
          {customerQuotationsLoading && customerQuotationRows.length === 0 && legacyQuotations.length === 0 ? (
            // Only block on the async chain-quotation fetch while there is
            // NOTHING else to show yet — legacy rows arrive as a prop (no
            // fetch of their own) and must never wait on an unrelated query.
            <Skeleton height={40} />
          ) : (customerQuotationRows.length === 0 && legacyQuotations.length === 0) ? (
            <p className="text-xs text-text-muted">ยังไม่มีใบเสนอราคาสำหรับดีลนี้</p>
          ) : (
            <div className="flex flex-col gap-1.5">
              {customerQuotationRows.map((q) => {
                const status = quotationStatusLabel(q.docStatus);
                return (
                  <DocumentRow
                    key={`chain-${q.id}`}
                    icon="fileText"
                    title={q.number ?? `ร่างใบเสนอราคา #${q.id}`}
                    meta={`${formatMoney(q.totalAmount)} · ครั้งที่ ${q.quotationRevisionNo ?? 1}`}
                    status={status}
                    actions={q.docStatus === 'ISSUED' || q.docStatus === 'ACCEPTED' ? [
                      { label: 'PDF', busy: busyKey === `chain-${q.id}-pdf`, onClick: () => downloadCustomerQuotation(q, 'pdf') },
                      { label: 'Excel', busy: busyKey === `chain-${q.id}-xlsx`, onClick: () => downloadCustomerQuotation(q, 'xlsx') },
                    ] : []}
                  />
                );
              })}
              {legacyQuotations.map((q) => {
                const status = quotationStatusLabel(q.docStatus);
                return (
                  <DocumentRow
                    key={`legacy-${q.id}`}
                    icon="fileText"
                    title={q.number}
                    meta={`${formatMoney(q.totalAmount)} · เอกสารเดิม · ออก ${formatThaiDate(q.issuedAt)}`}
                    status={status}
                    actions={[
                      { label: 'PDF', busy: busyKey === `legacy-${q.id}-pdf`, onClick: () => downloadLegacyQuotation(q, 'pdf') },
                      { label: 'Excel', busy: busyKey === `legacy-${q.id}-xlsx`, onClick: () => downloadLegacyQuotation(q, 'xlsx') },
                    ]}
                  />
                );
              })}
            </div>
          )}
        </div>
      ) : null}

      {canViewDepositAndInvoice ? (
        <div className="flex flex-col gap-2 rounded-md border border-border bg-surface p-3" data-testid="register-deposit-and-invoice">
          <strong className="text-sm">ใบแจ้งยอดมัดจำ และใบแจ้งหนี้ส่วนที่เหลือ</strong>
          {depositNoticesQuery.isLoading ? (
            <Skeleton height={40} />
          ) : depositNotices.length === 0 ? (
            <p className="text-xs text-text-muted">ยังไม่มีใบแจ้งยอดมัดจำสำหรับดีลนี้</p>
          ) : (
            <div className="flex flex-col gap-1.5">
              {depositNotices.map((doc) => (
                <DocumentRow
                  key={`deposit-${doc.id}`}
                  icon="fileText"
                  title={doc.docNumber ?? `ร่างใบแจ้งยอดมัดจำ #${doc.id}`}
                  meta={`มัดจำ ${Math.round(Number(doc.depositPercent ?? 0.5) * 100)}%`}
                  status={{ label: doc.status === 'ISSUED' ? 'ออกแล้ว' : 'ฉบับร่าง', tone: doc.status === 'ISSUED' ? 'success' : 'neutral' }}
                  actions={doc.status === 'ISSUED' ? [
                    { label: 'PDF', busy: busyKey === `deposit-${doc.id}-pdf`, onClick: () => downloadDepositNotice(doc, 'pdf') },
                    { label: 'Excel', busy: busyKey === `deposit-${doc.id}-xlsx`, onClick: () => downloadDepositNotice(doc, 'xlsx') },
                  ] : []}
                />
              ))}
            </div>
          )}
          <div className="mt-1 border-t border-border-subtle pt-2">
            <DocumentRow
              icon="fileText"
              title="ใบแจ้งหนี้ส่วนที่เหลือ"
              meta={remainingInvoiceReady ? 'พร้อมดาวน์โหลด' : 'ยังไม่ถึงขั้นตอน (ต้องออกใบเสนอราคาและรับสินค้าครบก่อน)'}
              status={{ label: remainingInvoiceReady ? 'พร้อมใช้งาน' : 'รอขั้นตอน', tone: remainingInvoiceReady ? 'success' : 'neutral' }}
              actions={remainingInvoiceReady ? [
                { label: 'Excel', busy: busyKey === 'remaining-invoice', onClick: downloadRemainingInvoice },
              ] : []}
            />
          </div>
        </div>
      ) : null}

      {canViewDocumentsTab ? (
        <div className="flex flex-col gap-2 rounded-md border border-border bg-surface p-3" data-testid="register-attachments">
          <strong className="text-sm">ไฟล์แนบ (PO / ใบเซ็น / ใบกำกับภาษี)</strong>
          {attachLoading ? (
            <Skeleton height={40} />
          ) : attachments.length === 0 ? (
            <p className="text-xs text-text-muted">ยังไม่มีไฟล์แนบสำหรับดีลนี้</p>
          ) : (
            <div className="flex flex-col gap-1.5">
              {attachments.map((att) => (
                <DocumentRow
                  key={`attachment-${att.id}`}
                  icon="paperclip"
                  title={att.fileName}
                  meta={att.attachType}
                  status={null}
                  actions={[{
                    label: 'ดูไฟล์',
                    href: api.attachments.fileUrl(att.id),
                  }]}
                />
              ))}
            </div>
          )}
        </div>
      ) : null}
    </Panel>
  );
}

function DocumentRow({ icon, title, meta, status, actions = [] }) {
  return (
    <div className="flex flex-wrap items-center gap-2 rounded-md border border-border-subtle bg-surface-muted p-2">
      <Icon name={icon} size={13} style={{ color: 'var(--color-text-muted)', flexShrink: 0 }} />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="truncate text-sm font-bold text-text">{title}</span>
          {status ? <StatusBadge tone={status.tone}>{status.label}</StatusBadge> : null}
        </div>
        {meta ? <div className="text-2xs text-text-muted">{meta}</div> : null}
      </div>
      <div className="flex shrink-0 flex-wrap gap-1.5">
        {actions.map((action) => (action.href ? (
          // Not a <Button>: this is a real navigation link (target="_blank" to
          // a file URL) — Button renders a <button>, which has no href.
          <a key={action.label} href={action.href} target="_blank" rel="noreferrer" className="secondary-button" style={{ fontSize: 12, padding: '4px 10px' }}>
            {action.label}
          </a>
        ) : (
          <Button key={action.label} type="button" variant="secondary" style={{ fontSize: 12, padding: '4px 10px' }}
            disabled={action.busy} onClick={action.onClick}>
            {action.busy ? 'กำลังดาวน์โหลด…' : action.label}
          </Button>
        )))}
      </div>
    </div>
  );
}
