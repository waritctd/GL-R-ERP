import { cn } from '../../utils/cn.js';

/**
 * Tailwind port of the `.status-*` TONE rules (styles.css). The base
 * `.status-badge` geometry deliberately stays in that stylesheet for now — see
 * the note below.
 *
 * The tone map reproduces the old selector list EXACTLY, gaps included: the
 * stylesheet had rules for indigo/teal/success/warning/danger/neutral/info and
 * none for the other tones callers pass (`amber`, `blue`, `rose`, `blocking`,
 * plus every dynamic `tone={status.tone}` value that resolves to one of those).
 * Those rendered as an uncolored pill and still do — filling the gaps here
 * would be a visual change, not a port, so it belongs in its own change.
 *
 * WHY THE GEOMETRY IS NOT PORTED YET: three rules still in styles.css override
 * this badge's `display` / `white-space` / `justify-content`, and all three
 * belong to surfaces that have not been migrated yet:
 *   - `.data-row > [span|td] > .status-badge` — forces `display: flex` so a
 *     badge inside a labelled mobile-card cell stays a compact pill instead of
 *     being stretched into a full-width bar.
 *   - the 721–1040px `.ticket-list-page … .status-badge` rule — lets the stage
 *     badge WRAP in the tablet band instead of truncating.
 *   - the ≤720px `.ticket-filter-bar > .status-badge` rule — `display: none`.
 *
 * styles.css loads into `@layer legacy`, which loses to `@layer utilities`
 * whatever the selector specificity — so moving those properties onto this
 * component silently kills all three overrides. That is measured, not
 * theorised: the screenshot suite caught the tablet wrap regression on
 * /tickets. The geometry moves here in the same change that ports the
 * DataTable row rules.
 */
const TONE_CLASSNAMES = {
  indigo: 'bg-info-bg-alt text-info-dark',
  teal: 'bg-success-bg text-success-dark',
  success: 'bg-success-bg text-success-dark',
  warning: 'bg-warning-bg text-warning',
  danger: 'bg-danger-bg text-danger-dark',
  neutral: 'bg-surface-subtle text-icon-muted',
  info: 'bg-info-bg text-info',
};

export function StatusBadge({ children, tone = 'neutral', icon }) {
  return (
    <span className={cn('status-badge', TONE_CLASSNAMES[tone])}>
      {icon}
      {children}
    </span>
  );
}
