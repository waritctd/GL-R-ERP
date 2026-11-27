import { Link } from 'react-router-dom';

/** An IR number: a link to the deal page for a viewer who may open it (import and readers), plain text for everyone else. */
export function IrNo({ row, open }) {
  return open
    ? <Link to={`/import/deals/${row.ticketId}`} className="text-info underline"><code>{row.docNumber}</code></Link>
    : <code>{row.docNumber}</code>;
}
