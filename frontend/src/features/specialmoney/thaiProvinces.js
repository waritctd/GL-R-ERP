// Domestic per-diem province picker for TRAVEL_PER_DIEM / TRAVEL_LODGING.
//
// The backend matches `detail.province` against `hr.special_money_excluded_province`
// by EXACT string equality (SpecialMoneyPolicyEvaluator.evaluateTravelPerDiem), so a
// free-text box would silently break the exclusion rule on any typo/spacing
// difference — this list exists so the dropdown value always matches what the
// server compares against. THAI_PROVINCES is the standard 77-province list (76
// provinces + Bangkok); EXCLUDED_PROVINCES is copied verbatim from the seed in
// V66__special_money_request_schema.sql (backend/src/main/resources/db/migration/).
//
// Note: the seed's excluded list includes "ลาดกระบัง" (a Bangkok district, not a
// province) alongside the six real Greater-Bangkok provinces it treats as "local
// commuting" — that is how the migration was authored, not a typo introduced here,
// so it is kept as its own selectable entry to match the server's exact-string check.
export const THAI_PROVINCES = [
  'กรุงเทพมหานคร',
  'กระบี่',
  'กาญจนบุรี',
  'กาฬสินธุ์',
  'กำแพงเพชร',
  'ขอนแก่น',
  'จันทบุรี',
  'ฉะเชิงเทรา',
  'ชลบุรี',
  'ชัยนาท',
  'ชัยภูมิ',
  'ชุมพร',
  'เชียงราย',
  'เชียงใหม่',
  'ตรัง',
  'ตราด',
  'ตาก',
  'นครนายก',
  'นครปฐม',
  'นครพนม',
  'นครราชสีมา',
  'นครศรีธรรมราช',
  'นครสวรรค์',
  'นนทบุรี',
  'นราธิวาส',
  'น่าน',
  'บึงกาฬ',
  'บุรีรัมย์',
  'ปทุมธานี',
  'ประจวบคีรีขันธ์',
  'ปราจีนบุรี',
  'ปัตตานี',
  'พระนครศรีอยุธยา',
  'พะเยา',
  'พังงา',
  'พัทลุง',
  'พิจิตร',
  'พิษณุโลก',
  'เพชรบุรี',
  'เพชรบูรณ์',
  'แพร่',
  'ภูเก็ต',
  'มหาสารคาม',
  'มุกดาหาร',
  'แม่ฮ่องสอน',
  'ยโสธร',
  'ยะลา',
  'ร้อยเอ็ด',
  'ระนอง',
  'ระยอง',
  'ราชบุรี',
  'ลพบุรี',
  'ลาดกระบัง',
  'ลำปาง',
  'ลำพูน',
  'เลย',
  'ศรีสะเกษ',
  'สกลนคร',
  'สงขลา',
  'สตูล',
  'สมุทรปราการ',
  'สมุทรสงคราม',
  'สมุทรสาคร',
  'สระแก้ว',
  'สระบุรี',
  'สิงห์บุรี',
  'สุโขทัย',
  'สุพรรณบุรี',
  'สุราษฎร์ธานี',
  'สุรินทร์',
  'หนองคาย',
  'หนองบัวลำภู',
  'อ่างทอง',
  'อำนาจเจริญ',
  'อุดรธานี',
  'อุตรดิตถ์',
  'อุทัยธานี',
  'อุบลราชธานี',
].sort((a, b) => a.localeCompare(b, 'th'));

// Copied verbatim from V66's INSERT INTO hr.special_money_excluded_province.
export const EXCLUDED_PROVINCES = new Set([
  'สมุทรปราการ',
  'สมุทรสาคร',
  'นนทบุรี',
  'นครปฐม',
  'ปทุมธานี',
  'ฉะเชิงเทรา',
  'ลาดกระบัง',
]);

export function isExcludedProvince(province) {
  return EXCLUDED_PROVINCES.has(province);
}
