import { Icon } from './Icon.jsx';
import { Link } from 'react-router-dom';

// Tailwind port of `.breadcrumbs` (styles.css, retired).
//
// The `mobile:` overrides are the ported ≤720px rule: Thai has no inter-word
// spaces, so a long breadcrumb label is a single unbreakable run that stretched
// the page container past the viewport (43px on the deposit notice). Letting
// the labels wrap is the fix, and it has to reach the <nav>, each <li>, the
// <li>'s own <span>, and the link/button — hence the same pair repeated at each
// level rather than one rule on the root.
const WRAP_ON_PHONE = 'mobile:min-w-0 mobile:[overflow-wrap:anywhere]';
const CRUMB_LINK = [
  'border-0 bg-transparent p-0 text-primary text-xs/[inherit] font-semibold cursor-pointer',
  'no-underline hover:underline [overflow-wrap:anywhere]',
  WRAP_ON_PHONE,
].join(' ');

export function Breadcrumbs({ items }) {
  if (!items || items.length === 0) return null;

  return (
    <nav className={`breadcrumbs mb-2 ${WRAP_ON_PHONE}`} aria-label="breadcrumb">
      <ol className="flex items-center flex-wrap gap-1 list-none m-0 p-0">
        {items.map((item, index) => {
          const isLast = index === items.length - 1;
          return (
            <li key={item.label + index} className={`flex items-center gap-1 text-xs/[inherit] min-w-0 ${WRAP_ON_PHONE}`}>
              {item.to && !isLast ? (
                <Link to={item.to} className={CRUMB_LINK}>
                  {item.label}
                </Link>
              ) : typeof item.onClick === 'function' && !isLast ? (
                <button type="button" onClick={item.onClick} className={CRUMB_LINK}>
                  {item.label}
                </button>
              ) : (
                <span
                  aria-current={isLast ? 'page' : undefined}
                  className={`${isLast ? 'text-text-muted font-semibold ' : ''}${WRAP_ON_PHONE}`}
                >
                  {item.label}
                </span>
              )}
              {!isLast ? <Icon name="chevronRight" size={12} aria-hidden="true" /> : null}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
