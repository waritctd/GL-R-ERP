/* Tokens only. "ขอออกใบกำกับ / ส่งของ" (phone design M11): the sheet the sales rep fills to ask the warehouse to check
 * the round's documents. The draft is made when the sheet opens, so leaving and coming back keeps everything; files
 * upload at once to the draft. "เอกสารของงานนี้" (RoundDocuments) lists the order's system documents: the ones that exist
 * are ticked and sent with the request, a missing one has สร้าง. A generator that is a page (the deposit notice) leaves
 * the sheet; the draft is server-side, so the sheet reopens from it with everything intact (?request=open).
 */
import { useEffect, useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../../api/index.js';
import { queryKeys } from '../../../api/queryKeys.js';
import { Button } from '../../../components/common/Button.jsx';
import { Icon } from '../../../components/common/Icon.jsx';
import { Modal } from '../../../components/common/Modal.jsx';
import { cn } from '../../../utils/cn.js';
import { formatMoney } from '../../../utils/format.js';
import { RemainingInvoiceDialog } from '../RemainingInvoiceDialog.jsx';
import { fileSizeText, MAX_FILES_PER_ROUND, MAX_UPLOAD_BYTES, useFileOpener } from './deliveryRound.js';
import { PreDepositField, parsePreDeposit } from './PreDepositField.jsx';
import { RoundDocuments, useRoundDocuments } from './RoundDocuments.jsx';

const SECTION_LABEL = 'flex items-baseline justify-between gap-2 text-[0.8125rem] font-bold text-text-secondary';
const ATTACH_BUTTON = 'min-h-12 flex-1 gap-2 border-dashed';

function QuotationChoice({ quotations, value, onChange }) {
  return (
    <fieldset className="m-0 flex flex-col gap-2 border-0 p-0" data-testid="quotation-choice">
      <legend className={cn(SECTION_LABEL, 'mb-2 w-full p-0')}>
        ใบเสนอราคาของรอบนี้ <span className="font-normal text-text-muted">ออเดอร์นี้มี {quotations.length} ใบ</span>
      </legend>
      {quotations.map((q) => (
        // eslint-disable-next-line jsx-a11y/label-has-associated-control -- the radio is nested in its label; the text sits two levels down
        <label
          key={q.id}
          className={cn(
            'flex min-h-14 cursor-pointer items-center gap-3 rounded-md border-[1.5px] px-3 py-2',
            value === q.id ? 'border-primary bg-primary-bg' : 'border-border-input bg-surface',
          )}
        >
          <input
            type="radio"
            name="delivery-quotation"
            className="size-5 accent-[var(--color-primary)]"
            checked={value === q.id}
            onChange={() => onChange(q.id)}
          />
          <span className="flex min-w-0 flex-col">
            <b className="text-[0.9375rem] tabular-nums">{q.number}</b>
            <small className="text-xs text-text-muted">อนุมัติแล้ว · {formatMoney(q.total)}</small>
          </span>
        </label>
      ))}
    </fieldset>
  );
}

/**
 * `order`: the response's order ref; `approvedQuotations`: its approved quotations; `summary`: the deal's summary.
 * `onCreateIr`: opens the IR generator (the host closes the sheet first and brings the rep back). Closing keeps the draft.
 */
export function DeliveryRequestSheet({ ticketId, order, approvedQuotations, summary, onCreateIr, showToast, onClose }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  // Unticked document keys: kept on the server draft, so a trip to a generator and back does not tick them again.
  const [unticked, setUnticked] = useState(null);
  const [invoiceOpen, setInvoiceOpen] = useState(false);
  const cameraRef = useRef(null);
  const pickerRef = useRef(null);
  const [round, setRound] = useState(null);
  const [error, setError] = useState(null);
  const [note, setNote] = useState(null);
  const [uploading, setUploading] = useState([]);
  const [fileErrors, setFileErrors] = useState([]);
  const [preDeposit, setPreDeposit] = useState('');

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: queryKeys.deliveryRequests(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketAttachments(ticketId) }); // the ไฟล์ tab's count
  };
  const adopt = (res) => {
    setRound(res.round);
    setNote((current) => current ?? res.round.note ?? '');
    setUnticked((current) => current ?? new Set(res.round.untickedDocs ?? []));
  };

  const createDraft = useMutation({
    mutationFn: () => api.deliveryRequests.createDraft(ticketId, {}),
    onSuccess: (res) => { adopt(res); refresh(); },
    onError: (e) => setError(e?.message || 'เปิดรอบส่งของไม่สำเร็จ'),
  });
  // The draft is created once, when the sheet opens; the server returns the existing open one if there is one.
  const started = useRef(false);
  useEffect(() => {
    if (started.current) return;
    started.current = true;
    createDraft.mutate();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- run once on open
  }, []);

  const savePatch = useMutation({
    mutationFn: (patch) => api.deliveryRequests.update(round.id, { quotationId: round.quotationId, note: note ?? '', ...patch }),
    onSuccess: (res) => { setRound(res.round); refresh(); },
    onError: (e) => setError(e?.message || 'บันทึกไม่สำเร็จ'),
  });
  const quotation = approvedQuotations.find((q) => q.id === round?.quotationId) ?? null;
  const docs = useRoundDocuments({ ticketId, quotation, approvedQuotations, summary, canCreateIr: Boolean(onCreateIr) });
  const selected = new Set(docs.rows.filter((r) => r.present && !r.other && !(unticked ?? new Set()).has(r.key)).map((r) => r.key));
  const toggleDoc = (key) => {
    const next = new Set(unticked ?? []);
    if (next.has(key)) next.delete(key); else next.add(key);
    setUnticked(next);
    savePatch.mutate({ untickedDocs: [...next] });
  };
  // Sending makes an old deal pre-portal: the rep says what the customer already paid outside the portal.
  const asksDeposit = Boolean(order.unconfirmed && !order.prePortal);
  const depositValue = parsePreDeposit(preDeposit);
  const depositTooBig = asksDeposit && !Number.isNaN(depositValue) && quotation?.total != null && depositValue > Number(quotation.total);
  const send = useMutation({
    mutationFn: async () => {
      await api.deliveryRequests.update(round.id, { quotationId: round.quotationId, note: note ?? '' });
      const systemDocs = docs.rows.filter((r) => selected.has(r.key)).map((r) => ({ kind: r.kind, id: r.id }));
      return api.deliveryRequests.send(round.id, { systemDocs, ...(asksDeposit ? { preDeposit: parsePreDeposit(preDeposit) } : {}) });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tickets'] });
      queryClient.invalidateQueries({ queryKey: ['notifications'] });
      onClose();
    },
    onError: (e) => setError(e?.message || 'ส่งให้คลังไม่สำเร็จ'),
  });

  const upload = async (fileList) => {
    const files = Array.from(fileList ?? []);
    if (!round || files.length === 0) return;
    setFileErrors([]);
    for (const file of files) {
      if (file.size > MAX_UPLOAD_BYTES) {
        setFileErrors((prev) => [...prev, `${file.name}: ไฟล์มีขนาดใหญ่เกินไป (สูงสุด 10 MB)`]);
        continue;
      }
      setUploading((prev) => [...prev, file.name]);
      try {
        const res = await api.deliveryRequests.addFile(round.id, file);
        setRound(res.round);
      } catch (e) {
        setFileErrors((prev) => [...prev, `${file.name}: ${e?.message || 'อัปโหลดไม่สำเร็จ'}`]);
      } finally {
        setUploading((prev) => prev.filter((name) => name !== file.name));
      }
    }
    refresh();
  };

  const removeFile = useMutation({
    mutationFn: (attachmentId) => api.attachments.delete(attachmentId),
    onSuccess: (_res, attachmentId) => {
      setRound((r) => (r ? { ...r, files: r.files.filter((f) => f.attachmentId !== attachmentId) } : r));
      refresh();
    },
    onError: (e) => setFileErrors([e?.message || 'เอาไฟล์ออกไม่สำเร็จ']),
  });

  // Leaving for a generator saves the note and the quotation choice first; the round itself is already a server draft.
  const createDocument = async (row) => {
    setError(null);
    try {
      await api.deliveryRequests.update(round.id, { quotationId: round.quotationId, note: note ?? '' });
    } catch (e) {
      setError(e?.message || 'บันทึกไม่สำเร็จ');
      return;
    }
    if (row.create === 'invoice') setInvoiceOpen(true);
    else if (row.create === 'ir') onCreateIr();
    else navigate(`/tickets/${ticketId}/deposit?returnTo=ship-request${round.quotationId ? `&quotation=${round.quotationId}` : ''}`);
  };

  const needsChoice = approvedQuotations.length > 1 && !round?.quotationId;
  let blocked = null;
  // `error` (not createDraft.isError): StrictMode's remount resets the mutation observer, the message stays.
  const openFailed = !round && error != null;
  if (!round) blocked = openFailed ? 'เปิดรอบส่งของไม่ได้' : 'กำลังเปิดรอบส่งของ…';
  else if (needsChoice) blocked = 'เลือกใบเสนอราคาของรอบนี้ก่อนส่ง';
  else if (docs.loading) blocked = 'กำลังโหลดเอกสารของงานนี้…';
  else if (docs.failed) blocked = 'โหลดเอกสารของงานนี้ไม่สำเร็จ ลองใหม่ก่อนส่ง';
  else if (uploading.length > 0) blocked = 'รออัปโหลดไฟล์ให้เสร็จก่อน';
  else if (asksDeposit && Number.isNaN(depositValue)) blocked = 'ใส่มัดจำที่รับแล้วก่อนใช้ระบบ (0 ถ้ายังไม่ได้รับ)';
  else if (depositTooBig) blocked = 'มัดจำที่รับแล้วเกินยอดใบเสนอราคา';
  // An old deal has no lines until its order is confirmed by this send, so only a confirmed order can be fully covered.
  else if (round.lines.length === 0 && !order.unconfirmed) blocked = 'ทุกรายการมีรอบส่งครอบคลุมแล้ว';
  const sending = send.isPending;
  // The system documents stored by an earlier send are not extra files: not listed here, not counted against the cap.
  const [openFile, openError] = useFileOpener();
  const extraFiles = (round?.files ?? []).filter((f) => f.kind !== 'SYSTEM_DOC');
  const filesFull = extraFiles.length >= MAX_FILES_PER_ROUND;

  return (
    <Modal
      title="ขอออกใบกำกับ / ส่งของ"
      subtitle={`${order.displayCode}${round ? ` · รอบส่งที่ ${round.roundNo}` : ''}`}
      onClose={sending ? () => {} : onClose}
      placement="sheet-on-phone"
      testId="delivery-request-sheet"
      footer={(
        <div className="flex w-full flex-col gap-2">
          {blocked ? <p role="status" className="m-0 text-center text-sm text-text-secondary" data-testid="send-blocked-reason">{blocked}</p> : null}
          <Button
            type="button"
            variant="primary"
            className="w-full"
            disabled={Boolean(blocked) || sending}
            data-testid="send-to-warehouse"
            onClick={() => { setError(null); send.mutate(); }}
          >
            {sending ? 'กำลังส่ง…' : 'ส่งให้คลัง'}
          </Button>
        </div>
      )}
    >
      <div className="flex flex-col gap-5">
        <div className="flex flex-col gap-0.5">
          <p className="m-0 text-base"><b>{order.projectName || order.customerName}</b></p>
          {/* The buyer is the chosen quotation's customer, never only the deal's headline one (owner rule #65). */}
          {round?.customerName ? <p className="m-0 text-sm text-text-secondary" data-testid="round-buyer">ผู้ซื้อ · {round.customerName}</p> : null}
        </div>
        {error ? <p role="alert" className="m-0 text-sm text-danger" data-testid="sheet-error">{error}</p> : null}
        {openFailed && approvedQuotations.length === 0 ? (
          // An order with no approved quotation cannot be asked for: the way forward is the quotation.
          <Button type="button" variant="secondary" className="w-full" data-testid="create-quotation-link" onClick={() => navigate(`/quotations/new?ticket=${ticketId}`)}>
            สร้างใบเสนอราคา
          </Button>
        ) : null}

        {approvedQuotations.length > 1 ? (
          <QuotationChoice
            quotations={approvedQuotations}
            value={round?.quotationId ?? null}
            onChange={(id) => { setRound((r) => (r ? { ...r, quotationId: id } : r)); savePatch.mutate({ quotationId: id }); }}
          />
        ) : null}

        {order.unconfirmed ? (
          <p className="m-0 text-sm text-text-secondary" data-testid="lines-from-quotation">รายการสินค้าจะมาจากใบเสนอราคาที่เลือกเมื่อกดส่ง</p>
        ) : null}
        {asksDeposit ? (
          <PreDepositField
            id="request-pre-deposit"
            value={preDeposit}
            onChange={setPreDeposit}
            error={depositTooBig ? 'เกินยอดใบเสนอราคา' : null}
            max={quotation?.total ?? null}
            disabled={!round || sending}
          />
        ) : null}

        <RoundDocuments
          rows={docs.rows}
          loading={docs.loading}
          failed={docs.failed}
          onRetry={docs.retry}
          selected={selected}
          onToggle={toggleDoc}
          onCreate={createDocument}
          disabled={!round || sending}
        />

        <section className="flex flex-col gap-2" data-testid="round-attachments">
          <h3 className={cn(SECTION_LABEL, 'm-0')}>แนบไฟล์เพิ่ม <span className="font-normal text-text-muted">กี่ไฟล์ก็ได้</span></h3>
          {round && (extraFiles.length > 0 || uploading.length > 0) ? (
            <ul className="m-0 list-none divide-y divide-border-subtle overflow-hidden rounded-md border border-border bg-surface p-0">
              {extraFiles.map((file) => (
                <li key={file.attachmentId} className="flex min-h-14 items-center gap-3 pl-3" data-testid="attached-file">
                  <a
                    href={api.attachments.fileUrl(file.attachmentId)}
                    onClick={openFile(api.attachments.fileUrl(file.attachmentId), () => api.attachments.fileBlob(file.attachmentId))}
                    target="_blank"
                    rel="noreferrer"
                    data-testid="attached-file-link"
                    className="flex min-h-14 min-w-0 flex-1 items-center gap-3 text-text no-underline"
                  >
                    <Icon name={file.mimeType?.startsWith('image/') ? 'image' : 'fileText'} size={20} />
                    <span className="flex min-w-0 flex-1 flex-col">
                      <b className="truncate text-[0.9375rem]">{file.fileName}</b>
                      <small className="text-xs text-text-muted">{fileSizeText(file.fileSize)}</small>
                    </span>
                  </a>
                  <Button
                    type="button"
                    variant="icon"
                    className="border-0"
                    aria-label={`เอา ${file.fileName} ออก`}
                    disabled={removeFile.isPending}
                    onClick={() => removeFile.mutate(file.attachmentId)}
                  >
                    <Icon name="close" size={18} />
                  </Button>
                </li>
              ))}
              {uploading.map((name) => (
                <li key={`up-${name}`} className="flex min-h-14 items-center gap-3 px-3 text-text-muted" aria-busy="true">
                  <Icon name="upload" size={20} />
                  <span className="truncate text-[0.9375rem]">{name} · กำลังอัปโหลด…</span>
                </li>
              ))}
            </ul>
          ) : null}
          {openError ? <p role="alert" className="m-0 text-sm text-danger">{openError}</p> : null}
          {fileErrors.map((message) => <p key={message} role="alert" className="m-0 text-sm text-danger">{message}</p>)}
          <div className="flex gap-2">
            {/* Phone: the camera opens straight away. Desktop and tablet use เลือกไฟล์ alone. */}
            <Button
              type="button"
              variant="secondary"
              className={cn(ATTACH_BUTTON, 'hidden phone:inline-flex')}
              disabled={!round || filesFull}
              data-testid="attach-camera"
              onClick={() => cameraRef.current?.click()}
            >
              <Icon name="camera" size={18} />
              ถ่ายรูป
            </Button>
            <Button
              type="button"
              variant="secondary"
              className={ATTACH_BUTTON}
              disabled={!round || filesFull}
              data-testid="attach-picker"
              onClick={() => pickerRef.current?.click()}
            >
              <Icon name="paperclip" size={18} />
              เลือกไฟล์
            </Button>
          </div>
          {filesFull ? <p className="m-0 text-xs text-text-muted" data-testid="files-full">แนบไฟล์ครบ {MAX_FILES_PER_ROUND} ไฟล์ต่อรอบแล้ว</p> : null}
          <input
            ref={cameraRef}
            type="file"
            accept="image/*"
            capture="environment"
            className="sr-only"
            tabIndex={-1}
            aria-hidden="true"
            data-testid="camera-input"
            onChange={(e) => { upload(e.target.files); e.target.value = ''; }}
          />
          <input
            ref={pickerRef}
            type="file"
            accept=".pdf,image/*,.xls,.xlsx"
            multiple
            className="sr-only"
            tabIndex={-1}
            aria-hidden="true"
            data-testid="file-input"
            onChange={(e) => { upload(e.target.files); e.target.value = ''; }}
          />
        </section>

        <label className="flex flex-col gap-1.5">
          <span className={SECTION_LABEL}>หมายเหตุถึงคลัง <span className="font-normal text-text-muted">ไม่บังคับ</span></span>
          <textarea
            rows={3}
            value={note ?? ''}
            disabled={!round}
            onChange={(e) => setNote(e.target.value)}
            onBlur={() => { if (round && (note ?? '') !== (round.note ?? '')) savePatch.mutate({ note: note ?? '' }); }}
            className="w-full rounded-md border-[1.5px] border-border-input bg-surface px-3 py-2 text-base font-normal text-text"
            data-testid="round-note-input"
          />
        </label>
        <p className="m-0 text-xs text-text-muted">เอกสารและไฟล์ทั้งหมดจะเก็บไว้ในแท็บไฟล์ของงานนี้</p>
      </div>
      {invoiceOpen ? (
        <RemainingInvoiceDialog
          ticketId={ticketId}
          canWrite
          showToast={showToast}
          initialQuotationId={round?.quotationId ?? null}
          onClose={() => { setInvoiceOpen(false); queryClient.invalidateQueries({ queryKey: queryKeys.documentChain(ticketId) }); }}
        />
      ) : null}
    </Modal>
  );
}
