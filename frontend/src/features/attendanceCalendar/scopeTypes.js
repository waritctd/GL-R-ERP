// Thai display metadata for hr.work_schedule_assignment's scopeType — mirrors
// backend/.../attendance/schedule/ScopeType.java exactly, including its own documented invariant: "declaration
// order here is that precedence".
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
