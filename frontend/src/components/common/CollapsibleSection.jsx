import { useId, useState } from 'react';
import { Icon } from './Icon.jsx';
import { cn } from '../../utils/cn.js';

/** Accessible titled section for progressive disclosure of long forms. Read-only record views
 * (EmployeeDetailPage) want the grouping and the surface but have nothing to progressively disclose — a
 * chevron there promises hidden content that doesn't exist. */
export function CollapsibleSection({
  title,
  subtitle,
  defaultOpen = true,
  collapsible = true,
  children,
  id,
  headerRight,
}) {
  const [open, setOpen] = useState(defaultOpen);
  const autoId = useId();
  const sectionId = id ?? autoId;
  const headerId = `${sectionId}-header`;
  const bodyId = `${sectionId}-body`;

  if (!collapsible) {
    return (
      <section
        className="collapsible-section is-static border border-border rounded-md bg-surface [&+&]:mt-4"
        aria-labelledby={headerId}
      >
        <div className="collapsible-header flex items-center justify-between gap-3">
          <div className="flex-1 flex items-center pt-3 px-3 pb-0">
            <span className="collapsible-title-group flex flex-col gap-0.5">
              <h2 id={headerId} className="collapsible-title text-[length:var(--text-base)] m-0">{title}</h2>
              {subtitle ? <span className="collapsible-subtitle text-[length:var(--text-xs)] font-medium text-text-muted">{subtitle}</span> : null}
            </span>
          </div>
          {headerRight ? <div className="collapsible-header-right pt-3 pr-3 pb-0 pl-0">{headerRight}</div> : null}
        </div>
        <div className="collapsible-body pt-0 px-3 pb-3">{children}</div>
      </section>
    );
  }

  return (
    <section className="collapsible-section border border-border rounded-md bg-surface [&+&]:mt-4">
      {/* `mobile:flex-wrap` — at ≤720px the title and `headerRight` stop competing for one row. Wrapping gives the
       * heading the full width and drops the controls onto their own row beneath it. */}
      <div className="collapsible-header flex items-center justify-between gap-3 mobile:flex-wrap mobile:gap-0">
        {/* The button is WRAPPED IN A HEADING, per the ARIA APG accordion pattern (` <button aria-expanded>`). So
         * making a section collapsible silently cost a heading, which is backwards: the more content a section hides,
         * the more its title matters for navigating by heading. */}
        <h2 id={`${headerId}-heading`} className="collapsible-title-heading m-0 min-w-0 flex-1 text-[length:var(--text-lg)] font-bold mobile:basis-full">
        <button
          type="button"
          id={headerId}
          className="collapsible-header-button w-full flex items-center gap-2 bg-transparent border-0 text-left p-4 text-text rounded-md hover:bg-surface-hover focus-visible:outline-none focus-visible:shadow-[var(--shadow-focus-ring)]"
          aria-expanded={open}
          aria-controls={bodyId}
          onClick={() => setOpen((value) => !value)}
        >
          <Icon
            name="chevronDown"
            size={16}
            className={cn(
              'collapsible-chevron flex-none text-icon-muted transition-transform duration-150 ease-[ease] motion-reduce:transition-none',
              open ? 'rotate-0' : '-rotate-90',
              open && 'is-open',
            )}
          />
          <span className="collapsible-title-group flex min-w-0 flex-col gap-0.5">
            <span className="collapsible-title text-[length:var(--text-lg)] font-bold text-text">{title}</span>
            {subtitle ? <span className="collapsible-subtitle text-[length:var(--text-xs)] font-medium text-text-muted">{subtitle}</span> : null}
          </span>
        </button>
        </h2>
        {headerRight ? <div className="collapsible-header-right pr-4 mobile:basis-full mobile:pb-4 mobile:pl-4">{headerRight}</div> : null}
      </div>
      {open ? (
        <div id={bodyId} role="region" aria-labelledby={headerId} className="collapsible-body pt-0 px-4 pb-4">
          {children}
        </div>
      ) : null}
    </section>
  );
}
