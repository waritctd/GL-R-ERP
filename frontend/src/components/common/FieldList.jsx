import { cn } from '../../utils/cn.js';

/** Renders responsive two- or three-column definition lists; children are dt/dd pairs. */
export function FieldList({ columns = 2, className, children, ...props }) {
  return (
    <dl
      className={cn(
        'grid gap-x-[18px] gap-y-[15px] mb-0 mt-4 mobile:grid-cols-1',
        // dt: field labels are real data captions the user reads, not icons/ placeholders, so per DESIGN.md's Muted
        // Floor Rule they sit at Ink Muted (not Ink Faint, which is ~2.6:1 on white).
        '[&_dt]:m-0 [&_dt]:block [&_dt]:text-text-muted [&_dt]:text-xs [&_dt]:font-medium',
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

/** InfoGrid — reproduces `.info-grid` / `.info-grid.two`: display: grid; gap: 16px; (two:
 * grid-template-columns: repeat(2, minmax(0, 1fr))) ≤1040px (two only): repeat(2, minmax(0, 1fr)) — no-op,
 * same as base ≤720px (two only): grid-template-columns: 1fr */
export function InfoGrid({ two = false, className, children, ...props }) {
  return (
    <div
      className={cn(
        'grid gap-4',
        two ? 'grid-cols-2 mobile:grid-cols-1' : '',
        className,
      )}
      {...props}
    >
      {children}
    </div>
  );
}

/** DetailHero — reproduces `.detail-hero` (+ `.detail-hero.compact`): display: flex; align-items: center; gap:
 * 18px; padding: 20px; background/border/radius via the shared `.panel` surface rule (border-only as of item
 * 3's shadow-sm cleanup — see Layout.jsx's Panel doc comment). */
export function DetailHero({ compact = false, className, children, ...props }) {
  const items = Array.isArray(children) ? children : [children];
  return (
    <section
      className={cn(
        'bg-surface border border-border rounded-md',
        'flex items-center gap-[18px] p-5',
        'mobile:flex-col mobile:items-start mobile:gap-2.5 mobile:p-3.5',
        'mobile:[&_h1]:m-0 mobile:[&_h1]:text-lg mobile:[&_h1]:leading-snug',
        'mobile:[&_p]:mt-0.5 mobile:[&_p]:text-sm mobile:[&_p]:text-text-muted',
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
