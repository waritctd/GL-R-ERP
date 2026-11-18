// Owner/keyer split (2026-11-05): `createdById` is the deal's OWNER, `keyedById` the person who typed it
// in (the actor on every new deal; null only on pre-V202 deals). Both pass every owner gate; commission/notifications stay owner-only.
// Mirrors the backend's DealOwnership.isOwnerOrKeyer.
export function isOwnerOrKeyer(deal, userId) {
  if (deal == null || userId == null) return false;
  return (deal.createdById != null && Number(deal.createdById) === Number(userId))
    || (deal.keyedById != null && Number(deal.keyedById) === Number(userId));
}

// "Manage all sales documents" grant (Ploy 2026-11-18): a holder (AuthResponse.canManageAllSalesDocuments, a UI hint the
// backend re-reads live) acts as the owner of every deal, in addition to their own role. Commission, notifications and
// dashboards stay owner-only, so they keep using isOwnerOrKeyer.
/** The grant only counts for the sales and sales_manager roles (Ploy 2026-11-19); any other role's flag gives nothing extra. */
export function holdsAllSalesDocumentsGrant(user) {
  return user?.canManageAllSalesDocuments === true && (user.role === 'sales' || user.role === 'sales_manager');
}

export function actsAsOwner(deal, user) {
  return holdsAllSalesDocumentsGrant(user) || isOwnerOrKeyer(deal, user?.id);
}

/** The sales-owner gate every sales-side control repeats: a sales rep who owns/keyed the deal, or a grant holder. */
export function isSalesOwner(deal, user) {
  return (user?.role === 'sales' || holdsAllSalesDocumentsGrant(user)) && actsAsOwner(deal, user);
}
