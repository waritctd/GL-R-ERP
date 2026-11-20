import { useId, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { formatThaiDate } from '../../utils/format.js';
import { baht } from './boardFormat.js';
import { EXPECTED_KIND_LABEL, weekRangeLabel } from './cashFormat.js';

export const unmatchedReceiptsKey = () => ['tickets', 'receiptImports', 'unmatched'];
const ignoredReceiptsKey = () => ['tickets', 'receiptImports', 'ignored'];
const managerViewKey = () => ['tickets', 'receiptImports', 'managerView'];

const INPUT = 'rounded-md border border-border-input bg-surface px-2.5 py-1.5 text-sm text-text';
const HINT_TONE = {
  'ลูกค้า + ยอดตรง': 'bg-success-bg text-success',
  'เลขอ้างอิงตรง': 'bg-success-bg text-success',
};
const toSatang = (v) => Math.round(Number(v || 0) * 100);

function expectedLine(c) {
  if (c.expectedAmount == null) return null;
  return [`${EXPECTED_KIND_LABEL[c.expectedKind] ?? 'ที่คาด'} ${baht(c.expectedAmount)}`,
    c.expectedWeek ? `สัปดาห์ ${weekRangeLabel(c.expectedWeek)}` : null].filter(Boolean).join(' · ');
}

/**
 * The deal picker and the split (review M5 / M7), for one queued row: search account's scope (GET …/deals), attach
 * the row to one deal, or split it across several — sent only when the lines sum exactly to the row.
 */
function DealPicker({ row, busy, onResolve }) {
  const ids = useId();
  const [q, setQ] = useState('');
  const [results, setResults] = useState(null);
  const [split, setSplit] = useState([]); // [{ deal, amount }]
  const remaining = (toSatang(row.amount) - split.reduce((s, l) => s + toSatang(l.amount), 0)) / 100;
  const ready = split.length >= 2 && remaining === 0 && split.every((l) => toSatang(l.amount) > 0);

  async function search() {
    try {
      setResults(await api.receiptImports.deals(q.trim()));
    } catch {
      setResults([]);
    }
  }

  return (
    <div className="grid gap-2 rounded-md border border-border bg-surface-muted p-3">
      <div className="flex flex-wrap items-center gap-2">
        <label htmlFor={`${ids}-q`} className="sr-only">ค้นหางาน</label>
        <input
          id={`${ids}-q`}
          value={q}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') search(); }}
          placeholder="ชื่อลูกค้า เลขที่งาน หรือชื่อโครงการ"
          className={`${INPUT} min-w-0 flex-1 basis-[200px]`}
        />
        <Button type="button" size="sm" variant="secondary" onClick={search}>ค้นหา</Button>
      </div>
      {results && results.length === 0 ? <p className="m-0 text-sm text-text-muted">ไม่พบงานที่ตรงกันในขอบเขตของฝ่ายบัญชี</p> : null}
      {results?.length ? (
        <ul className="m-0 grid list-none gap-1.5 p-0">
          {results.map((d) => (
            <li key={d.ticketId} className="flex flex-wrap items-center gap-2 text-sm">
              <span className="min-w-0 flex-1 basis-[180px] [overflow-wrap:anywhere]">
                <b>{d.title || d.code}</b>
                <small className="block text-xs text-text-muted">{[d.code, d.customerName, d.ownerName].filter(Boolean).join(' · ')}</small>
              </span>
              <Button type="button" size="sm" disabled={busy} aria-label={`จับคู่กับงานนี้ ${d.title ?? ''}`}
                onClick={() => onResolve({ ticketId: d.ticketId })}>
                จับคู่กับงานนี้
              </Button>
              <Button type="button" size="sm" variant="secondary" aria-label={`แบ่งยอด ${d.title ?? ''}`}
                disabled={split.some((l) => l.deal.ticketId === d.ticketId)}
                onClick={() => setSplit((s) => [...s, { deal: d, amount: '' }])}>
                แบ่งยอด
              </Button>
            </li>
          ))}
        </ul>
      ) : null}
      {split.length ? (
        <div className="grid gap-1.5 border-t border-border pt-2">
          {split.map((l, i) => (
            <label key={l.deal.ticketId} className="flex flex-wrap items-center gap-2 text-sm">
              <span className="min-w-0 flex-1">{l.deal.title || l.deal.code}</span>
              <input
                aria-label={`ยอดของ ${l.deal.title}`}
                inputMode="decimal"
                value={l.amount}
                onChange={(e) => {
                  const v = e.target.value.replace(/[^\d.]/g, '');
                  setSplit((s) => s.map((x, j) => (j === i ? { ...x, amount: v } : x)));
                }}
                className={`${INPUT} w-[130px] text-right tabular-nums`}
              />
            </label>
          ))}
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className={`text-sm ${remaining === 0 ? 'text-success' : 'text-text-muted'}`}>
              {remaining === 0 ? 'ยอดครบพอดี' : `เหลืออีก ${baht(remaining)}`}
            </span>
            <Button type="button" size="sm" disabled={busy || !ready}
              onClick={() => onResolve({
                allocations: split.map((l) => ({ ticketId: l.deal.ticketId, amount: toSatang(l.amount) / 100 })),
              })}>
              บันทึกการแบ่งยอด
            </Button>
          </div>
        </div>
      ) : null}
    </div>
  );
}

/**
 * รายการรับเงินที่ยังจับคู่ไม่ได้ — ACCOUNT's queue (review H3: account only; the server re-checks every call). Per row:
 * the deals the matcher points at (one-tap จับคู่กับดีลนี้), เลือกดีลเอง (search account's scope, or split across
 * deals), ส่งให้ผู้จัดการขายดู (flags it into the manager's read-only view; account still resolves), ข้ามไปก่อน. Rows set
 * aside are listed below and can be brought back.
 */
export function UnmatchedReceipts({ showToast = () => {} }) {
  const queryClient = useQueryClient();
  const [failure, setFailure] = useState(null);
  const [picking, setPicking] = useState(null);
  const query = useQuery({ queryKey: unmatchedReceiptsKey(), queryFn: () => api.receiptImports.unmatched() });
  const ignoredQuery = useQuery({ queryKey: ignoredReceiptsKey(), queryFn: () => api.receiptImports.ignored() });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['tickets'] });
  const resolve = useMutation({ mutationFn: ({ row, body }) => api.receiptImports.resolve(row.rowId, body), onSuccess: refresh });
  const ignore = useMutation({ mutationFn: (row) => api.receiptImports.ignore(row.rowId), onSuccess: refresh });
  const unignore = useMutation({ mutationFn: (row) => api.receiptImports.unignore(row.rowId), onSuccess: refresh });
  const flag = useMutation({ mutationFn: (row) => api.receiptImports.flagManager(row.rowId), onSuccess: refresh });
  const sameAsManual = useMutation({ mutationFn: (row) => api.receiptImports.sameAsManual(row.rowId), onSuccess: refresh });
  const rows = query.data ?? [];
  const ignored = ignoredQuery.data ?? [];
  const busy = resolve.isPending || ignore.isPending || unignore.isPending || flag.isPending || sameAsManual.isPending;

  async function run(mutation, variables, success) {
    setFailure(null);
    try {
      await mutation.mutateAsync(variables);
      showToast('success', success);
    } catch (error) {
      setFailure(error?.message || 'บันทึกไม่สำเร็จ');
      showToast('error', error?.message || 'บันทึกไม่สำเร็จ');
    }
  }

  return (
    <Panel
      flush
      title={(
        <span className="grid gap-0.5">
          <span>รายการรับเงินที่ยังจับคู่ไม่ได้</span>
          <small className="text-xs font-normal text-text-muted">จากไฟล์ Express — ฝ่ายบัญชีจับคู่เท่านั้น</small>
        </span>
      )}
      actions={rows.length ? (
        <span className="rounded-full bg-text px-2.5 py-px text-xs font-extrabold tabular-nums text-surface">{rows.length}</span>
      ) : null}
    >
      {failure ? <p role="alert" className="m-0 px-5 pt-3 text-sm font-bold text-danger mobile:px-3.5">{failure}</p> : null}
      {query.isError ? (
        <p className="m-0 px-5 py-4 text-sm font-bold text-danger mobile:px-3.5">โหลดไม่สำเร็จ</p>
      ) : query.isLoading ? (
        <div className="grid gap-2 px-5 py-4" role="status" aria-busy="true"><Skeleton height={48} /></div>
      ) : rows.length === 0 ? (
        <p className="m-0 px-5 py-4 text-sm text-text-muted mobile:px-3.5">จับคู่ครบทุกรายการแล้ว</p>
      ) : (
        <ul className="m-0 grid list-none p-0">
          {rows.map((row) => (
            <li key={row.rowId} data-testid={`unmatched-${row.rowId}`} className="grid gap-2.5 border-t border-border px-5 py-3.5 first:border-t-0 mobile:px-3.5">
              <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
                <span className="grid min-w-0 gap-0.5">
                  <b className="font-mono text-sm text-text">{row.docNo}</b>
                  <small className="text-xs text-text-muted [overflow-wrap:anywhere]">
                    {[formatThaiDate(row.docDate), row.customerName, row.reference ? `อ้างอิง ${row.reference}` : null]
                      .filter(Boolean).join(' · ')}
                  </small>
                </span>
                <b className="tabular-nums text-text">{baht(row.amount)}</b>
              </div>
              {row.reason === 'POSSIBLE_MANUAL_DUPLICATE' && row.possibleManual ? (
                // Review N1: never linked silently — account says which it is.
                <div className="grid gap-2 rounded-md border border-warning bg-warning-bg px-3 py-2">
                  {row.possibleManual.source === 'TAX_INVOICE' ? (
                    <p className="m-0 text-sm text-text" data-testid="twin-tax-invoice">
                      {'อาจเป็นเงินก้อนเดียวกับใบกำกับภาษี ' + (row.possibleManual.invoiceNumber ?? '')
                        + (row.possibleManual.roundNo ? ` (รอบส่งที่ ${row.possibleManual.roundNo})` : '')
                        + ' ที่บันทึกรับเงินไว้แล้ว ' + baht(row.possibleManual.amount) + ' · ' + formatThaiDate(row.possibleManual.receivedAt)}
                    </p>
                  ) : (
                    <p className="m-0 text-sm text-text">
                      {'อาจเป็นรายการเดียวกับที่บันทึกเอง: ' + (EXPECTED_KIND_LABEL[row.possibleManual.kind] ?? row.possibleManual.kind)
                        + ' ' + baht(row.possibleManual.amount) + ' · ' + formatThaiDate(row.possibleManual.receivedAt)
                        + (row.possibleManual.recordedByName ? ' · ' + row.possibleManual.recordedByName : '')}
                    </p>
                  )}
                  <div className="flex flex-wrap gap-2">
                    {row.possibleManual.alreadyLinked ? null : (
                      // The receipt is already linked to another Express row — the server would 409 this.
                      <Button type="button" size="sm" disabled={busy}
                        onClick={() => run(sameAsManual, row, row.possibleManual.source === 'TAX_INVOICE'
                          ? 'จับคู่กับใบกำกับแล้ว ไม่บันทึกซ้ำ' : 'ผูกกับรายการที่บันทึกเองแล้ว ไม่บันทึกซ้ำ')}>
                        {row.possibleManual.source === 'TAX_INVOICE' ? 'เงินก้อนเดียวกับใบกำกับ' : 'รายการเดียวกับที่บันทึกเอง'}
                      </Button>
                    )}
                    <Button type="button" size="sm" variant="secondary" disabled={busy}
                      onClick={() => run(resolve, { row, body: { ticketId: row.possibleManual.ticketId } }, 'บันทึกเป็นอีกรายการแล้ว')}>
                      คนละรายการ
                    </Button>
                  </div>
                </div>
              ) : null}
              {row.candidates.length ? (
                <ul className="m-0 grid list-none gap-2 p-0">
                  {row.candidates.map((c) => (
                    <li key={c.ticketId} className="flex flex-wrap items-center gap-x-3 gap-y-1.5 rounded-md border border-border bg-surface-muted px-3 py-2">
                      <span className="grid min-w-0 flex-1 basis-[200px] gap-0.5">
                        <b className="text-sm text-text [overflow-wrap:anywhere]">{c.title || `#${c.ticketId}`}</b>
                        <small className="text-xs text-text-muted [overflow-wrap:anywhere]">
                          {[expectedLine(c), c.ownerName].filter(Boolean).join(' · ')}
                        </small>
                      </span>
                      <span className={`whitespace-nowrap rounded-full px-2 py-px text-2xs font-bold ${HINT_TONE[c.hint] ?? 'bg-surface-subtle text-text-muted'}`}>
                        {c.hint}
                      </span>
                      <Button type="button" size="sm" disabled={busy} aria-label={`จับคู่กับงานนี้ ${c.title ?? ''}`}
                        onClick={() => run(resolve, { row, body: { ticketId: c.ticketId } }, 'จับคู่แล้ว')}>
                        จับคู่กับงานนี้
                      </Button>
                    </li>
                  ))}
                </ul>
              ) : row.reason === 'POSSIBLE_MANUAL_DUPLICATE' ? null : (
                <p className="m-0 text-sm text-text-muted">ไม่พบงานที่ตรง — เลือกงานเอง ส่งให้ผู้จัดการขายดู หรือข้ามไป</p>
              )}
              {picking === row.rowId ? (
                <DealPicker row={row} busy={busy}
                  onResolve={(body) => run(resolve, { row, body }, 'จับคู่แล้ว').then(() => setPicking(null))} />
              ) : null}
              <div className="flex flex-wrap gap-1">
                <Button type="button" size="sm" variant="secondary"
                  onClick={() => setPicking(picking === row.rowId ? null : row.rowId)}>
                  เลือกงานเอง
                </Button>
                <Button type="button" size="sm" variant="text" disabled={busy || Boolean(row.managerFlaggedAt)}
                  onClick={() => run(flag, row, 'ส่งให้ผู้จัดการขายดูแล้ว')}>
                  {row.managerFlaggedAt ? 'ส่งให้ผู้จัดการขายแล้ว' : 'ส่งให้ผู้จัดการขายดู'}
                </Button>
                <Button type="button" size="sm" variant="text" disabled={busy} aria-label={`ข้าม ${row.docNo}`}
                  onClick={() => run(ignore, row, 'ข้ามรายการนี้แล้ว')}>
                  ข้ามไปก่อน
                </Button>
              </div>
            </li>
          ))}
        </ul>
      )}
      {ignored.length ? (
        <details className="border-t border-border px-5 py-3 text-sm mobile:px-3.5">
          <summary className="cursor-pointer font-bold text-text-secondary">รายการที่ข้ามไป ({ignored.length})</summary>
          <ul className="m-0 mt-2 grid list-none gap-1.5 p-0">
            {ignored.map((row) => (
              <li key={row.rowId} className="flex flex-wrap items-center justify-between gap-2">
                <span className="font-mono">{row.docNo} · {baht(row.amount)}</span>
                <Button type="button" size="sm" variant="text" disabled={busy} aria-label={`นำกลับ ${row.docNo}`}
                  onClick={() => run(unignore, row, 'นำกลับมาแล้ว')}>
                  นำกลับ
                </Button>
              </li>
            ))}
          </ul>
        </details>
      ) : null}
    </Panel>
  );
}

/**
 * The sales manager's / CEO's READ-ONLY view of the queue (review H3): how many rows wait for account, plus only the
 * rows account sent with ส่งให้ผู้จัดการขายดู. No deals to pick, no actions — account resolves. Nothing when nothing waits.
 */
export function UnmatchedReceiptsSummary() {
  const query = useQuery({ queryKey: managerViewKey(), queryFn: () => api.receiptImports.managerView() });
  const view = query.data;
  if (!view || (view.openUnmatched === 0 && view.flagged.length === 0)) return null;
  return (
    <section aria-label="รายการรับเงินที่ยังจับคู่ไม่ได้" className="grid gap-2 rounded-md border border-border bg-surface p-5 mobile:p-3.5">
      <h3 className="m-0 text-md">รายการรับเงินที่ยังจับคู่ไม่ได้</h3>
      <p className="m-0 text-sm text-text-muted">{view.openUnmatched} รายการรอฝ่ายบัญชีจับคู่</p>
      {view.flagged.length ? (
        <ul className="m-0 grid list-none gap-1.5 p-0 text-sm">
          {view.flagged.map((r) => (
            <li key={r.rowId} className="flex flex-wrap items-baseline justify-between gap-2 border-t border-border pt-1.5">
              <span className="min-w-0 [overflow-wrap:anywhere]">
                <b className="font-mono">{r.docNo}</b>
                <small className="block text-xs text-text-muted">
                  {[formatThaiDate(r.docDate), r.customerName, r.reference].filter(Boolean).join(' · ')}
                </small>
              </span>
              <b className="tabular-nums">{baht(r.amount)}</b>
            </li>
          ))}
        </ul>
      ) : null}
      <p className="m-0 text-xs text-text-muted">ฝ่ายบัญชีส่งรายการเหล่านี้มาให้ดู — บอกฝ่ายบัญชีว่าเป็นของงานใด</p>
    </section>
  );
}
