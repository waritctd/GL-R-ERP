import { Link } from 'react-router-dom';

/**
 * An IR number. A sales job's opens the deal page, a งานนำเข้า's (no deal) its job page, for a viewer who may open that page
 * (`open` for the deal, `openJob` for the job); plain text for everyone else.
 */
export function IrNo({ row, open, openJob }) {
  const to = row.ticketId == null ? (openJob ? `/fulfilment/jobs/${row.importRequestId}` : null) : (open ? `/import/deals/${row.ticketId}` : null);
  return to
    ? <Link to={to} className="text-info underline"><code>{row.docNumber}</code></Link>
    : <code>{row.docNumber}</code>;
}
