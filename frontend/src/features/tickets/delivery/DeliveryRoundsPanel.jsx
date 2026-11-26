/* Tokens only. "ส่งของ": the delivery rounds of one order (phone designs M10 and M14). Self-contained: it takes the
 * deal's id and reads its own rounds, so the warehouse and issuer slices can mount it where they need it. The round's
 * four numbered steps, the request the rep sends (DeliveryRequestSheet), the warehouse's review actions, the invoice
 * issuer's TaxInvoiceStep, the delivery record (RecordDeliverySheet, M17/M18: sales or any warehouse staff, only against
 * an INVOICED round), and finished rounds folded away.
 */
import { useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../../../api/index.js';
import { queryKeys } from '../../../api/queryKeys.js';
import { Button, buttonVariants } from '../../../components/common/Button.jsx';
import { Icon } from '../../../components/common/Icon.jsx';
import { cn } from '../../../utils/cn.js';
import { DeliveryRequestSheet } from './DeliveryRequestSheet.jsx';
import { ForwardSheet } from './ForwardSheet.jsx';
import { RecordDeliverySheet } from './RecordDeliverySheet.jsx';
import { SendBackSheet } from './SendBackSheet.jsx';
import { TaxInvoiceStep } from './TaxInvoiceStep.jsx';
import { VoidInvoiceSheet } from './VoidInvoiceSheet.jsx';
import { canRequestDelivery, deliveryRoundsQueryOptions, isDelivered, openRoundsOf, requestActionLabel } from './deliveryRoundsQuery.js';
import { fileCaption, MAX_UPLOAD_BYTES, useFileOpener, returnReasonText, ROUND_STEPS, roundQtyText, stepState, thaiDateTime } from './deliveryRound.js';
import { formatThaiDate, formatMoney } from '../../../utils/format.js';

const OPEN = ['DRAFT', 'SENT', 'RETURNED', 'FORWARDED'];

function Step({ index, state, label, caption, pill }) {
  return (
    <li
      data-testid={`round-step-${index + 1}`}
      data-state={state}
      aria-current={state === 'now' ? 'step' : undefined}
      className="flex min-h-14 items-start gap-3 px-4 py-3"
    >
      <span
        className={cn(
          'grid size-7 shrink-0 place-items-center rounded-full text-sm font-bold',
          state === 'done' && 'bg-success text-surface',
          state === 'now' && 'bg-primary text-surface',
          state === 'todo' && 'border border-border-input bg-surface text-text-muted',
        )}
      >
        {state === 'done' ? <Icon name="check" size={16} /> : index + 1}
      </span>
      <span className="flex min-w-0 flex-1 flex-col">
        <b className={cn('text-base', state === 'todo' ? 'text-text-secondary' : 'text-text')}>{label}</b>
        {caption ? <small className="text-xs text-text-muted">{caption}</small> : null}
      </span>
      {pill ? <span className="self-start rounded-full bg-warning-bg px-2.5 py-0.5 text-xs font-bold text-warning">{pill}</span> : null}
    </li>
  );
}

function stepCaption(round, index, warehouse) {
  const state = stepState(round.status, index);
  if (index === 0) {
    if (state === 'done') return [round.requestedByName, thaiDateTime(round.sentAt)].filter(Boolean).join(' · ');
    return round.status === 'RETURNED' ? 'คลังส่งกลับให้แก้ไขเอกสาร' : ROUND_STEPS[0].hint;
  }
  if (index === 1 && state === 'now') return warehouse && round.viewer?.canReview ? 'รอคุณตรวจ' : 'รอคลังตรวจเอกสาร';
  if (index === 2 && state === 'now') return round.viewer?.canIssue ? 'รอคุณแนบใบกำกับที่ออกแล้ว' : 'รอผู้ออกใบกำกับภาษี';
  if (index === 2 && state === 'done') {
    return [round.invoice?.invoicedByName, thaiDateTime(round.invoice?.invoicedAt), 'บันทึกรับเงินแล้ว'].filter(Boolean).join(' · ');
  }
  if (index === 3 && state === 'now') return 'ฝ่ายขายหรือเจ้าหน้าที่คลังบันทึกได้';
  return state === 'done' && index === 1 && round.forwardedByName
    ? [round.forwardedByName, thaiDateTime(round.forwardedAt)].filter(Boolean).join(' · ')
    : ROUND_STEPS[index].hint;
}

/** `warehouse`: the warehouse's own view of a SENT or FORWARDED round: it may add files, and take back its own while SENT. */
function RoundFiles({ round, warehouse = null }) {
  const queryClient = useQueryClient();
  const [openFile, openError] = useFileOpener();
  const pickerRef = useRef(null);
  const [uploading, setUploading] = useState([]);
  const [errors, setErrors] = useState([]);
  const canAttach = Boolean(warehouse) && ['SENT', 'FORWARDED'].includes(round.status);
  // The issuer's own step shows the file it is about to confirm; listing it twice would only confuse.
  const files = round.viewer?.canIssue ? round.files.filter((f) => f.kind !== 'INVOICE') : round.files;
  const refresh = () => queryClient.invalidateQueries({ queryKey: queryKeys.deliveryRequests(warehouse?.ticketId) });
  const remove = useMutation({
    mutationFn: (attachmentId) => api.attachments.delete(attachmentId),
    onSuccess: refresh,
    onError: (e) => setErrors([e?.message || 'เอาไฟล์ออกไม่สำเร็จ']),
  });
  const upload = async (fileList) => {
    setErrors([]);
    for (const file of Array.from(fileList ?? [])) {
      if (file.size > MAX_UPLOAD_BYTES) {
        setErrors((prev) => [...prev, `${file.name}: ไฟล์มีขนาดใหญ่เกินไป (สูงสุด 10 MB)`]);
        continue;
      }
      setUploading((prev) => [...prev, file.name]);
      try {
        await api.deliveryRequests.addFile(round.id, file);
      } catch (e) {
        setErrors((prev) => [...prev, `${file.name}: ${e?.message || 'อัปโหลดไม่สำเร็จ'}`]);
      } finally {
        setUploading((prev) => prev.filter((name) => name !== file.name));
      }
    }
    refresh();
  };
  if (files.length === 0 && !canAttach) return null;
  return (
    <section className="flex flex-col gap-2" data-testid="round-files">
      <h3 className="m-0 flex items-baseline justify-between px-1 text-[0.8125rem] font-bold text-text-secondary">
        เอกสารของรอบนี้ <span className="font-normal text-text-muted">{files.length} ไฟล์</span>
      </h3>
      {files.length > 0 ? (
        <ul className="m-0 list-none divide-y divide-border-subtle overflow-hidden rounded-md border border-border bg-surface p-0">
          {files.map((file) => {
            const removable = canAttach && round.status === 'SENT' && file.kind !== 'SYSTEM_DOC' && file.uploadedById === warehouse.userId;
            return (
              <li key={file.attachmentId} className="flex items-center">
                <a
                  href={api.attachments.fileUrl(file.attachmentId)}
                  onClick={openFile(api.attachments.fileUrl(file.attachmentId), () => api.attachments.fileBlob(file.attachmentId))}
                  target="_blank"
                  rel="noreferrer"
                  className="flex min-h-14 min-w-0 flex-1 items-center gap-3 px-4 py-2 text-text no-underline"
                >
                  <Icon name={file.mimeType?.startsWith('image/') ? 'image' : 'fileText'} size={20} />
                  <span className="flex min-w-0 flex-1 flex-col">
                    <b className="truncate text-[0.9375rem]">{file.fileName}</b>
                    <small className="text-xs text-text-muted">{fileCaption(file)}</small>
                  </span>
                </a>
                {removable ? (
                  <Button
                    type="button"
                    variant="icon"
                    className="mr-2 border-0"
                    aria-label={`เอา ${file.fileName} ออก`}
                    disabled={remove.isPending}
                    onClick={() => remove.mutate(file.attachmentId)}
                  >
                    <Icon name="close" size={18} />
                  </Button>
                ) : null}
              </li>
            );
          })}
        </ul>
      ) : null}
      {uploading.map((name) => <p key={name} className="m-0 px-1 text-sm text-text-muted" aria-busy="true">{name} · กำลังอัปโหลด…</p>)}
      {openError ? <p role="alert" className="m-0 px-1 text-sm text-danger">{openError}</p> : null}
      {errors.map((message) => <p key={message} role="alert" className="m-0 px-1 text-sm text-danger">{message}</p>)}
      {canAttach ? (
        <>
          <Button
            type="button"
            variant="secondary"
            className="min-h-12 w-full gap-2 border-dashed"
            disabled={uploading.length > 0}
            data-testid="warehouse-attach"
            onClick={() => pickerRef.current?.click()}
          >
            <Icon name="paperclip" size={18} />
            แนบไฟล์เพิ่ม
          </Button>
          <input
            ref={pickerRef}
            type="file"
            accept=".pdf,image/*,.xls,.xlsx"
            multiple
            className="sr-only"
            tabIndex={-1}
            aria-hidden="true"
            data-testid="warehouse-file-input"
            onChange={(e) => { upload(e.target.files); e.target.value = ''; }}
          />
        </>
      ) : null}
    </section>
  );
}

/** `warehouse`: { ticketId, userId } on the warehouse's own screen. */
// The warehouse's plan for this round: what sales tells the customer. A delivered round shows its delivery instead.
function TripPlan({ trip }) {
  if (!trip || trip.state === 'DELIVERED') return null;
  if (trip.state !== 'PLANNED') {
    return <p role="status" className="m-0 rounded-md border border-border bg-warning-bg px-3 py-2 text-sm text-warning-dark" data-testid="trip-plan">เลื่อนส่ง · รอจัดรอบส่งใหม่</p>;
  }
  return (
    <div role="status" className="rounded-md border border-border bg-surface px-3 py-2 text-sm" data-testid="trip-plan">
      <span className="block text-text-muted">จะส่งถึงลูกค้า</span>
      <b className="block text-lg">{formatThaiDate(trip.deliveryDate)}</b>
      <b className="block">{trip.route}</b>
      {[trip.timeSlot, trip.vehicle, trip.note].filter(Boolean).map((text) => <span key={text} className="block text-text-muted">{text}</span>)}
    </div>
  );
}

function OpenRound({ round, orderLabel, warehouse = null, onVoid = null, onRecord = null }) {
  const qty = roundQtyText(round);
  return (
    <section className="flex flex-col gap-2" data-testid="open-round">
      <h3 className="m-0 flex items-baseline justify-between gap-2 px-1 text-[0.8125rem] font-bold text-text-secondary">
        {orderLabel}รอบส่งที่ {round.roundNo}
        {qty ? <span className="font-normal text-text-muted">{qty}</span> : null}
      </h3>
      <TripPlan trip={round.trip} />
      {round.status === 'INVOICED' ? (
        <div role="status" className="rounded-md border border-success bg-success-bg px-3 py-2 text-sm text-text" data-testid="invoiced-callout">
          <b className="block text-base">ออกใบกำกับแล้ว ส่งของได้</b>
          {round.invoice?.number ? (
            <span>ใบกำกับ <b className="font-mono">{round.invoice.number}</b>{round.invoice.total != null ? ` · ${formatMoney(round.invoice.total)}` : ''}</span>
          ) : null}
        </div>
      ) : null}
      {round.status === 'INVOICED' ? (
        <ul className="m-0 list-none divide-y divide-border-subtle overflow-hidden rounded-md border border-border bg-surface p-0" data-testid="round-lines">
          {round.lines.map((line) => (
            <li key={line.itemId} className="flex items-start justify-between gap-3 px-4 py-2">
              <span className="min-w-0 flex-1 text-[0.9375rem] font-bold [overflow-wrap:anywhere]">{line.description}</span>
              <span className="shrink-0 text-sm tabular-nums text-text-secondary">
                ส่งแล้ว {Number(line.deliveredQty || 0).toLocaleString('en-US')} / {Number(line.coveredQty).toLocaleString('en-US')} {line.unit}
              </span>
            </li>
          ))}
        </ul>
      ) : null}
      <ol className="m-0 list-none divide-y divide-border-subtle overflow-hidden rounded-md border border-border bg-surface p-0">
        {ROUND_STEPS.map((step, index) => (
          <Step
            key={step.key}
            index={index}
            state={stepState(round.status, index)}
            label={index === 0 && stepState(round.status, 0) === 'done' ? 'ฝ่ายขายส่งเรื่อง' : step.label}
            caption={stepCaption(round, index, Boolean(warehouse))}
            pill={(index === 1 && warehouse && round.viewer?.canReview) || (index === 2 && round.viewer?.canIssue) ? 'ถึงคิวคุณ' : null}
          />
        ))}
      </ol>
      {round.status !== 'DRAFT' && round.customerName ? (
        <p className="m-0 px-1 text-sm text-text-secondary" data-testid="round-buyer">ผู้ซื้อ · <b className="text-text">{round.customerName}</b></p>
      ) : null}
      {round.status === 'RETURNED' && returnReasonText(round) ? (
        <div role="status" className="flex flex-col gap-2 rounded-md border border-warning-border bg-warning-bg px-3 py-2 text-sm text-text" data-testid="returned-reason">
          <p className="m-0"><b>คลังส่งกลับ:</b> {returnReasonText(round)}</p>
        </div>
      ) : null}
      {round.status !== 'DRAFT' && round.note ? (
        <div className="rounded-md border border-border bg-surface px-4 py-3" data-testid="round-note">
          <small className="block text-xs font-bold text-text-secondary">หมายเหตุจากฝ่ายขาย</small>
          <p className="m-0 mt-1 whitespace-pre-wrap text-base">{round.note}</p>
        </div>
      ) : null}
      {round.status !== 'DRAFT' ? <RoundFiles round={round} warehouse={warehouse} /> : null}
      {onRecord ? (
        <Button type="button" variant="secondary" className="min-h-12 w-full" data-testid={`record-delivery-secondary-${round.roundNo}`} onClick={() => onRecord(round)}>
          บันทึกการส่งสินค้า รอบส่งที่ {round.roundNo}
        </Button>
      ) : null}
      {round.status === 'INVOICED' && round.viewer?.recordBlockedReason ? (
        <p role="status" className="m-0 rounded-md border border-warning-border bg-warning-bg px-3 py-2 text-sm text-text" data-testid="record-blocked-reason">
          บันทึกการส่งยังไม่ได้: {round.viewer.recordBlockedReason}
        </p>
      ) : null}
      {round.status === 'INVOICED' && round.viewer?.canVoid && onVoid ? (
        <Button type="button" variant="text" className="min-h-12 w-full text-danger" data-testid="void-invoice-button" onClick={() => onVoid(round)}>
          ยกเลิกใบกำกับนี้
        </Button>
      ) : null}
      {round.status === 'INVOICED' && !round.viewer?.canVoid && round.viewer?.voidBlockedReason ? (
        <p className="m-0 text-sm text-text-secondary" data-testid="void-blocked-reason">ยกเลิกใบกำกับนี้ไม่ได้: {round.viewer.voidBlockedReason}</p>
      ) : null}
    </section>
  );
}

function PastRound({ round }) {
  return (
    <details className="rounded-md border border-border bg-surface" data-testid={`past-round-${round.roundNo}`}>
      <summary className="flex min-h-14 cursor-pointer items-center gap-3 px-4 py-2">
        <span className="flex min-w-0 flex-1 flex-col">
          <b className="text-base">รอบส่งที่ {round.roundNo} <span className="font-normal text-text-muted">{roundQtyText(round)}</span></b>
          <small className="text-xs text-text-muted">
            ส่งของแล้ว · ใบกำกับ {round.invoice?.number ?? '-'}
          </small>
        </span>
        <span className="rounded-full bg-success-bg px-2.5 py-0.5 text-xs font-bold text-success">เสร็จ</span>
      </summary>
      <div className="px-4 pb-3"><RoundFiles round={round} /></div>
    </details>
  );
}

/**
 * `ticketId`: the order (deal) on screen. `showRequestButton={false}`: the host shows the request action itself (the deal
 * page puts it in its sticky bar) and opens the sheet with `?request=open`.
 * `warehouseUserId`: set on the warehouse's screen (RoundWorkView): it reads no deal, shows no delivered total, and a SENT
 * round gets its review actions: forward (the one primary) or send back with a reason.
 * `dealless`: mounted where the viewer reads no deal (an invoice issuer in RoundWorkView, account's finance page): no
 * deal read, no delivered total, no request button.
 * `hostHasPrimary`: the host (the deal page) owns the one primary action of the view; the panel then draws no primary of
 * its own for recording. `hostRecordRoundId`: the host shows this round's "บันทึกการส่งสินค้า" itself and opens the sheet
 * with `?record=<round id>`; the panel draws no button for that round.
 * `canRecordNoInvoice`: the server advertises recording a delivery to this viewer (the sales side). While goods are still to
 * deliver, the panel then always offers a secondary "บันทึกการส่งโดยยังไม่มีใบกำกับ" (a note/reference is required).
 * `quiet`: a host that only shows the panel when there is something to show (the finance page): nothing at all for an
 * order with no rounds, or when the rounds cannot be read.
 * Whoever holds the issuer grant gets the TaxInvoiceStep on a FORWARDED round (`round.viewer.canIssue`, the server's call).
 */
export function DeliveryRoundsPanel({ ticketId, showRequestButton = true, hostRecordRoundId = null, hostHasPrimary = false, canRecordNoInvoice = false, onCreateIr, showToast, warehouseUserId = null, dealless = false, quiet = false }) {
  const [searchParams, setSearchParams] = useSearchParams();
  const [localOpen, setLocalOpen] = useState(false);
  const isWarehouse = warehouseUserId != null;
  const readsNoDeal = isWarehouse || dealless;
  const queryClient = useQueryClient();
  const [sendBackFor, setSendBackFor] = useState(null);
  const [forwardFor, setForwardFor] = useState(null);
  const [voiding, setVoiding] = useState(null);
  const [recordFor, setRecordFor] = useState(null);
  const [noInvoiceOpen, setNoInvoiceOpen] = useState(false);
  // The last delivery saved from this screen (it stays up after the refetch moves the round on).
  const [doneNotice, setDoneNotice] = useState(null);
  // Why the last warehouse action did not go through: stays up after the refetch hides the review block.
  const [forwardError, setForwardError] = useState(null);
  // Set while the rep is away creating an IR from the sheet: the way back to the same request.
  const [irTrip, setIrTrip] = useState(false);
  const sheetOpen = !readsNoDeal && (localOpen || searchParams.get('request') === 'open');
  const roundsQuery = useQuery(deliveryRoundsQueryOptions(ticketId));
  // Same key as the deal page's own read; fresh for a while, so mounting here never refetches what the page just loaded.
  const ticketQuery = useQuery({
    queryKey: queryKeys.ticketDetail(ticketId),
    queryFn: () => api.tickets.get(ticketId).then((r) => r.ticket),
    enabled: Boolean(ticketId) && !readsNoDeal,
    staleTime: 30_000,
  });

  const forward = useMutation({
    mutationFn: (rid) => api.deliveryRequests.forward(rid),
    onSuccess: () => {
      setForwardFor(null);
      queryClient.invalidateQueries({ queryKey: queryKeys.deliveryRequests(ticketId) });
      queryClient.invalidateQueries({ queryKey: ['notifications'] });
    },
    onError: (e) => {
      setForwardFor(null);
      setForwardError(e?.message || 'ส่งต่อไม่สำเร็จ');
      queryClient.invalidateQueries({ queryKey: queryKeys.deliveryRequests(ticketId) });
    },
  });

  const closeRecord = () => {
    setRecordFor(null);
    setNoInvoiceOpen(false);
    if (searchParams.get('record')) {
      const next = new URLSearchParams(searchParams);
      next.delete('record');
      setSearchParams(next, { replace: true });
    }
  };
  const recordDone = () => {
    closeRecord();
    setDoneNotice('บันทึกการส่งสินค้าแล้ว');
    showToast?.('success', 'บันทึกการส่งสินค้าแล้ว');
    queryClient.invalidateQueries({ queryKey: queryKeys.deliveryRequests(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
    queryClient.invalidateQueries({ queryKey: ['notifications'] });
  };

  const closeSheet = () => {
    setLocalOpen(false);
    if (searchParams.get('request')) {
      const next = new URLSearchParams(searchParams);
      next.delete('request');
      setSearchParams(next, { replace: true });
    }
  };

  // The IR generator lives on this tab (DealFulfilmentPanel): close the sheet (the draft stays on the server), open it,
  // and keep a way back to the request.
  // The way-back bar shows only when the IR flow really opened (the generator answers false when it refused).
  const createIr = onCreateIr ? () => { closeSheet(); setIrTrip(onCreateIr() === true); } : undefined;
  const backToRequest = () => { setIrTrip(false); setLocalOpen(true); };

  if (quiet && (roundsQuery.isError || (roundsQuery.data && roundsQuery.data.rounds.length === 0))) return null;
  if (roundsQuery.isError && readsNoDeal && roundsQuery.error?.status === 403) {
    return (
      <section className="flex flex-col gap-3" data-testid="rounds-none">
        <p role="status" className="m-0 text-base text-text-secondary">{isWarehouse ? 'งานนี้ยังไม่มีคำขอที่ส่งถึงคลัง' : 'งานนี้ยังไม่มีรอบที่ส่งต่อให้คุณออกใบกำกับ'}</p>
        <Link to="/" className="inline-flex min-h-12 items-center font-bold text-primary">กลับหน้าแรก</Link>
      </section>
    );
  }
  if (roundsQuery.isError) {
    return (
      <section className="flex flex-col gap-2" data-testid="rounds-error">
        <p role="alert" className="m-0 text-sm text-danger">โหลดรอบส่งของไม่สำเร็จ</p>
        <Button type="button" variant="secondary" className="w-full sm:w-auto" onClick={() => roundsQuery.refetch()}>ลองใหม่</Button>
      </section>
    );
  }
  if (roundsQuery.isLoading || !roundsQuery.data) {
    return <div className="h-48 animate-pulse rounded-md bg-surface-subtle" data-testid="rounds-skeleton" aria-busy="true" />;
  }

  const { order, rounds, startBlockedReason, approvedQuotations } = roundsQuery.data;
  // Every unfinished round is shown. The viewer's actionable rounds (issuer: FORWARDED, warehouse: SENT) each show their
  // action; the oldest gets the one primary button, the others a secondary one on their card.
  const openRounds = openRoundsOf(roundsQuery.data);
  // An INVOICED round with quantity left waits for its delivery: sales or any warehouse staff records it (canRecordDelivery).
  const recordable = rounds.filter((r) => r.viewer?.canRecordDelivery);
  const actionable = [...openRounds.filter((r) => r.viewer?.canIssue || r.viewer?.canReview), ...recordable]
    .sort((a, b) => a.roundNo - b.roundNo);
  const primaryId = actionable[0]?.id ?? null;
  // An INVOICED round stays a full card until everything it covers is delivered, then it folds away with the finished ones.
  const invoicedRounds = rounds.filter((r) => r.status === 'INVOICED' && !isDelivered(r)).sort((a, b) => a.roundNo - b.roundNo);
  const pastRounds = rounds.filter((r) => (!OPEN.includes(r.status) && r.status !== 'INVOICED') || (r.status === 'INVOICED' && isDelivered(r)));
  // `?record=<round id>` opens that round's sheet; `?record=next` (the deal page's sticky "บันทึกส่งมอบ") opens the oldest
  // recordable round's, or the no-invoice sheet when there is none.
  const recordRaw = searchParams.get('record');
  const recordParam = Number(recordRaw) || null;
  const recordNext = recordRaw === 'next';
  const oldestRecordable = [...recordable].sort((a, b) => a.roundNo - b.roundNo)[0] ?? null;
  const recordRound = recordable.find((r) => r.id === (recordFor ?? recordParam)) ?? (recordNext ? oldestRecordable : null);
  const primaryRecord = hostHasPrimary ? null : (recordable.find((r) => r.id === primaryId) ?? null);
  const canRequest = canRequestDelivery(roundsQuery.data);
  const items = ticketQuery.data?.items ?? [];
  const marked = items.reduce((sum, i) => sum + Number(i.qtyNotDelivered || 0), 0);
  const ordered = items.reduce((sum, i) => sum + Number(i.qty || 0), 0) - marked; // deliverable, as the deal's ส่งมอบ %
  const delivered = items.reduce((sum, i) => sum + Number(i.qtyDelivered || 0), 0);
  const percent = ordered > 0 ? Math.min(100, Math.round((delivered / ordered) * 100)) : 0;
  // The warehouse reads no deal: it may still record before the invoice through the oldest sent round it can see.
  const uninvoiced = isWarehouse ? [...rounds].filter((r) => ['SENT', 'FORWARDED'].includes(r.status)).sort((a, b) => a.roundNo - b.roundNo)[0] ?? null : null;
  const missing = items.some((i) => Number(i.qty || 0) - Number(i.qtyNotDelivered || 0) - Number(i.qtyDelivered || 0) > 0);
  // The no-invoice path (a note is required) is always a SECONDARY way to record while goods are still to deliver.
  const salesNoInvoice = canRecordNoInvoice && !readsNoDeal && missing;
  const noInvoiceSheet = noInvoiceOpen || (recordNext && !recordRound && salesNoInvoice);
  const orderLabel = order.orderNo > 1 || order.parentCode ? `ออเดอร์ ${order.orderNo} · ` : '';

  return (
    <section className="flex flex-col gap-4" data-testid="delivery-rounds-panel">
      {doneNotice ? <p role="status" className="m-0 rounded-md border border-success bg-success-bg px-3 py-2 text-sm font-bold text-success" data-testid="record-done">{doneNotice}</p> : null}
      {forwardError ? <p role="alert" className="m-0 rounded-md border border-danger bg-surface px-3 py-2 text-sm text-danger" data-testid="forward-error">{forwardError}</p> : null}
      {ordered + marked > 0 && !readsNoDeal ? (
        <div className="rounded-md border border-border bg-surface px-4 py-3" data-testid="delivered-summary">
          <div className="flex items-baseline justify-between gap-3">
            <span className="text-sm text-text-muted">ส่งมอบแล้ว</span>
            <b className="tabular-nums">{delivered.toLocaleString('en-US')} / {ordered.toLocaleString('en-US')} แผ่น</b>
          </div>
          {marked > 0 ? (
            <p className="m-0 mt-1 text-xs text-text-muted" data-testid="delivered-summary-marked">
              สั่ง {(ordered + marked).toLocaleString('en-US')} · ไม่ได้รับของ {marked.toLocaleString('en-US')} แผ่น
            </p>
          ) : null}
          <div className="mt-2 h-2 overflow-hidden rounded-full bg-surface-muted" role="img" aria-label={`ส่งมอบแล้วร้อยละ ${percent}`}>
            <div className="h-full bg-primary" style={{ width: `${percent}%` }} />
          </div>
        </div>
      ) : null}

      {irTrip ? (
        <button
          type="button"
          onClick={backToRequest}
          data-testid="back-to-request"
          className="flex min-h-12 w-full cursor-pointer items-center justify-between gap-3 rounded-md border-[1.5px] border-primary bg-primary-bg px-4 text-left text-base font-bold text-primary"
        >
          สร้างใบขอซื้อเสร็จแล้ว? กลับไปที่คำขอ
          <Icon name="chevronRight" size={16} />
        </button>
      ) : null}

      {openRounds.map((round) => (
        <div key={round.id} className="flex flex-col gap-4">
          <OpenRound
            round={round}
            orderLabel={orderLabel}
            warehouse={isWarehouse ? { ticketId, userId: warehouseUserId } : null}
          />
          {round.viewer?.canIssue ? (
            <TaxInvoiceStep round={round} ticketId={ticketId} invoiceable={roundsQuery.data.invoiceable ?? null} primary={round.id === primaryId} />
          ) : null}
          {isWarehouse && round.viewer?.canReview && round.id !== primaryId ? (
            <div className="flex flex-col gap-1" data-testid={`review-actions-${round.roundNo}`}>
              <Button type="button" variant="secondary" className="w-full" data-testid={`forward-button-${round.roundNo}`}
                onClick={() => { setForwardError(null); setForwardFor(round); }}>
                ส่งต่อให้ออกใบกำกับ รอบส่งที่ {round.roundNo}
              </Button>
              <Button type="button" variant="text" className="min-h-12 w-full" onClick={() => setSendBackFor(round)}>
                เอกสารไม่ครบ ส่งกลับฝ่ายขาย
              </Button>
            </div>
          ) : null}
        </div>
      ))}
      {invoicedRounds.map((round) => (
        <OpenRound
          key={round.id}
          round={round}
          orderLabel={orderLabel}
          warehouse={isWarehouse ? { ticketId, userId: warehouseUserId } : null}
          onVoid={setVoiding}
          onRecord={round.viewer?.canRecordDelivery && round.id !== hostRecordRoundId && (hostHasPrimary || round.id !== primaryId) ? setRecordFor : null}
        />
      ))}
      {rounds.length === 0 ? (
        <p className="m-0 rounded-md border border-dashed border-border-input px-4 py-5 text-center text-text-muted" data-testid="rounds-empty">
          ยังไม่ได้ขอออกใบกำกับ
        </p>
      ) : null}

      {pastRounds.map((round) => <PastRound key={round.id} round={round} />)}

      {isWarehouse && actionable.find((r) => r.id === primaryId)?.viewer?.canReview ? (
        <div
          className="sticky bottom-0 z-10 -mx-1 flex flex-col gap-1 border-t border-border bg-surface px-1 pb-[max(0.5rem,env(safe-area-inset-bottom))] pt-3"
          data-testid="review-actions"
        >
          <Button
            type="button"
            variant="primary"
            className="w-full"
            data-testid="forward-button"
            onClick={() => { setForwardError(null); setForwardFor(actionable.find((r) => r.id === primaryId)); }}
          >
            ส่งต่อให้ออกใบกำกับ
          </Button>
          <Button type="button" variant="text" className="min-h-12 w-full" data-testid="send-back-button" onClick={() => setSendBackFor(actionable.find((r) => r.id === primaryId))}>
            เอกสารไม่ครบ ส่งกลับฝ่ายขาย
          </Button>
        </div>
      ) : null}
      {isWarehouse && uninvoiced ? (
        <Button type="button" variant="secondary" className="min-h-12 w-full" data-testid="record-no-invoice-button" onClick={() => setNoInvoiceOpen(true)}>
          บันทึกการส่งโดยยังไม่มีใบกำกับ
        </Button>
      ) : null}
      {salesNoInvoice ? (
        <Button type="button" variant="secondary" className="min-h-12 w-full" data-testid="record-no-invoice-button" onClick={() => setNoInvoiceOpen(true)}>
          บันทึกการส่งโดยยังไม่มีใบกำกับ
        </Button>
      ) : null}
      {primaryRecord ? (
        <div
          className="sticky bottom-0 z-10 -mx-1 flex flex-col gap-1 border-t border-border bg-surface px-1 pb-[max(0.5rem,env(safe-area-inset-bottom))] pt-3"
          data-testid="record-actions"
        >
          <Button type="button" variant="primary" className="w-full" data-testid="record-delivery-button" onClick={() => setRecordFor(primaryRecord.id)}>
            บันทึกการส่งสินค้า
          </Button>
        </div>
      ) : null}
      {noInvoiceSheet ? (
        <RecordDeliverySheet
          order={order}
          events={ticketQuery.data?.events ?? null}
          ticketId={ticketId}
          items={items}
          uninvoicedRound={isWarehouse ? uninvoiced : null}
          fulfilmentStatus={ticketQuery.data?.summary?.fulfillmentStatus ?? null}
          onClose={closeRecord}
          onDone={recordDone}
        />
      ) : null}
      {recordRound ? (
        <RecordDeliverySheet round={recordRound} order={order} ticketId={ticketId} events={ticketQuery.data?.events ?? null} items={ticketQuery.data?.items ?? []} fulfilmentStatus={ticketQuery.data?.summary?.fulfillmentStatus ?? null} onClose={closeRecord} onDone={recordDone} />
      ) : null}
      {voiding ? <VoidInvoiceSheet round={voiding} ticketId={ticketId} onClose={() => setVoiding(null)} /> : null}
      {forwardFor ? (
        <ForwardSheet
          round={forwardFor}
          pending={forward.isPending}
          onConfirm={() => forward.mutate(forwardFor.id)}
          onClose={() => setForwardFor(null)}
        />
      ) : null}
      {sendBackFor ? (
        <SendBackSheet
          ticketId={ticketId}
          order={order}
          round={sendBackFor}
          onClose={(notice) => { setSendBackFor(null); if (notice) setForwardError(notice); }}
        />
      ) : null}

      {canRequest && showRequestButton && !readsNoDeal ? (
        <Button type="button" variant={recordable.length > 0 ? 'secondary' : 'primary'} className="w-full" data-testid="request-delivery-button" onClick={() => setLocalOpen(true)}>
          {requestActionLabel(roundsQuery.data)}
        </Button>
      ) : null}
      {!canRequest && startBlockedReason && !readsNoDeal ? (
        <div className="flex flex-col gap-2">
          <p role="status" className="m-0 text-sm text-text-secondary" data-testid="request-blocked-reason">{startBlockedReason}</p>
          {approvedQuotations.length === 0 && !openRounds.length && startBlockedReason === 'ต้องมีใบเสนอราคาที่อนุมัติแล้ว' ? (
            <Link to={`/quotations/new?ticket=${ticketId}`} className={cn(buttonVariants({ variant: 'secondary' }), 'w-full')} data-testid="create-quotation-link">
              สร้างใบเสนอราคา
            </Link>
          ) : null}
        </div>
      ) : null}

      {sheetOpen ? (
        <DeliveryRequestSheet
          ticketId={ticketId}
          order={order}
          approvedQuotations={approvedQuotations}
          summary={ticketQuery.data?.summary}
          onCreateIr={createIr}
          showToast={showToast}
          onClose={closeSheet}
        />
      ) : null}
    </section>
  );
}
