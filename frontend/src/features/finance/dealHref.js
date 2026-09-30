// Where a deal opens for a given viewer. Account is refused on the /tickets deal routes (H1
// lockdown) and reads a deal through the finance view instead; every other role keeps the ticket
// page. Presentation only — the server is the authority on who may read what.
export function dealHref(role, id) {
  return role === 'account' ? `/finance/deals/${id}` : `/tickets/${id}`;
}
