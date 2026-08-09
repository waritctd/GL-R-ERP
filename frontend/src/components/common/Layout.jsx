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
 * body inset, so a table runs edge to edge, plus clipped overflow so its
 * corners follow the card radius. The header then takes its own inset and a
 * rule beneath it, because without the card's padding a bare heading would sit
 * flat against the border.
 *
 * ── WHY THE CLIP IS `overflow-x: auto`, NOT `overflow: hidden` ──────────────
 * This used to be a plain `overflow-hidden`, and that quietly deleted data.
 *
 * A flush panel is what `DataTable` renders every table into, and those tables
 * carry a `nav-drawer:min-w-[Npx]` floor so their columns are not squeezed
 * unreadable in the 721-1040px band. A minimum wider than the card, inside a
 * box that clips both axes and scrolls neither, does not overflow — it
 * disappears, with no scrollbar and no page overflow to hint that anything is
 * missing. Measured at 768px on main (e43cce56), all unreachable by ANY
 * gesture:
 *
 *   /leave                     980px grid in a 700px card   280px gone
 *   /employee-requests?tab=ot  940px grid in a 702px card   238px gone
 *   /profile                   900px grid in a 702px card   198px gone
 *   /attendance                860px grid in a 702px card   158px gone
 *
 * The columns that vanish are the rightmost ones, which is where these tables
 * put status and row actions — so on /attendance the late/early/OT badge was
 * off-screen at exactly the width the min-width floor was added to protect it
 * at. The floor stopped the squeeze and the panel hid the result, so the
 * earlier fix looked like it had worked.
 *
 * Three separate call sites had already patched this individually by passing
 * `overflow-x-auto` down (`DataTable`'s `panelClassName`, and two hand-rolled
 * tables), and #643 reintroduced it on a brand-new panel within hours. That is
 * the signature of a default that is wrong, not of three careless call sites.
 *
 * `overflow-y` stays `hidden`, so this is strictly a widening: the vertical
 * clip is unchanged, and nothing that was visible before becomes clipped now.
 * The corner radius still clips (verified: `border-radius` unchanged at 8px,
 * `unreachable` 2 -> 0 on /profile, page overflow still 0).
 *
 * ── WHY `overflow-y: hidden` IS THE LOAD-BEARING HALF ───────────────────────
 * An element whose overflow is not `visible` becomes a SCROLL CONTAINER, and a
 * `position: sticky` descendant sticks within its nearest scroll container. So
 * the risk in this change was never the horizontal axis — it was accidentally
 * giving these cards a VERTICAL scrollport and re-anchoring every sticky child
 * inside them. Keeping `overflow-y: hidden` is what prevents that: no flush
 * Panel gains a vertical scrollport, so no sticky descendant changes behaviour.
 *
 * State it that way round, not as "no caller has a sticky descendant" — that
 * would be false. `PayrollPage` has three, and it is also the one caller that
 * overrides this line, passing
 * `panelClassName="max-h-[min(68vh,760px)] overflow-auto"` for its own scroll
 * region (it is the only user of `DataTable`'s `stickyHeader`). It survives
 * because `Panel` merges through `cn()` = `twMerge(clsx(...))`, and
 * tailwind-merge treats `overflow-auto` as conflicting with BOTH longhands:
 *
 *   twMerge('... overflow-x-auto overflow-y-hidden max-h-[...] overflow-auto')
 *     -> '... max-h-[...] overflow-auto'      // both longhands dropped
 *
 * Verified in node, and behaviourally on the page: payroll's table panel
 * computes `overflow-x: auto`, `overflow-y: auto`, `max-height: 612px` — i.e.
 * untouched. **If `Panel` is ever "simplified" to string concatenation instead
 * of `cn`, payroll silently loses vertical scrolling and no test will fail.**
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
        flush ? 'overflow-x-auto overflow-y-hidden' : 'p-5',
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
          ? 'mb-0 border-b border-border-subtle px-5 py-4 mobile:px-4 mobile:py-3.5'
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
 * Always renders a `<div>` — deliberately NOT polymorphic any more
 * (#safe-form-primitive). It used to accept `as="form"` for callers that
 * wanted real `<form>` semantics (a footer submit button using `form="<id>"`)
 * on the same element as the grid, but that was a second, unguarded way to
 * put a `<form>` on the page that the ban on raw `<form>` JSX
 * (eslint.config.js) could never see, because `<FormGrid as="form">` isn't a
 * `<form>` JSX element — it's a call to this function that happens to choose
 * one at runtime. A caller that needs both the grid layout and real form
 * semantics now wraps `<SafeForm>` around `<FormGrid>` instead of collapsing
 * them into one element; see EmployeeFormModal.jsx for the shape. Two
 * elements instead of one costs nothing visually — `<form>` has no default
 * display of its own that the grid depended on — and it means every `<form>`
 * in this codebase is reachable by one lint rule instead of two.
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
export function FormGrid({ single = false, className, children, ...props }) {
  return (
    <div
      className={cn(
        'grid items-start gap-[14px] mobile:grid-cols-1',
        single ? 'grid-cols-1' : 'grid-cols-2',
        className,
      )}
      {...props}
    >
      {children}
    </div>
  );
}

/** Reproduces `.span-2` (grid-column: span 2; span 1 at ≤720px). Apply to a FormGrid child. */
export const formGridSpan2 = 'col-span-2 mobile:col-span-1';

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
        'grid grid-cols-4 gap-[14px] nav-drawer:grid-cols-2 mobile:grid-cols-2 mobile:gap-2.5',
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
 * FilterField — one labelled control inside a wrapping `FilterBar` / `FilterRow`.
 *
 * Replaces the bare `<label>ชื่อ<control/></label>` these bars were built from.
 * That pattern relies on the global `label { display: grid }` base rule to stack
 * the label text above the control, which works — but it leaves the field with
 * flexbox's default `min-width: auto`, and that is the bug this component
 * exists to remove.
 *
 * ── WHY `min-w-0` IS LOAD-BEARING ────────────────────────────────────────────
 * A flex item's automatic minimum size is its content's *min-content* width, so
 * a field can never shrink below the widest thing inside it — and a control it
 * wraps is `width: 100%` (index.css `@layer base`), which resolves against a
 * track the control itself is dictating. For a text input the min-content width
 * is small and nothing shows. For `input[type="date"]` / `input[type="time"]` it
 * is the width of the browser's NATIVE control, which differs per platform:
 * Chromium renders a compact `10/08/2026`, and iOS Safari renders a much wider
 * localized date. So the row fits on the machine it was built on and overflows
 * on the phone it ships to — the field cannot shrink, and `flex-wrap` cannot
 * help either, because wrapping an item that is already too wide for the line
 * changes nothing.
 *
 * Measured on /attendance at 390px, modelling the native control's intrinsic
 * width (the fix is the second column):
 *
 *   intrinsic width   bare <label>                       with min-w-0
 *   260px             row 27px past the bar, 11px page   row 83px inside, 0
 *   320px             row 87px past the bar, 71px page   row 83px inside, 0
 *
 * `min-w-0` lets the field shrink to its share of the line, and the `basis`
 * below decides when it stops sharing and takes a line of its own instead.
 *
 * ── WHY THE BASIS IS FULL-WIDTH ON MOBILE ────────────────────────────────────
 * Without an explicit basis every field sizes to its own content, so which
 * fields end up sharing a line is decided by string lengths rather than by
 * design. On /employee-requests at 390px that produced จากวันที่ alone on one
 * row with dead space beside it, ถึงวันที่ + สถานะ sharing the next, and
 * พนักงาน full width — three different control widths and two different label
 * x-positions in one bar. `mobile:basis-full` makes it one field per line at
 * ≤720px: every control the same width, every label on the same left edge.
 * Above that the fields share lines from a common floor, so they still line up.
 *
 * ── AND WHY THERE IS NO `grow` ───────────────────────────────────────────────
 * `grow` looks right until a line wraps. Measured on /employee-requests at
 * 768px with `grow`: three fields sat at 218px on the first line and พนักงาน —
 * alone on the second — stretched to 565px, because a flex item that owns its
 * line absorbs all of it. That is the same raggedness this component exists to
 * remove, just one breakpoint further out. A fixed basis keeps every field the
 * same width on every line at every width, and the leftover space on the last
 * line is where the submit button sits — which is what a filter bar wants.
 *
 * ── `htmlFor` IS REQUIRED WHEN THE FIELD HOLDS MORE THAN ONE CONTROL ─────────
 * By default this renders a `<label>` that WRAPS its children, which implicitly
 * associates it with the single control inside. That association is only valid
 * for one control: a `<label>` containing several is ambiguous, and the browser
 * (and `getByLabelText`) will not reliably resolve it to the one you meant.
 * /attendance's date stepper is exactly that case — [prev] [date] [next] — and
 * wrapping all three broke `findByLabelText('วันที่')`, which stopped resolving
 * to the date input. Passing `htmlFor` switches the wrapper to a `<div>` and
 * renders a real `<label htmlFor>` pointing at the one control that owns the
 * name, leaving the buttons (which carry their own `aria-label`) outside it.
 * Rule of thumb: one control, omit it; more than one, pass it.
 */
export function FilterField({ label, htmlFor, hint, className, labelClassName, children, ...props }) {
  const Wrapper = htmlFor ? 'div' : 'label';
  const LabelTag = htmlFor ? 'label' : 'span';
  return (
    <Wrapper className={cn('grid content-start gap-[7px] min-w-0 basis-[190px] mobile:basis-full', className)} {...props}>
      {/*
        One grid item, not two. Under the global `label { display: grid }` rule
        every direct child becomes its own row, so a bare text node beside any
        sibling would stack with a 7px gap instead of reading as one label —
        the same trap FormField.jsx documents for its required marker.
      */}
      {label ? <LabelTag htmlFor={htmlFor} className={labelClassName}>{label}</LabelTag> : null}
      {children}
      {hint ? <span className="text-xs font-medium text-text-muted">{hint}</span> : null}
    </Wrapper>
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
