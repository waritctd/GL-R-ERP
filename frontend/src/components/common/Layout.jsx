import { cn } from '../../utils/cn.js';

/**
 * PageStack — reproduces `.page-stack`:
 *   display: grid; gap: 18px; min-width: 0; max-width: 1320px;
 * `grid-cols-1` (not bare `grid`) is required: a bare `grid` container has no
 * explicit column, so the browser sizes its one implicit column to the
 * *max-content* width of the widest child (e.g. a tablet-only
 * `min-w-[780px]` DataTable) regardless of viewport — the legacy `.page-stack
 * > * { min-width: 0 }` rule can't stop that, because min-width:0 on a child
 * only lets it shrink *within* an already-constrained track; here the track
 * itself was growing unconstrained. `grid-cols-1` makes the column an
 * explicit `minmax(0,1fr)` track, which actually fills/shrinks to the
 * available width — verified fix for a 768px page-level horizontal-scroll
 * regression on Attendance (content-scroll scrollWidth 814 vs clientWidth
 * 682, i.e. every sibling row stretched to the table's width, not just the
 * table).
 */
export function PageStack({ className, children, ...props }) {
  return (
    <div className={cn('grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px]', className)} {...props}>
      {children}
    </div>
  );
}

/**
 * Panel — reproduces `.panel`:
 *   background: var(--color-surface); border: 1px solid var(--color-border);
 *   border-radius: var(--radius-md); padding: 20px;
 * Item 3 (owner-approved polish): dropped the legacy rule's `box-shadow:
 * var(--shadow-sm)` — at that token's near-invisible 1px/3%-opacity value, a
 * bordered card gained nothing visible from also carrying a shadow, so cards
 * are border-only now. Other hardcoded `shadow-sm` card surfaces across the
 * app — including the legacy `.panel`/`.table-panel`/`.profile-strip`/
 * `.stat-card` CSS rule itself (styles.css), which the ~20 call sites still
 * on the bare class name (not this component) resolve to — were brought in
 * line with this same call; genuinely floating chrome
 * (Modal, OverflowMenu, Toast, the autocomplete panel in
 * PricingRequestCreateModal.jsx) was deliberately left on shadow-md/lg.
 * Optional `title`/`actions` render a `.panel-header` row; `Panel.Header` is
 * also exported for callers that need custom header content.
 *
 * `flush` is the Tailwind equivalent of the legacy `.table-panel` class: no
 * body inset, so a table runs edge to edge, plus `overflow-hidden` so its
 * corners follow the card radius. The header then takes its own inset and a
 * rule beneath it, because without the card's padding a bare heading would sit
 * flat against the border.
 *
 * It replaces a regex that sniffed `p-0` out of `className` to infer the same
 * thing. That inference was invisible at the call site — nine callers spelled
 * it `className="!p-0 overflow-hidden"` and nothing said why, and any caller
 * that zeroed padding for an unrelated reason would have silently acquired a
 * bordered header. A prop says what it means and cannot be triggered by
 * accident.
 */
export function Panel({ title, actions, flush = false, className, children, ...props }) {
  return (
    <section
      className={cn(
        'bg-surface border border-border rounded-md',
        flush ? 'overflow-hidden' : 'p-5',
        className,
      )}
      {...props}
    >
      {title || actions ? (
        <PanelHeader bordered={flush}>
          {title ? <h2 className="m-0 min-w-0 text-lg break-words">{title}</h2> : null}
          {actions}
        </PanelHeader>
      ) : null}
      {children}
    </section>
  );
}

/**
 * Panel.Header — reproduces `.panel-header`:
 *   display: flex; align-items: center; justify-content: space-between;
 *   gap: 14px; margin-bottom: 16px;
 *
 * `bordered` is the header a `flush` panel needs: its own inset (the card has
 * none to lend it) and a rule under it, with the bottom margin dropped —
 * a flush body brings its own padding, so keeping the margin would double-space
 * the rule away from the title. That is the same defect the legacy
 * `.table-panel > .panel-header` rule carries, fixed here by construction
 * rather than by override.
 */
function PanelHeader({ bordered = false, className, children, ...props }) {
  return (
    <div
      className={cn(
        'flex min-h-11 flex-wrap items-center justify-between gap-[14px]',
        bordered
          ? 'mb-0 border-b border-border-subtle px-5 py-4 max-[720px]:px-4 max-[720px]:py-3.5'
          : 'mb-4',
        className,
      )}
      {...props}
    >
      {children}
    </div>
  );
}

Panel.Header = PanelHeader;

/**
 * FormGrid — reproduces `.form-grid` / `.form-grid.single`:
 *   display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px;
 *   (single: grid-template-columns: 1fr)
 *   ≤720px: grid-template-columns: 1fr (both variants)
 * `span-2` children should pass `className="span-2-item"` via the exported
 * `formGridSpan2` class name, reproducing `.span-2` (span 2 / span 1 ≤720px).
 * Renders a `<div>` by default; pass `as="form"` for callers that need real
 * `<form>` semantics (e.g. a footer submit button using `form="<id>"`).
 *
 * `items-start` is not cosmetic. Grid's default `align-items: stretch` sized
 * every field box to the tallest field in its row, and because a field is
 * itself a grid of auto rows, that spare height was absorbed by the *control*
 * — more for a field with no hint line (2 rows to share it) than for one with
 * a hint (3 rows), so side-by-side controls ended up different heights and
 * different vertical offsets. EmployeeFormModal's `ค่าตอบแทนกรรมการ` (no hint,
 * 47px) beside `ภาษีหัก ณ ที่จ่าย` (hint, 36px) was the clearest case: the two
 * differ in source only by `hint=`. Fields now sit at their natural height and
 * a short field simply leaves whitespace below it. FormField.jsx's
 * `content-start` fixes the same thing one level down; both are kept so a
 * field is correct in any parent and a non-FormField child is correct here.
 */
export function FormGrid({ as: Component = 'div', single = false, className, children, ...props }) {
  return (
    <Component
      className={cn(
        'grid items-start gap-[14px] max-[720px]:grid-cols-1',
        single ? 'grid-cols-1' : 'grid-cols-2',
        className,
      )}
      {...props}
    >
      {children}
    </Component>
  );
}

/** Reproduces `.span-2` (grid-column: span 2; span 1 at ≤720px). Apply to a FormGrid child. */
export const formGridSpan2 = 'col-span-2 max-[720px]:col-span-1';

/**
 * StatGrid — reproduces `.stat-grid`:
 *   display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px;
 *   ≤1040px: repeat(2, minmax(0, 1fr)); ≤720px: repeat(2, minmax(0, 1fr)) with a
 *   tighter gap — a single stacked column forced users to scroll past several
 *   screens of stat tiles before reaching real content (see styles.css's
 *   ≤720px `.stat-grid`/`.stat-card` rules, which this mirrors).
 */
export function StatGrid({ className, children, ...props }) {
  return (
    <div
      className={cn(
        'grid grid-cols-4 gap-[14px] max-[1040px]:grid-cols-2 max-[720px]:grid-cols-2 max-[720px]:gap-2.5',
        className,
      )}
      {...props}
    >
      {children}
    </div>
  );
}

/**
 * FilterBar — reproduces `.filter-bar`:
 *   display: flex; flex-wrap: wrap; gap: 10px; align-items: center;
 *   background: var(--color-surface); border: 1px solid var(--color-border);
 *   border-radius: var(--radius-md); padding: 14px;
 */
export function FilterBar({ className, children, ...props }) {
  return (
    <div
      className={cn(
        'flex flex-wrap gap-[10px] items-center bg-surface border border-border rounded-md p-[14px]',
        className,
      )}
      {...props}
    >
      {children}
    </div>
  );
}

/**
 * FilterRow — a horizontal row mixing labelled fields with unlabelled controls
 * (chip rows, buttons, bare selects).
 *
 * `items-end`, never `items-center`. A labelled field is a *stack* — label
 * above control — so centring the row centres that whole stack against its
 * neighbour and pushes the control below it. On `/tax-allowance-review` the
 * ปีภาษี field measured 64px beside a 41px status-chip row, which dropped the
 * select ~12px below the chips it filters alongside. Aligning on the bottom
 * edge lines the control up with the thing next to it, which is what the eye
 * actually reads as "aligned" — the label just floats above.
 *
 * Reach for this whenever a `FormField` shares a flex row with something that
 * has no label of its own. If every child is a labelled field, plain
 * `items-end` is unnecessary — their labels align at the top by themselves.
 */
export function FilterRow({ className, children, ...props }) {
  return (
    <div className={cn('flex flex-wrap items-end gap-3', className)} {...props}>
      {children}
    </div>
  );
}

/**
 * RowActions — reproduces `.row-actions`:
 *   display: flex; justify-content: flex-end; gap: 8px;
 */
export function RowActions({ className, children, ...props }) {
  return (
    <div className={cn('flex justify-end gap-2', className)} {...props}>
      {children}
    </div>
  );
}
