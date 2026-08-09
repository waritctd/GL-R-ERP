import { Icon } from './Icon.jsx';
import { Link } from 'react-router-dom';

export function Breadcrumbs({ items }) {
  if (!items || items.length === 0) return null;

  return (
    <nav className="mb-2 mobile:min-w-0 mobile:[overflow-wrap:anywhere]" aria-label="breadcrumb">
      <ol className="flex items-center flex-wrap gap-1 list-none m-0 p-0">
        {items.map((item, index) => {
          const isLast = index === items.length - 1;
          return (
            <li
              key={item.label + index}
              className="flex items-center gap-1 text-[length:var(--text-xs)] min-w-0 mobile:[overflow-wrap:anywhere]"
            >
              {item.to && !isLast ? (
                <Link
                  to={item.to}
                  className="border-0 bg-transparent p-0 text-primary text-[length:var(--text-xs)] font-semibold cursor-pointer [overflow-wrap:anywhere] no-underline hover:underline mobile:min-w-0"
                >
                  {item.label}
                </Link>
              ) : typeof item.onClick === 'function' && !isLast ? (
                <button
                  type="button"
                  onClick={item.onClick}
                  className="border-0 bg-transparent p-0 text-primary text-[length:var(--text-xs)] font-semibold cursor-pointer [overflow-wrap:anywhere] no-underline hover:underline mobile:min-w-0"
                >
                  {item.label}
                </button>
              ) : (
                <span
                  aria-current={isLast ? 'page' : undefined}
                  className={`${isLast ? 'text-text-muted font-semibold ' : ''}mobile:min-w-0 mobile:[overflow-wrap:anywhere]`}
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
