import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { formatThaiDate } from '../../utils/format.js';
import { baht } from './boardFormat.js';
import { EXPECTED_KIND_LABEL, weekRangeLabel } from './cashFormat.js';

export const unmatchedReceiptsKey = () => ['tickets', 'receiptImports', 'unmatched'];

const HINT_TONE = {
  'ลูกค้า + ยอดตรง': 'bg-success-bg text-success',
  'เลขอ้างอิงตรง': 'bg-success-bg text-success',
};

function expectedLine(c) {
  if (c.expectedAmount == null) return null;
  return [`${EXPECTED_KIND_LABEL[c.expectedKind] ?? 'ที่คาด'} ${baht(c.expectedAmount)}`,
    c.expectedWeek ? `สัปดาห์ ${weekRangeLabel(c.expectedWeek)}` : null].filter(Boolean).join(' · ');
}

/**
 * รายการรับเงินที่ยังจับคู่ไม่ได้ (plan task 3.3, design m-match): Express rows the matcher would not place, with the
 * deals they could be and a one-tap จับคู่กับดีลนี้ each. GET /api/sales/receipt-imports/unmatched already scopes it —
 * a rep gets only rows one of their own deals could be, with only those deals. `canIgnore` (account / sales_manager /
 * ceo — the server re-checks) adds ข้าม. `hideWhenEmpty` renders nothing at all when no row waits (the rep's and the
 * manager's boards); the account page keeps an empty state instead.
 */
export function UnmatchedReceipts({ showToast = () => {}, canIgnore = false, hideWhenEmpty = false }) {
  const queryClient = useQueryClient();
  // Shown inline too: the boards that mount this (CEO / manager overview) have no toast to hand it.
  const [failure, setFailure] = useState(null);
  const query = useQuery({ queryKey: unmatchedReceiptsKey(), queryFn: () => api.receiptImports.unmatched() });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['tickets'] });
  const resolve = useMutation({
    mutationFn: ({ row, ticketId }) => api.receiptImports.resolve(row.rowId, { ticketId }),
    onSuccess: refresh,
  });
  const ignore = useMutation({ mutationFn: (row) => api.receiptImports.ignore(row.rowId), onSuccess: refresh });
  const rows = query.data ?? [];
  const busy = resolve.isPending || ignore.isPending;

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

  if (hideWhenEmpty && (query.isLoading || query.isError || rows.length === 0)) return null;

  return (
    <Panel
      flush
      title={(
        <span className="grid gap-0.5">
          <span>รายการรับเงินที่ยังจับคู่ไม่ได้</span>
          <small className="text-xs font-normal text-text-muted">จากไฟล์ Express ที่บัญชีอัปโหลด</small>
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
                      <Button
                        type="button"
                        size="sm"
                        disabled={busy}
                        aria-label={`จับคู่กับดีลนี้ ${c.title ?? ''}`}
                        onClick={() => run(resolve, { row, ticketId: c.ticketId }, 'จับคู่แล้ว')}
                      >
                        จับคู่กับดีลนี้
                      </Button>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="m-0 text-sm text-text-muted">ไม่พบดีลที่ตรง — บันทึกรับชำระเองที่หน้าดีล หรือข้ามไป</p>
              )}
              {canIgnore ? (
                <div>
                  <Button
                    type="button"
                    size="sm"
                    variant="text"
                    disabled={busy}
                    aria-label={`ข้าม ${row.docNo}`}
                    onClick={() => run(ignore, row, 'ข้ามรายการนี้แล้ว')}
                  >
                    ข้ามไปก่อน
                  </Button>
                </div>
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}
