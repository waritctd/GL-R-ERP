import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { OverflowMenu } from '../../components/common/OverflowMenu.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import {
  dealLifecycleLabel, dealStageLabel, formatMoney, formatThaiDate, fulfilmentStatusLabel,
  paymentStageLabel, pricingRequestStatusLabel, ticketStatusLabel,
} from '../../utils/format.js';
import { activePricingRequestsSummary } from '../pricingRequests/pricingRequestMeta.js';
import { visibleSections } from './salesViewScope.js';

// Slice A "chip diet": which viewer gets which of the 5 possible header
// chips. ขั้นตอนดีล and การนำเข้า are unconditional for every sales/CRM role
// that ever reaches this page, so they're not in this table — only the other
// three vary:
//
//                  sales  sales_manager  ceo  import  account
//   คำขอราคา         yes      yes        yes   yes      NO
//   การชำระเงิน       yes      yes        yes   NO       yes
//   มูลค่าดีล         yes      yes        yes   NO       yes
//
// คำขอราคา mirrors TicketDetailPage's own `canViewPricingRequests` gate
// byte-for-byte, NOT `visibleSections(role).pricingRequest` — the two happen
// to agree today, but they answer different questions (that flag decides
// whether the PricingRequestPanel/detail chain renders further down the
// page; this one decides whether the `pricingRequests` list was ever fetched
// for this role at all). The distinction matters here: when a role can't
// view pricing requests, TicketDetailPage hard-codes `pricingRequests` to
// `[]` for it regardless of whether requests exist — so without this
// exclusion the chip would render "ยังไม่มี" (none yet) for `account` even on
// a deal with live requests. Hiding it is a correctness fix, not
// decluttering.
const CAN_VIEW_PRICING_REQUESTS_ROLES = ['sales', 'import', 'ceo', 'sales_manager'];

/**
 * One compact "stat chip": a label (dt) over either a StatusBadge (when
 * `tone` is given) or a plain value (dd) — deal value has no status tone of
 * its own. Nesting fix (card-diet, 2026-07): this used to be its own
 * bordered/background box (`rounded-lg border border-border bg-surface-subtle
 * px-3 py-2`) sitting INSIDE DealStateHeader's own card — a nested card,
 * never right per DESIGN.md. Renders as a plain dt/dd pair now; the parent
 * `<dl>` grid spacing is what keeps the (role-variable, 3-5) values distinct,
 * not per-item chrome.
 */
function StatChip({ label, value, tone, className = '' }) {
  return (
    <div className={`flex min-w-0 flex-col gap-1 ${className}`}>
      <dt className="text-2xs font-bold uppercase tracking-wide text-text-muted">{label}</dt>
      <dd className="m-0 min-w-0">
        {tone ? (
          <StatusBadge tone={tone}>{value}</StatusBadge>
        ) : (
          <span className="block truncate text-sm font-extrabold text-text">{value}</span>
        )}
      </dd>
    </div>
  );
}

/**
 * Which column-span classes the LAST chip in the row needs, for a grid whose
 * column template is fixed at 2 (base) / 3 (tablet) / 5 (lg) but whose chip
 * COUNT varies by role (3, 4, or 5 — see CAN_VIEW_PRICING_REQUESTS_ROLES /
 * `sections.payment` above). See the long comment above the `<dl>` below for
 * the full "why" (measured label widths, why stranding one chip alone on the
 * final row is the failure mode being avoided); this is just the math.
 *
 * A count strands exactly one chip alone on the grid's final row only when
 * there IS a final row (count > cols) AND it holds exactly one item
 * (count % cols === 1) — the grid fills row-major in reading order, so that
 * leftover item is always the LAST chip. When that happens, the last chip
 * spans the full row at that breakpoint instead of sitting in it alone (the
 * same trick the original fixed-five-chip layout already used at the base
 * breakpoint: `col-span-2` there, because 5 % 2 === 1). Every other chip
 * count/breakpoint combination needs no override — the default (no explicit
 * col-span class) already occupies exactly one column.
 */
function lastChipSpanClass(count) {
  const strandsAt = (cols) => count > cols && count % cols === 1;
  const base = strandsAt(2) ? 'col-span-2' : 'col-span-1';
  const tablet = strandsAt(3) ? 'tablet:col-span-3' : 'tablet:col-span-1';
  const lg = strandsAt(5) ? 'lg:col-span-5' : 'lg:col-span-1';
  return `${base} ${tablet} ${lg}`;
}

/**
 * Deal Workspace state header (Phase 2 Slice S2, folded into the ticket-
 * detail IA rebuild Phase 1 — see
 * docs/ui-repair/02-information-architecture/TICKET_INFORMATION_ARCHITECTURE.md
 * "Persistent header" / "Action bar (sticky)"): the single glanceable
 * summary of "where is this deal, and whose move is it" — deal code/title/
 * customer + lifecycle badge, a compact stat strip (sales stage × PCR status
 * × payment status × fulfilment status × deal value — role-variable since
 * Slice A "chip diet", see CAN_VIEW_PRICING_REQUESTS_ROLES above; `role` is a
 * required prop), ONE work-state banner line ("whose move, what's blocking"
 * — region 4/6/7 of the IA), and the
 * sticky action bar's primary CTA + "⋯" overflow trigger. `sticky top-0`:
 * this is the one thing that should never scroll out of view on a page this
 * long (see the Phase-1 handoff's measured "4,324px / 12 panels / 15
 * buttons" finding).
 *
 * Every value here is read straight off the ticket summary / the already-
 * fetched pricingRequests list, or derived by the parent (workState.js's
 * resolveWorkState + the SAME `can` flags, themselves gated on
 * `GET /{id}/actions`, that drive every button elsewhere on the page) — this
 * component never invents a status or action the data can't support, it
 * only surfaces what TicketDetailPage already computed, once, at the top.
 *
 * `bannerText`: the ONE work-state line (already composed by the parent —
 * "รอฝ่ายนำเข้า — รอชำระมัดจำ" / a bare blocker / etc.), or null when there is
 * nothing to say — either because the deal is on hold/dormant/lost
 * (DealStagePanel already renders a dedicated banner for those states), or
 * because `primaryAction` already exists and carries the same message on its
 * own label, so the bar renders the button alone rather than a redundant
 * line next to it. `primaryAction`: the one CTA node this viewer may act on
 * right now, or null — never a second copy of a button rendered elsewhere on
 * the page. `overflowItems`: the "⋯" menu's items (see OverflowMenu.jsx),
 * already filtered to what this viewer may do — empty/undefined renders no
 * trigger at all.
 */
export function DealStateHeader({
  summary, pricingRequests = [], role, primaryAction, bannerText, overflowItems, onRefresh, condensed = false,
}) {
  const lifecycle = dealLifecycleLabel(summary.lifecycle ?? 'ACTIVE');
  const status = ticketStatusLabel(summary.status);
  const stage = dealStageLabel(summary.salesStage);
  const payment = paymentStageLabel(summary.paymentStage);
  const fulfilment = summary.fulfillmentStatus ? fulfilmentStatusLabel(summary.fulfillmentStatus) : null;
  const hasDealValue = Number(summary.amountPayable ?? 0) > 0;

  // Most-recently-created non-cancelled pricing request stands in for "PCR
  // status" at a glance — activePricingRequestsSummary already sorts oldest
  // first (see pricingRequestMeta.js), so the last entry is the newest.
  const pricingSummary = activePricingRequestsSummary(pricingRequests);
  const latestPr = pricingSummary ? pricingSummary.requests[pricingSummary.requests.length - 1] : null;
  const pricingStatus = latestPr ? pricingRequestStatusLabel(latestPr.status) : null;

  // Role-aware chip set (Slice A "chip diet" — see CAN_VIEW_PRICING_REQUESTS_ROLES's
  // own doc comment above for the full table + reasoning). `sections.payment`
  // already expresses "does this role have any business with the payment
  // machinery" (salesViewScope.js) — reused here for both การชำระเงิน and
  // มูลค่าดีล rather than re-deciding the same rule twice. Hiding มูลค่าดีล for
  // `import` only reduces what import is SHOWN here: TicketService.projectForRole
  // still sends summary.amountPayable to import over the wire regardless of
  // this chip, so this is NOT closing an exposure — the field itself stays
  // unscoped (the actually-scoped CEO-only costing/margin data is a separate,
  // untouched mechanism — showCalcBreakdown in TicketDetailPage).
  const sections = visibleSections(role);
  const canViewPricingRequests = CAN_VIEW_PRICING_REQUESTS_ROLES.includes(role);
  const chips = [
    { key: 'stage', label: 'ขั้นตอนดีล', value: stage.label, tone: stage.tone },
    canViewPricingRequests && {
      key: 'pricing',
      label: 'คำขอราคา',
      value: pricingStatus ? pricingStatus.label : 'ยังไม่มี',
      tone: pricingStatus ? pricingStatus.tone : 'neutral',
    },
    sections.payment && { key: 'payment', label: 'การชำระเงิน', value: payment.label, tone: payment.tone },
    {
      key: 'fulfilment',
      label: 'การนำเข้า',
      value: fulfilment ? fulfilment.label : 'ยังไม่เริ่ม',
      tone: fulfilment ? fulfilment.tone : 'neutral',
    },
    sections.payment && {
      key: 'dealValue',
      label: 'มูลค่าดีล',
      value: hasDealValue ? formatMoney(summary.amountPayable ?? 0) : '—',
    },
  ].filter(Boolean);

  const hasActionBar = Boolean(bannerText || primaryAction || (overflowItems && overflowItems.length > 0));
  const actionControls = (
    <div className="flex shrink-0 items-center gap-2">
      {primaryAction ? <div className="shrink-0">{primaryAction}</div> : null}
      <OverflowMenu items={overflowItems} mobilePlacement="up" />
    </div>
  );

  if (condensed) {
    return (
      <section data-testid="deal-state-header" data-condensed="true" className="bg-surface px-4 py-3">
        <div className="flex min-w-0 flex-wrap items-center gap-2 sm:gap-3">
          <code className="rounded bg-surface-subtle px-2 py-0.5 text-xs text-text-muted">{summary.code}</code>
          <h1 className="m-0 min-w-[12rem] flex-1 truncate text-base font-extrabold text-text">
            {summary.customerName || summary.title}
          </h1>
          <StatusBadge tone={stage.tone}>{stage.label}</StatusBadge>
          {bannerText ? (
            <span className="hidden min-w-0 max-w-[22rem] truncate text-xs font-bold text-info xl:block">{bannerText}</span>
          ) : null}
          {onRefresh ? (
            <Button type="button" variant="icon" onClick={onRefresh} title="รีเฟรช" aria-label="รีเฟรช">
              <Icon name="refresh" />
            </Button>
          ) : null}
          {hasActionBar ? (
            <div data-testid="ticket-action-bar" className="ml-auto flex shrink-0 items-center gap-2">
              {actionControls}
            </div>
          ) : null}
        </div>
      </section>
    );
  }

  return (
    <section data-testid="deal-state-header" data-condensed="false" className="flex flex-col gap-4 bg-surface p-4 sm:p-5 mobile:gap-3 mobile:rounded-lg mobile:border mobile:border-border mobile:p-3">
      <div className="relative flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 mobile:pr-12">
          <div className="flex flex-wrap items-center gap-2">
            <code className="rounded bg-surface-subtle px-2 py-0.5 text-xs text-text-muted">{summary.code}</code>
            <StatusBadge tone={lifecycle.tone}>{lifecycle.label}</StatusBadge>
          </div>
          <h1 className="mt-2 truncate text-xl font-extrabold text-text sm:text-2xl">
            {summary.customerName || summary.title}
          </h1>
          {summary.projectName ? (
            <p className="mt-0.5 truncate text-sm text-text-muted">{summary.projectName}</p>
          ) : null}
          <div className="mt-3 flex flex-wrap items-center gap-2 text-xs text-text-muted sm:gap-4">
            <span className="mobile:hidden"><StatusBadge tone={status.tone}>{status.label}</StatusBadge></span>
            {summary.hasEdits ? <span className="mobile:hidden"><StatusBadge tone="warning">✎ มีการแก้ไข</StatusBadge></span> : null}
            <span className="mobile:hidden">สร้างโดย <strong className="text-text-secondary">{summary.createdByName || '-'}</strong> · {formatThaiDate(summary.createdAt)}</span>
            {summary.assignedToName ? (
              <span className="mobile:hidden">เจ้าหน้าที่นำเข้าที่ดูแล <strong className="text-text-secondary">{summary.assignedToName}</strong></span>
            ) : null}
            <span>อัปเดตล่าสุด <strong className="text-text-secondary">{formatThaiDate(summary.updatedAt)}</strong></span>
          </div>
        </div>
        {onRefresh ? (
          <Button type="button" variant="icon" className="mobile:absolute mobile:right-0 mobile:top-0" onClick={onRefresh} title="รีเฟรช" aria-label="รีเฟรช">
            <Icon name="refresh" />
          </Button>
        ) : null}
      </div>

      {/* Responsive-overflow fix (Phase 2): `.status-badge` is
          `white-space: nowrap` (styles.css:1795). FIX 4 (Opus review —
          measured truth, corrected): the widest Thai stage label is NOT
          "เสนอราคาผู้ออกแบบ/เจ้าของ" (167px, stage 4 — the most common stage,
          but not the widest one) — it's "นัดส่งสินค้า / นัดรับเงินส่วนที่เหลือ" at
          190px, with "เข้าถึงเจ้าของ/ผู้ออกแบบโครงการ" close behind at 189px.
          CORRECTED (Slice A, measured from the compiled CSS rather than
          re-asserted): the old text here claimed the fifth column measures
          ~174px "at exactly 1024px (`lg:grid-cols-5`'s own breakpoint)".
          There IS no fifth column at 1024px. `tablet` is a BANDED variant
          — `(min-width:721px) and (max-width:1040px)`, see index.css —
          while `lg` is min-width:1024px, so the two OVERLAP across
          1024-1040px. Both rules land in the same `utilities` layer at
          equal specificity, and `tablet:` is emitted AFTER `lg:` in the
          built stylesheet, so `tablet:` wins that band: the grid is 3
          columns until 1041px, and only then becomes 5. The narrow-column
          wrap the rest of this comment describes is real, it just happens
          above 1040px rather than at 1024px. That wrap is the fix working
          as intended, not a bug: (a) below wraps the badge instead of
          letting it overflow the column and paint over the next chip, and
          the column only needs to be wide enough for the badge to wrap
          sanely, not wide enough to fit the widest label on one line.
          (a) `[&_.status-badge]:whitespace-normal` lets the badge wrap
          inside THIS dl only — it wins over styles.css's nowrap because
          that file loads into `@layer legacy` (src/index.css:1 declares
          `@layer theme, legacy, utilities`), so an unlayered Tailwind
          utility outranks it without needing `!`. The shared StatusBadge
          component itself is untouched.
          (b) Slice A "chip diet": the chip count is no longer fixed at
          five — it's role-variable (3 for import, 4 for account, 5 for
          sales/sales_manager/ceo; see CAN_VIEW_PRICING_REQUESTS_ROLES /
          `sections.payment` above). The column TEMPLATE stays fixed at
          2 (base) / 3 (tablet) / 5 (lg) across every count — only the
          LAST chip's own col-span changes, via `lastChipSpanClass`
          (defined above StatChip), to fill the final row wherever a
          fixed count would otherwise leave exactly one chip stranded
          alone on it: base strands at counts 3 and 5 (3%2===1, 5%2===1),
          tablet strands at count 4 (4%3===1), and lg never strands for
          any count ≤ 5. This is the same reasoning the original
          fixed-five layout used (it strands at base only, hence its old
          bare `col-span-2 tablet:col-span-1 lg:col-span-1`) — generalized
          instead of re-measured per count, since the underlying "row-major
          fill, remainder-1 is the only bad case" math doesn't change with
          N. See this branch's handoff for the measured label widths and
          the 390/834/1024/1440 column-width proof this generalizes. */}
      <dl className="m-0 grid grid-cols-2 gap-x-3 gap-y-3 tablet:grid-cols-3 lg:grid-cols-5 mobile:gap-y-2 [&_.status-badge]:whitespace-normal">
        {chips.map((chip, index) => (
          <StatChip
            key={chip.key}
            label={chip.label}
            value={chip.value}
            tone={chip.tone}
            className={index === chips.length - 1 ? lastChipSpanClass(chips.length) : ''}
          />
        ))}
      </dl>

      {/* The sticky action bar (IA "Action bar (sticky)"): ONE work-state
          line + the one primary CTA this viewer may act on + the "⋯"
          overflow. Hidden entirely when there is nothing to say and nothing
          to do — never an empty chrome bar. */}
      {hasActionBar ? (
        <div
          data-testid="ticket-action-bar"
          // No `bannerText`: the primary CTA IS the message (its own label
          // says the same thing "ถึงคิวคุณ: <label>" used to say), so on
          // desktop this sheds its info-tinted chrome entirely — no border,
          // no tinted background, no padding — and right-aligns, letting the
          // lone button read as the emphasis instead of competing with a
          // redundant line next to it. On mobile it must still read as a
          // solid bar (it's `fixed` over scrolling content — a transparent
          // fixed bar would be unreadable), so it keeps its own bg/border/
          // padding/shadow/safe-area there regardless of bannerText.
          className={bannerText
            ? 'flex flex-wrap items-center justify-between gap-3 rounded-lg border border-info-border bg-info-bg px-4 py-3 mobile:fixed mobile:inset-x-0 mobile:bottom-0 mobile:z-20 mobile:rounded-none mobile:border-x-0 mobile:border-b-0 mobile:px-4 mobile:py-3 mobile:shadow-lg mobile:[padding-bottom:max(18px,env(safe-area-inset-bottom))]'
            : 'flex flex-wrap items-center justify-end gap-3 mobile:fixed mobile:inset-x-0 mobile:bottom-0 mobile:z-20 mobile:bg-surface mobile:border-t mobile:border-border mobile:px-4 mobile:py-3 mobile:shadow-lg mobile:[padding-bottom:max(18px,env(safe-area-inset-bottom))]'}
        >
          {bannerText ? (
            <div className="flex min-w-0 items-start gap-2">
              <Icon name="chevronRight" size={14} className="mt-0.5 shrink-0 text-info" />
              <span className="text-sm font-bold text-info">{bannerText}</span>
            </div>
          ) : null}
          {actionControls}
        </div>
      ) : null}
    </section>
  );
}
