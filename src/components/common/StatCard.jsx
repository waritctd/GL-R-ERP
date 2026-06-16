import { Icon } from './Icon.jsx';

export function StatCard({ icon, label, value, helper, tone = 'indigo' }) {
  return (
    <div className="stat-card">
      <div className={`stat-icon stat-${tone}`}>
        <Icon name={icon} size={21} />
      </div>
      <div className="stat-value">{value}</div>
      <div className="stat-label">{label}</div>
      {helper ? <div className="stat-helper">{helper}</div> : null}
    </div>
  );
}
