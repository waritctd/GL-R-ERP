import { useId } from 'react';
import { cn } from '../../utils/cn.js';
import { Breadcrumbs } from './Breadcrumbs.jsx';

/**
 * PageHeader — Tailwind port of `.page-heading`/`.page-heading-copy`/
 * `.page-heading-eyebrow`/`.page-heading-context`/`.page-actions` (+ the
 * shared `.login-form h1/p, .page-heading h1/p` rule's `.page-heading` half;
 * `.login-form h1/p` stays in styles.css untouched).
 *
 * `headingClassName`/`subtitleClassName`/`actionsClassName` exist ONLY so
 * TicketListPage.jsx can reproduce its old `.ticket-list-page .page-heading
 * h1/p`/`.ticket-list-page .page-actions` mobile overrides without those
 * legacy selectors (deleted from styles.css along with the base rule) — most
 * callers should never need them.
 */
export function PageHeader({
  title,
  subtitle,
  actions,
  breadcrumbs,
  context,
  eyebrow,
  id,
  className,
  headingClassName,
  subtitleClassName,
  actionsClassName,
}) {
  const generatedId = useId();
  const titleId = id || `page-title-${generatedId}`;

  return (
    <header
      className={cn(
        'grid grid-cols-[minmax(0,1fr)_auto] items-start gap-[18px] mobile:grid-cols-[minmax(0,1fr)] mobile:gap-2.5',
        className,
      )}
      aria-labelledby={titleId}
    >
      <div className="min-w-0">
        {breadcrumbs ? <Breadcrumbs items={breadcrumbs} /> : null}
        {eyebrow ? (
          <span className="mb-1 block text-xs font-extrabold uppercase tracking-normal text-text-muted">{eyebrow}</span>
        ) : null}
        <h1
          id={titleId}
          className={cn('m-0 text-2xl leading-[1.2] [overflow-wrap:anywhere] text-balance', headingClassName)}
        >
          {title}
        </h1>
        {subtitle ? (
          // Explicit mt-1/mx-0/mb-0 (not a shorthand `m-0` + `mt-1` combo,
          // whose cascade order Tailwind does not contractually guarantee):
          // this project ships no Tailwind preflight, so an unreset <p> falls
          // back to the UA default margin on every side this doesn't zero.
          <p className={cn('mt-1 mr-0 mb-0 ml-0 max-w-[72ch] text-text-muted [overflow-wrap:anywhere]', subtitleClassName)}>{subtitle}</p>
        ) : null}
        {context ? <div className="mt-2 flex flex-wrap gap-2">{context}</div> : null}
      </div>
      {actions ? (
        <div className={cn('flex flex-wrap items-center justify-end gap-2.5 mobile:w-full mobile:justify-start', actionsClassName)}>{actions}</div>
      ) : null}
    </header>
  );
}
