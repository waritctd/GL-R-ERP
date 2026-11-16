import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { OverflowMenu } from '../../components/common/OverflowMenu.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { dealLifecycleLabel } from '../../utils/format.js';
import { baht } from '../salesBoard/boardFormat.js';
import { DealStepper } from './DealStepper.jsx';
import { NextStepCard } from './NextStepCard.jsx';
import { REP_STEPS } from './dealSteps.js';

/**
 * The deal page header (drawing a-deal): code and lifecycle, the deal named by its project, one line of who / how
 * much / how likely, the quick actions โทรแล้ว · นัดเข้าพบ · ⋯, the S stepper and the ขั้นต่อไป card. It replaces
 * DealStateHeader's five status chips (the drawing's "สถานะ 4–5 ชุด → แถบขั้น S อันเดียว").
 *
 * It keeps DealStateHeader's sticky behaviour: TicketDetailPage condenses it on scroll (`condensed`), and then only
 * the identity row stays, with the primary button and ⋯ moved into it; the sub line and stepper collapse with a
 * grid-rows transition (instant under prefers-reduced-motion) and the ขั้นต่อไป card is not rendered.
 *
 * Every prop that carries an action is resolved by the page; this component only places them.
 */
export function DealHeader({
  summary,
  stepIndex = -1,
  nextStep = null,
  // Facts the card states under its title (a lost deal's reason / date, the stage a paused or lost deal was left at),
  // and the "ถัดไป: …" line (DealStagePanel's NextStageLine), which replaces the stage table's hint when present.
  facts = [],
  nextLine = null,
  bannerText = null,
  primaryAction = null,
  overflowItems = [],
  quickActions = null,
  // Who / value / chance, each null when this viewer may not see it (the page decides).
  designerName = null,
  dealValue = null,
  winProbability = null,
  // deal-party-model.md: `{ projectName, count }` while several contractors are bidding and none is awarded — the
  // deal's customer is then only a placeholder, so the line says the bill-to waits on the bid.
  awaitingBid = null,
  condensed = false,
  onMenuOpenChange,
  onRefresh,
}) {
  const lifecycle = dealLifecycleLabel(summary.lifecycle ?? 'ACTIVE');
  const step = stepIndex >= 0 ? REP_STEPS[stepIndex] : null;
  // Never an empty h1 (fix round 1, L4): the deal's code when it has no name at all.
  const title = awaitingBid?.projectName || summary.projectName || summary.title || summary.customerName
    || summary.code || `งาน #${summary.id}`;
  const sub = [
    awaitingBid
      ? <StatusBadge key="bid" tone="warning">{`ลูกค้าผู้ชำระเงิน · รอผลประมูล ${awaitingBid.count} ราย`}</StatusBadge>
      : summary.customerName && summary.customerName !== title ? <span key="customer">{summary.customerName}</span> : null,
    designerName ? <span key="designer">ผู้ออกแบบ {designerName}</span> : null,
    dealValue != null && Number(dealValue) > 0 ? <span key="value" className="tabular-nums">{baht(dealValue)}</span> : null,
    winProbability != null ? <span key="win" className="tabular-nums">{`โอกาส ${winProbability}%`}</span> : null,
  ].filter(Boolean);
  const overflow = overflowItems.length > 0
    // The trigger sits at the top of the header now (it was in the phone's bottom bar), so on a phone the menu opens
    // DOWN — upward it pushed its first items off the top of the screen (fix round 1, M0).
    ? <OverflowMenu items={overflowItems} mobilePlacement="down" onOpenChange={onMenuOpenChange} />
    : null;

  return (
    <section
      data-testid="deal-state-header"
      data-condensed={condensed ? 'true' : 'false'}
      className={`flex flex-col bg-surface transition-[gap,padding] duration-200 ease-out motion-reduce:transition-none mobile:rounded-md mobile:border mobile:border-border mobile:!gap-3 mobile:!p-3 ${
        condensed ? 'gap-0 px-4 py-3' : 'gap-4 p-4 sm:p-5'
      }`}
    >
      <div data-testid="deal-state-header-bar" className="flex min-w-0 flex-wrap items-start gap-x-3 gap-y-2">
        <div className="flex min-w-0 flex-1 basis-60 flex-col gap-1">
          <div className="flex flex-wrap items-center gap-2">
            <code className="rounded bg-surface-subtle px-2 py-0.5 text-xs text-text-muted">{summary.code}</code>
            {condensed && step
              ? <StatusBadge tone="info">{`${step.code} ${step.label}`}</StatusBadge>
              : <StatusBadge tone={lifecycle.tone}>{lifecycle.label}</StatusBadge>}
            {summary.hasEdits && !condensed ? <StatusBadge tone="warning">✎ มีการแก้ไข</StatusBadge> : null}
          </div>
          <h1
            className={`m-0 min-w-0 font-extrabold leading-tight text-text transition-[font-size] duration-200 ease-out motion-reduce:transition-none [overflow-wrap:anywhere] ${
              condensed ? 'truncate text-base' : 'text-xl sm:text-2xl'
            }`}
          >
            {title}
          </h1>
        </div>
        {/* min-w-0 + wrap: on a 320px phone the cluster (โทรแล้ว, นัดเข้าพบ, ⋯, รีเฟรช) wraps instead of widening the page. */}
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          {condensed ? (
            <div data-testid="ticket-action-bar" className="flex items-center gap-2">
              {primaryAction}
              {overflow}
            </div>
          ) : (
            <>
              {quickActions}
              {overflow}
            </>
          )}
          {onRefresh ? (
            <Button type="button" variant="icon" onClick={onRefresh} title="รีเฟรช" aria-label="รีเฟรช" className="mobile:min-h-[44px] mobile:min-w-[44px]">
              <Icon name="refresh" />
            </Button>
          ) : null}
        </div>
      </div>

      {/* Collapsible extras: grid-rows 1fr→0fr animates the height; only non-interactive content and the stepper's
          own scroll wrapper live here, so overflow-hidden never clips a menu or dialog. Force-open on a phone. */}
      <div
        data-testid="deal-header-extras"
        className={`grid transition-[grid-template-rows,opacity] duration-200 ease-out motion-reduce:transition-none mobile:!grid-rows-[1fr] mobile:!opacity-100 ${
          condensed ? 'grid-rows-[0fr] opacity-0' : 'grid-rows-[1fr] opacity-100'
        }`}
        aria-hidden={condensed ? 'true' : undefined}
      >
        <div className="flex min-h-0 flex-col gap-4 overflow-hidden mobile:gap-3">
          {sub.length > 0 ? (
            <p data-testid="deal-header-sub" className="m-0 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-text-muted [&>*+*]:before:mr-2 [&>*+*]:before:content-['·']">
              {sub}
            </p>
          ) : null}
          <DealStepper current={stepIndex} />
        </div>
      </div>

      {!condensed ? (
        <NextStepCard
          title={nextStep?.title ?? null}
          hint={nextStep?.hint ?? null}
          facts={facts}
          nextLine={nextLine}
          bannerText={bannerText}
          primaryAction={primaryAction}
        />
      ) : null}
    </section>
  );
}
