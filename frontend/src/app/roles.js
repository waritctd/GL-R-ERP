export const ACCESS_ROLES = [
  ['employee', 'EMPLOYEE · พนักงาน'],
  ['hr', 'HR · ฝ่ายบุคคล'],
];

const divisionRoleRules = [
  { keys: ['17', 'hr', 'ฝ่ายบุคคล'], role: 'hr' },
  { keys: ['2', 'md', 'ผู้บริหารระดับสูง'], role: 'ceo' },
  { keys: ['16', 'mn', 'ผู้บริหาร'], role: 'hr' },
  { keys: ['9', 'sa', 'ฝ่ายขาย'], role: 'sales' },
  { keys: ['5', 'pcim', 'จัดซื้อต่างประเทศ'], role: 'import' },
];

const positionRoleRules = [
  { keys: ['ผู้ช่วยผู้จัดการฝ่ายขาย'], role: 'sales_manager' },
];

function canonical(value = '') {
  return value.toString().trim().toLowerCase().replace(/[.\s_-]+/g, ' ').replace(/[^\p{L}\p{N}& ]/gu, '');
}

export function roleForDivision(divisionId, divisionName, positionName = '') {
  const positionHaystack = canonical(positionName);
  const positionRule = positionRoleRules.find((item) => item.keys.some((key) => positionHaystack === canonical(key)));
  if (positionRule) return positionRule.role;

  const haystack = `${canonical(divisionId)} ${canonical(divisionName)}`;
  const rule = divisionRoleRules.find((item) => item.keys.some((key) => haystack.includes(canonical(key))));
  return rule?.role || 'employee';
}
