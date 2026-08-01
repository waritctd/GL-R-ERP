import { useMemo } from 'react';
import { formatMoney } from '../../utils/format.js';
import { AUTO_GRANTED_ROWS, TAX_ALLOWANCE_GROUPS } from './taxAllowanceSchema.js';
import { capMapFrom, fieldCapCaption } from './taxAllowanceCaps.js';

const BREAKDOWN_GRID = 'grid grid-cols-[minmax(0,2fr)_minmax(0,1fr)_minmax(0,1.6fr)] gap-x-3 gap-y-1.5 text-sm';

/**
 * Per-field declared-vs-cap breakdown — issue #387's "direct answer to 'who has allowance on
 * what'". Shared by the HR register's expandable row, the payroll drill-down, and (in summary
 * form) the profile panel. Only renders fields the employee actually declared a non-zero amount
 * for, plus the two auto-granted rows, so a fresh/empty declaration doesn't dump 21 zero rows.
 */
export function TaxAllowanceBreakdown({ declaration, caps = [] }) {
  const capByCategory = useMemo(() => capMapFrom(caps), [caps]);

  const declaredRows = useMemo(() => {
    const allowances = declaration?.allowances || {};
    const rows = [];
    for (const group of TAX_ALLOWANCE_GROUPS) {
      for (const field of group.fields) {
        if (field.kind === 'checkbox') continue;
        const amount = Number(allowances[field.key] || 0);
        if (field.kind === 'money' && amount > 0) {
          rows.push({
            key: field.key,
            label: field.label,
            amount,
            cap: field.capCategory ? capByCategory.get(field.capCategory) : null,
          });
        }
      }
    }
    return rows;
  }, [declaration, capByCategory]);

  if (!declaration) {
    return <p className="m-0 text-sm text-text-muted">ยังไม่มีแบบแจ้งค่าลดหย่อน</p>;
  }

  return (
    <div className="grid gap-3">
      <div className={BREAKDOWN_GRID}>
        <span className="font-extrabold text-text-muted">รายการ</span>
        <span className="text-right font-extrabold text-text-muted">ยื่นไว้</span>
        <span className="font-extrabold text-text-muted">เพดาน (จาก /caps)</span>
        {AUTO_GRANTED_ROWS.map((row) => {
          const cap = row.capCategory ? capByCategory.get(row.capCategory) : null;
          return (
            <div className="contents" key={row.key}>
              <span className="text-text-muted">{row.label} <span className="text-2xs">(อัตโนมัติ)</span></span>
              <span className="text-right font-mono">{cap ? formatMoney(cap.ownCap) : '-'}</span>
              <span className="text-2xs text-text-muted">{row.note ?? '-'}</span>
            </div>
          );
        })}
        {declaredRows.length === 0 ? (
          <div className="col-span-3 py-1 text-text-muted">ยังไม่ได้ประกาศค่าลดหย่อนเพิ่มเติมรายการใด</div>
        ) : declaredRows.map((row) => (
          <div className="contents" key={row.key}>
            <span>{row.label}</span>
            <span className="text-right font-mono font-bold">{formatMoney(row.amount)}</span>
            <span className="text-2xs text-text-muted">{fieldCapCaption(row.cap) ?? '-'}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
