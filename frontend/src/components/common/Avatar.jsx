import { initialsFromName } from '../../utils/format.js';

export function Avatar({ employee, name, size = 'md' }) {
  const label = employee?.initials || initialsFromName(name || employee?.nameTh);
  return (
    <div
      className={`avatar avatar-${size}`}
      style={{
        background: employee?.avatarBg || '#e2e8f0',
        color: employee?.avatarFg || '#475569',
      }}
      title={employee?.nameTh || name}
    >
      {label}
    </div>
  );
}
