// Owner/keyer split (2026-11-05): `createdById` is the deal's OWNER, `keyedById` the person who typed it
// in (the actor on every new deal; null only on pre-V202 deals). Both pass every owner gate; commission/notifications stay owner-only.
// Mirrors the backend's DealOwnership.isOwnerOrKeyer.
export function isOwnerOrKeyer(deal, userId) {
  if (deal == null || userId == null) return false;
  return (deal.createdById != null && Number(deal.createdById) === Number(userId))
    || (deal.keyedById != null && Number(deal.keyedById) === Number(userId));
}
