/* Tokens only. "เอกสารของงานนี้" in the request sheet (phone design M11): the order's system documents. One that exists is
 * ticked and attached automatically (it can be unticked); one that is missing shows "ยังไม่มี" and "สร้าง", or the
 * generator's own reason when it refuses. The facts are the server's: the document chain (deposit notice, remaining
 * invoice) and the stored import requests. Nothing here decides a document's state.
 */
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../../api/index.js';
import { queryKeys } from '../../../api/queryKeys.js';
import { Button } from '../../../components/common/Button.jsx';
import { Icon } from '../../../components/common/Icon.jsx';
import { cn } from '../../../utils/cn.js';
import { formatMoney, formatThaiDate } from '../../../utils/format.js';
import { blockedText, depositHint, remainingHint } from '../../documentChain/documentChainText.js';
import { useDocumentChain } from '../../documentChain/documentChain.js';
import { depositReadyForIr } from '../dealMoneyStatus.js';
import { DOC_AMBIGUOUS_TEXT, docBelongsToRound, openBlobInTab } from './deliveryRound.js';
import { depositNoticesQueryOptions } from '../../deposits/createDepositNoticeDraft.js';

// The PDF of each kind. The routes answer as a download (Content-Disposition: attachment), so a plain link would save the
// file instead of showing it: the PDF is fetched and shown in a tab that was opened in the tap itself.
const VIEW_PDF = {
  QUOTATION: (id) => api.dealQuotations.downloadPdf(id),
  DEPOSIT_NOTICE: (id) => api.depositNotices.downloadPdf(id),
  REMAINING_INVOICE: (id) => api.storedRemainingInvoices.download(id),
  IMPORT_REQUEST: (id) => api.storedImportRequests.download(id),
};

const ROW = 'flex min-h-14 items-center gap-3 rounded-md border-[1.5px] px-3 py-2';

/**
 * The rows of the section and the documents they stand for. `quotation`: the round's chosen quotation (always a row);
 * `approvedQuotations`: the order's, to tell whose a document is. `summary`: the deal's summary (the IR's deposit gate).
 * `canCreateIr`: the host can open the IR generator. Returns `{ rows, loading, failed, retry }`; a row is
 * `{ key, kind, id, title, sub, present }`; a present row of another quotation has `other: true` (shown, never
 * attached to this round); a missing one has `{ create: 'deposit' | 'invoice' | 'ir' | null, blocked }`.
 */
export function useRoundDocuments({ ticketId, quotation, approvedQuotations = [], summary, canCreateIr }) {
  const chainQuery = useDocumentChain(ticketId);
  const noticesQuery = useQuery({ ...depositNoticesQueryOptions(ticketId), enabled: Boolean(ticketId), retry: false });
  const invoicesQuery = useQuery({
    queryKey: queryKeys.storedRemainingInvoices(ticketId),
    queryFn: () => api.storedRemainingInvoices.listForTicket(ticketId).then((r) => r.remainingInvoices ?? []),
    enabled: Boolean(ticketId),
    retry: false,
  });
  const irQuery = useQuery({
    queryKey: queryKeys.storedImportRequests(ticketId),
    queryFn: () => api.storedImportRequests.listForTicket(ticketId).then((r) => r.importRequests ?? []),
    enabled: Boolean(ticketId),
    retry: false,
  });
  const queries = [chainQuery, noticesQuery, invoicesQuery, irQuery];
  const loading = queries.some((q) => q.isLoading);
  const failed = queries.some((q) => q.isError);
  const retry = () => queries.filter((q) => q.isError).forEach((q) => q.refetch());
  const chain = chainQuery.data ?? null;
  const rows = [];

  rows.push(quotation
    ? { key: `QUOTATION:${quotation.id}`, kind: 'QUOTATION', id: quotation.id, present: true,
      title: `ใบเสนอราคา ${quotation.number}`, sub: `อนุมัติแล้ว · ${formatMoney(quotation.total)}` }
    : { key: 'QUOTATION', kind: 'QUOTATION', present: false, title: 'ใบเสนอราคา', sub: 'เลือกใบเสนอราคาของรอบนี้', create: null, blocked: null });

  // The server's rule (docBelongsToRound): a document of another quotation, or one naming none of this order's quotations
  // while the order has several, is shown but never attached to this round.
  const verdict = (ref) => {
    const { ok, named } = docBelongsToRound(ref, approvedQuotations, quotation?.id);
    return ok ? null : named ? `ออกจากใบเสนอราคา ${named.number} · ไม่แนบในรอบนี้` : DOC_AMBIGUOUS_TEXT;
  };

  const deposit = chain?.deposit;
  if (deposit?.required && !loading && !failed) {
    const issuedNotice = (noticesQuery.data ?? []).filter((d) => d.status === 'ISSUED').sort((a, b) => (b.version ?? 0) - (a.version ?? 0))[0];
    const elsewhere = issuedNotice ? verdict(issuedNotice.reference) : null;
    if (issuedNotice && elsewhere) {
      rows.push({ key: `DEPOSIT_NOTICE:${issuedNotice.id}`, kind: 'DEPOSIT_NOTICE', id: issuedNotice.id, present: true, other: true,
        title: `ใบแจ้งยอดเงินรับมัดจำ ${issuedNotice.docNumber ?? ''}`.trim(), sub: elsewhere });
    } else if (issuedNotice) {
      rows.push({
        key: `DEPOSIT_NOTICE:${issuedNotice.id}`, kind: 'DEPOSIT_NOTICE', id: issuedNotice.id, present: true,
        title: `ใบแจ้งยอดเงินรับมัดจำ ${issuedNotice.docNumber ?? ''}`.trim(),
        sub: `ออกแล้ว${issuedNotice.issueDate ? ` · ${formatThaiDate(issuedNotice.issueDate)}` : ''}`,
      });
    } else {
      const blocked = deposit.blockedReason ? blockedText(deposit.blockedReason)
        : deposit.state === 'NOT_YET' ? depositHint(chain)
          : !chain.viewer?.mayWriteDepositNotice ? 'ฝ่ายขายเจ้าของงานเป็นผู้สร้าง'
            : quotation?.depositBlockedReason ?? null;
      rows.push({
        key: 'DEPOSIT_NOTICE', kind: 'DEPOSIT_NOTICE', present: false, title: 'ใบแจ้งยอดเงินรับมัดจำ',
        sub: deposit.draftExists ? 'ยังไม่มี · มีฉบับร่างรอออก' : 'ยังไม่มี', create: blocked ? null : 'deposit', blocked,
      });
    }
  }

  const remaining = chain?.remaining;
  if (remaining && remaining.state !== 'NOT_NEEDED' && !loading && !failed) {
    const issuedInvoice = (invoicesQuery.data ?? []).find((r) => r.status === 'ISSUED');
    const forOther = issuedInvoice
      ? verdict(approvedQuotations.find((q) => q.id === issuedInvoice.customerQuotationId)?.number) : null;
    if (issuedInvoice && forOther) {
      rows.push({ key: `REMAINING_INVOICE:${issuedInvoice.id}`, kind: 'REMAINING_INVOICE', id: issuedInvoice.id, present: true, other: true,
        title: `ใบแจ้งหนี้ส่วนที่เหลือ ${issuedInvoice.docNumber ?? ''}`.trim(),
        sub: forOther });
    } else if (issuedInvoice) {
      rows.push({
        key: `REMAINING_INVOICE:${issuedInvoice.id}`, kind: 'REMAINING_INVOICE', id: issuedInvoice.id, present: true,
        title: `ใบแจ้งหนี้ส่วนที่เหลือ ${issuedInvoice.docNumber ?? ''}`.trim(),
        sub: `ออกแล้ว${issuedInvoice.docDate ? ` · ${formatThaiDate(issuedInvoice.docDate)}` : ''}`,
      });
    } else {
      const blocked = remainingHint(chain) ?? (remaining.state === 'WAITING' ? 'ออกได้หลังฝ่ายบัญชียืนยันรับมัดจำ' : null)
        ?? (!chain.viewer?.mayWriteRemainingInvoice ? 'ฝ่ายขายเจ้าของงานเป็นผู้สร้าง' : null);
      rows.push({
        key: 'REMAINING_INVOICE', kind: 'REMAINING_INVOICE', present: false, title: 'ใบแจ้งหนี้ส่วนที่เหลือ',
        sub: remaining.draftExists ? 'ยังไม่มี · มีฉบับร่างรอออก' : 'ยังไม่มี', create: blocked ? null : 'invoice', blocked,
      });
    }
  }

  // IRs: one row per issued stored IR (the owner's rule: existing IRs of the order are shown and attached). The
  // generator's own gates (deposit ready, factories picked) answer at press time; only the deposit gate is known here.
  if (irQuery.isSuccess) {
    const issued = irQuery.data.filter((r) => r.status === 'ISSUED');
    issued.forEach((r) => rows.push({
      key: `IMPORT_REQUEST:${r.id}`, kind: 'IMPORT_REQUEST', id: r.id, present: true,
      title: `ใบขอซื้อ ${r.docNumber ?? ''}`.trim(), sub: `ออกแล้ว${r.factoryName ? ` · ${r.factoryName}` : ''}`,
    }));
    if (issued.length === 0 && canCreateIr) {
      const blocked = depositReadyForIr(summary) ? null : 'ออกใบขอซื้อ (IR) ได้หลังรับชำระมัดจำแล้ว';
      rows.push({ key: 'IMPORT_REQUEST', kind: 'IMPORT_REQUEST', present: false, title: 'ใบขอซื้อ', sub: 'ยังไม่มี', create: blocked ? null : 'ir', blocked });
    }
  }
  return { rows, loading, failed, retry };
}

/** "ดู": opens the document in a new tab so the rep can check it before sending. Never part of the tick. */
function ViewDoc({ row }) {
  const [state, setState] = useState('idle');
  const open = async () => {
    setState('busy');
    setState((await openBlobInTab(() => VIEW_PDF[row.kind](row.id))) === 'ok' ? 'idle' : 'failed');
  };
  return (
    <span className="flex shrink-0 flex-col items-end">
      <Button
        type="button"
        variant="secondary"
        className="min-h-11 min-w-11 shrink-0 px-3"
        aria-label={`ดู ${row.title}`}
        data-testid={`doc-view-${row.kind}`}
        disabled={state === 'busy'}
        onClick={open}
      >
        ดู
      </Button>
      {state === 'failed' ? <small role="alert" className="text-xs text-danger" data-testid={`doc-view-failed-${row.kind}`}>เปิดไฟล์ไม่สำเร็จ</small> : null}
    </span>
  );
}

/** `selected`: Set of row keys that are ticked; `onToggle(key)`; `onCreate(row)` runs the row's generator. */
export function RoundDocuments({ rows, loading, failed, onRetry, selected, onToggle, onCreate, disabled }) {
  return (
    <section className="flex flex-col gap-2" data-testid="round-documents">
      <h3 className="m-0 flex items-baseline justify-between gap-2 text-[0.8125rem] font-bold text-text-secondary">
        เอกสารของงานนี้ <span className="font-normal text-text-muted">มีแล้วแนบให้ · ยังไม่มีสร้างได้</span>
      </h3>
      {rows.map((row) => (row.other ? (
        <div key={row.key} data-testid={`doc-row-${row.kind}`} data-other="true" className={cn(ROW, 'border-border-input bg-surface-subtle')}>
          <span className="flex min-w-0 flex-1 flex-col">
            <b className="text-[0.9375rem] text-text-secondary [overflow-wrap:anywhere]">{row.title}</b>
            <small className="text-xs text-text-muted">{row.sub}</small>
          </span>
          <ViewDoc row={row} />
        </div>
      ) : row.present ? (
        <div
          key={row.key}
          data-testid={`doc-row-${row.kind}`}
          className={cn(ROW, selected.has(row.key) ? 'border-primary bg-primary-bg' : 'border-border-input bg-surface')}
        >
          <label className="flex min-w-0 flex-1 cursor-pointer items-center gap-3">
            <input
              type="checkbox"
              className="size-5 shrink-0 accent-[var(--color-primary)]"
              checked={selected.has(row.key)}
              onChange={() => onToggle(row.key)}
              aria-label={`แนบ ${row.title}`}
            />
            <span className="flex min-w-0 flex-col">
              <b className="text-[0.9375rem] [overflow-wrap:anywhere]">{row.title}</b>
              <small className="text-xs text-text-muted">{row.sub}</small>
            </span>
          </label>
          <ViewDoc row={row} />
        </div>
      ) : (
        <div key={row.key} data-testid={`doc-row-${row.kind}`} data-missing="true" className={cn(ROW, 'border-dashed border-border-input bg-surface')}>
          <span className="grid size-7 shrink-0 place-items-center rounded-md border border-border-input"><Icon name="plus" size={14} /></span>
          <span className="flex min-w-0 flex-1 flex-col">
            <b className="text-[0.9375rem] text-text-secondary">{row.title}</b>
            <small className="text-xs text-text-muted">{row.sub}</small>
            {row.blocked ? <small className="text-xs text-text-secondary" data-testid={`doc-blocked-${row.kind}`}>{row.blocked}</small> : null}
          </span>
          {row.create ? (
            <Button type="button" variant="secondary" className="min-h-11 shrink-0" disabled={disabled} data-testid={`doc-create-${row.kind}`} onClick={() => onCreate(row)}>
              สร้าง
            </Button>
          ) : null}
        </div>
      )))}
      {loading ? <p className="m-0 text-xs text-text-muted" aria-busy="true">กำลังโหลดเอกสารของงานนี้…</p> : null}
      {failed ? (
        <div role="alert" className="flex items-center justify-between gap-3 rounded-md border border-danger px-3 py-2" data-testid="docs-error">
          <span className="text-sm text-danger">โหลดเอกสารของงานนี้ไม่สำเร็จ ส่งไม่ได้จนกว่าจะโหลดได้</span>
          <Button type="button" variant="secondary" className="min-h-11 shrink-0" onClick={onRetry}>ลองใหม่</Button>
        </div>
      ) : null}
    </section>
  );
}
