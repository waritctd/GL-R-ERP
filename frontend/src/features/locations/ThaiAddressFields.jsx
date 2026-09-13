import { useId } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { FormField } from '../../components/common/FormField.jsx';
import { SearchableCombobox } from '../../components/common/SearchableCombobox.jsx';
import { Button } from '../../components/common/Button.jsx';

export function emptyThaiAddress() {
  return { addressLine: '', provinceCode: '', districtCode: '', subdistrictCode: '', postalCode: '' };
}
export function completeThaiAddress(value) {
  return Boolean(value.provinceCode && value.districtCode && value.subdistrictCode);
}

export function ThaiAddressFields({ value, onChange, disabled = false }) {
  const id = useId();
  const provinces = useQuery({ queryKey: ['locations', 'provinces'], queryFn: () => api.locations.provinces(), staleTime: Infinity });
  const districts = useQuery({ queryKey: ['locations', 'districts', value.provinceCode],
    queryFn: () => api.locations.districts(value.provinceCode), enabled: Boolean(value.provinceCode), staleTime: Infinity });
  const subdistricts = useQuery({ queryKey: ['locations', 'subdistricts', value.districtCode],
    queryFn: () => api.locations.subdistricts(value.districtCode), enabled: Boolean(value.districtCode), staleTime: Infinity });
  const districtLabel = !value.provinceCode ? 'เขต / อำเภอ' : value.provinceCode === '10' ? 'เขต' : 'อำเภอ';
  const subdistrictLabel = !value.provinceCode ? 'แขวง / ตำบล' : value.provinceCode === '10' ? 'แขวง' : 'ตำบล';
  const selected = subdistricts.data?.items.find((s) => s.code === value.subdistrictCode);
  const postcodes = selected?.postalCodes ?? [];
  const failed = [provinces, districts, subdistricts].filter((q) => q.isError);
  return <fieldset className="m-0 min-w-0 border-0 p-0" disabled={disabled}>
    <legend className="mb-2 text-sm font-bold text-text-secondary">ที่อยู่</legend>
    <div className="grid grid-cols-2 gap-3 mobile:grid-cols-1">
      <div className="col-span-full"><FormField label="เลขที่ / อาคาร / หมู่ / ซอย / ถนน" htmlFor={`${id}-line`}>
        <input id={`${id}-line`} maxLength={1500} value={value.addressLine} onChange={(e) => onChange({ addressLine: e.target.value })} />
      </FormField></div>
      <FormField label="จังหวัด" htmlFor={`${id}-province`} required>
        <SearchableCombobox id={`${id}-province`} label="จังหวัด" value={value.provinceCode} disabled={disabled}
          options={provinces.data?.items ?? []} loading={provinces.isPending}
          onChange={(provinceCode) => onChange({ provinceCode, districtCode: '', subdistrictCode: '', postalCode: '' })} />
      </FormField>
      <FormField label={districtLabel} htmlFor={`${id}-district`} required>
        <SearchableCombobox id={`${id}-district`} label={districtLabel} value={value.districtCode} disabled={disabled || !value.provinceCode}
          options={districts.data?.items ?? []} loading={districts.isPending} placeholder={value.provinceCode ? 'พิมพ์ค้นหา…' : 'เลือกจังหวัดก่อน'}
          onChange={(districtCode) => onChange({ districtCode, subdistrictCode: '', postalCode: '' })} />
      </FormField>
      <FormField label={subdistrictLabel} htmlFor={`${id}-subdistrict`} required>
        <SearchableCombobox id={`${id}-subdistrict`} label={subdistrictLabel} value={value.subdistrictCode} disabled={disabled || !value.districtCode}
          options={subdistricts.data?.items ?? []} loading={subdistricts.isPending} placeholder={value.districtCode ? 'พิมพ์ค้นหา…' : 'เลือกเขต / อำเภอก่อน'}
          onChange={(subdistrictCode) => {
            const codes = subdistricts.data?.items.find((s) => s.code === subdistrictCode)?.postalCodes ?? [];
            onChange({ subdistrictCode, postalCode: codes.length === 1 ? codes[0] : '' });
          }} />
      </FormField>
      <FormField label="รหัสไปรษณีย์" htmlFor={`${id}-postal`}>
        {postcodes.length > 1 ? <SearchableCombobox id={`${id}-postal`} label="รหัสไปรษณีย์" value={value.postalCode}
          options={postcodes.map((code) => ({ code, nameTh: code }))} disabled={disabled}
          onChange={(postalCode) => onChange({ postalCode })} />
          : <input id={`${id}-postal`} value={value.postalCode} readOnly placeholder="เลือกแขวง / ตำบลก่อน" />}
      </FormField>
    </div>
    {failed.length ? <div role="alert" className="mt-2 text-sm text-danger">โหลดข้อมูลที่อยู่ไม่สำเร็จ <Button variant="text" onClick={() => failed.forEach((q) => q.refetch())}>ลองอีกครั้ง</Button></div> : null}
  </fieldset>;
}
