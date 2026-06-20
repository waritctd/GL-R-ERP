export const divisions = [
  { id: '10', code: 'AC', th: 'AC-ฝ่ายบัญชี', en: 'Accounting' },
  { id: '7', code: 'AM', th: 'AM-ฝ่ายธุรการ', en: 'Administration' },
  { id: '17', code: 'HR', th: 'HR-ฝ่ายบุคคล', en: 'Human Resources' },
  { id: '2', code: 'MD', th: 'MD-ผู้บริหารระดับสูง', en: 'Managing Director' },
  { id: '16', code: 'MN', th: 'MN-ผู้บริหาร', en: 'Management' },
  { id: '14', code: 'PC', th: 'PC-ฝ่ายจัดซื้อ', en: 'Procurement' },
  { id: '5', code: 'PCIM', th: 'PCIM-จัดซื้อต่างประเทศ', en: 'Overseas Procurement' },
  { id: '11', code: 'PD', th: 'PD-ฝ่ายผลิต', en: 'Production' },
  { id: '18', code: 'QC&ISO', th: 'QC&ISO', en: 'Quality & ISO' },
  { id: '9', code: 'SA', th: 'SA-ฝ่ายขาย', en: 'Sales' },
  { id: '15', code: 'SADS', th: 'SADS-ออกแบบ', en: 'Design' },
  { id: '13', code: 'Sales Support 2', th: 'Sales Support  2', en: 'Sales Support 2' },
  { id: '12', code: 'Sales Support 1', th: 'Sales Support 1', en: 'Sales Support 1' },
  { id: '6', code: 'Sales Support 1', th: 'Sales Support 1', en: 'Sales Support 1' },
  { id: '8', code: 'Sales Support 2', th: 'Sales Support 2', en: 'Sales Support 2' },
  { id: '4', code: 'SR', th: 'SR-โชว์รูม', en: 'Showroom' },
  { id: '1', code: 'SV', th: 'SV-ฝ่ายบริการ', en: 'Service' },
  { id: '3', code: 'WH', th: 'WH-ฝ่ายคลังสินค้า', en: 'Warehouse' },
];

export const statuses = [
  { id: 'ACT', th: 'ทำงานปกติ', en: 'Active', tone: 'success' },
  { id: 'PRB', th: 'ทดลองงาน', en: 'Probation', tone: 'warning' },
  { id: 'RSG', th: 'ลาออก', en: 'Resigned', tone: 'danger' },
];

function normalizeDivision(value = '') {
  return value.toString().trim().toLowerCase().replace(/\s+/g, ' ');
}

function divisionKeys(division) {
  return [division.id, division.code, division.th].filter(Boolean).map(normalizeDivision);
}

export function findDivision(divisionId, divisionName) {
  const keys = [divisionId, divisionName].filter(Boolean).map(normalizeDivision);
  return divisions.find((division) => {
    const knownKeys = divisionKeys(division);
    return keys.some((key) => knownKeys.includes(key));
  });
}
