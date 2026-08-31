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
      // The title column carries a 180px FLOOR, not `minmax(0,1fr)`. That difference is
      // load-bearing, and the failure it prevents is invisible rather than loud:
      //
      // `minmax(0,1fr)` lets the title column shrink to ZERO while the actions column (`auto`)
      // never shrinks at all. So a header with enough action buttons hands the entire row to the
      // buttons -- and because <h1>/<p> below carry `[overflow-wrap:anywhere]`, the starved text
      // does not overflow where you would see it. It breaks INSIDE the word, one character per
      // line. Measured on /attendance as HR at 721px with four buttons (2026-08-30): the title
      // column resolved to 19.6px against 619px of actions, "เวลาทำงาน" rendered as 8 lines of one
      // character, and the header grew to 459px tall.
      //
      // Thai makes this worse than it sounds: Thai text has no inter-word spaces, so a title is
      // effectively one long token and `anywhere` is the only thing letting it wrap at all --
      // there is no word boundary to fall back on, and no width too narrow for it to "fit".
      //
      // The actions container already has `flex-wrap`. It never wrapped only because the title
      // column yielded first, so giving the title a floor is all that is needed -- the buttons
      // then wrap to a second row on their own and the header settles at 86px.
      //
      // 180px is a MINIMUM, not a width: `1fr` still gives the title everything spare, so this is
      // a no-op at desktop widths and on any page whose actions are narrow. It is also never
      // applied on mobile -- `mobile:grid-cols-[minmax(0,1fr)]` below collapses to a single column
      // under 720px, where the title already gets the full row.
      //
      // Why the band is easy to miss: <=720px stacks (clean), 860-1040px has room (clean), and
      // 1041px+ breaks AGAIN because the permanent sidebar narrows the content area. A
      // 390/1024/1440 sweep therefore reports no problem at all. Swept 360-1440 incl. 721/768/1041.
      className={cn(
        'grid grid-cols-[minmax(180px,1fr)_auto] items-start gap-[18px] mobile:grid-cols-[minmax(0,1fr)] mobile:gap-2.5',
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
        // `page-actions` carries no CSS of its own — kept only because
        // e2e/hr.spec.js scopes a button query to it to disambiguate from an
        // EmptyState action with the same accessible name.
        <div className={cn('page-actions flex flex-wrap items-center justify-end gap-2.5 mobile:w-full mobile:justify-start', actionsClassName)}>{actions}</div>
      ) : null}
    </header>
  );
}
