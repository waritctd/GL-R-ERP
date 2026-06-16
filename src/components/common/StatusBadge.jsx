export function StatusBadge({ children, tone = 'neutral', icon }) {
  return (
    <span className={`status-badge status-${tone}`}>
      {icon}
      {children}
    </span>
  );
}
