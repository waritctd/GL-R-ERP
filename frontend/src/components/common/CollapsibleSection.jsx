import { useId, useState } from 'react';
import { Icon } from './Icon.jsx';
import { cn } from '../../utils/cn.js';

// Tailwind port of `.collapsible-*` (styles.css, retired).
//
// `is-static` (`collapsible={false}`) tightened the padding because a heading
// needs less room than a 44px-target button: read-only record views stack many
// short groups, and the collapsible padding stack (16px header + 16px FieldList
// margin) left 32px of dead space under every title. That variant also dropped
// the title to `--text-base` (1rem, from Tailwind's own default theme — the app
// never defined its own), which `text-base` reproduces.
const SECTION_CLASS = 'collapsible-section border border-border rounded-md bg-surface [&+&]:mt-4';
const HEADER_CLASS = 'collapsible-header flex items-center justify-between gap-3';
const TITLE_GROUP_CLASS = 'collapsible-title-group flex flex-col gap-0.5';
const SUBTITLE_CLASS = 'collapsible-subtitle text-xs/[inherit] font-medium text-text-muted';

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
}) {
  const [open, setOpen] = useState(defaultOpen);
  const autoId = useId();
  const sectionId = id ?? autoId;
  const headerId = `${sectionId}-header`;
  const bodyId = `${sectionId}-body`;

  if (!collapsible) {
    return (
      <section className={cn(SECTION_CLASS, 'is-static')} aria-labelledby={headerId}>
        <div className={HEADER_CLASS}>
          <div className="collapsible-header-static flex-1 flex items-center px-3 pt-3">
            <span className={TITLE_GROUP_CLASS}>
              <h2 id={headerId} className="collapsible-title m-0 text-base/[inherit] font-bold text-text">{title}</h2>
              {subtitle ? <span className={SUBTITLE_CLASS}>{subtitle}</span> : null}
            </span>
          </div>
          {headerRight ? <div className="collapsible-header-right pr-3 pt-3">{headerRight}</div> : null}
        </div>
        <div className="collapsible-body px-3 pb-3">{children}</div>
      </section>
    );
  }

  return (
    <section className={SECTION_CLASS}>
      <div className={HEADER_CLASS}>
        <button
          type="button"
          id={headerId}
          className={[
            'collapsible-header-button flex-1 flex items-center gap-2',
            'bg-transparent border-0 text-left p-4 text-text rounded-md',
            'hover:bg-surface-hover',
            'focus-visible:outline-none focus-visible:shadow-[var(--shadow-focus-ring)]',
          ].join(' ')}
          aria-expanded={open}
          aria-controls={bodyId}
          onClick={() => setOpen((value) => !value)}
        >
          <Icon
            name="chevronDown"
            size={16}
            className={cn(
              'collapsible-chevron flex-none text-icon-muted transition-transform duration-150 ease-in-out motion-reduce:transition-none',
              open ? 'is-open rotate-0' : '-rotate-90',
            )}
          />
          <span className={TITLE_GROUP_CLASS}>
            <span className="collapsible-title text-lg/[inherit] font-bold text-text">{title}</span>
            {subtitle ? <span className={SUBTITLE_CLASS}>{subtitle}</span> : null}
          </span>
        </button>
        {headerRight ? <div className="collapsible-header-right pr-4">{headerRight}</div> : null}
      </div>
      {open ? (
        <div id={bodyId} role="region" aria-labelledby={headerId} className="collapsible-body px-4 pb-4">
          {children}
        </div>
      ) : null}
    </section>
  );
}
