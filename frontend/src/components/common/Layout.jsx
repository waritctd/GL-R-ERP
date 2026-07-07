import { cn } from '../../utils/cn.js';

/**
 * PageStack — reproduces `.page-stack`:
 *   display: grid; gap: 18px; max-width: 1320px;
 */
export function PageStack({ className, children, ...props }) {
  return (
    <div className={cn('grid gap-[18px] max-w-[1320px]', className)} {...props}>
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
 * Panel.Header — reproduces `.panel-header`:
 *   display: flex; align-items: center; justify-content: space-between;
 *   gap: 14px; margin-bottom: 16px;
 */
function PanelHeader({ className, children, ...props }) {
  return (
    <div
      className={cn('flex items-center justify-between gap-[14px] mb-4', className)}
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
 */
export function FormGrid({ single = false, className, children, ...props }) {
  return (
    <div
      className={cn(
        'grid gap-[14px] max-[720px]:grid-cols-1',
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
export const formGridSpan2 = 'col-span-2 max-[720px]:col-span-1';

/**
 * StatGrid — reproduces `.stat-grid`:
 *   display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px;
 *   ≤1040px: repeat(2, minmax(0, 1fr)); ≤720px: 1fr
 */
export function StatGrid({ className, children, ...props }) {
  return (
    <div
      className={cn(
        'grid grid-cols-4 gap-[14px] max-[1040px]:grid-cols-2 max-[720px]:grid-cols-1',
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
