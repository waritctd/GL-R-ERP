/* Tokens only. Step 3 for the invoice issuer (phone design M16): attach the issued tax invoice (an Express PDF), check
 * what the system read from it (or type it when it could not), then confirm. Confirming marks the round invoiced, counts
 * the invoice as money received (the part not already recorded) and tells the warehouse it may deliver. */
import { useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../../../api/index.js';
import { queryKeys } from '../../../api/queryKeys.js';
import { Button } from '../../../components/common/Button.jsx';
import { Icon } from '../../../components/common/Icon.jsx';
import { Modal } from '../../../components/common/Modal.jsx';
import { ThaiDateInput } from '../../../components/common/ThaiDateInput.jsx';
import { bangkokTodayIso, formatMoney, formatThaiDate } from '../../../utils/format.js';
import { fileSizeText, MAX_UPLOAD_BYTES } from './deliveryRound.js';

const AMOUNT = /^\d+(\.\d{1,2})?$/;
const amountOf = (text) => String(text ?? '').replace(/,/g, '').trim();

/** What is wrong with the three values, or null: the reason the confirm button stays off. */
export function invoiceFormProblem({ number, date, total }) {
  if (!number.trim() || number.trim().length > 60) return 'กรอกเลขที่ใบกำกับ (ไม่เกิน 60 ตัวอักษร)';
  if (!date) return 'เลือกวันที่ของใบกำกับ';
  if (date > bangkokTodayIso()) return 'วันที่ใบกำกับต้องไม่เป็นวันในอนาคต';
  if (!AMOUNT.test(amountOf(total)) || Number(amountOf(total)) <= 0) return 'กรอกยอดรวมเป็นตัวเลขที่มากกว่า 0';
  return null;
}

function ConfirmSheet({ values, invoiceable, pending, error, onConfirm, onClose }) {
  return (
    <Modal
      title="ยืนยันใบกำกับภาษี"
      onClose={pending ? () => {} : onClose}
      placement="sheet-on-phone"
      testId="invoice-confirm-sheet"
      footer={(
        <div className="flex w-full flex-col gap-1">
          {error ? <p role="alert" className="m-0 text-center text-sm text-danger" data-testid="invoice-confirm-error">{error}</p> : null}
          <Button type="button" variant="primary" className="w-full" disabled={pending} data-testid="invoice-confirm-go" onClick={onConfirm}>
            {pending ? 'กำลังบันทึก…' : 'ยืนยันและแจ้งคลัง'}
          </Button>
          <Button type="button" variant="text" className="min-h-12 w-full" disabled={pending} onClick={onClose}>กลับไปแก้</Button>
        </div>
      )}
    >
      <dl className="m-0 flex flex-col gap-2 text-base">
        <div className="flex justify-between gap-3"><dt className="text-text-muted">เลขที่ใบกำกับ</dt><dd className="m-0 font-bold">{values.number.trim()}</dd></div>
        <div className="flex justify-between gap-3"><dt className="text-text-muted">วันที่</dt><dd className="m-0 font-bold">{formatThaiDate(values.date)}</dd></div>
        <div className="flex justify-between gap-3"><dt className="text-text-muted">ยอดรวม รวม VAT</dt><dd className="m-0 font-bold tabular-nums">{formatMoney(amountOf(values.total))}</dd></div>
        {invoiceable != null ? (
          <div className="flex justify-between gap-3" data-testid="invoiceable-line"><dt className="text-text-muted">ออกใบกำกับได้อีก</dt><dd className="m-0 tabular-nums">{formatMoney(invoiceable)}</dd></div>
        ) : null}
      </dl>
      <p className="mt-3 text-sm text-text-secondary">ยอดนี้จะถูกบันทึกเป็นเงินที่รับแล้วของรอบส่งนี้ ยกเลิกได้เฉพาะใบกำกับล่าสุด และก่อนบันทึกการส่งของ</p>
    </Modal>
  );
}

/** The buyer to type into Express, read from the quotation of this round (owner rule #65): each value has its own copy button. */
function BuyerCard({ round }) {
  const [copied, setCopied] = useState(null);
  const copy = async (key, text) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(key);
    } catch {
      setCopied('failed');
    }
  };
  const rows = [['name', 'ชื่อลูกค้า', round.customerName], ['taxId', 'เลขประจำตัวผู้เสียภาษี', round.taxId], ['branch', 'สาขา', round.branch]];
  return (
    <div className="flex flex-col gap-1 rounded-md border border-border bg-surface px-4 py-3" data-testid="invoice-buyer">
      <h4 className="m-0 text-[0.8125rem] font-bold text-text-secondary">ออกใบกำกับในนาม (ผู้ซื้อ)</h4>
      {rows.map(([key, label, value]) => (
        <div key={key} className="flex min-h-12 items-center justify-between gap-3">
          <span className="flex min-w-0 flex-col">
            <small className="text-xs text-text-muted">{label}</small>
            <b className="text-base [overflow-wrap:anywhere]" data-testid={`invoice-buyer-${key}`}>{value || '—'}</b>
          </span>
          <Button type="button" variant="secondary" className="min-h-11 shrink-0" disabled={!value} aria-label={`คัดลอก${label}`} data-testid={`invoice-buyer-copy-${key}`} onClick={() => copy(key, value)}>
            {copied === key ? 'คัดลอกแล้ว' : 'คัดลอก'}
          </Button>
        </div>
      ))}
      {copied === 'failed' ? <p role="alert" className="m-0 text-sm text-danger">คัดลอกไม่สำเร็จ เลือกข้อความแล้วคัดลอกเอง</p> : null}
      {round.customerFromDeal ? <p className="m-0 text-xs text-text-muted" data-testid="invoice-buyer-from-deal">ใบเสนอราคานี้ไม่ได้ระบุลูกค้า ใช้ลูกค้าของงานแทน</p> : null}
    </div>
  );
}

/** `round`: a FORWARDED round the viewer may issue (viewer.canIssue). */
/** `primary`: false when another round of the order is also waiting on this viewer; its action then sits on the card as a secondary button. */
export function TaxInvoiceStep({ round, ticketId, invoiceable = null, primary = true }) {
  const queryClient = useQueryClient();
  const pickerRef = useRef(null);
  const file = round.files.find((f) => f.kind === 'INVOICE') ?? null;
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState(null);
  // null until a file was read in this visit; { readOk } after.
  const [readOk, setReadOk] = useState(null);
  const [totalWeak, setTotalWeak] = useState(false);
  const [number, setNumber] = useState('');
  const [date, setDate] = useState('');
  const [total, setTotal] = useState('');
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [confirmError, setConfirmError] = useState(null);

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: queryKeys.deliveryRequests(ticketId) });
    queryClient.invalidateQueries({ queryKey: ['notifications'] });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketPayments(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
  };

  const upload = async (picked) => {
    if (!picked) return;
    setUploadError(null);
    if (picked.size > MAX_UPLOAD_BYTES) {
      setUploadError('ไฟล์มีขนาดใหญ่เกินไป (สูงสุด 10 MB)');
      return;
    }
    setUploading(true);
    try {
      const res = await api.deliveryRequests.attachTaxInvoice(round.id, picked);
      setReadOk(Boolean(res.readOk));
      setTotalWeak(Boolean(res.totalWeak));
      // What was typed survives replacing the file with one that reads nothing.
      if (res.read?.invoiceNumber) setNumber(res.read.invoiceNumber);
      if (res.read?.invoiceDate) setDate(res.read.invoiceDate);
      if (res.read?.total != null) setTotal(String(res.read.total));
      refresh();
    } catch (e) {
      setUploadError(e?.message || 'อัปโหลดไม่สำเร็จ');
    } finally {
      setUploading(false);
    }
  };

  const confirm = useMutation({
    mutationFn: () => api.deliveryRequests.confirmInvoice(round.id, {
      invoiceNumber: number.trim(), invoiceDate: date, total: Number(amountOf(total)),
    }),
    onSuccess: () => { setConfirmOpen(false); refresh(); },
    onError: (e) => {
      setConfirmError(e?.message || 'ยืนยันไม่สำเร็จ');
      // 409: another confirm got there first or the round moved on; the screen refreshes behind the sheet.
      if (e?.status === 409) refresh();
    },
  });

  const problem = !file ? 'แนบไฟล์ใบกำกับภาษีก่อน' : invoiceFormProblem({ number, date, total });
  const fieldClass = 'min-h-12 w-full rounded-md border border-border-input bg-surface px-3 text-base';

  return (
    <section className="flex flex-col gap-3" data-testid="tax-invoice-step">
      <h3 className="m-0 px-1 text-[0.8125rem] font-bold text-text-secondary">ใบกำกับภาษีที่ออกแล้ว</h3>
      {round.customerName ? <BuyerCard round={round} /> : null}
      {file ? (
        <div className="flex min-h-14 items-center gap-3 rounded-md border border-border bg-surface px-4 py-2" data-testid="invoice-file">
          <Icon name="fileText" size={20} />
          <a href={api.attachments.fileUrl(file.attachmentId)} target="_blank" rel="noreferrer" className="flex min-h-12 min-w-0 flex-1 flex-col justify-center text-text no-underline" data-testid="invoice-open">
            <b className="truncate text-[0.9375rem]">{file.fileName}</b>
            <small className="text-xs text-text-muted">{['แนบแล้ว · เปิดดู', fileSizeText(file.fileSize)].filter(Boolean).join(' · ')}</small>
          </a>
          <Button type="button" variant="text" className="min-h-12" disabled={uploading} data-testid="invoice-replace" onClick={() => pickerRef.current?.click()}>เปลี่ยน</Button>
        </div>
      ) : (
        <Button type="button" variant="secondary" className="min-h-12 w-full gap-2 border-dashed" disabled={uploading} data-testid="invoice-attach" onClick={() => pickerRef.current?.click()}>
          <Icon name="paperclip" size={18} />
          {uploading ? 'กำลังอ่านไฟล์…' : 'แนบใบกำกับภาษี (PDF)'}
        </Button>
      )}
      <input
        ref={pickerRef}
        type="file"
        accept="application/pdf,.pdf"
        className="sr-only"
        tabIndex={-1}
        aria-hidden="true"
        data-testid="invoice-file-input"
        onChange={(e) => { upload(e.target.files?.[0]); e.target.value = ''; }}
      />
      {uploadError ? <p role="alert" className="m-0 px-1 text-sm text-danger" data-testid="invoice-upload-error">{uploadError}</p> : null}

      {file ? (
        <>
          {readOk !== null ? (
            <p role="status" className={`m-0 rounded-md px-3 py-2 text-sm ${readOk && !totalWeak ? 'bg-success-bg text-success' : 'bg-warning-bg text-warning'}`} data-testid="invoice-read-hint">
              {!readOk ? 'อ่านจากไฟล์ไม่ได้ กรอกเอง' : totalWeak ? 'อ่านจากไฟล์ให้แล้ว กรุณาตรวจยอด' : 'อ่านจากไฟล์ให้แล้ว ตรวจและแก้ได้ก่อนยืนยัน'}
            </p>
          ) : null}
          <div className="flex flex-col gap-3 rounded-md border border-border bg-surface p-4">
            <label className="flex flex-col gap-1 text-sm font-bold text-text-secondary">
              เลขที่ใบกำกับ
              <input className={fieldClass} value={number} maxLength={60} autoComplete="off" data-testid="invoice-number" onChange={(e) => setNumber(e.target.value)} />
            </label>
            <div className="flex flex-col gap-1 text-sm font-bold text-text-secondary">
              <label htmlFor="invoice-date">วันที่</label>
              <ThaiDateInput id="invoice-date" value={date} max={bangkokTodayIso()} onChange={(e) => setDate(e.target.value)} />
            </div>
            <label className="flex flex-col gap-1 text-sm font-bold text-text-secondary">
              <span>ยอดรวม <span className="font-normal text-text-muted">รวม VAT</span>{invoiceable != null ? <span className="font-normal text-text-muted" data-testid="invoiceable-hint"> · ออกใบกำกับได้อีก {formatMoney(invoiceable)}</span> : null}</span>
              <input className={`${fieldClass} text-right font-bold tabular-nums`} value={total} inputMode="decimal" autoComplete="off" data-testid="invoice-total" onChange={(e) => setTotal(e.target.value)} />
            </label>
          </div>
          <p className="m-0 px-1 text-sm text-text-muted">เมื่อยืนยัน ยอดนี้ถูกบันทึกเป็นเงินที่รับแล้วของรอบส่งนี้ และคลังจะได้รับแจ้งว่าส่งของได้</p>
        </>
      ) : null}

      <div className={primary ? 'sticky bottom-0 z-10 -mx-1 flex flex-col gap-1 border-t border-border bg-surface px-1 pb-[max(0.5rem,env(safe-area-inset-bottom))] pt-3' : 'flex flex-col gap-1'} data-testid="invoice-actions">
        {problem ? <p role="status" className="m-0 text-center text-sm text-text-secondary" data-testid="invoice-blocked">{problem}</p> : null}
        <Button type="button" variant={primary ? 'primary' : 'secondary'} className="w-full" disabled={Boolean(problem) || uploading} data-testid="invoice-confirm" onClick={() => { setConfirmError(null); setConfirmOpen(true); }}>
          ยืนยันและแจ้งคลัง
        </Button>
      </div>
      {confirmOpen ? (
        <ConfirmSheet
          values={{ number, date, total }}
          invoiceable={invoiceable}
          pending={confirm.isPending}
          error={confirmError}
          onConfirm={() => confirm.mutate()}
          onClose={() => setConfirmOpen(false)}
        />
      ) : null}
    </section>
  );
}
