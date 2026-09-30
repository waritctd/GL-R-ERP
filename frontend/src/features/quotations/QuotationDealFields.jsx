import { FormField } from '../../components/common/FormField.jsx';
import { DesignerPicker } from './DesignerPicker.jsx';

/**
 * Fields that identify the quotation's recipient-side department and designer.
 * Kept shared between standalone creation and an existing deal/draft so the two
 * entry paths cannot drift apart visually or in how they update quotation terms.
 */
export function QuotationDealFields({ terms, onChange, disabled = false, idPrefix = 'quotation', showToast }) {
  const setTerm = (key, value) => onChange({ [key]: value });

  return (
    <div className="col-span-full grid grid-cols-2 gap-3 mobile:grid-cols-1">
      <FormField label="ฝ่าย" htmlFor={`${idPrefix}-dept-code`}>
        <input
          id={`${idPrefix}-dept-code`}
          value={terms.deptCode}
          disabled={disabled}
          onChange={(e) => setTerm('deptCode', e.target.value)}
        />
      </FormField>
      <DesignerPicker
        value={terms.unitCode}
        disabled={disabled}
        onSelectCode={(code) => setTerm('unitCode', code)}
        idPrefix={`${idPrefix}-designer-picker`}
        label="หน่วยงาน / ผู้ออกแบบ"
        showToast={showToast}
      />
    </div>
  );
}
