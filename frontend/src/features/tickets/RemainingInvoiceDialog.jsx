import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { downloadBlob } from '../../utils/download.js';
import { formatMoney } from '../../utils/format.js';

/**
 * Prefilled-dialog for ใบแจ้งหนี้ส่วนที่เหลือ (remaining invoice): stateless by owner decision —
 * every field here is a DEFAULT the backend already computed
 * (DepositNoticeService#getRemainingInvoiceOptions), never a stored draft. A caller who wants the
 * one-click behaviour can still call `api.tickets.downloadRemainingInvoice(ticketId)` directly
 * with no params at all; this dialog exists for the cases where the default reference/deposit
 * reference/date/notes need a look or an override before the file goes out.
 *
 * Field values ALWAYS round-trip to the download call once the options have loaded — there is no
 * "omit this param" state reachable from the UI (that only happens on the bare one-click path).
 * Clearing the reference field therefore sends an explicit empty string (leave H9 blank), not a
 * fallback to the default; see DepositNoticeController's own query-param Javadoc for why "absent"
 * and "present but empty" are deliberately different wire states.
 */
export function RemainingInvoiceDialog({ ticketId, onClose, onDownloaded }) {
  // Live-preview selector (owner ruling — several ACCEPTED quotations can qualify): re-fetches
  // options for the chosen quotation so items/deposit/references never mix across quotations.
  // null until the caller picks one explicitly; the very first load always asks for the
  // server's own default (no quotationId param) so a single-quotation deal never round-trips
  // twice.
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
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState(null);

  // Prefill on every genuinely NEW options payload — keyed on the `options` object itself, not on
  // a derived scalar like defaultQuotationId/docNumber. Those two are INVARIANT across a
  // quotation switch: defaultQuotationId is always "the newest qualifying quotation" (computed in
  // DepositNoticeService#resolveRemainingInvoice before it ever looks at the caller-supplied
  // quotationId), and docNumber is derived from the ticket alone — so keying on them used to only
  // re-prefill by ACCIDENT, when `options` happened to pass through `undefined` mid-refetch (which
  // also produced a jarring full Skeleton flash on every switch). That accident does not survive a
  // switch BACK to an already-cached quotation: React Query serves the cached data for that
  // (ticketId, quotationId) key in the very same render, with no undefined in between, so the old
  // deps never changed value and the fields kept showing the PREVIOUS quotation's stale data even
  // though `options` itself had already moved on — see RemainingInvoiceDialog.test.jsx's own
  // A -> B -> A regression test for exactly this.
  //
  // React Query hands back a FRESH object from the queryFn on every settled fetch for the ACTIVE
  // (ticketId, selectedQuotationId) key — including a cache hit, which still resolves to the
  // object captured at that key's own original fetch, distinct from whatever was shown just
  // before — and keeps that reference stable across unrelated re-renders (typing in the reference
  // field, toggling a note). Depending on `options` itself therefore re-runs this effect exactly
  // when — and only when — the data it reads has actually changed, correct by construction rather
  // than by cache-timing luck.
  useEffect(() => {
    if (!options) return;
    setReference(options.defaultReference ?? '');
    setDepositReference(options.defaultDepositReference ?? '');
    setIssueDate(options.defaultIssueDate ?? '');
    setSelectedNoteIds(
      (options.noteTemplates ?? []).filter((t) => t.defaultSelected).map((t) => t.id),
    );
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
    setSelectedNoteIds((current) => (
      current.includes(id) ? current.filter((n) => n !== id) : [...current, id]
    ));
  }

  async function handleDownload() {
    setDownloadError(null);
    setDownloading(true);
    try {
      const blob = await api.tickets.downloadRemainingInvoice(ticketId, {
        reference,
        depositReference,
        issueDate,
        noteIds: selectedNoteIds,
        quotationId: selectedQuotationId ?? options?.defaultQuotationId ?? undefined,
      });
      downloadBlob(blob, `remaining-invoice-${ticketId}`, 'xlsx');
      onDownloaded?.();
      onClose();
    } catch (err) {
      setDownloadError(err.message || 'ดาวน์โหลดไม่สำเร็จ');
    } finally {
      setDownloading(false);
    }
  }

  const canDownload = initialized && !overCapacity && !blockingReason && !downloading;

  if (!ticketId) return null;

  return (
    <Modal
      title="ใบแจ้งหนี้ส่วนที่เหลือ"
      subtitle={options?.docNumber ? `เลขที่เอกสาร ${options.docNumber}` : undefined}
      onClose={onClose}
      testId="remaining-invoice-dialog"
      footer={(
        <>
          <Button type="button" variant="secondary" onClick={onClose}>ยกเลิก</Button>
          <Button type="button" variant="primary" disabled={!canDownload} onClick={handleDownload}
            data-testid="remaining-invoice-download">
            {downloading ? 'กำลังดาวน์โหลด…' : 'ดาวน์โหลด'}
          </Button>
        </>
      )}
    >
      {optionsQuery.isLoading ? (
        <Skeleton height={220} />
      ) : optionsQuery.isError ? (
        <p className="m-0 text-sm font-bold text-danger" role="alert">
          {optionsQuery.error?.message || 'โหลดข้อมูลใบแจ้งหนี้ส่วนที่เหลือไม่สำเร็จ'}
        </p>
      ) : !options ? (
        // Defensive guard: options can be undefined for a beat even after isLoading/isError both
        // clear (e.g. a query disabled mid-flight by a falsy ticketId) — never index into it.
        <Skeleton height={220} />
      ) : (
        <div className="flex flex-col gap-4">
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
              {blockingReason} — ดาวน์โหลดไม่ได้จนกว่าจะแก้ไข
            </p>
          ) : null}

          {overCapacity ? (
            <p className="m-0 rounded-md border border-danger bg-danger-bg p-3 text-sm font-bold text-danger" role="alert">
              {`รายการมี ${options.itemCount} รายการ เกินความจุของแบบฟอร์ม (สูงสุด ${options.maxItems} แถว) `}
              กรุณารวมรายการหรือติดต่อผู้ดูแลระบบ — ดาวน์โหลดไม่ได้จนกว่าจะแก้ไข
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

          {/* Rule D: hidden entirely when there is no deduction (bypass policy / no matched
              deposit notice) — depositAmount is 0 and depositReferenceOptions is empty in that
              case, so there is nothing meaningful to pick a reference for and no deduction row
              will print. */}
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

          {downloadError ? (
            <p className="m-0 text-sm font-bold text-danger" role="alert">{downloadError}</p>
          ) : null}
        </div>
      )}
    </Modal>
  );
}
