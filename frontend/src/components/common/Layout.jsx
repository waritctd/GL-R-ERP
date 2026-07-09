import { cn } from '../../utils/cn.js';

/**
 * PageStack — top-level page rhythm:
 *   display: grid; gap: var(--space-5) (20px); max-width: 1320px;
 */
export function PageStack({ className, children, ...props }) {
  return (
    <div className={cn('grid gap-[20px] max-w-[1320px]', className)} {...props}>
      {children}
    </div>
  );
}

/**
 * Panel — reproduces `.panel`:
 *   background: var(--color-surface); border: 1px solid var(--color-border);
 *   border-radius: var(--radius-md); box-shadow: var(--shadow-sm); padding: 20px;
 * Optional `title`/`actions` render a `.panel-header` row; `Panel.Header` is
 * also exported for callers that need custom header content.
 */
export function Panel({ title, actions, className, children, ...props }) {
  return (
    <section
      className={cn(
        'bg-surface border border-border rounded-md shadow-sm p-5',
        className,
      )}
      {...props}
    >
      {title || actions ? (
        <PanelHeader>
          {title ? <h2 className="m-0 text-lg">{title}</h2> : null}
          {actions}
        </PanelHeader>
      ) : null}
      {children}
    </section>
  );
}

/**
 * Panel.Header — title/actions row:
 *   display: flex; align-items: center; justify-content: space-between;
 *   gap: var(--space-4) (16px); margin-bottom: var(--space-4) (16px);
 */
function PanelHeader({ className, children, ...props }) {
  return (
    <div
      className={cn('flex items-center justify-between gap-4 mb-4', className)}
      {...props}
    >
      {children}
    </div>
  );
}

Panel.Header = PanelHeader;

/**
 * FormGrid — form field grid:
 *   display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--space-4) (16px);
 *   (single: grid-template-columns: 1fr)
 *   ≤720px: grid-template-columns: 1fr (both variants)
 * `span-2` children should pass `className="span-2-item"` via the exported
 * `formGridSpan2` class name, reproducing `.span-2` (span 2 / span 1 ≤720px).
 * Renders a `<div>` by default; pass `as="form"` for callers that need real
 * `<form>` semantics (e.g. a footer submit button using `form="<id>"`).
 */
export function FormGrid({ as: Component = 'div', single = false, className, children, ...props }) {
  return (
    <Component
      className={cn(
        'grid gap-4 max-[720px]:grid-cols-1',
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
 * StatGrid — dashboard stat-card grid:
 *   display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--space-4) (16px);
 *   ≤1040px: repeat(2, minmax(0, 1fr)); ≤720px: 1fr
 */
export function StatGrid({ className, children, ...props }) {
  return (
    <div
      className={cn(
        'grid grid-cols-4 gap-4 max-[1040px]:grid-cols-2 max-[720px]:grid-cols-1',
        className,
      )}
      {...props}
    >
      {children}
    </div>
  );
}

/**
 * FilterBar — toolbar row for search/filter controls:
 *   display: flex; flex-wrap: wrap; gap: var(--space-2) (8px); align-items: center;
 *   background: var(--color-surface); border: 1px solid var(--color-border);
 *   border-radius: var(--radius-md); padding: var(--space-4) (16px);
 */
export function FilterBar({ className, children, ...props }) {
  return (
    <div
      className={cn(
        'flex flex-wrap gap-2 items-center bg-surface border border-border rounded-md p-4',
        className,
      )}
      {...props}
    >
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
