import { NavLink, useNavigate } from 'react-router-dom';
import { roleLabel } from '../../utils/format.js';
import { Avatar } from '../common/Avatar.jsx';
import { Button } from '../common/Button.jsx';
import { Icon } from '../common/Icon.jsx';

export function Sidebar({ id, drawerRef, isDrawerOpen = false, items, user, employee, onLogout }) {
  const navigate = useNavigate();
  return (
    <aside
      id={id}
      ref={drawerRef}
      className={`sidebar ${isDrawerOpen ? 'is-mobile-drawer-open' : ''}`}
      tabIndex={-1}
    >
      <button className="brand" type="button" onClick={() => navigate('/')}>
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
          <NavLink
            key={item.path}
            to={item.path}
            end={item.path === '/'}
            className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
          >
            <Icon name={item.icon} size={19} />
            <span>
              {item.label}
              <small>{item.helper}</small>
            </span>
            {item.badge ? <b>{item.badge}</b> : null}
          </NavLink>
        ))}
      </nav>

      <div className="sidebar-account">
        <Avatar employee={employee} name={user.name} size="sm" />
        <span>
          <strong>{employee?.nameTh || user.name}</strong>
          <small>{roleLabel(user.role)}</small>
        </span>
        <Button
          variant="icon"
          className="bg-transparent text-text-faint border-transparent"
          type="button"
          onClick={onLogout}
          title="ออกจากระบบ"
          aria-label="ออกจากระบบ"
        >
          <Icon name="logout" />
        </Button>
      </div>
    </aside>
  );
}
