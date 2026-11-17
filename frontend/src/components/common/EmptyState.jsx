import { Icon } from './Icon.jsx';

// `titleAnnouncedElsewhere` (FIX F10, DataTable review-remediation): set by a caller that already announces
// this same title text itself via its own `aria-live` region (DataTable's zero-row live region does exactly
// this).
export function EmptyState({ icon = 'search', title, description, titleAnnouncedElsewhere = false }) {
  return (
    <div className="empty-state grid min-h-[220px] place-items-center content-center gap-2 text-center text-text-muted">
      <Icon name={icon} size={34} className="text-text-faint" />
      <strong aria-hidden={titleAnnouncedElsewhere ? 'true' : undefined}>{title}</strong>
      {description ? <span>{description}</span> : null}
    </div>
  );
}
