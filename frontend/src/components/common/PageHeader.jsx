import { useId } from 'react';
import { Breadcrumbs } from './Breadcrumbs.jsx';

export function PageHeader({
  title,
  subtitle,
  actions,
  breadcrumbs,
  context,
  eyebrow,
  id,
}) {
  const generatedId = useId();
  const titleId = id || `page-title-${generatedId}`;

  return (
    <header className="page-heading" aria-labelledby={titleId}>
      <div className="page-heading-copy">
        {breadcrumbs ? <Breadcrumbs items={breadcrumbs} /> : null}
        {eyebrow ? <span className="page-heading-eyebrow">{eyebrow}</span> : null}
        <h1 id={titleId}>{title}</h1>
        {subtitle ? <p>{subtitle}</p> : null}
        {context ? <div className="page-heading-context">{context}</div> : null}
      </div>
      {actions ? <div className="page-actions">{actions}</div> : null}
    </header>
  );
}
