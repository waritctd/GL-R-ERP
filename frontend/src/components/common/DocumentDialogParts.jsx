import { Icon } from './Icon.jsx';
import { cn } from '../../utils/cn.js';

// The drawn parts the document dialogs share (owner drawings m-deposit-issue / m-remaining / m-ir / m-delivery,
// 2026-11-16): a one-line summary, a compact figures table, and the warn / info call-outs. Presentation only — every
// figure passed in is a value the caller already has from the server.

/** The drawing's `.sum`: one bold line, an icon, on the subtle surface. */
export function DialogSummary({ icon = 'fileText', testId, children }) {
  return (
    <div
      data-testid={testId}
      className="flex items-center gap-2.5 rounded-md bg-surface-subtle px-3 py-2.5 text-sm font-bold text-text [overflow-wrap:anywhere]"
    >
      <span className="shrink-0 text-primary" aria-hidden="true"><Icon name={icon} size={16} /></span>
      <span className="min-w-0">{children}</span>
    </div>
  );
}

const CALLOUT_TONE = {
  warn: 'border-warning-border bg-warning-bg-soft text-warning-dark',
  info: 'border-primary bg-info-bg-alt text-text-secondary',
};

/** The drawing's `.warn` / `.info`: a left-ruled note. */
export function DialogCallout({ tone = 'info', testId, id, children }) {
  return (
    <p
      id={id}
      data-testid={testId}
      role={tone === 'warn' ? 'note' : undefined}
      className={cn('m-0 rounded-r-md border-l-[3px] px-3 py-2 text-sm leading-normal', CALLOUT_TONE[tone])}
    >
      {children}
    </p>
  );
}

/**
 * The drawing's `.mini` table, in its own horizontal scroller so a narrow phone scrolls the table, never the page.
 * `minWidth` is the width below which it scrolls rather than squeezes (Tailwind class, e.g. 'min-w-[26rem]').
 */
export function DialogTable({ label, minWidth = '', testId, children }) {
  return (
    <div className="-mx-1 overflow-x-auto px-1" data-testid={testId}>
      <table aria-label={label} className={cn('w-full border-collapse text-sm tabular-nums', minWidth)}>
        {children}
      </table>
    </div>
  );
}

export const dialogTh = 'border-b border-border bg-surface-muted px-2 py-1.5 text-left text-2xs font-bold text-text-muted';
export const dialogTd = 'border-b border-border px-2 py-2 align-middle';
