// Thai display metadata for hr.work_schedule_assignment's scopeType — mirrors
// backend/.../attendance/schedule/ScopeType.java exactly, including its own documented invariant:
// "declaration order here is that precedence" (EMPLOYEE wins over DEPARTMENT wins over DIVISION —
// see TieredWorkScheduleResolver#resolve). Kept in its own tiny tested module because the
// assignments tab renders this list in two places (the create form's scope-type <select> and the
// "ลำดับความสำคัญ" precedence explainer banner) and both must stay in the same order the resolver
// actually uses — a future edit that changes ScopeType.java's declaration order without a matching
// edit here would silently make this explainer lie about which assignment actually wins.
export const SCOPE_TYPES = [
  { value: 'EMPLOYEE', label: 'พนักงานรายคน', idLabel: 'รหัสพนักงาน', precedence: 1 },
  { value: 'DEPARTMENT', label: 'แผนก', idLabel: 'รหัสแผนก', precedence: 2 },
  { value: 'DIVISION', label: 'ฝ่าย', idLabel: 'รหัสฝ่าย', precedence: 3 },
];

export function scopeTypeLabel(scopeType) {
  return SCOPE_TYPES.find((entry) => entry.value === scopeType)?.label ?? scopeType ?? '-';
}

export function scopeIdLabel(scopeType) {
  return SCOPE_TYPES.find((entry) => entry.value === scopeType)?.idLabel ?? 'รหัสขอบเขต';
}
