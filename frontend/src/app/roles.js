export const ACCESS_ROLES = [
  ['employee', 'EMPLOYEE · พนักงาน'],
  ['hr', 'HR · ฝ่ายบุคคล'],
];

const divisionRoleRules = [
  { keys: ['17', 'hr', 'ฝ่ายบุคคล'], role: 'hr' },
  { keys: ['2', 'md', 'ผู้บริหารระดับสูง'], role: 'hr' },
  { keys: ['16', 'mn', 'ผู้บริหาร'], role: 'hr' },
];

function canonical(value = '') {
  return value.toString().trim().toLowerCase().replace(/[.\s_-]+/g, ' ').replace(/[^\p{L}\p{N}& ]/gu, '');
}

export function roleForDivision(divisionId, divisionName) {
  const haystack = `${canonical(divisionId)} ${canonical(divisionName)}`;
  const rule = divisionRoleRules.find((item) => item.keys.some((key) => haystack.includes(canonical(key))));
  return rule?.role || 'employee';
}
