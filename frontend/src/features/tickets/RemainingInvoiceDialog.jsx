import { useEffect, useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { DialogSummary } from '../../components/common/DocumentDialogParts.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { DocumentPreparerSelect } from '../deposits/DocumentPreparerSelect.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { downloadBlob } from '../../utils/download.js';
import { invalidateDealDocuments } from '../documentChain/documentChain.js';
import { formatMoney } from '../../utils/format.js';
import { ThaiDateInput } from '../../components/common/ThaiDateInput.jsx';
import { paymentTermsLine } from '../finance/paymentDueText.js';

const STATUS_LABEL = { DRAFT: 'ร่าง', ISSUED: 'ออกแล้ว', SUPERSEDED: 'ถูกแทนที่แล้ว' };
const STATUS_TONE = { DRAFT: 'neutral', ISSUED: 'success', SUPERSEDED: 'neutral' };

/**
 * A titled section of either form. Inside the dialog a full Panel (its own border and padding) would sit as a box in
 * a box, so the section keeps the Panel TITLE's look (the deposit notice page's `h2 m-0 text-lg`, one level down here
 * because the dialog's own title is the h2) over a hairline, and lays its fields out exactly as the page does.
 */
function Section({ title, children }) {
  return (
    <section className="flex flex-col gap-3">
      <h3 className="m-0 min-w-0 border-b border-border-subtle pb-2 text-lg break-words">{title}</h3>
      {children}
    </section>
  );
}

/** The deposit notice page's field grid: two columns, one on mobile. */
const FIELD_GRID = 'grid grid-cols-2 gap-3 mobile:grid-cols-1';

const ITEM_GRID = 'grid-cols-[minmax(0,3fr)_minmax(5.5rem,0.9fr)_minmax(5.5rem,0.9fr)_minmax(7rem,1fr)] tablet:min-w-0 reflow-cards';

/**
 * The stored lines of the document, READ-ONLY: a snapshot of the deposit notice (or quotation) taken when the draft
 * was saved. Nothing here is editable; the server re-snapshots them on save.
 */
function StoredItems({ items }) {
  return (
    <Section title="รายการสินค้า">
      <div className="overflow-x-auto overflow-y-hidden rounded-md border border-border" data-testid="remaining-invoice-items">
        <div className={`${ITEM_GRID} table-head`}>
          <span>รายละเอียด</span>
          <span className="text-right">จำนวน</span>
          <span className="text-right">ราคาสุทธิ/หน่วย</span>
          <span className="text-right">เป็นเงิน</span>
        </div>
        {items.map((it, idx) => (
          <div key={it.id ?? idx} className={`${ITEM_GRID} data-row`}>
            <span className="min-w-0" data-label="รายละเอียด"><span className="font-bold [overflow-wrap:anywhere]">{it.description}</span></span>
            <span className="tabular-nums text-right" data-label="จำนวน">{`${Number(it.qty).toLocaleString('th-TH')} ${it.unit ?? ''}`.trim()}</span>
            <span className="tabular-nums text-right" data-label="ราคาสุทธิ/หน่วย">{formatMoney(it.netUnitPrice ?? it.unitPrice)}</span>
            <span className="tabular-nums text-right font-bold" data-label="เป็นเงิน">{formatMoney(it.amount)}</span>
          </div>
        ))}
      </div>
    </Section>
  );
}

/** The deposit notice page's totals block: right-aligned figures, the payable row emphasised. */
function TotalsBlock({ rows, payable }) {
  return (
    <div className="flex flex-col items-end gap-1" data-testid="remaining-invoice-preview">
      {rows.map((r) => (
        <span key={r.label} className={`text-sm ${r.bold ? 'font-bold text-text' : 'text-text-muted'}`}>
          {r.label} <span className="tabular-nums text-text">{r.value}</span>
        </span>
      ))}
      <span className="text-lg font-extrabold">{payable.label} <span className="tabular-nums">{payable.value}</span></span>
    </div>
  );
}

/**
 * Drawing m-remaining's summary line: "ส่วนที่เหลือ … = มูลค่า … − มัดจำ …". All three figures are ONE served row's own
 * fields (the options preview's netAmount/itemsTotal/depositAmount, or a stored row's netAmount/itemsTotal/
 * depositDeduction), consistent by construction: DepositNoticeService#computeRemainingInvoiceMoney sets
 * netAmount = itemsTotal − depositAmount. Nothing is computed here. "มัดจำ", not the drawing's "มัดจำที่ได้รับ": the
 * deduction is the deposit notice's billed deposit (pre-VAT), not a received amount. With no deduction on the row
 * the equation is left out rather than completed with a zero the server did not send.
 */
function RemainingSummaryLine({ net, itemsTotal, deposit }) {
  const hasDeduction = deposit !== null && deposit !== undefined;
  return (
    <DialogSummary testId="remaining-invoice-summary">
      {`ส่วนที่เหลือ ${formatMoney(net)} (ก่อน VAT)`}
      {hasDeduction ? ` = มูลค่า ${formatMoney(itemsTotal)} − มัดจำ ${formatMoney(deposit)}` : null}
    </DialogSummary>
  );
}

/**
 * The drawn footer row: the write buttons, right-aligned. The drawing's source note ("ทุกบรรทัดเติมจากใบเสนอราคาและ
 * การส่งของแล้ว") is left out (fix round 1, F6): the lines come from the matched deposit notice's snapshot, or the
 * quotation when there is no notice, or legacy approved prices (DepositNoticeService#resolveFromQuotation /
 * #resolveLegacy), never from deliveries — and nothing this dialog receives says which, so no one sentence is true
 * for every deal.
 */
function DialogFooterRow({ children }) {
  return (
    <div className="flex items-center justify-between gap-2 border-t border-border pt-3 mobile:flex-col-reverse mobile:items-stretch [&>button]:mobile:w-full" data-testid="remaining-invoice-footer">
      {children}
    </div>
  );
}

/**
 * ใบแจ้งหนี้ส่วนที่เหลือ — a STORED, versioned document: DRAFT ->
 * ISSUED -> SUPERSEDED, minted on the shared GLR<yy><seq>-<version> sequence. This dialog covers
 * the whole lifecycle in one place:
 *
 *  - No live document yet -> the pre-existing prefill preview
 *    (`api.tickets.remainingInvoiceOptions`, unchanged — see DepositNoticeService's own Javadoc)
 *    with one action, "สร้างร่าง", which POSTs a new stored DRAFT instead of downloading a
 *    one-shot file.
 *  - A live DRAFT -> an editable form (same fields) with "บันทึก" (re-snapshot + save), "ลบร่าง",
 *    and "ออกใบแจ้งหนี้" (issue — mints the real number).
 *  - A live ISSUED document (no draft) -> a read-only summary, "ออกฉบับแก้ไข" (revise — prepares a
 *    new correcting DRAFT), and a download button.
 *  - Every ISSUED/SUPERSEDED version is listed below, each individually downloadable — including
 *    while a revision DRAFT is open above it: the document being corrected stays ISSUED (see
 *    RemainingInvoiceService#revise's Javadoc) and must stay reachable/downloadable.
 *
 * `canWrite`: the caller must pass
 * whether the VIEWING user is this deal's owning sales rep — EXACTLY the predicate
 * `DepositNoticeService#requireDepositNoticeIssueGate`/mockApi's own
 * `requireRemainingInvoiceWriteGate` enforce server-side (sales role + `user.id ===
 * ticket.createdById`), never re-derived here. Defaults to `true` for backward compatibility with
 * every existing caller/test that renders this dialog without knowing about the gate yet — a real
 * caller (DealDocumentRegister, TicketDetailPage) always passes the computed value explicitly.
 * This is UI convenience only (hides buttons that would otherwise just 403), never the actual
 * security boundary — that is, and stays, the backend gate above.
 */
export function RemainingInvoiceDialog({ ticketId, canWrite = true, onClose, onDownloaded }) {
  const queryClient = useQueryClient();

  // Slice 6.1: the payment terms and due date, READ-ONLY, from the summary the server already serves
  // (TicketSummaryDto#paymentDueBasis / #paymentDueCreditDays / #paymentDueDate). Same key and fn as the deal page's
  // own ticket read (TicketDetailPage), so there it is a cache hit; elsewhere one GET. A read the viewer may not make
  // just leaves the line out.
  const dealQuery = useQuery({
    queryKey: queryKeys.ticketDetail(ticketId),
    queryFn: () => api.tickets.get(ticketId).then((r) => r.ticket),
    enabled: Boolean(ticketId),
    retry: false,
  });
  const termsLine = paymentTermsLine(dealQuery.data?.summary);

  const listQuery = useQuery({
    queryKey: queryKeys.storedRemainingInvoices(ticketId),
    queryFn: () => api.storedRemainingInvoices.listForTicket(ticketId).then((r) => r.remainingInvoices ?? []),
    enabled: Boolean(ticketId),
  });
  const list = useMemo(() => listQuery.data ?? [], [listQuery.data]);
  const draft = list.find((d) => d.status === 'DRAFT');
  const issued = list.find((d) => d.status === 'ISSUED');
  const history = useMemo(
    () => [...list].filter((d) => d.status !== 'DRAFT').sort((a, b) => b.version - a.version),
    [list],
  );

  // Every write here (save / issue / revise / delete) moves the document chain: the invoice list AND the summary the
  // deal page's เงิน tab and the quotation page's chain card read.
  function invalidateList() {
    return invalidateDealDocuments(queryClient, ticketId);
  }

  async function handleDownloadVersion(id, docNumber) {
    const blob = await api.storedRemainingInvoices.download(id);
    downloadBlob(blob, docNumber || `draft-${id}`, 'pdf');
    onDownloaded?.();
  }

  if (!ticketId) return null;

  return (
    <Modal
      title="ใบแจ้งหนี้ส่วนที่เหลือ"
      subtitle={issued?.docNumber ? `เลขที่เอกสารล่าสุด ${issued.docNumber}` : undefined}
      onClose={onClose}
      testId="remaining-invoice-dialog"
      footer={<Button type="button" variant="secondary" onClick={onClose}>ปิด</Button>}
    >
      {listQuery.isLoading ? (
        <Skeleton height={220} />
      ) : listQuery.isError ? (
        <p className="m-0 text-sm font-bold text-danger" role="alert">
          {listQuery.error?.message || 'โหลดข้อมูลใบแจ้งหนี้ส่วนที่เหลือไม่สำเร็จ'}
        </p>
      ) : (
        <>
          {termsLine ? (
            <p data-testid="remaining-invoice-terms"
              className="m-0 mb-3 flex flex-wrap items-baseline gap-x-2 gap-y-0.5 rounded-md border border-border-subtle bg-surface-muted px-3 py-2 text-sm">
              <span className="font-bold text-text-secondary">เงื่อนไขชำระ</span>
              <span className="text-text [overflow-wrap:anywhere]">{termsLine}</span>
            </p>
          ) : null}
          {draft && canWrite ? (
            <DraftEditor draft={draft} issuedOfSameQuotation={list.find((d) => d.status === 'ISSUED' && d.customerQuotationId === draft.customerQuotationId)} onSaved={invalidateList} onIssued={invalidateList}
              onDeleted={invalidateList} onDownloaded={onDownloaded} />
          ) : issued ? (
            <IssuedSummary issued={issued} canWrite={canWrite} onRevised={invalidateList} onDownloaded={onDownloaded} />
          ) : canWrite ? (
            <CreateForm ticketId={ticketId} onCreated={invalidateList} />
          ) : (
            <WaitingForSalesState />
          )}
          {/* Rendered unconditionally alongside EITHER branch: opening a revision (draft && canWrite
              both true, while the predecessor is still ISSUED per RemainingInvoiceService#revise's
              Javadoc) must not hide the live ISSUED document AND its download. `history` already
              includes every non-DRAFT row (the live ISSUED one included), so a revision in progress
              still shows the predecessor's own download button here. */}
          {history.length > 0 ? (
            <VersionHistory versions={history} onDownload={handleDownloadVersion} />
          ) : null}
        </>
      )}
    </Modal>
  );
}

/** Nothing issued yet, and the viewer is not the deal's owning sales rep — there is nothing
 * for them to write OR to read/download, so the dialog says so plainly instead of either hiding
 * itself (which would look like the feature is broken) or showing controls that would only 403. */
function WaitingForSalesState() {
  return (
    <p className="m-0 rounded-md border border-border-subtle bg-surface-muted p-3 text-sm text-text-secondary"
      data-testid="remaining-invoice-waiting-for-sales">
      ยังไม่มีใบแจ้งหนี้ส่วนที่เหลือสำหรับงานนี้ — รอฝ่ายขายออกเอกสาร
    </p>
  );
}

/** Every past ISSUED/SUPERSEDED version, newest first, each individually downloadable. */
function VersionHistory({ versions, onDownload }) {
  return (
    <div className="mt-4 flex flex-col gap-2 border-t border-border-subtle pt-4" data-testid="remaining-invoice-history">
      <h4 className="m-0 text-sm font-bold text-text-secondary">ประวัติเอกสาร</h4>
      <ul className="m-0 flex list-none flex-col gap-1.5 p-0">
        {versions.map((v) => (
          <li key={v.id} className="flex items-center justify-between gap-2 rounded-md border border-border-subtle p-2 text-sm">
            <span>
              <span className="font-bold">{v.docNumber}</span>{' '}
              <StatusBadge tone={STATUS_TONE[v.status] ?? 'neutral'}>{STATUS_LABEL[v.status] ?? v.status}</StatusBadge>
            </span>
            <Button type="button" variant="secondary" onClick={() => onDownload(v.id, v.docNumber)}
              data-testid={`remaining-invoice-download-version-${v.id}`}>
              ดาวน์โหลด
            </Button>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** Read-only summary of the currently-live ISSUED document, plus the "ออกฉบับแก้ไข" action —
 * hidden when `canWrite` is false: a non-owning viewer still reads/downloads, never revises. */
function IssuedSummary({ issued, canWrite, onRevised, onDownloaded }) {
  const [revising, setRevising] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState(null);

  async function handleRevise() {
    setError(null);
    setRevising(true);
    try {
      await api.storedRemainingInvoices.revise(issued.id);
      await onRevised?.();
    } catch (err) {
      setError(err.message || 'ออกฉบับแก้ไขไม่สำเร็จ');
    } finally {
      setRevising(false);
    }
  }

  async function handleDownload() {
    setError(null);
    setDownloading(true);
    try {
      const blob = await api.storedRemainingInvoices.download(issued.id);
      downloadBlob(blob, issued.docNumber || `remaining-invoice-${issued.id}`, 'pdf');
      onDownloaded?.();
    } catch (err) {
      setError(err.message || 'ดาวน์โหลดไม่สำเร็จ');
    } finally {
      setDownloading(false);
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <RemainingSummaryLine net={issued.netAmount} itemsTotal={issued.itemsTotal} deposit={issued.depositDeduction} />
      <div className="flex flex-col gap-1 rounded-md border border-border-subtle bg-surface-muted p-3 text-sm" data-testid="remaining-invoice-issued-summary">
        <div className="flex justify-between"><span className="text-text-muted">เลขที่เอกสาร</span><span className="font-bold">{issued.docNumber}</span></div>
        <div className="flex justify-between"><span className="text-text-muted">วันที่</span><span>{issued.docDate}</span></div>
        <div className="flex justify-between"><span className="text-text-muted">อ้างอิง</span><span>{issued.reference || '-'}</span></div>
        <div className="flex justify-between"><span className="text-text-muted">ผู้จัดทำ</span><span>{issued.preparerName || issued.createdByName || '-'}</span></div>
        <div className="flex justify-between"><span className="text-text-muted">ยอดสินค้า</span><span>{formatMoney(issued.itemsTotal)}</span></div>
        <div className="flex justify-between"><span className="text-text-muted">หักมัดจำ</span><span>{formatMoney(issued.depositDeduction)}</span></div>
        <div className="flex justify-between font-bold"><span>รวมเป็นเงิน</span><span>{formatMoney(issued.netAmount)}</span></div>
        <div className="flex justify-between"><span className="text-text-muted">ภาษีมูลค่าเพิ่ม 7%</span><span>{formatMoney(issued.vatAmount)}</span></div>
        <div className="flex justify-between border-t border-border-subtle pt-1 text-base font-extrabold">
          <span>ยอดชำระ</span><span>{formatMoney(issued.grandTotal)}</span>
        </div>
      </div>
      {error ? <p className="m-0 text-sm font-bold text-danger" role="alert">{error}</p> : null}
      <div className="flex gap-2">
        <Button type="button" variant="primary" disabled={downloading} onClick={handleDownload}
          data-testid="remaining-invoice-download">
          {downloading ? 'กำลังดาวน์โหลด…' : 'ดาวน์โหลด'}
        </Button>
        {canWrite ? (
          <Button type="button" variant="secondary" disabled={revising} onClick={handleRevise}
            data-testid="remaining-invoice-revise">
            {revising ? 'กำลังออกฉบับแก้ไข…' : 'ออกฉบับแก้ไข'}
          </Button>
        ) : null}
      </div>
    </div>
  );
}

/** Editable form for the live DRAFT — save (re-snapshot), issue, or delete. */
function DraftEditor({ draft, issuedOfSameQuotation, onSaved, onIssued, onDeleted, onDownloaded }) {
  const templatesQuery = useQuery({
    queryKey: queryKeys.depositNoteTemplates(),
    queryFn: () => api.depositNotices.noteTemplates().then((r) => r.templates ?? []),
  });
  const templates = useMemo(
    () => [...(templatesQuery.data ?? [])].sort((a, b) => a.sortOrder - b.sortOrder),
    [templatesQuery.data],
  );

  // Restores the deposit-reference suggestion list: `api.tickets.remainingInvoiceOptions` (the
  // stateless preview) still serves `depositReferenceOptions`, and CreateForm below already queries
  // it for this. A NON-BLOCKING query feeding a <datalist> ONLY (like the reference field's own
  // `list=`); it never prefills a form field from this result, which preserves the invariant that
  // the editor's own FIELD VALUES come only from the stored `draft` row (pinned by this file's test
  // "prefills the editor from the stored draft").
  const depositReferenceOptionsQuery = useQuery({
    queryKey: queryKeys.remainingInvoiceOptions(draft.ticketId, draft.customerQuotationId),
    queryFn: () => api.tickets.remainingInvoiceOptions(draft.ticketId, draft.customerQuotationId)
      .then((r) => r.options),
    enabled: Boolean(draft.ticketId),
  });
  const depositReferenceOptions = depositReferenceOptionsQuery.data?.depositReferenceOptions ?? [];
  // The issue confirm shows THIS query's amounts, refetched when it opens: update + issue re-snapshot the money
  // from live data on the server, so the draft's last saved snapshot may no longer be what gets issued.
  const freshPreview = depositReferenceOptionsQuery.data;
  const previewLoading = depositReferenceOptionsQuery.isFetching;
  const previewFailed = !previewLoading && (depositReferenceOptionsQuery.isError || !freshPreview);
  const previewBlocking = !previewLoading && !previewFailed ? freshPreview.blockingReason : null;

  const [reference, setReference] = useState(draft.reference ?? '');
  const [depositReference, setDepositReference] = useState(draft.depositReference ?? '');
  const [docDate, setDocDate] = useState(draft.docDate ?? '');
  const [notes, setNotes] = useState(draft.notes ?? []);
  const [preparerId, setPreparerId] = useState(draft.preparerId ?? null);
  const [saving, setSaving] = useState(false);
  const [issuing, setIssuing] = useState(false);
  // Issuing mints the legal document number and cannot be undone, so it asks first.
  const [confirmingIssue, setConfirmingIssue] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState(null);

  // Re-prefill whenever a genuinely different (or freshly re-snapshotted) draft loads — same "key on the object
  // itself" discipline the stateless preview dialog already used.
  useEffect(() => {
    setReference(draft.reference ?? '');
    setDepositReference(draft.depositReference ?? '');
    setDocDate(draft.docDate ?? '');
    setPreparerId(draft.preparerId ?? null);
    setNotes(draft.notes ?? []);
  }, [draft]);

  function toggleNote(text) {
    setNotes((current) => (current.includes(text) ? current.filter((n) => n !== text) : [...current, text]));
  }

  async function handleSave() {
    setError(null);
    setSaving(true);
    try {
      await api.storedRemainingInvoices.update(draft.id, { reference, depositReference, docDate, notes, preparerId });
      await onSaved?.();
    } catch (err) {
      setError(err.message || 'บันทึกไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  // "ออกใบแจ้งหนี้" must not silently discard edits typed but never saved with "บันทึก": issue() on the
  // backend freezes whatever is LAST SAVED on the row (it only re-snapshots the COMPUTED content;
  // the dialog fields reference/depositReference/docDate/notes are preserved verbatim). So this
  // saves the CURRENT form state first, then issues.
  async function handleIssue() {
    setConfirmingIssue(false);
    setError(null);
    setIssuing(true);
    try {
      await api.storedRemainingInvoices.update(draft.id, { reference, depositReference, docDate, notes, preparerId });
      await api.storedRemainingInvoices.issue(draft.id);
      await onIssued?.();
    } catch (err) {
      setError(err.message || 'ออกใบแจ้งหนี้ไม่สำเร็จ');
    } finally {
      setIssuing(false);
    }
  }

  async function handleDelete() {
    setError(null);
    setDeleting(true);
    try {
      await api.storedRemainingInvoices.deleteDraft(draft.id);
      await onDeleted?.();
    } catch (err) {
      setError(err.message || 'ลบร่างไม่สำเร็จ');
    } finally {
      setDeleting(false);
    }
  }

  async function handleDownload() {
    setError(null);
    setDownloading(true);
    try {
      const blob = await api.storedRemainingInvoices.download(draft.id);
      downloadBlob(blob, draft.docNumber || `draft-${draft.id}`, 'pdf');
      onDownloaded?.();
    } catch (err) {
      setError(err.message || 'ดาวน์โหลดไม่สำเร็จ');
    } finally {
      setDownloading(false);
    }
  }

  const busy = saving || issuing || deleting;

  return (
    <div className="flex flex-col gap-4" data-testid="remaining-invoice-draft-editor">
      {/* The draft's own stored figures, as the preview box below already shows them; the issue confirm still
          re-reads the server's fresh preview before anything is issued. */}
      <RemainingSummaryLine net={draft.netAmount} itemsTotal={draft.itemsTotal} deposit={draft.depositDeduction} />
      <Section title="ข้อมูลเอกสาร">
        <div className={FIELD_GRID}>
          <FormField label="อ้างอิงใบเสนอราคา / ใบสั่งซื้อ (H9)" htmlFor="remaining-invoice-reference">
            <input id="remaining-invoice-reference" type="text" value={reference}
              onChange={(e) => setReference(e.target.value)} />
          </FormField>
          {draft.depositDeduction ? (
            <FormField label="เลขอ้างอิงมัดจำ (แถวหัก มัดจำ)" htmlFor="remaining-invoice-deposit-reference">
              <input id="remaining-invoice-deposit-reference" type="text" list="remaining-invoice-deposit-reference-options"
                value={depositReference} onChange={(e) => setDepositReference(e.target.value)} />
            </FormField>
          ) : null}
          <datalist id="remaining-invoice-deposit-reference-options">
            {depositReferenceOptions.map((opt) => (
              <option key={opt.value || '(blank)'} value={opt.value}>{opt.label}</option>
            ))}
          </datalist>
          <FormField label="วันที่ (H7)" htmlFor="remaining-invoice-issue-date">
            <ThaiDateInput id="remaining-invoice-issue-date" value={docDate ?? ''}
              onChange={(e) => setDocDate(e.target.value)} />
          </FormField>
          <DocumentPreparerSelect ticketId={draft.ticketId} id="remaining-invoice-preparer" value={preparerId}
            storedName={draft.preparerName} onChange={setPreparerId} />
        </div>
      </Section>

      {draft.items?.length ? <StoredItems items={draft.items} /> : null}

      <Section title="เงื่อนไข">
        <fieldset className="m-0 flex flex-col gap-1.5 border-0 p-0">
          <legend className="p-0 text-sm font-bold text-text-secondary">หมายเหตุ</legend>
          {templates.length === 0 ? (
            <p className="m-0 text-xs text-text-muted">ไม่มีหมายเหตุให้เลือก</p>
          ) : templates.map((note) => (
            <label key={note.id} className="flex items-start gap-2 text-sm font-normal text-text">
              <input type="checkbox" className="mt-0.5" checked={notes.includes(note.text)}
                onChange={() => toggleNote(note.text)} />
              <span>{note.text}</span>
            </label>
          ))}
        </fieldset>
      </Section>

      <Section title="ยอดรวม">
        <TotalsBlock
          rows={[
            { label: 'ยอดสินค้า', value: formatMoney(draft.itemsTotal) },
            { label: 'หักมัดจำ', value: formatMoney(draft.depositDeduction) },
            { label: 'รวมเป็นเงิน', value: formatMoney(draft.netAmount), bold: true },
            { label: 'ภาษีมูลค่าเพิ่ม 7%', value: formatMoney(draft.vatAmount) },
          ]}
          payable={{ label: 'ยอดชำระ', value: formatMoney(draft.grandTotal) }} />
      </Section>

      {error ? <p className="m-0 text-sm font-bold text-danger" role="alert">{error}</p> : null}

      {/* Not in the drawing, kept: the draft's preview download and ลบร่าง, each with its old gate. */}
      <div className="flex flex-wrap gap-2 mobile:flex-col [&>button]:mobile:w-full">
        <Button type="button" variant="secondary" disabled={downloading} onClick={handleDownload}
          data-testid="remaining-invoice-download-draft">
          {downloading ? 'กำลังดาวน์โหลด…' : 'ดาวน์โหลด (ตัวอย่าง)'}
        </Button>
        <Button type="button" variant="danger" disabled={busy} onClick={handleDelete}
          data-testid="remaining-invoice-delete-draft">
          {deleting ? 'กำลังลบ…' : 'ลบร่าง'}
        </Button>
      </div>

      <DialogFooterRow>
        <Button type="button" variant="secondary" disabled={busy} onClick={handleSave}
          data-testid="remaining-invoice-save-draft">
          {saving ? 'กำลังบันทึก…' : 'บันทึกร่าง'}
        </Button>
        <Button type="button" variant="primary" disabled={busy} onClick={() => { depositReferenceOptionsQuery.refetch(); setConfirmingIssue(true); }}
          data-testid="remaining-invoice-issue">
          {issuing ? 'กำลังออกเอกสาร…' : 'ออกใบแจ้งหนี้'}
        </Button>
      </DialogFooterRow>

      <ConfirmDialog
        open={confirmingIssue}
        title="ยืนยันการออกเอกสาร"
        message={(
          <div className="flex flex-col gap-1 text-sm">
            <div className="flex justify-between"><span className="text-text-muted">อ้างอิง</span><span>{reference || '-'}</span></div>
            {previewLoading ? <p className="m-0 text-text-muted">กำลังคำนวณยอด…</p> : null}
            {previewFailed ? <p className="m-0 font-bold text-danger" role="alert">คำนวณยอดไม่สำเร็จ — ระบบจะคำนวณใหม่ตอนออกเอกสาร</p> : null}
            {previewBlocking ? <p className="m-0 font-bold text-danger" role="alert">{previewBlocking}</p> : null}
            {!previewLoading && !previewFailed && !previewBlocking ? (
              <>
                <div className="flex justify-between"><span className="text-text-muted">ยอดสินค้า</span><span>{formatMoney(freshPreview.itemsTotal)}</span></div>
                <div className="flex justify-between"><span className="text-text-muted">หักมัดจำ</span><span>{formatMoney(freshPreview.depositAmount)}</span></div>
                <div className="flex justify-between"><span className="text-text-muted">ภาษีมูลค่าเพิ่ม 7%</span><span>{formatMoney(freshPreview.vatAmount)}</span></div>
                <div className="flex justify-between font-bold"><span>ยอดชำระ</span><span>{formatMoney(freshPreview.totalPayable)}</span></div>
              </>
            ) : null}
            <p className="m-0 pt-2 text-text-secondary">
              {issuedOfSameQuotation
                ? `เลขที่เอกสาร ${issuedOfSameQuotation.baseNumber}-${issuedOfSameQuotation.version + 1}`
                : 'ระบบจะออกเลขที่เอกสารใหม่เมื่อยืนยัน'}
            </p>
          </div>
        )}
        confirmLabel="ออกใบแจ้งหนี้"
        busy={issuing}
        confirmDisabled={previewLoading || Boolean(previewBlocking)}
        onConfirm={handleIssue}
        onCancel={() => setConfirmingIssue(false)}
      />
    </div>
  );
}

/**
 * No live document yet — the stateless prefill preview (`api.tickets.remainingInvoiceOptions`),
 * whose terminal action creates a STORED DRAFT (`api.storedRemainingInvoices.createDraft`) instead
 * of downloading a one-shot file.
 */
function CreateForm({ ticketId, onCreated }) {
  const [selectedQuotationId, setSelectedQuotationId] = useState(null);

  const optionsQuery = useQuery({
    queryKey: queryKeys.remainingInvoiceOptions(ticketId, selectedQuotationId),
    queryFn: () => api.tickets.remainingInvoiceOptions(ticketId, selectedQuotationId).then((r) => r.options),
    enabled: Boolean(ticketId),
  });
  const options = optionsQuery.data;

  const [reference, setReference] = useState('');
  const [depositReference, setDepositReference] = useState('');
  const [issueDate, setIssueDate] = useState('');
  const [selectedNoteIds, setSelectedNoteIds] = useState([]);
  const [initialized, setInitialized] = useState(false);
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState(null);

  // This effect keys on `options` itself, not a derived scalar.
  useEffect(() => {
    if (!options) return;
    setReference(options.defaultReference ?? '');
    setDepositReference(options.defaultDepositReference ?? '');
    setIssueDate(options.defaultIssueDate ?? '');
    setSelectedNoteIds((options.noteTemplates ?? []).filter((t) => t.defaultSelected).map((t) => t.id));
    setInitialized(true);
  }, [options]);

  const blockingReason = options?.blockingReason ?? null;
  const showQuotationPicker = (options?.quotationOptions ?? []).length >= 2;

  const sortedNotes = useMemo(
    () => [...(options?.noteTemplates ?? [])].sort((a, b) => a.sortOrder - b.sortOrder),
    [options],
  );

  function toggleNote(id) {
    setSelectedNoteIds((current) => (current.includes(id) ? current.filter((n) => n !== id) : [...current, id]));
  }

  async function handleCreateDraft() {
    setCreateError(null);
    setCreating(true);
    try {
      const noteTexts = sortedNotes.filter((t) => selectedNoteIds.includes(t.id)).map((t) => t.text);
      await api.storedRemainingInvoices.createDraft(ticketId, {
        quotationId: selectedQuotationId ?? options?.defaultQuotationId ?? undefined,
        reference, depositReference, docDate: issueDate, notes: noteTexts,
      });
      await onCreated?.();
    } catch (err) {
      setCreateError(err.message || 'สร้างร่างไม่สำเร็จ');
    } finally {
      setCreating(false);
    }
  }

  const canCreate = initialized && !blockingReason && !creating;

  return (
    <div className="flex flex-col gap-4">
      {optionsQuery.isLoading ? (
        <Skeleton height={220} />
      ) : optionsQuery.isError ? (
        <p className="m-0 text-sm font-bold text-danger" role="alert">
          {optionsQuery.error?.message || 'โหลดข้อมูลใบแจ้งหนี้ส่วนที่เหลือไม่สำเร็จ'}
        </p>
      ) : !options ? (
        <Skeleton height={220} />
      ) : (
        <>
          {/* A blocked preview is a document the server will not issue, so it gets no headline figures. */}
          {!blockingReason ? (
            <RemainingSummaryLine net={options.netAmount} itemsTotal={options.itemsTotal} deposit={options.depositAmount} />
          ) : null}

          {blockingReason ? (
            <p className="m-0 rounded-md border border-danger bg-danger-bg p-3 text-sm font-bold text-danger" role="alert"
              data-testid="remaining-invoice-blocking-reason">
              {blockingReason} — สร้างร่างไม่ได้จนกว่าจะแก้ไข
            </p>
          ) : null}

          <Section title="ข้อมูลเอกสาร">
            <div className={FIELD_GRID}>
              {showQuotationPicker ? (
                <FormField label="ใบเสนอราคา (มีมากกว่า 1 ฉบับที่ใช้ได้)" htmlFor="remaining-invoice-quotation">
                  <select id="remaining-invoice-quotation"
                    value={selectedQuotationId ?? options.defaultQuotationId ?? ''}
                    onChange={(e) => setSelectedQuotationId(e.target.value ? Number(e.target.value) : null)}>
                    {options.quotationOptions.map((opt) => (
                      <option key={opt.value} value={opt.value}>{opt.label}</option>
                    ))}
                  </select>
                </FormField>
              ) : null}

              <FormField label="อ้างอิงใบเสนอราคา / ใบสั่งซื้อ (H9)" htmlFor="remaining-invoice-reference">
                <div className="flex gap-2">
                  <input id="remaining-invoice-reference" type="text" className="min-w-0 flex-1" list="remaining-invoice-reference-options"
                    value={reference} placeholder="พิมพ์เลขที่ใบสั่งซื้อของลูกค้า หรือเว้นว่าง"
                    onChange={(e) => setReference(e.target.value)} />
                  <Button type="button" variant="secondary" onClick={() => setReference('')} title="ล้างค่า" aria-label="ล้างอ้างอิง">
                    ล้าง
                  </Button>
                </div>
                <datalist id="remaining-invoice-reference-options">
                  {(options.referenceOptions ?? []).map((opt) => (
                    <option key={opt.value} value={opt.value}>{opt.label}</option>
                  ))}
                </datalist>
              </FormField>

              {options.depositAmount > 0 && (options.depositReferenceOptions ?? []).length > 0 ? (
                <FormField label="เลขอ้างอิงมัดจำ (แถวหัก มัดจำ)" htmlFor="remaining-invoice-deposit-reference">
                  <select id="remaining-invoice-deposit-reference" value={depositReference}
                    onChange={(e) => setDepositReference(e.target.value)}>
                    {options.depositReferenceOptions.map((opt) => (
                      <option key={opt.value || '(blank)'} value={opt.value}>{opt.label}</option>
                    ))}
                  </select>
                </FormField>
              ) : null}

              <FormField label="วันที่ (H7)" htmlFor="remaining-invoice-issue-date">
                <ThaiDateInput id="remaining-invoice-issue-date" value={issueDate}
                  onChange={(e) => setIssueDate(e.target.value)} />
              </FormField>
            </div>
          </Section>

          <Section title="เงื่อนไข">
            <fieldset className="m-0 flex flex-col gap-1.5 border-0 p-0">
              <legend className="p-0 text-sm font-bold text-text-secondary">หมายเหตุ</legend>
              {sortedNotes.length === 0 ? (
                <p className="m-0 text-xs text-text-muted">ไม่มีหมายเหตุให้เลือก</p>
              ) : sortedNotes.map((note) => (
                <label key={note.id} className="flex items-start gap-2 text-sm font-normal text-text">
                  <input type="checkbox" className="mt-0.5" checked={selectedNoteIds.includes(note.id)}
                    onChange={() => toggleNote(note.id)} />
                  <span>{note.text}</span>
                </label>
              ))}
            </fieldset>
          </Section>

          <Section title="ยอดรวม">
            <TotalsBlock
              rows={[
                { label: 'จำนวนรายการ', value: options.itemCount },
                { label: 'ยอดสินค้า', value: formatMoney(options.itemsTotal) },
                { label: 'หักมัดจำ', value: formatMoney(options.depositAmount) },
                { label: 'รวมเป็นเงิน', value: formatMoney(options.netAmount), bold: true },
                { label: 'ภาษีมูลค่าเพิ่ม 7%', value: formatMoney(options.vatAmount) },
              ]}
              payable={{ label: 'ยอดชำระ', value: formatMoney(options.totalPayable) }} />
          </Section>

          {createError ? <p className="m-0 text-sm font-bold text-danger" role="alert">{createError}</p> : null}

          {/* "สร้างร่าง" became the drawn "บันทึกร่าง": this button creates the stored DRAFT, nothing more. */}
          <DialogFooterRow>
            <Button type="button" variant="primary" className="ml-auto mobile:ml-0" disabled={!canCreate} onClick={handleCreateDraft}
              data-testid="remaining-invoice-create-draft">
              {creating ? 'กำลังบันทึกร่าง…' : 'บันทึกร่าง'}
            </Button>
          </DialogFooterRow>
        </>
      )}
    </div>
  );
}
