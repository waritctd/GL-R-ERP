import { Link } from 'react-router-dom';
import { canAccessPath } from '../../app/permissions.js';
import { cn } from '../../utils/cn.js';

/**
 * การขนส่ง is one menu item for everyone. A person who may open both screens (import's rounds and the sales team's list)
 * switches between them here; everyone else sees no switch. Nothing either screen offers is lost.
 */
export function ShippingSwitch({ user, current }) {
  if (!canAccessPath('/fulfilment', user) || !canAccessPath('/my-orders', user)) return null;
  const tabs = [{ to: '/fulfilment', key: 'rounds', label: 'รอบขนส่ง' }, { to: '/my-orders', key: 'orders', label: 'รายการของทีมขาย' }];
  return (
    <nav className="flex gap-2" aria-label="มุมมองการขนส่ง" data-testid="shipping-switch">
      {tabs.map((t) => (
        <Link key={t.key} to={t.to} aria-current={current === t.key ? 'page' : undefined} data-testid={`shipping-switch-${t.key}`}
          className={cn('inline-flex min-h-11 items-center rounded-full border px-4 text-sm font-bold no-underline',
            current === t.key ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface text-text hover:bg-surface-hover')}>
          {t.label}
        </Link>
      ))}
    </nav>
  );
}
