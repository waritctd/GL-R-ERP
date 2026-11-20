// AuthResponse carries `admin` as a sibling of `user`, not a field on it (UserPrincipal is session
// state with 306 test construction sites). Flatten it here so the rest of the app keeps reading one
// user object. This only decides whether the nav item renders — every admin endpoint re-checks the
// flag against the database.
//
// `canCreateQuotation` is the same shape, carried by the same record: the per-employee "can create
// quotations" capability. Also a hint only: DealQuotationService re-reads the grant live on every
// request.
//
// `canIssueBillingNote` is the same shape again: the per-employee "can issue billing notes"
// capability. Also a hint only: BillingNoteService.hasWriteGrant re-reads the grant live on every
// request.
//
// `canIssueTaxInvoice` is the same shape once more: the per-person invoice-issuer grant (any role). A hint only:
// DeliveryRequestService re-reads it live on every call.
export function userFromAuthResponse(response) {
  if (!response?.user) return null;
  return {
    ...response.user,
    admin: Boolean(response.admin),
    // Display identity only (developers who run the portal); `role` stays the permission role.
    operator: Boolean(response.operator),
    canCreateQuotation: Boolean(response.canCreateQuotation),
    canIssueBillingNote: Boolean(response.canIssueBillingNote),
    canIssueTaxInvoice: Boolean(response.canIssueTaxInvoice),
    canManageAllSalesDocuments: Boolean(response.canManageAllSalesDocuments),
    canViewAs: Boolean(response.canViewAs),
    canViewPeopleInsights: Boolean(response.canViewPeopleInsights),
    viewAs: response.viewAs ?? null,
  };
}
