import { useId, useState } from 'react';
import { Icon } from './Icon.jsx';
import { cn } from '../../utils/cn.js';

/**
 * Accessible titled section for progressive disclosure of long forms.
 *
 * The header is a native `<button>` (space/enter toggle for free, focusable
 * without extra tabindex wiring) that controls a `role="region"` body via
 * `aria-expanded`/`aria-controls`/`aria-labelledby`. When collapsed, the body
 * is not rendered at all rather than hidden with CSS: callers pass controlled
 * inputs whose values live in the PARENT's state, so unmounting the section's
 * children here is safe (nothing is lost) and keeps the DOM light.
 *
 * Example:
 *   <CollapsibleSection title="ที่อยู่จัดส่ง" subtitle="ไม่บังคับ">
 *     <input value={address} onChange={(e) => setAddress(e.target.value)} />
 *   </CollapsibleSection>
 *
 * `collapsible={false}` drops the disclosure entirely: a plain heading, no
 * chevron, body always rendered. Read-only record views (EmployeeDetailPage)
 * want the grouping and the surface but have nothing to progressively disclose
 * — a chevron there promises hidden content that doesn't exist. The `is-static`
 * modifier also tightens the padding, since a heading needs less room than a
 * 44px-target button.
 */
export function CollapsibleSection({
  title,
  subtitle,
  defaultOpen = true,
  collapsible = true,
  children,
  id,
  headerRight,
  /**
   * Heading level for the section title, as a number. Defaults to 2, which is right for a section
   * sitting directly on a page. Pass 3 when NESTING one of these inside another — a section inside
   * a section is a sub-section, and emitting a second <h2> there would flatten the two into
   * siblings in the heading outline, which is exactly the navigation aid this component wraps its
   * button in a heading to provide.
   */
  headingLevel = 2,
}) {
  const Heading = `h${headingLevel}`;
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
              <Heading id={headerId} className="collapsible-title text-[length:var(--text-base)] m-0">{title}</Heading>
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
      {/*
        `mobile:flex-wrap` — at ≤720px the title and `headerRight` stop competing for one row.
        Measured at 390px with a badge + a refresh button in headerRight: the disclosure button was
        squeezed to ~200px, which wrapped "คำขอแก้ไขเวลาเข้า-ออกงาน" onto three lines and its
        subtitle onto eight — a wall of text where a section heading should be. Wrapping gives the
        heading the full width and drops the controls onto their own row beneath it.
      */}
      <div className="collapsible-header flex items-center justify-between gap-3 mobile:flex-wrap mobile:gap-0">
        {/*
          The button is WRAPPED IN A HEADING, per the ARIA APG accordion pattern
          (`<h3><button aria-expanded>`). Without it this variant rendered its title inside a bare
          <button> and the section vanished from the page's heading outline — while the
          `collapsible={false}` variant twenty lines above used a real <h2> for the same title.
          So making a section collapsible silently cost a heading, which is backwards: the more
          content a section hides, the more its title matters for navigating by heading.

          Caught by AttendancePage.test.jsx's `getByRole('heading', { name: ... })` when the
          attendance-correction section became collapsible — the test was right and the component
          was wrong.

          `flex-1` moves to the heading (the flex child now) and the button takes `w-full`, so the
          rendered layout is unchanged for every existing caller.
        */}
        <Heading id={`${headerId}-heading`} className="collapsible-title-heading m-0 min-w-0 flex-1 text-[length:var(--text-lg)] font-bold mobile:basis-full">
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
        </Heading>
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
