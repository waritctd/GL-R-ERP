import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { cn } from '../../utils/cn.js';
import { formatQuotationMoney, lineTypeOf } from './quotationMeta.js';
import { approvedPriceText, discountText } from './approvedItemsReuse.js';

/**
 * Add-on orders (สั่งเพิ่ม): tick an item already approved in this project and it joins the quotation at its approved
 * price (locked, enforced by the server too) — only the quantity is the rep's. When every line is reused and the
 * deposit % is the first order's, the server approves on submit and nobody is asked.
 */
export function ApprovedItemsPicker({
  query, rows, onPick, onUnpick, onQuantity, onAddOther, totals, noApproval, currency = 'THB',
}) {
  const reusable = query.data?.items ?? [];
  const pickedBySource = new Map(rows.filter((r) => r.sourceQuotationItemId != null).map((r) => [r.sourceQuotationItemId, r]));
  const sourceCodes = [...new Set(reusable.map((r) => `${r.quotationNumber} ของออเดอร์ ${orderNo(r.orderCode)}`))];

  return (
    <Panel title="เลือกจากรายการที่อนุมัติแล้ว" actions={<span className="text-xs font-bold text-text-muted">เลือกแล้ว {pickedBySource.size}</span>}>
      {query.isLoading ? (
        <div className="grid gap-2" aria-busy="true" aria-label="กำลังโหลดรายการที่อนุมัติแล้ว">
          {[0, 1].map((n) => <div key={n} className="h-16 animate-pulse rounded-md bg-surface-subtle" />)}
        </div>
      ) : query.isError ? (
        <div className="grid gap-2">
          <p role="alert" className="m-0 text-sm text-danger">โหลดรายการที่อนุมัติแล้วไม่สำเร็จ</p>
          <Button variant="secondary" className="min-h-11" onClick={() => query.refetch()}>ลองอีกครั้ง</Button>
        </div>
      ) : (
        <div className="grid gap-3">
          {reusable.length === 0 ? (
            <p className="m-0 text-sm text-text-muted">ยังไม่มีรายการที่อนุมัติแล้วในงานนี้</p>
          ) : (
            <>
              <p className="m-0 text-xs text-text-muted">
                จาก {sourceCodes.join(', ')} · ใช้ราคาเดิม ไม่ต้องขออนุมัติซ้ำ
              </p>
              <ul className="m-0 grid list-none gap-2 pl-0">
                {reusable.map((item) => {
                  const row = pickedBySource.get(item.sourceQuotationItemId);
                  const plain = lineTypeOf(item) === 'PLAIN';
                  const name = item.descriptionLine || [item.brand, item.model].filter(Boolean).join(' ');
                  const detail = [item.color, item.texture, item.sizeText].filter(Boolean).join(' · ');
                  return (
                    <li key={item.sourceQuotationItemId} className={cn('rounded-md border p-3', row ? 'border-primary bg-primary/5' : 'border-border-muted')}>
                      <button
                        type="button" role="checkbox" aria-checked={Boolean(row)}
                        className="flex min-h-11 w-full items-start gap-3 border-0 bg-transparent p-0 text-left"
                        onClick={() => (row ? onUnpick(row.clientId) : onPick(item))}
                      >
                        <span aria-hidden="true" className={cn('mt-0.5 grid size-6 shrink-0 place-items-center rounded border', row ? 'border-primary bg-primary text-white' : 'border-border')}>
                          {row ? <Icon name="check" size={16} /> : null}
                        </span>
                        <span className="grid min-w-0 gap-0.5">
                          <b className="text-sm">{name}</b>
                          {detail ? <small className="text-xs text-text-muted">{detail}</small> : null}
                          <small className="text-xs text-text-muted">
                            {approvedPriceText(item, (v) => formatQuotationMoney(v, currency))} · ราคาที่อนุมัติแล้ว{row ? ' แก้ไม่ได้' : ''}
                          </small>
                          {discountText(item, (v) => formatQuotationMoney(v, currency)) ? (
                            <small className="text-xs text-text-muted">{discountText(item, (v) => formatQuotationMoney(v, currency))}</small>
                          ) : null}
                        </span>
                      </button>
                      {row ? (
                        <label className="mt-2 grid gap-1 pl-9 text-xs font-bold text-text-muted" htmlFor={`reuse-qty-${row.clientId}`}>
                          จำนวนที่สั่งเพิ่ม <span>{plain ? (row.unit || 'หน่วย') : 'ตร.ม.'}</span>
                          <input
                            id={`reuse-qty-${row.clientId}`} type="number" inputMode="decimal" step="0.01" min="0"
                            className="min-h-11 text-right font-bold tabular-nums"
                            value={(plain ? row.quantity : row.areaSqm) ?? ''}
                            onChange={(e) => onQuantity(row.clientId, plain ? 'quantity' : 'areaSqm', e.target.value === '' ? '' : Number(e.target.value))}
                          />
                        </label>
                      ) : null}
                    </li>
                  );
                })}
              </ul>
            </>
          )}
          <button type="button" className="min-h-11 self-start border-0 bg-transparent p-0 text-left text-sm font-bold text-primary underline" onClick={onAddOther}>
            เพิ่มสินค้าอื่นที่ยังไม่เคยอนุมัติ
          </button>
          <dl className="m-0 grid gap-1 rounded-md bg-surface-subtle p-3 text-sm">
            <div className="flex justify-between"><dt className="text-text-muted">รวมก่อน VAT</dt><dd className="m-0 font-bold tabular-nums">{formatQuotationMoney(totals.subtotal, currency)}</dd></div>
            <div className="flex justify-between"><dt className="text-text-muted">VAT 7%</dt><dd className="m-0 font-bold tabular-nums">{formatQuotationMoney(totals.vat, currency)}</dd></div>
            <div className="flex justify-between text-base"><dt className="font-bold">ยอดรวม</dt><dd className="m-0 font-extrabold tabular-nums">{formatQuotationMoney(totals.grand, currency)}</dd></div>
          </dl>
          <p className={cn('m-0 text-xs font-bold', noApproval ? 'text-success' : 'text-text-muted')} data-testid="reuse-approval-note">
            {noApproval ? 'ไม่ต้องรออนุมัติ เพราะใช้ราคาเดิมทุกรายการ' : 'ต้องขออนุมัติ เพราะมีสินค้าใหม่ ราคาที่เปลี่ยน หรือเงื่อนไขต่างจากออเดอร์แรก'}
          </p>
        </div>
      )}
    </Panel>
  );
}

/** "DL-2026-0013-2" -> 2; the root's own "-1" is already in the code. */
function orderNo(code) {
  return String(code ?? '').split('-').at(-1);
}
