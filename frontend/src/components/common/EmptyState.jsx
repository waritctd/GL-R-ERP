import { Icon } from './Icon.jsx';

export function EmptyState({ icon = 'search', title, description }) {
  return (
    <div className="empty-state">
      <Icon name={icon} size={34} />
      <strong>{title}</strong>
      {description ? <span>{description}</span> : null}
    </div>
  );
}
