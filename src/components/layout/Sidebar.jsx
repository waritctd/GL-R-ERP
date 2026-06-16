import { roleLabel } from '../../utils/format.js';
import { Avatar } from '../common/Avatar.jsx';
import { Icon } from '../common/Icon.jsx';

export function Sidebar({ items, activeRoute, onRoute, user, employee, onLogout }) {
  return (
    <aside className="sidebar">
      <button className="brand" type="button" onClick={() => onRoute('dashboard')}>
        <span className="brand-mark">
          <i />
          <i />
          <i />
          <i />
        </span>
        <span>
          <strong>GL&R</strong>
          <small>HR Portal</small>
        </span>
      </button>

      <nav className="nav-list" aria-label="Primary navigation">
        {items.map((item) => (
          <button
            key={item.route}
            type="button"
            className={`nav-item ${activeRoute === item.route ? 'active' : ''}`}
            onClick={() => onRoute(item.route)}
          >
            <Icon name={item.icon} size={19} />
            <span>
              {item.label}
              <small>{item.helper}</small>
            </span>
            {item.badge ? <b>{item.badge}</b> : null}
          </button>
        ))}
      </nav>

      <div className="sidebar-account">
        <Avatar employee={employee} name={user.name} size="sm" />
        <span>
          <strong>{employee?.nameTh || user.name}</strong>
          <small>{roleLabel(user.role)}</small>
        </span>
        <button className="icon-button dark" type="button" onClick={onLogout} title="ออกจากระบบ">
          <Icon name="logout" />
        </button>
      </div>
    </aside>
  );
}
