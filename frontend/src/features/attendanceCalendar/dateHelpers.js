// `new Date().toISOString().slice(0, 10)` is the existing "today as an ISO date string" idiom in
// this codebase (CommissionPage.jsx, DealHistoryPanel.jsx, PayrollPage.jsx) — reused here rather
// than inventing a second one. Used as the `min` on the assignments create form's effectiveFrom
// date input (WorkScheduleAssignmentAdminService#create rejects a backdated effectiveFrom with a
// 400 — see that class's javadoc on why: the resolver is unversioned and a backdated row would
// silently rewrite past attendance results).
export function todayIso() {
  return new Date().toISOString().slice(0, 10);
}
