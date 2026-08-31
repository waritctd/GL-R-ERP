import { useMemo } from 'react';
import { formatMoney, formatShortDate } from '../../utils/format.js';
import { AUTO_GRANTED_ROWS, LOR_YOR_01_SECTIONS, declaredAllowanceTotal, readFieldValue } from './taxAllowanceSchema.js';
import { capMapFrom, fieldCapCaption } from './taxAllowanceCaps.js';
import { hasAllowanceDisagreement, payrollVerificationInfo } from './taxAllowanceStatus.js';
import { Icon } from '../../components/common/Icon.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';

const BREAKDOWN_GRID = 'grid grid-cols-[minmax(0,2fr)_minmax(0,1fr)_minmax(0,1.6fr)] gap-x-3 gap-y-1.5 text-sm';

/**
 * Per-field declared-vs-cap breakdown — issue #387's "direct answer to 'who has allowance on
 * what'". Shared by the HR register's expandable row, the payroll drill-down, and (in summary
 * form) the profile panel. Only renders fields the employee actually declared a non-zero amount
 * for, plus the two auto-granted rows, so a fresh/empty declaration doesn't dump 21 zero rows.
 *
 * `payrollAllowance` (2026-08, "register shows what payroll actually uses") is OPTIONAL and used
 * only by TaxAllowanceReviewPage's expanded row — this component's other caller, TaxAllowanceDrilldown
 * (the payroll-page drill-down), never passes it, so this prop being omitted must reproduce the exact
 * prior output, which is why every branch below is additive (wrapped in `payrollAllowance ? ... :
 * null`) rather than a rewrite of the existing declaration grid. (TaxAllowanceSummaryPanel, the
 * profile-page panel, does NOT render this component at all — it has its own summary layout — so it
 * is not "a caller that omits the prop"; there is no third caller.) It is one row from `GET
 * /api/payroll/tax-allowances?year=` (hr.employee_tax_allowance, resolved by
 * `resolvePayrollAllowance` in taxAllowanceStatus.js) — a SEPARATE table from `declaration`
 * (hr.tax_allowance_declaration), which the two can legitimately disagree with.
 */
export function TaxAllowanceBreakdown({ declaration, caps = [], payrollAllowance = null }) {
  const capByCategory = useMemo(() => capMapFrom(caps), [caps]);

  const declaredRows = useMemo(() => {
    const allowances = declaration?.allowances || {};
    const rows = [];
    // Reads through `readFieldValue` because three ล.ย.01 amounts live under the nested
    // `lorYor01` payload (ข้อ 3's second tier, ข้อ 4's spouse row, ข้อ 9) rather than in the flat
    // allowance bag — a plain `allowances[field.key]` would silently show them as zero.
    const source = { ...allowances, lorYor01: declaration?.lorYor01 || {} };
    for (const group of LOR_YOR_01_SECTIONS) {
      for (const field of group.fields) {
        if (field.kind === 'checkbox' || field.kind === 'text') continue;
        const amount = Number(readFieldValue(source, field.key) || 0);
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

  if (!declaration && !payrollAllowance) {
    return <p className="m-0 text-sm text-text-muted">ยังไม่มีแบบแจ้งค่าลดหย่อน</p>;
  }

  // Both totals reuse `declaredAllowanceTotal` (taxAllowanceSchema.js): the payroll row's
  // `allowances` object is the identical PayrollTaxAllowanceInput shape (same 16 money keys) the
  // declaration's `allowances` already is, so the SAME summing function is honest for either side
  // without a second, parallel total helper to keep in sync.
  //
  // F1 (review remediation, 2026-08): `payrollTotal` is a RAW sum of those 16 stored columns —
  // exactly what `declaredAllowanceTotal` has always meant ("display-only ... not a capped or
  // tax-adjusted figure", see that function's own doc comment) and NOT what `PayrollCalculator`
  // deducts. `PayrollCalculator#allowanceBreakdown` clamps nearly every one of these (per-field caps,
  // the 500,000 retirement cluster, RMF's own 30%-of-income ceiling, the donation ceiling...) AND
  // adds the SSO allowance this sum never included in the first place (SSO is one of
  // AUTO_GRANTED_ROWS above, never a declared/stored column). So `payrollTotal` can overstate the
  // real deduction by a wide margin — e.g. a stored rmfAllowance of 600,000 reads as 600,000 here
  // while payroll applies at most 500,000 (the cluster cap) or 30% of projected income, whichever
  // binds first. This panel must never claim `payrollTotal` IS the applied figure — only that it is
  // what got stored — and must point the reader to where the real, post-clamp number lives:
  // PayrollPage's own "ค่าลดหย่อนรวม" line (`selectedLine.taxAllowanceTotal`), which comes straight
  // off `PayrollCalculator`'s output and is the one honest "ใช้จริง" figure in this whole feature.
  const payrollTotal = payrollAllowance ? declaredAllowanceTotal({ allowances: payrollAllowance.allowances }) : null;
  const declaredTotal = declaration ? declaredAllowanceTotal(declaration) : null;
  // F4 (review remediation): sourced from the ONE shared predicate (taxAllowanceStatus.js) that
  // TaxAllowanceReviewPage's summary-row flag also reads, instead of this panel keeping its own,
  // slightly looser condition — see that function's own doc comment for the EXPIRED_UNVERIFIED case
  // this used to get wrong (flagging a disagreement against a payroll row that no longer applies to
  // withholding at all).
  const disagrees = hasAllowanceDisagreement(declaration, payrollAllowance);
  const verificationInfo = payrollAllowance ? payrollVerificationInfo(payrollAllowance.verificationStatus) : null;

  return (
    <div className="grid gap-3">
      {payrollAllowance ? (
        <div className="grid gap-1.5 rounded-md border border-border-subtle bg-surface-muted p-3 text-sm">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="font-extrabold">ค่าลดหย่อนที่บันทึกในระบบเงินเดือน</span>
            <StatusBadge tone={verificationInfo.tone}>{verificationInfo.label}</StatusBadge>
          </div>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="text-text-muted">ยอดรวมที่บันทึกไว้ ก่อนตัดเพดาน (จาก hr.employee_tax_allowance)</span>
            <span className="font-mono font-bold">{formatMoney(payrollTotal)}</span>
          </div>
          {/* F1: the number above is the RAW stored figure, not what payroll deducts — see this
              component's own comment on `payrollTotal`. Said plainly here rather than left implied,
              with a pointer to where the post-clamp figure actually lives. */}
          <p className="m-0 text-2xs text-text-muted">
            ยอดนี้คือค่าที่บันทึกไว้ ยังไม่ผ่านการตัดเพดานตามที่กฎหมายกำหนด — ยอดค่าลดหย่อนที่ใช้ลดภาษีจริงหลังตัดเพดานแล้ว
            ดูได้ที่หน้า “เงินเดือน” ของพนักงาน ในบรรทัด “ค่าลดหย่อนรวม”
          </p>
          <div className="text-2xs text-text-muted">
            {/* Whole-year ruling (2026-08-31): everything promoted since reads month 1 and is simply
                "มีผลทั้งปีภาษี". A pre-ruling row still dated 2-12 keeps naming its month — that is
                real, still governs payroll from that month on, and hiding it would make a stale
                mid-year declaration indistinguishable from a current whole-year one. */}
            {Number(payrollAllowance.effectiveMonth) === 1
              ? 'มีผลทั้งปีภาษี'
              : `มีผลตั้งแต่เดือน ${payrollAllowance.effectiveMonth}`}
            {payrollAllowance.verifiedAt ? ` · ยืนยันเมื่อ ${formatShortDate(payrollAllowance.verifiedAt)}` : ''}
            {payrollAllowance.verificationDeadline ? ` · ต้องยืนยันภายใน ${formatShortDate(payrollAllowance.verificationDeadline)}` : ''}
          </div>
          {disagrees ? (
            <p className="m-0 mt-1 flex items-center gap-1.5 text-xs font-bold text-warning-dark">
              <Icon name="triangleAlert" size={13} />
              ยอดที่ประกาศไว้ ({formatMoney(declaredTotal)}) ไม่ตรงกับยอดที่บันทึกในระบบเงินเดือน ({formatMoney(payrollTotal)})
            </p>
          ) : null}
        </div>
      ) : null}

      {!declaration ? (
        <p className="m-0 text-sm text-text-muted">ยังไม่มีแบบแจ้ง ล.ย.01 ผ่านระบบนี้ — ยอดด้านบนมาจากข้อมูลเดิมก่อนระบบนี้</p>
      ) : (
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
      )}
    </div>
  );
}
