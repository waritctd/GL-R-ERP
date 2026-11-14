import { cn } from '../../utils/cn.js';

/** Stacks page content in a bounded single-column grid so wide children cannot expand the page. */
export function PageStack({ className, children, ...props }) {
  return (
    <div className={cn('grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px]', className)} {...props}>
      {children}
    </div>
  );
}

/** Renders a bordered content panel with optional title, actions, and flush body. */
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

/** Lays out panel headings and actions; bordered headers provide their own inset for flush panels. */
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

/** FormGrid — reproduces `.form-grid` / `.form-grid.single`: display: grid; grid-template-columns: repeat(2,
 * minmax); gap: 14px; ≤720px: grid-template-columns: 1fr `span-2` children should pass
 * `className="span-2-item"` via the exported `formGridSpan2` class name, reproducing `.span-2`. */
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

/** StatGrid — reproduces `.stat-grid`: display: grid; grid-template-columns: repeat(4, minmax); gap: 14px;
 * ≤1040px: repeat(2, minmax); ≤720px: repeat(2, minmax) with a tighter gap — a single stacked column forced
 * users to scroll past several screens of stat tiles before reaching real content. */
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

/** FilterBar — reproduces `.filter-bar`: display: flex; flex-wrap: wrap; gap: 10px; align-items: center;
 * background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius-md);
 * padding: 14px; */
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

/** FilterRow — a horizontal row mixing labelled fields with unlabelled controls (chip rows, buttons, bare
 * selects). `items-end`, never `items-center`. Reach for this whenever a `FormField` shares a flex row with
 * something that has no label of its own. */
export function FilterRow({ className, children, ...props }) {
  return (
    <div className={cn('flex flex-wrap items-end gap-3', className)} {...props}>
      {children}
    </div>
  );
}

/** FilterField — one labelled control inside a wrapping `FilterBar` / `FilterRow`. Replaces the bare
 * `<label>ชื่อ<control/></label>` these bars were built from. For a text input the min-content width is small
 * and nothing shows. */
export function FilterField({ label, htmlFor, hint, className, labelClassName, children, ...props }) {
  const Wrapper = htmlFor ? 'div' : 'label';
  const LabelTag = htmlFor ? 'label' : 'span';
  return (
    <Wrapper className={cn('grid content-start gap-[7px] min-w-0 basis-[190px] mobile:basis-full', className)} {...props}>
      {/* One grid item, not two. Under the global `label { display: grid }` rule every direct child becomes its own
       * row, so a bare text node beside any sibling would stack with a 7px gap instead of reading as one label — the
       * same trap FormField.jsx documents for its required marker. */}
      {label ? <LabelTag htmlFor={htmlFor} className={labelClassName}>{label}</LabelTag> : null}
      {children}
      {hint ? <span className="text-xs font-medium text-text-muted">{hint}</span> : null}
    </Wrapper>
  );
}

/** RowActions — reproduces `.row-actions`: display: flex; justify-content: flex-end; gap: 8px; */
export function RowActions({ className, children, ...props }) {
  return (
    <div className={cn('flex justify-end gap-2', className)} {...props}>
      {children}
    </div>
  );
}
