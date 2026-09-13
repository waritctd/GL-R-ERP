const bare = (name) => (name ?? '').trim().replace(/^(จังหวัด|อำเภอ|เขต|ตำบล|แขวง)\s*/, '');
export function formatThaiAddress(value) {
  if (!value.provinceCode || !value.districtCode || !value.subdistrictCode) return value.address ?? '';
  const bangkok = value.provinceCode === '10';
  return [value.addressLine, `${bangkok ? 'แขวง' : 'ตำบล'}${bare(value.subdistrictNameTh)}`,
    `${bangkok ? 'เขต' : 'อำเภอ'}${bare(value.districtNameTh)}`,
    `${bangkok ? '' : 'จังหวัด'}${bare(value.provinceNameTh)}`, value.postalCode]
    .filter((part) => part?.trim()).map((part) => part.trim()).join(' ');
}
