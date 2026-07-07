// Central query-key factory for the shared server-state layer. Keeping keys in
// one place makes invalidation call sites unambiguous and typo-proof.
export const queryKeys = {
  currentEmployee: (id) => ['currentEmployee', id],
  employees: () => ['employees'],
  profileRequests: () => ['profileRequests'],
  dashboardSummary: () => ['dashboardSummary'],
};
