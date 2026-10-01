import { useId, useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { cn } from '../../utils/cn.js';
import { formatMoney } from '../../utils/format.js';

// Rates arrive as a JSON number OR string (a server BigDecimal). The ladder's rates have two
// decimals, the team-override rate has three (0.075%), so: never fewer than two, never more than
// three — `toFixed(2)` would print 0.075 as 0.08.
const formatRate = (value) => `${Number(value).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 3 })}%`;
const nonZero = (value) => value != null && Number(value) !== 0;

/**
 * One line of the ledger. `testId` is the row's contract with its tests; `op` is the running-total
 * operator ("+" / "=") and is aria-hidden because the label already says what the line is.
 */
function Row({ testId, op, label, hint, amount, tone, children, strong = false, headline = false }) {
  return (
    <div
      data-testid={testId}
      className={cn(
        'grid min-w-0 grid-cols-[minmax(0,1fr)_auto] items-baseline gap-x-4 gap-y-1 border-t border-border-subtle py-2.5 first:border-t-0',
        strong && 'rounded-md border-t-0 bg-surface-muted px-3 py-3',
      )}
    >
      <dt className={cn('min-w-0 text-md [overflow-wrap:anywhere]', strong ? 'font-extrabold text-text' : 'text-text-secondary')}>
        <span aria-hidden="true" className="mr-1.5 inline-block w-3 text-text-muted">{op}</span>
        {label}
        {hint ? <span className="mt-0.5 block text-sm text-text-muted">{hint}</span> : null}
        {children}
      </dt>
      <dd
        className={cn(
          'm-0 text-right font-bold tabular-nums [overflow-wrap:anywhere]',
          headline ? 'text-2xl font-extrabold text-text' : strong ? 'text-lg text-text' : 'text-md text-text',
          tone === 'danger' && 'text-danger',
        )}
      >
        {formatMoney(amount)}
      </dd>
    </div>
  );
}

// Why a recipient's team row can read 0.00, derived ONLY from API fields (there is no suppression
// flag): the company total is at or under the threshold, or -- when it cleared the bar -- a
// hand-entered team commission (kind MANAGER) replaced the computed one. Returns null when the
// amount is non-zero (nothing to explain) or the threshold is unknown (nothing to compare).
function teamOverrideReason(summary) {
  if (Number(summary.teamOverrideAmount) !== 0 || summary.teamOverrideThresholdBase == null) return null;
  return Number(summary.companyCommissionableBase) <= Number(summary.teamOverrideThresholdBase)
    ? 'ยอดรับทั้งบริษัทยังไม่ถึงเกณฑ์'
    : 'แทนที่ด้วยรายการที่บันทึกเอง (ดูรายการปรับปรุง)';
}

/** The bands the base actually reached (commission != 0), as range · rate · amount. */
function ReachedBands({ tiers }) {
  const reached = (tiers ?? []).filter((row) => nonZero(row.commission));
  if (reached.length === 0) {
    // Mock mode (VITE_USE_MOCKS=true) has no tier config, and a base under the floor reaches no band.
    return <p className="m-0 mt-2 text-sm text-text-muted">ไม่มีรายละเอียดขั้นบันไดค่าคอมให้แสดงในขณะนี้</p>;
  }
  return (
    <ul className="m-0 mt-2 grid list-none gap-1 rounded-md bg-surface-muted p-3 text-sm">
      {reached.map((row) => (
        <li key={row.tierNumber} className="grid min-w-0 grid-cols-[minmax(0,1fr)_auto_auto] items-baseline gap-x-4 mobile:grid-cols-[minmax(0,1fr)_auto]">
          <span className="min-w-0 text-text-secondary [overflow-wrap:anywhere] mobile:col-span-2">
            {`${formatMoney(row.lowerBound)} – ${row.upperBound == null ? 'ขึ้นไป' : formatMoney(row.upperBound)}`}
          </span>
          <span className="tabular-nums text-text-muted">{formatRate(row.ratePercent)}</span>
          <span className="text-right font-bold tabular-nums text-text [overflow-wrap:anywhere]">{formatMoney(row.commission)}</span>
        </li>
      ))}
    </ul>
  );
}

/**
 * "ค่าคอมของฉัน" — the month's commission as a vertical ledger that BUILDS the total, one line per
 * limb. Every figure is the server's (CommissionService#monthlySummary): nothing here adds,
 * multiplies or rounds; a limb that is zero is left out rather than shown as a zero, except the
 * three that define the statement (base, ladder, total). The total is labelled a PREVIEW because it
 * counts receipts still waiting for approval.
 *
 * Reused as-is by the sales_manager / CEO "สรุปรายคน" picker, with `heading` naming the rep.
 */
export function CommissionStatement({ summary, heading = 'ค่าคอมของฉัน' }) {
  const [bandsOpen, setBandsOpen] = useState(false);
  const bandsId = useId();
  const isTeamRecipient = summary.companyCommissionableBase != null;

  return (
    <section aria-label={heading} className="grid min-w-0 gap-3 rounded-md border border-border bg-surface p-5 mobile:p-4">
      <div className="grid gap-1">
        <h2 className="m-0 text-lg font-extrabold text-text [overflow-wrap:anywhere]">{heading}</h2>
        <p data-testid="statement-preview-note" className="m-0 max-w-[72ch] text-sm text-text-muted">
          ประมาณการ — นับรวมใบกำกับที่ยังไม่อนุมัติ ตัวเลขอาจเปลี่ยนเมื่อผู้จัดการและ CEO อนุมัติ
        </p>
      </div>

      {summary.belowFloor ? (
        // The floor itself is a policy number owned by the backend, so it is named generically.
        <p
          role="status"
          data-testid="statement-below-floor"
          className="m-0 flex items-start gap-2 rounded-md border border-warning-border bg-warning-bg-soft px-3 py-2 text-sm text-warning-dark"
        >
          <Icon name="triangleAlert" size={16} className="mt-0.5 shrink-0" />
          <span>ฐานต่ำกว่าเกณฑ์ขั้นต่ำตามนโยบาย — รอบนี้ยังไม่ได้ค่าคอมตามขั้นบันได</span>
        </p>
      ) : null}

      <dl className="m-0 grid min-w-0">
        {summary.rawCommissionableBase != null ? (
          <Row testId="statement-raw" label="ยอดรับเงิน (ไม่รวม VAT)" amount={summary.rawCommissionableBase} />
        ) : null}
        {nonZero(summary.weightUpliftBase) ? (
          // Negative when a weighted sale was clawed back in the month: a deduction, shown as a minus
          // operator and a positive magnitude so the ledger still adds up by eye.
          Number(summary.weightUpliftBase) < 0
            ? <Row testId="statement-uplift" op="−" label="ส่วนลดจากการหักคืนรายการสต็อก (2x/3x)" amount={Math.abs(Number(summary.weightUpliftBase))} />
            : <Row testId="statement-uplift" op="+" label="ส่วนเพิ่มจากสินค้าสต็อก (2x/3x)" amount={summary.weightUpliftBase} />
        ) : null}
        <Row testId="statement-base" op="=" label="ฐานคิดค่าคอม" amount={summary.commissionableBase} strong />

        <Row testId="statement-tier" label="ค่าคอมตามขั้นบันได" amount={summary.tierCommission}>
          <div className="mt-1">
            <Button
              type="button"
              variant="text"
              className="whitespace-nowrap text-sm mobile:min-h-11"
              aria-expanded={bandsOpen}
              aria-controls={bandsId}
              onClick={() => setBandsOpen((open) => !open)}
            >
              ดูขั้นบันไดที่ได้รับ
              <Icon name={bandsOpen ? 'chevronUp' : 'chevronDown'} size={14} />
            </Button>
          </div>
          {bandsOpen ? <div id={bandsId}><ReachedBands tiers={summary.tiers} /></div> : null}
        </Row>

        {nonZero(summary.incentiveAmount) ? (
          <Row testId="statement-incentive" op="+" label="Incentive ข้อ 12" amount={summary.incentiveAmount} />
        ) : null}
        {nonZero(summary.stockBonusAmount) ? (
          <Row testId="statement-stock-bonus" op="+" label="โบนัสขายสต็อก" amount={summary.stockBonusAmount} />
        ) : null}
        {isTeamRecipient ? (
          <Row
            testId="statement-team-override"
            op="+"
            label="ค่าคอมทีม"
            hint={(
              <>
                {`(ยอดรับทั้งบริษัท ${formatMoney(summary.companyCommissionableBase)} − ${formatMoney(summary.teamOverrideThresholdBase)}) × ${formatRate(summary.teamOverrideRatePercent)}`}
                {teamOverrideReason(summary) ? <span className="mt-0.5 block font-bold text-text-secondary">{teamOverrideReason(summary)}</span> : null}
              </>
            )}
            amount={summary.teamOverrideAmount}
          />
        ) : null}
        {nonZero(summary.manualTotal) ? (
          <Row
            testId="statement-manual"
            op="+"
            label="รายการปรับปรุง"
            amount={summary.manualTotal}
            tone={Number(summary.manualTotal) < 0 ? 'danger' : undefined}
          />
        ) : null}
        <div className="mt-2">
          <Row testId="statement-total" op="=" label="รวมค่าคอม (ประมาณการ)" amount={summary.totalCommission} strong headline />
        </div>
      </dl>
    </section>
  );
}
