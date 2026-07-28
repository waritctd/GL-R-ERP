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
export function EmptyState({ icon = 'search', title, description, titleAnnouncedElsewhere = false }) {
  return (
    <div className="empty-state">
      <Icon name={icon} size={34} />
      <strong aria-hidden={titleAnnouncedElsewhere ? 'true' : undefined}>{title}</strong>
      {description ? <span>{description}</span> : null}
    </div>
  );
}
