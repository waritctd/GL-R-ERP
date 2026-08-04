import { Icon } from './Icon.jsx';

// `titleAnnouncedElsewhere` (FIX F10, DataTable review-remediation): set by a
// caller that already announces this same title text itself via its own
// `aria-live` region (DataTable's zero-row live region does exactly this).
// Without it, the title would be read twice in a linear screen-reader pass
// — once via the live-region announcement, once as this visible heading.
// `aria-hidden` removes only the redundant copy from the accessibility tree;
// it stays visible for sighted users, and the description (never duplicated
// elsewhere) is untouched. Defaults to false so every other EmptyState
// caller (there is no live region wrapping most of them) is unaffected.
/**
 * Tailwind port of `.empty-state` (styles.css, retired). Exported because four
 * callers render a bare "nothing here yet" line without this component's
 * icon/title/description structure (EmployeeDashboard, EmployeeSelfService,
 * SalesOverview, AccessDeniedPage) and must keep matching it exactly.
 *
 * `text-text-muted` sits on the CONTAINER, not on a child, so bare text nodes
 * from those callers inherit a contrast-safe color instead of falling through
 * to Ink Faint. Only the icon is pinned to Ink Faint (DESIGN.md: Ink Faint is
 * for icons/placeholders only, never for text on a light surface). The
 * `empty-state` class name carries no rule any more — it stays as the hook
 * DataTable.test.jsx and phase4a-acceptance.spec.js select on.
 */
export const emptyStateClass = 'empty-state min-h-[220px] grid place-items-center content-center gap-2 text-text-muted text-center';

export function EmptyState({ icon = 'search', title, description, titleAnnouncedElsewhere = false }) {
  return (
    <div className={emptyStateClass}>
      <Icon name={icon} size={34} className="text-text-faint" />
      <strong aria-hidden={titleAnnouncedElsewhere ? 'true' : undefined}>{title}</strong>
      {description ? <span>{description}</span> : null}
    </div>
  );
}
