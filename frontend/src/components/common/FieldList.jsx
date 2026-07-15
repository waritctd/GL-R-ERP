import { cn } from '../../utils/cn.js';

/**
 * FieldList — reproduces `.field-list` / `.field-list.three`:
 *   display: grid; grid-template-columns: repeat(2|3, minmax(0, 1fr));
 *   gap: 15px 18px; margin: 16px 0 0;
 *   dt: margin 0, block, faint, text-xs, weight 500 (via global `.field-list dt` rule, unchanged)
 *   dd: margin 0, margin-top 3px, weight 700, color text, overflow-wrap anywhere
 *   ≤720px: grid-template-columns: 1fr
 * Renders a <dl>; children should be <dt>/<dd> pairs.
 */
export function FieldList({ columns = 2, className, children, ...props }) {
  return (
    <dl
      className={cn(
        'grid gap-x-[18px] gap-y-[15px] mt-4 max-[720px]:grid-cols-1',
        // dt reproduces the shared `.field-list dt` rule (styles.css:304): block/faint/xs/500.
        '[&_dt]:m-0 [&_dt]:block [&_dt]:text-text-faint [&_dt]:text-xs [&_dt]:font-medium',
        '[&_dd]:m-0 [&_dd]:mt-[3px] [&_dd]:font-bold [&_dd]:text-text [&_dd]:[overflow-wrap:anywhere]',
        columns === 3 ? 'grid-cols-3' : 'grid-cols-2',
        className,
      )}
      {...props}
    >
      {children}
    </dl>
  );
}

/**
 * InfoGrid — reproduces `.info-grid` / `.info-grid.two`:
 *   display: grid; gap: 16px; (two: grid-template-columns: repeat(2, minmax(0, 1fr)))
 *   ≤1040px (two only): repeat(2, minmax(0, 1fr)) — no-op, same as base
 *   ≤720px (two only): grid-template-columns: 1fr
 */
export function InfoGrid({ two = false, className, children, ...props }) {
  return (
    <div
      className={cn(
        'grid gap-4',
        two ? 'grid-cols-2 max-[720px]:grid-cols-1' : '',
        className,
      )}
      {...props}
    >
      {children}
    </div>
  );
}

/**
 * DetailHero — reproduces `.detail-hero` (+ `.detail-hero.compact`):
 *   display: flex; align-items: center; gap: 18px; padding: 20px;
 *   background/border/radius/shadow via the shared `.panel` surface rule.
 *   2nd child: flex: 1; min-width: 0.
 *   compact: max-width: 980px.
 *   ≤720px: flex-direction: column; align-items: flex-start.
 * Expects exactly 2+ children; the 2nd child gets the flex-1/min-w-0 treatment
 * via a wrapper — callers should pass their "main" block as the 2nd child.
 * Callers (ProfilePage/EmployeeDetailPage) author a plain `<h1>`/`<p>` as the
 * name/subtitle inside that 2nd child. Neither ever had CSS sizing the `h1` —
 * it fell back to the browser default (2em + ~0.67em block margins). That's
 * masked on desktop by `items-center` row alignment, but on mobile the row
 * becomes a column and the default margins show up as dead vertical space
 * above the status badge, so this scopes an explicit compact size to ≤720px.
 */
export function DetailHero({ compact = false, className, children, ...props }) {
  const items = Array.isArray(children) ? children : [children];
  return (
    <section
      className={cn(
        'bg-surface border border-border rounded-md shadow-sm',
        'flex items-center gap-[18px] p-5',
        'max-[720px]:flex-col max-[720px]:items-start max-[720px]:gap-2.5 max-[720px]:p-3.5',
        'max-[720px]:[&_h1]:m-0 max-[720px]:[&_h1]:text-lg max-[720px]:[&_h1]:leading-snug',
        'max-[720px]:[&_p]:mt-0.5 max-[720px]:[&_p]:text-sm max-[720px]:[&_p]:text-text-muted',
        compact ? 'max-w-[980px]' : '',
        className,
      )}
      {...props}
    >
      {items.map((child, index) =>
        index === 1 ? (
          <div className="flex-1 min-w-0" key={child?.key ?? index}>
            {child}
          </div>
        ) : (
          child
        ),
      )}
    </section>
  );
}
