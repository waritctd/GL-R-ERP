import { useEffect, useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { downloadBlob } from '../../utils/download.js';
import { formatMoney } from '../../utils/format.js';

const STATUS_LABEL = { DRAFT: 'ร่าง', ISSUED: 'ออกแล้ว', SUPERSEDED: 'ถูกแทนที่แล้ว' };

/**
 * ใบแจ้งหนี้ส่วนที่เหลือ — now a STORED, versioned document (V188, GLA-99 step 2): DRAFT ->
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
 *  - Every ISSUED/SUPERSEDED version is listed below, each individually downloadable — including,
 *    per P4 (GLA-99 step 2 review-round-2), while a revision DRAFT is open above it: the document
 *    being corrected stays ISSUED (see RemainingInvoiceService#revise's own Javadoc) and must stay
 *    reachable/downloadable, not disappear behind the draft editor.
 *
 * `canWrite` (R4, owner ruling 2026-09-20 — GLA-99 step 2 review-round-1): the caller must pass
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

  function invalidateList() {
    return queryClient.invalidateQueries({ queryKey: queryKeys.storedRemainingInvoices(ticketId) });
  }

  async function handleDownloadVersion(id, docNumber) {
    const blob = await api.storedRemainingInvoices.download(id);
    downloadBlob(blob, docNumber || `draft-${id}`, 'xlsx');
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
          {draft && canWrite ? (
            <DraftEditor draft={draft} onSaved={invalidateList} onIssued={invalidateList}
              onDeleted={invalidateList} onDownloaded={onDownloaded} />
          ) : issued ? (
            <IssuedSummary issued={issued} canWrite={canWrite} onRevised={invalidateList} onDownloaded={onDownloaded} />
          ) : canWrite ? (
            <CreateForm ticketId={ticketId} onCreated={invalidateList} />
          ) : (
            <WaitingForSalesState />
          )}
          {/* P4 (Opus review, GLA-99 step 2 review-round-2, 2026-09-20): this used to live ONLY
              inside the `issued` branch above, so opening a revision (draft && canWrite both true,
              while the predecessor is still ISSUED per RemainingInvoiceService#revise's own
              Javadoc) hid the live ISSUED document AND its download entirely — the caller could see
              nothing of what they were about to replace. `history` already includes every
              non-DRAFT row (the live ISSUED one included, per its own filter above), so hoisting
              this out to render unconditionally alongside EITHER branch is enough: a revision in
              progress still shows the predecessor's own download button here. */}
          {history.length > 0 ? (
            <VersionHistory versions={history} onDownload={handleDownloadVersion} />
          ) : null}
        </>
      )}
    </Modal>
  );
}

/** R4: nothing issued yet, and the viewer is not the deal's owning sales rep — there is nothing
 * for them to write OR to read/download, so the dialog says so plainly instead of either hiding
 * itself (which would look like the feature is broken) or showing controls that would only 403. */
function WaitingForSalesState() {
  return (
    <p className="m-0 rounded-md border border-border-subtle bg-surface-muted p-3 text-sm text-text-secondary"
      data-testid="remaining-invoice-waiting-for-sales">
      ยังไม่มีใบแจ้งหนี้ส่วนที่เหลือสำหรับดีลนี้ — รอฝ่ายขายออกเอกสาร
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
              <span className="text-text-muted">({STATUS_LABEL[v.status] ?? v.status})</span>
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
 * hidden when `canWrite` is false (R4): a non-owning viewer still reads/downloads, never revises. */
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
      downloadBlob(blob, issued.docNumber || `remaining-invoice-${issued.id}`, 'xlsx');
      onDownloaded?.();
    } catch (err) {
      setError(err.message || 'ดาวน์โหลดไม่สำเร็จ');
    } finally {
      setDownloading(false);
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1 rounded-md border border-border-subtle bg-surface-muted p-3 text-sm" data-testid="remaining-invoice-issued-summary">
        <div className="flex justify-between"><span className="text-text-muted">เลขที่เอกสาร</span><span className="font-bold">{issued.docNumber}</span></div>
        <div className="flex justify-between"><span className="text-text-muted">วันที่</span><span>{issued.docDate}</span></div>
        <div className="flex justify-between"><span className="text-text-muted">อ้างอิง</span><span>{issued.reference || '-'}</span></div>
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
function DraftEditor({ draft, onSaved, onIssued, onDeleted, onDownloaded }) {
  const templatesQuery = useQuery({
    queryKey: queryKeys.depositNoteTemplates(),
    queryFn: () => api.depositNotices.noteTemplates().then((r) => r.templates ?? []),
  });
  const templates = useMemo(
    () => [...(templatesQuery.data ?? [])].sort((a, b) => a.sortOrder - b.sortOrder),
    [templatesQuery.data],
  );

  // P7 (Opus review, GLA-99 step 2 review-round-2, 2026-09-20): RESTORES the deposit-reference
  // suggestion list review-round-1's own nit had dropped as "not cheap" — that reasoning was
  // wrong. `api.tickets.remainingInvoiceOptions` (the stateless preview) still serves
  // `depositReferenceOptions`, and CreateForm below already queries it for exactly this purpose.
  // The fix is a NON-BLOCKING query feeding a <datalist> ONLY, same pattern as the reference
  // field's own `list=` attribute — it never calls setState/prefills a form field from this
  // result. That preserves the invariant review-round-1 was protecting (the editor's own FIELD
  // VALUES still come only from the stored `draft` row, pinned by this file's own "prefills the
  // editor from the stored draft" test) while giving the field its suggestion list back.
  const depositReferenceOptionsQuery = useQuery({
    queryKey: queryKeys.remainingInvoiceOptions(draft.ticketId, draft.customerQuotationId),
    queryFn: () => api.tickets.remainingInvoiceOptions(draft.ticketId, draft.customerQuotationId)
      .then((r) => r.options),
    enabled: Boolean(draft.ticketId),
  });
  const depositReferenceOptions = depositReferenceOptionsQuery.data?.depositReferenceOptions ?? [];

  const [reference, setReference] = useState(draft.reference ?? '');
  const [depositReference, setDepositReference] = useState(draft.depositReference ?? '');
  const [docDate, setDocDate] = useState(draft.docDate ?? '');
  const [notes, setNotes] = useState(draft.notes ?? []);
  const [saving, setSaving] = useState(false);
  const [issuing, setIssuing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState(null);

  // Re-prefill whenever a genuinely different (or freshly re-snapshotted) draft loads — same
  // "key on the object itself" discipline the stateless preview dialog already used.
  useEffect(() => {
    setReference(draft.reference ?? '');
    setDepositReference(draft.depositReference ?? '');
    setDocDate(draft.docDate ?? '');
    setNotes(draft.notes ?? []);
  }, [draft]);

  function toggleNote(text) {
    setNotes((current) => (current.includes(text) ? current.filter((n) => n !== text) : [...current, text]));
  }

  async function handleSave() {
    setError(null);
    setSaving(true);
    try {
      await api.storedRemainingInvoices.update(draft.id, { reference, depositReference, docDate, notes });
      await onSaved?.();
    } catch (err) {
      setError(err.message || 'บันทึกไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  // R2 (Opus review, GLA-99 step 2 review-round-1): "ออกใบแจ้งหนี้" must not silently discard
  // edits the caller typed but never clicked "บันทึก" for — issue() on the backend freezes
  // whatever is LAST SAVED on the row (it only re-snapshots the COMPUTED content, per O3; the
  // dialog fields reference/depositReference/docDate/notes are preserved verbatim, never
  // recomputed there). So this saves the CURRENT form state first, then issues.
  async function handleIssue() {
    setError(null);
    setIssuing(true);
    try {
      await api.storedRemainingInvoices.update(draft.id, { reference, depositReference, docDate, notes });
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
      downloadBlob(blob, draft.docNumber || `draft-${draft.id}`, 'xlsx');
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
      <div className="flex flex-col gap-1.5">
        <label className="text-sm font-bold text-text-secondary" htmlFor="remaining-invoice-reference">
          อ้างอิงใบเสนอราคา / ใบสั่งซื้อ (H9)
        </label>
        <input id="remaining-invoice-reference" type="text" value={reference}
          onChange={(e) => setReference(e.target.value)} />
      </div>

      {draft.depositDeduction ? (
        <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary" htmlFor="remaining-invoice-deposit-reference">
          เลขอ้างอิงมัดจำ (แถวหัก มัดจำ)
          <input id="remaining-invoice-deposit-reference" type="text" list="remaining-invoice-deposit-reference-options"
            value={depositReference} onChange={(e) => setDepositReference(e.target.value)} />
        </label>
      ) : null}
      <datalist id="remaining-invoice-deposit-reference-options">
        {depositReferenceOptions.map((opt) => (
          <option key={opt.value || '(blank)'} value={opt.value}>{opt.label}</option>
        ))}
      </datalist>

      <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary" htmlFor="remaining-invoice-issue-date">
        วันที่ (H7)
        <input id="remaining-invoice-issue-date" type="date" value={docDate ?? ''}
          onChange={(e) => setDocDate(e.target.value)} />
      </label>

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

      <div className="flex flex-col gap-1 rounded-md border border-border-subtle bg-surface-muted p-3 text-sm" data-testid="remaining-invoice-preview">
        <div className="flex justify-between"><span className="text-text-muted">ยอดสินค้า</span><span>{formatMoney(draft.itemsTotal)}</span></div>
        <div className="flex justify-between"><span className="text-text-muted">หักมัดจำ</span><span>{formatMoney(draft.depositDeduction)}</span></div>
        <div className="flex justify-between font-bold"><span>รวมเป็นเงิน</span><span>{formatMoney(draft.netAmount)}</span></div>
        <div className="flex justify-between"><span className="text-text-muted">ภาษีมูลค่าเพิ่ม 7%</span><span>{formatMoney(draft.vatAmount)}</span></div>
        <div className="flex justify-between border-t border-border-subtle pt-1 text-base font-extrabold">
          <span>ยอดชำระ</span><span>{formatMoney(draft.grandTotal)}</span>
        </div>
      </div>

      {error ? <p className="m-0 text-sm font-bold text-danger" role="alert">{error}</p> : null}

      <div className="flex flex-wrap gap-2">
        <Button type="button" variant="secondary" disabled={busy} onClick={handleSave}
          data-testid="remaining-invoice-save-draft">
          {saving ? 'กำลังบันทึก…' : 'บันทึก'}
        </Button>
        <Button type="button" variant="primary" disabled={busy} onClick={handleIssue}
          data-testid="remaining-invoice-issue">
          {issuing ? 'กำลังออกเอกสาร…' : 'ออกใบแจ้งหนี้'}
        </Button>
        <Button type="button" variant="secondary" disabled={downloading} onClick={handleDownload}
          data-testid="remaining-invoice-download-draft">
          {downloading ? 'กำลังดาวน์โหลด…' : 'ดาวน์โหลด (ตัวอย่าง)'}
        </Button>
        <Button type="button" variant="danger" disabled={busy} onClick={handleDelete}
          data-testid="remaining-invoice-delete-draft">
          {deleting ? 'กำลังลบ…' : 'ลบร่าง'}
        </Button>
      </div>
    </div>
  );
}

/**
 * No live document yet — the pre-existing stateless prefill preview
 * (`api.tickets.remainingInvoiceOptions`), unchanged, but its terminal action now creates a
 * STORED DRAFT (`api.storedRemainingInvoices.createDraft`) instead of downloading a one-shot file.
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

  // See the pre-V188 revision of this file for the full reasoning on why this effect keys on
  // `options` itself, not a derived scalar — unchanged by this branch.
  useEffect(() => {
    if (!options) return;
    setReference(options.defaultReference ?? '');
    setDepositReference(options.defaultDepositReference ?? '');
    setIssueDate(options.defaultIssueDate ?? '');
    setSelectedNoteIds((options.noteTemplates ?? []).filter((t) => t.defaultSelected).map((t) => t.id));
    setInitialized(true);
  }, [options]);

  const overCapacity = Boolean(options) && options.itemCount > options.maxItems;
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

  const canCreate = initialized && !overCapacity && !blockingReason && !creating;

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
          {showQuotationPicker ? (
            <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary" htmlFor="remaining-invoice-quotation">
              ใบเสนอราคา (มีมากกว่า 1 ฉบับที่ใช้ได้)
              <select id="remaining-invoice-quotation"
                value={selectedQuotationId ?? options.defaultQuotationId ?? ''}
                onChange={(e) => setSelectedQuotationId(e.target.value ? Number(e.target.value) : null)}>
                {options.quotationOptions.map((opt) => (
                  <option key={opt.value} value={opt.value}>{opt.label}</option>
                ))}
              </select>
            </label>
          ) : null}

          {blockingReason ? (
            <p className="m-0 rounded-md border border-danger bg-danger-bg p-3 text-sm font-bold text-danger" role="alert"
              data-testid="remaining-invoice-blocking-reason">
              {blockingReason} — สร้างร่างไม่ได้จนกว่าจะแก้ไข
            </p>
          ) : null}

          {overCapacity ? (
            <p className="m-0 rounded-md border border-danger bg-danger-bg p-3 text-sm font-bold text-danger" role="alert">
              {`รายการมี ${options.itemCount} รายการ เกินความจุของแบบฟอร์ม (สูงสุด ${options.maxItems} แถว) `}
              กรุณารวมรายการหรือติดต่อผู้ดูแลระบบ — สร้างร่างไม่ได้จนกว่าจะแก้ไข
            </p>
          ) : null}

          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-bold text-text-secondary" htmlFor="remaining-invoice-reference">
              อ้างอิงใบเสนอราคา / ใบสั่งซื้อ (H9)
            </label>
            <div className="flex gap-2">
              <input id="remaining-invoice-reference" type="text" className="flex-1" list="remaining-invoice-reference-options"
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
          </div>

          {options.depositAmount > 0 && (options.depositReferenceOptions ?? []).length > 0 ? (
            <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary" htmlFor="remaining-invoice-deposit-reference">
              เลขอ้างอิงมัดจำ (แถวหัก มัดจำ)
              <select id="remaining-invoice-deposit-reference" value={depositReference}
                onChange={(e) => setDepositReference(e.target.value)}>
                {options.depositReferenceOptions.map((opt) => (
                  <option key={opt.value || '(blank)'} value={opt.value}>{opt.label}</option>
                ))}
              </select>
            </label>
          ) : null}

          <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary" htmlFor="remaining-invoice-issue-date">
            วันที่ (H7)
            <input id="remaining-invoice-issue-date" type="date" value={issueDate}
              onChange={(e) => setIssueDate(e.target.value)} />
          </label>

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

          <div className="flex flex-col gap-1 rounded-md border border-border-subtle bg-surface-muted p-3 text-sm" data-testid="remaining-invoice-preview">
            <div className="flex justify-between"><span className="text-text-muted">จำนวนรายการ</span><span>{options.itemCount}</span></div>
            <div className="flex justify-between"><span className="text-text-muted">ยอดสินค้า</span><span>{formatMoney(options.itemsTotal)}</span></div>
            <div className="flex justify-between"><span className="text-text-muted">หักมัดจำ</span><span>{formatMoney(options.depositAmount)}</span></div>
            <div className="flex justify-between font-bold"><span>รวมเป็นเงิน</span><span>{formatMoney(options.netAmount)}</span></div>
            <div className="flex justify-between"><span className="text-text-muted">ภาษีมูลค่าเพิ่ม 7%</span><span>{formatMoney(options.vatAmount)}</span></div>
            <div className="flex justify-between border-t border-border-subtle pt-1 text-base font-extrabold">
              <span>ยอดชำระ</span><span>{formatMoney(options.totalPayable)}</span>
            </div>
          </div>

          {createError ? <p className="m-0 text-sm font-bold text-danger" role="alert">{createError}</p> : null}

          <Button type="button" variant="primary" disabled={!canCreate} onClick={handleCreateDraft}
            data-testid="remaining-invoice-create-draft">
            {creating ? 'กำลังสร้างร่าง…' : 'สร้างร่าง'}
          </Button>
        </>
      )}
    </div>
  );
}
