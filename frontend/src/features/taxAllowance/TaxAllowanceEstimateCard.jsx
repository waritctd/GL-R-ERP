import { Icon } from '../../components/common/Icon.jsx';
import { formatMoney } from '../../utils/format.js';

/**
 * "What this saves me" card (issue #387 decision #4). Renders ONLY figures
 * `PayrollAllowanceEstimateResult` returned — no client-side Thai tax arithmetic, ever (CLAUDE.md;
 * a JS reimplementation would drift from PayrollCalculator and lie to the employee about their own
 * tax). When the server reports `estimateAvailable === false` this shows `unavailableReason`
 * instead of a number — never a fabricated ฿0 saving.
 */
export function TaxAllowanceEstimateCard({ loading, error, result }) {
  return (
    <section className="rounded-md border border-border bg-surface p-4" aria-live="polite">
      <div className="mb-2 flex items-center gap-2 text-sm font-extrabold text-text">
        <Icon name="calculator" size={16} />
        ประมาณการผลต่อภาษี
      </div>
      {loading ? (
        <p className="m-0 text-sm text-text-muted">กำลังคำนวณ…</p>
      ) : error ? (
        <p className="m-0 text-sm font-bold text-danger">{error}</p>
      ) : !result ? (
        <p className="m-0 text-sm text-text-muted">แก้ไขค่าลดหย่อนด้านบนเพื่อดูผลกระทบต่อภาษีโดยประมาณ</p>
      ) : result.estimateAvailable === false ? (
        <p className="m-0 text-sm font-bold text-warning">{result.unavailableReason || 'ไม่สามารถประมาณการได้ในขณะนี้'}</p>
      ) : (
        <>
          <p className="m-0 text-base">
            ภาษีต่อเดือน ปัจจุบัน <strong>{formatMoney(result.baselineWithholdingTax)}</strong>
            {' → '}
            <strong>{formatMoney(result.proposedWithholdingTax)}</strong>
            {Number(result.monthlyTaxSavings) !== 0 ? (
              <span className={Number(result.monthlyTaxSavings) > 0 ? 'text-success font-bold' : 'text-danger font-bold'}>
                {' '}({Number(result.monthlyTaxSavings) > 0 ? 'ประหยัด' : 'เพิ่มขึ้น'} {formatMoney(Math.abs(Number(result.monthlyTaxSavings)))}/เดือน)
              </span>
            ) : null}
          </p>
          <p className="m-0 mt-1 text-xs text-text-muted">
            ค่าลดหย่อนรวม {formatMoney(result.baselineTaxAllowanceTotal)} → {formatMoney(result.proposedTaxAllowanceTotal)}
          </p>
          {result.disclaimer ? <p className="m-0 mt-2 text-2xs text-text-faint">{result.disclaimer}</p> : null}
        </>
      )}
    </section>
  );
}
