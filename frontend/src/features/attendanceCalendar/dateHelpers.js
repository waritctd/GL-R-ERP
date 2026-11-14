// `new Date().toISOString().slice(0, 10)` is the existing "today as an ISO date string" idiom in this codebase
// (CommissionPage.jsx, DealHistoryPanel.jsx, PayrollPage.jsx) — reused here rather than inventing a second
// one.
export function todayIso() {
  return new Date().toISOString().slice(0, 10);
}
