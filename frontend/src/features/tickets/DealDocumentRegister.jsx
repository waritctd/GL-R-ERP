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
import {
  depositNoticeStatusLabel, formatMoney, formatThaiDate, quotationRecipientLabel, quotationStatusLabel,
} from '../../utils/format.js';
import { canViewCustomerQuotation } from '../pricingRequests/pricingRequestMeta.js';
import { canViewDealQuotation, dealQuotationStatusLabel } from '../quotations/quotationMeta.js';
import { isRemainingInvoiceReady } from './remainingInvoiceReadiness.js';
import { buttonVariants } from '../../components/common/Button.jsx';
import { cn } from '../../utils/cn.js';
import { RemainingInvoiceDialog } from './RemainingInvoiceDialog.jsx';
import {
  dropLegacyDuplicatedByChain, groupChainQuotations, groupDepositNotices,
  groupDirectQuotations, groupLegacyQuotations, groupRemainingInvoices, sortGroupsNewestFirst,
} from './dealDocumentVersions.js';

/**
 * Slice D ("the เอกสาร document register"): one read-only roll-up of every document a deal can
 * produce — customer quotations (the PricingRequest/CustomerQuotation chain, the legacy
 * ticket-native rows and the direct v2 quotations), the deposit notice, the remaining invoice, and
 * formal attachments (PO / signed quotation / tax invoice / other) — rendered together in the
 * เอกสาร tab. Every document is shown with ALL its versions: the current (newest) version first,
 * older versions always visible beneath it (see dealDocumentVersions.js for grouping).
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
  pricingRequestsLoading = false,
  legacyQuotations = [],
  attachments = [],
  attachLoading = false,
}) {
  const [busyKey, setBusyKey] = useState(null);
  const [remainingInvoiceDialogOpen, setRemainingInvoiceDialogOpen] = useState(false);

  // Unchanged from the pre-Slice-D `documents` tab gate (ticketDetailTabs.js) — see this file's
  // own header comment for why it stays a two-part role+section check rather than collapsing to
  // just `canViewPricingRequests`.
  const canViewQuotations = canViewPricingRequests && Boolean(sections?.dealQuotation || sections?.quotation);
  // Mirrors DepositNoticeService#requireTicketViewer via salesViewScope's own `depositNotice`
  // section id (see header comment) — governs BOTH the deposit notice and the remaining invoice.
  const canViewDepositAndInvoice = Boolean(sections?.depositNotice);
  // R4 (GLA-99 step 2 review-round-1): the remaining invoice's own WRITE gate — mirrors
  // RemainingInvoiceService#requireDepositNoticeIssueGate / mockApi's
  // requireRemainingInvoiceWriteGate exactly (sales role + this deal's own owner, no CEO
  // carve-out). UI-only convenience for RemainingInvoiceDialog's own button visibility — the
  // backend gate above is what actually enforces this.
  const canWriteRemainingInvoice = user?.role === 'sales' && user?.id === summary?.createdById;

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

  // Direct (v2) quotations, listed here with their versions. The query is shared with
  // DealDirectQuotationPanel, so `Number(ticketId)` is required, not cosmetic: `ticketId` arrives
  // as the raw useParams() STRING and that panel keys the very same query with the numeric id —
  // the string would open a SECOND cache entry and fire a second request for data already loaded.
  const directQuotationsQuery = useQuery({
    queryKey: queryKeys.dealQuotationsByTicket(Number(ticketId)),
    queryFn: () => api.dealQuotations.listForTicket(Number(ticketId)).then((r) => r.items ?? []),
    enabled: Boolean(ticketId) && canViewQuotations && canViewDealQuotation(user),
  });

  const depositNoticesQuery = useQuery({
    queryKey: queryKeys.depositNotices(ticketId),
    queryFn: () => api.depositNotices.listByTicket(ticketId).then((r) => r.depositNotices ?? []),
    // Never fire this for a viewer DepositNoticeService#requireTicketViewer would 403 — same
    // reasoning as TicketDetailPage's own `attachmentsQuery`/`activitiesQuery` `enabled` gates.
    enabled: Boolean(ticketId) && canViewDepositAndInvoice,
  });
  const depositGroups = useMemo(() => groupDepositNotices(depositNoticesQuery.data ?? []), [depositNoticesQuery.data]);

  // Stored remaining-invoice versions. Same key form as RemainingInvoiceDialog (raw `ticketId`)
  // so both share one cache entry; same gate as the deposit notice (requireTicketViewer).
  const remainingInvoicesQuery = useQuery({
    queryKey: queryKeys.storedRemainingInvoices(ticketId),
    queryFn: () => api.storedRemainingInvoices.listForTicket(ticketId).then((r) => r.remainingInvoices ?? []),
    enabled: Boolean(ticketId) && canViewDepositAndInvoice,
  });
  const remainingGroups = useMemo(() => groupRemainingInvoices(remainingInvoicesQuery.data ?? []), [remainingInvoicesQuery.data]);

  const chainGroups = useMemo(() => groupChainQuotations(customerQuotationRows), [customerQuotationRows]);
  // Legacy rows that also exist in the chain (same id) render once, as the chain row. Until the
  // chain queries have settled ONCE we cannot tell which those are, so hold the legacy rows back
  // rather than flash duplicates. Latched: a later pricing request starting to load must not make
  // already-shown rows vanish.
  const [chainSettledOnce, setChainSettledOnce] = useState(false);
  if (!chainSettledOnce && !customerQuotationsLoading && !pricingRequestsLoading) setChainSettledOnce(true);
  const legacyRows = useMemo(
    () => (!chainSettledOnce ? [] : dropLegacyDuplicatedByChain(legacyQuotations, customerQuotationRows)),
    [chainSettledOnce, legacyQuotations, customerQuotationRows],
  );
  const legacyGroups = useMemo(() => groupLegacyQuotations(legacyRows), [legacyRows]);
  const directGroups = useMemo(() => groupDirectQuotations(directQuotationsQuery.data ?? []), [directQuotationsQuery.data]);
  const quotationDocCount = chainGroups.length + legacyGroups.length + directGroups.length;

  const remainingInvoiceReady = isRemainingInvoiceReady(summary);

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

  async function downloadDirectQuotation(q, format) {
    const key = `direct-${q.id}-${format}`;
    const blob = await handleDownload(key, () => (format === 'pdf'
      ? api.dealQuotations.downloadPdf(q.id)
      : api.dealQuotations.downloadXlsx(q.id)));
    downloadBlob(blob, q.number ?? `quotation-${q.id}`, format);
  }

  async function downloadRemainingInvoice(v) {
    const key = `remaining-${v.id}-xlsx`;
    const blob = await handleDownload(key, () => api.storedRemainingInvoices.download(v.id));
    downloadBlob(blob, v.docNumber || `draft-${v.id}`, 'xlsx');
  }

  function openRemainingInvoiceDialog() {
    setRemainingInvoiceDialogOpen(true);
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

  const pdfXlsx = (busyPrefix, run, v) => [
    { label: 'PDF', busy: busyKey === `${busyPrefix}-${v.id}-pdf`, onClick: () => run(v, 'pdf') },
    { label: 'Excel', busy: busyKey === `${busyPrefix}-${v.id}-xlsx`, onClick: () => run(v, 'xlsx') },
  ];

  const chainItem = (q) => ({
    id: q.id,
    testId: `document-version-chain-${q.id}`,
    title: q.number ?? `ร่างใบเสนอราคา #${q.id}`,
    status: quotationStatusLabel(q.docStatus),
    date: q.issuedAt ?? q.createdAt,
    amount: q.grandTotal,
    recipient: quotationRecipientLabel(q.recipientType ?? 'UNSPECIFIED').label,
    meta: `ครั้งที่ ${q.quotationRevisionNo ?? 1}`,
    // Download gate unchanged: only ISSUED / ACCEPTED chain rows.
    actions: q.docStatus === 'ISSUED' || q.docStatus === 'ACCEPTED'
      ? pdfXlsx('chain', downloadCustomerQuotation, q) : [],
  });
  const legacyItem = (q) => ({
    id: q.id,
    testId: `document-version-legacy-${q.id}`,
    title: q.number,
    status: quotationStatusLabel(q.docStatus),
    date: q.issuedAt,
    amount: q.totalAmount,
    recipient: quotationRecipientLabel(q.recipientType ?? 'UNSPECIFIED').label,
    meta: 'เอกสารเดิม',
    actions: pdfXlsx('legacy', downloadLegacyQuotation, q),
  });
  const directItem = (q) => ({
    id: q.id,
    testId: `document-version-direct-${q.id}`,
    title: q.number ?? `ร่างใบเสนอราคา #${q.id}`,
    status: dealQuotationStatusLabel(q.docStatus),
    date: q.approvedAt ?? q.createdAt,
    amount: q.grandTotal,
    // DealQuotationDto#recipientType (Round 8). An older backend omits it: undefined -> no chip
    // (never a wrong one) — the frontend deploys before the backend image.
    recipient: q.recipientType ? quotationRecipientLabel(q.recipientType).label : null,
    meta: `ครั้งที่ ${q.revisionNo ?? 1}`,
    // Mirrors DealDirectQuotationPanel: PDF + Excel for every status.
    actions: pdfXlsx('direct', downloadDirectQuotation, q),
  });
  const depositItem = (doc) => ({
    id: doc.id,
    testId: `document-version-deposit-${doc.id}`,
    title: doc.docNumber ?? `ร่างใบแจ้งยอดมัดจำ #${doc.id}`,
    status: depositNoticeStatusLabel(doc.status),
    date: doc.issueDate ?? doc.createdAt,
    meta: `มัดจำ ${Math.round(Number(doc.depositPercent ?? 0.5) * 100)}%`,
    // GLA-117: a SUPERSEDED notice was once ISSUED (DepositNoticeRepository#supersede only ever
    // transitions FROM ISSUED, keeping doc_number/pdf_path/xlsx_path intact) and
    // DepositNoticeService#getPdf/getXlsx re-render from that persisted snapshot with no status
    // guard — so it stays downloadable exactly like an ISSUED one. Only DRAFT (never rendered, no
    // doc number yet) has no download actions.
    actions: doc.status === 'ISSUED' || doc.status === 'SUPERSEDED'
      ? pdfXlsx('deposit', downloadDepositNotice, doc) : [],
  });
  const remainingItem = (v) => ({
    id: v.id,
    testId: `document-version-remaining-${v.id}`,
    title: v.docNumber ?? 'ใบแจ้งหนี้ส่วนที่เหลือ (ยังไม่ออกเลข)',
    status: depositNoticeStatusLabel(v.status),
    date: v.issuedAt ?? v.createdAt,
    amount: v.grandTotal,
    // Same rule as RemainingInvoiceDialog's version history: any stored, non-DRAFT version.
    actions: v.status === 'DRAFT' ? [] : [
      { label: 'Excel', busy: busyKey === `remaining-${v.id}-xlsx`, onClick: () => downloadRemainingInvoice(v) },
    ],
  });

  const renderGroups = (groups, toItem) => groups.map((g) => (
    <DocumentGroup key={g.key} items={g.versions.map(toItem)} />
  ));
  // One list across the three quotation families, ordered by each document's newest version.
  const quotationGroups = sortGroupsNewestFirst([
    ...chainGroups.map((g) => ({ ...g, toItem: chainItem })),
    ...legacyGroups.map((g) => ({ ...g, toItem: legacyItem })),
    ...directGroups.map((g) => ({ ...g, toItem: directItem })),
  ]);

  return (
    <Panel flush className="flex flex-col" title="เอกสารของดีล" data-testid="deal-document-register">
      {canViewQuotations ? (
        <RegisterSection testId="register-quotations" title="ใบเสนอราคา" count={quotationDocCount} unit="เอกสาร">
          {customerQuotationsLoading && quotationDocCount === 0 ? (
            // Only block on the async chain fetch while there is NOTHING else to show yet —
            // legacy rows arrive as a prop and must never wait on an unrelated query.
            <Skeleton height={40} />
          ) : quotationDocCount === 0 ? (
            directQuotationsQuery.isLoading
              ? <Skeleton height={40} />
              : <p className="text-xs text-text-muted">ยังไม่มีใบเสนอราคาสำหรับดีลนี้</p>
          ) : (
            <div className="flex flex-col gap-3">
              {quotationGroups.map((g) => <DocumentGroup key={g.key} items={g.versions.map(g.toItem)} />)}
            </div>
          )}
        </RegisterSection>
      ) : null}

      {canViewDepositAndInvoice ? (
        <RegisterSection testId="register-deposit" title="ใบแจ้งยอดมัดจำ"
          count={depositGroups.length} unit="เอกสาร">
          {depositNoticesQuery.isLoading ? (
            <Skeleton height={40} />
          ) : depositGroups[0]?.versions.length ? (
            renderGroups(depositGroups, depositItem)
          ) : (
            <p className="text-xs text-text-muted">ยังไม่มีใบแจ้งยอดมัดจำสำหรับดีลนี้</p>
          )}
        </RegisterSection>
      ) : null}

      {canViewDepositAndInvoice ? (
        <RegisterSection testId="register-remaining-invoice" title="ใบแจ้งหนี้ส่วนที่เหลือ" count={remainingGroups.length} unit="เอกสาร">
          {/* The readiness row (create/download via the dialog) always leads the section; the
              stored versions follow. Its title says what the button DOES, so it does not repeat
              the section heading. */}
          <DocumentRow
            icon="fileText"
            title="ออกใบแจ้งหนี้ส่วนที่เหลือ"
            meta={remainingInvoiceReady ? 'พร้อมดาวน์โหลด' : 'ยังไม่ถึงขั้นตอน (ต้องออกใบเสนอราคาและสินค้าพร้อมส่งมอบก่อน)'}
            status={{ label: remainingInvoiceReady ? 'พร้อมใช้งาน' : 'รอขั้นตอน', tone: remainingInvoiceReady ? 'success' : 'neutral' }}
            actions={remainingInvoiceReady ? [
              { label: 'Excel', busy: false, onClick: openRemainingInvoiceDialog },
            ] : []}
          />
          {remainingGroups.length > 0 ? (
            renderGroups(remainingGroups, remainingItem)
          ) : remainingInvoicesQuery.isLoading ? (
            <Skeleton height={40} />
          ) : (
            <p className="text-xs text-text-muted">ยังไม่มีใบแจ้งหนี้ส่วนที่เหลือสำหรับดีลนี้</p>
          )}
        </RegisterSection>
      ) : null}

      {canViewDocumentsTab ? (
        <RegisterSection testId="register-attachments" title="ไฟล์แนบ (PO / ใบเซ็น / ใบกำกับภาษี)">
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
        </RegisterSection>
      ) : null}

      {remainingInvoiceDialogOpen ? (
        <RemainingInvoiceDialog ticketId={ticketId} canWrite={canWriteRemainingInvoice}
          onClose={() => setRemainingInvoiceDialogOpen(false)} />
      ) : null}
    </Panel>
  );
}

// Sections are divided, not boxed: one Panel, a divider above every section but the first.
function RegisterSection({ testId, title, count, unit, children }) {
  return (
    <section className="flex flex-col gap-3 px-5 py-4 mobile:px-4 mobile:py-3.5 [&+&]:border-t [&+&]:border-border-subtle" data-testid={testId}>
      <div className="flex items-baseline gap-2">
        <strong className="text-sm font-bold text-text">{title}</strong>
        {count > 0 ? <span className="text-xs text-text-muted">{`${count} ${unit}`}</span> : null}
      </div>
      {children}
    </section>
  );
}

// One document: the current (newest) version as a normal row, older versions indented beneath.
function DocumentGroup({ items }) {
  const [current, ...older] = items;
  return (
    <div className="flex flex-col gap-1.5" data-testid="document-group">
      <DocumentRow icon="fileText" {...current} />
      {older.length > 0 ? (
        <div className="ml-1.5 flex flex-col gap-1 border-l border-border-subtle pl-6 mobile:pl-3">
          {older.map((v) => <DocumentRow key={v.testId} muted {...v} />)}
        </div>
      ) : null}
    </div>
  );
}

// Below desktop (<=1040px, the `nav-drawer` band) every button/link is a 44px touch target; the
// 8px gap between them is kept by `gap-2`. Desktop stays compact.
const TOUCH_TARGET = 'nav-drawer:min-h-11 nav-drawer:min-w-11';

function DocumentRow({ testId, icon, title, meta, status, date, amount, recipient, actions = [], muted = false }) {
  const hasAmount = amount !== undefined && amount !== null;
  const metaText = [date ? formatThaiDate(date) : null, meta].filter(Boolean).join(' · ');
  return (
    // Line 1: number + status + recipient (wraps). Line 2: date/meta. Line 3 (mobile) / right side
    // of line 2 (desktop): amount on the left, actions on the right.
    <div data-testid={testId} className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
      <div className="flex min-w-0 max-w-full flex-wrap items-center gap-x-2 gap-y-1">
        {!muted && icon ? <Icon name={icon} size={13} style={{ color: 'var(--color-text-muted)', flexShrink: 0 }} /> : null}
        <span className={cn('min-w-0 truncate mobile:whitespace-normal mobile:break-all', muted ? 'text-xs text-text-muted' : 'text-sm font-bold text-text')}>{title}</span>
        {status ? <StatusBadge tone={status.tone}>{status.label}</StatusBadge> : null}
        {/* Recipient of the whole document: shown on the current (top) row only. */}
        {recipient && !muted ? (
          <span data-testid="document-recipient" title={`สำหรับ: ${recipient}`}
            className="max-w-full truncate rounded-pill border border-border-subtle bg-surface-muted px-2 text-xs font-bold text-text-secondary">
            <span className="sr-only">สำหรับ: </span>{recipient}
          </span>
        ) : null}
      </div>
      <div className="flex min-w-0 flex-1 basis-full flex-wrap items-center justify-between gap-x-3 gap-y-2">
        {metaText ? <span className="text-xs text-text-muted mobile:basis-full">{metaText}</span> : null}
        <div className="ml-auto flex min-w-0 flex-wrap items-center justify-end gap-x-3 gap-y-2 mobile:w-full mobile:basis-full mobile:justify-between">
          {hasAmount ? (
            <span className={cn('tabular-nums', muted ? 'text-xs text-text-muted' : 'text-sm text-text')}>{formatMoney(amount)}</span>
          ) : null}
          {actions.length > 0 ? (
            <div className="ml-auto flex flex-wrap items-center justify-end gap-2">
              {actions.map((action) => (action.href ? (
                // Not a <Button>: this is a real navigation link (target="_blank" to
                // a file URL) — Button renders a <button>, which has no href.
                <a key={action.label} href={action.href} target="_blank" rel="noreferrer"
                  className={cn(buttonVariants({ variant: 'secondary' }), 'px-2.5 py-1 text-xs', TOUCH_TARGET)}>
                  {action.label}
                </a>
              ) : (
                <Button key={action.label} type="button" variant="secondary" className={TOUCH_TARGET}
                  style={{ fontSize: 12, padding: '4px 10px' }} disabled={action.busy} onClick={action.onClick}>
                  {action.busy ? 'กำลังดาวน์โหลด…' : action.label}
                </Button>
              )))}
            </div>
          ) : null}
        </div>
      </div>
    </div>
  );
}
