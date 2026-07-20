import { useId, useState } from 'react';
import { Icon } from './Icon.jsx';

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
      <section className="collapsible-section is-static" aria-labelledby={headerId}>
        <div className="collapsible-header">
          <div className="collapsible-header-static">
            <span className="collapsible-title-group">
              <h2 id={headerId} className="collapsible-title">{title}</h2>
              {subtitle ? <span className="collapsible-subtitle">{subtitle}</span> : null}
            </span>
          </div>
          {headerRight ? <div className="collapsible-header-right">{headerRight}</div> : null}
        </div>
        <div className="collapsible-body">{children}</div>
      </section>
    );
  }

  return (
    <section className="collapsible-section">
      <div className="collapsible-header">
        <button
          type="button"
          id={headerId}
          className="collapsible-header-button"
          aria-expanded={open}
          aria-controls={bodyId}
          onClick={() => setOpen((value) => !value)}
        >
          <Icon
            name="chevronDown"
            size={16}
            className={`collapsible-chevron${open ? ' is-open' : ''}`}
          />
          <span className="collapsible-title-group">
            <span className="collapsible-title">{title}</span>
            {subtitle ? <span className="collapsible-subtitle">{subtitle}</span> : null}
          </span>
        </button>
        {headerRight ? <div className="collapsible-header-right">{headerRight}</div> : null}
      </div>
      {open ? (
        <div id={bodyId} role="region" aria-labelledby={headerId} className="collapsible-body">
          {children}
        </div>
      ) : null}
    </section>
  );
}
