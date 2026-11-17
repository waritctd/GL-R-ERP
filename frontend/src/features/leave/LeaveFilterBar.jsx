import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { FilterField } from '../../components/common/Layout.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';
import { ThaiDateInput } from '../../components/common/ThaiDateInput.jsx';

// Leave-surface IA restructure (2026-08-10). This markup previously existed as two verbatim copies --
// MyLeaveTab.jsx and TeamLeaveTab.jsx each carried its own FILTER_BAR_CLASS constant, its own `items-end`
// rationale comment, and its own five status <option>s.
const FILTER_BAR_CLASS = 'flex flex-wrap gap-[10px] items-end bg-surface border border-border rounded-md p-[14px]';

// ⚠️ Fields are `FilterField` (Layout.jsx, added by #638) -- NEVER a hand-rolled
// `<label>ชื่อ<control/></label>`. Do not add `grow` (a lone wrapped field then absorbs its whole line) and
// re-measure 390/768/1440 if the basis changes.

// Mirrors LeaveStatus (backend enum) -- the five states LeaveService#parseStatus accepts.
const LEAVE_STATUS_OPTIONS = [
  { value: '', label: 'ทุกสถานะ' },
  { value: 'SUBMITTED', label: 'รออนุมัติ' },
  { value: 'APPROVED', label: 'อนุมัติแล้ว' },
  { value: 'REJECTED', label: 'ปฏิเสธแล้ว' },
  { value: 'CANCELLED', label: 'ยกเลิกแล้ว' },
  { value: 'AUTO_REJECTED', label: 'โควตาไม่พอ' },
];

/** The shared date/status filter for the leave surface's three request tabs. `values.from`/`values.to` are
 * expected to start EMPTY (2026-08-10 restructure). LeaveService#list then applies its own +/-12-month window. */
export function LeaveFilterBar({
  values,
  onChange,
  onSubmit,
  submitDisabled = false,
  refreshing = false,
  children,
}) {
  return (
    <SafeForm className={FILTER_BAR_CLASS} onSubmit={onSubmit}>
      <FilterField label="จากวันที่">
        <ThaiDateInput
          value={values.from}
          onChange={(event) => onChange('from', event.target.value)}
        />
      </FilterField>
      <FilterField label="ถึงวันที่">
        <ThaiDateInput
          value={values.to}
          onChange={(event) => onChange('to', event.target.value)}
        />
      </FilterField>
      <FilterField label="สถานะ">
        <select value={values.status} onChange={(event) => onChange('status', event.target.value)}>
          {LEAVE_STATUS_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>{option.label}</option>
          ))}
        </select>
      </FilterField>
      {children}
      <Button type="submit" disabled={submitDisabled}>
        <Icon name="search" />
        ค้นหา
      </Button>
      {refreshing ? (
        <span className="inline-flex items-center gap-1 text-xs text-text-muted" aria-live="polite">
          <Icon name="refresh" size={12} />
          กำลังอัปเดต…
        </span>
      ) : null}
    </SafeForm>
  );
}
