import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { FilterField } from '../../components/common/Layout.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';

// Leave-surface IA restructure (2026-08-10). This markup previously existed as two verbatim
// copies -- MyLeaveTab.jsx and TeamLeaveTab.jsx each carried its own FILTER_BAR_CLASS constant,
// its own `items-end` rationale comment, and its own five status <option>s. The restructure gives
// the review tab a filter too, so the choice was a third copy or one component; see
// .design/leave-surface-ia/INFORMATION_ARCHITECTURE.md (O2).
//
// FilterBar (Layout.jsx) renders a <div>; this row needs native submit semantics
// (Enter-to-submit on the search button), so its exact utility string is reproduced
// here rather than wrapping a <form> inside a non-form primitive.
//
// `items-end`, not `items-center`. Every field in this row is a labelled stack (label text above a
// control), except the trailing "ค้นหา" Button, which has no label above it. Centring the row
// centres each LABELLED STACK, not its control, so the unlabelled Button -- the shortest
// single-height item -- landed visibly above the bottom edge of the date/status controls beside it
// (measured button top=740 vs input top=751 at 1440px, an 11px stagger). Same defect class
// as #530 (TaxAllowanceReviewPage's filter row).
const FILTER_BAR_CLASS = 'flex flex-wrap gap-[10px] items-end bg-surface border border-border rounded-md p-[14px]';

// ⚠️ Fields are `FilterField` (Layout.jsx, added by #638) -- NEVER a hand-rolled
// `<label>ชื่อ<control/></label>`. That bare-label pattern IS the bug #638 fixed: a `<label>` is a
// flex item at `min-width: auto`, so it cannot shrink below its control's min-content width, and
// for `input[type="date"]` that is the browser's NATIVE control width -- narrow in Chromium, much
// wider on iOS. It fits on a dev machine and overflows the row on a real iPhone (measured on
// /attendance at 390px with the native width modelled at 260px: 27px past the bar, 11px page
// overflow; zero with FilterField's `min-w-0`).
//
// FilterField also deliberately has NO `grow`, and a fixed `basis-[190px]` + `mobile:basis-full` --
// that is what keeps every field the same width with labels on one left edge. Do not add `grow`
// (a lone wrapped field then absorbs its whole line) and re-measure 390/768/1440 if the basis
// changes. Pass `htmlFor` for any field holding more than one control, or the label association
// becomes ambiguous and `getByLabelText` breaks.
//
// #638 converted the two INLINE copies of this bar (MyLeaveTab, TeamLeaveTab) separately. Those
// copies are gone: this component is the single one, so the conversion lives here once.

// Mirrors LeaveStatus (backend enum) -- the five states LeaveService#parseStatus accepts.
export const LEAVE_STATUS_OPTIONS = [
  { value: '', label: 'ทุกสถานะ' },
  { value: 'SUBMITTED', label: 'รออนุมัติ' },
  { value: 'APPROVED', label: 'อนุมัติแล้ว' },
  { value: 'REJECTED', label: 'ปฏิเสธแล้ว' },
  { value: 'CANCELLED', label: 'ยกเลิกแล้ว' },
  { value: 'AUTO_REJECTED', label: 'โควตาไม่พอ' },
];

/**
 * The shared date/status filter for the leave surface's three request tabs.
 *
 * `values.from`/`values.to` are expected to start EMPTY (2026-08-10 restructure). An empty string
 * is not "no filter applied by accident" -- it is the deliberate default, and it reaches the
 * backend as an ABSENT parameter because hrApi.js's `withQuery` drops `''` (hrApi.js:7).
 * LeaveService#list then applies its own +/-12-month window. So "show me recent leave" needs no
 * date typed in, which is the whole point: the employee sees their history on arrival.
 *
 * `children` renders after the status select and before the submit button -- TeamLeaveTab uses it
 * for its employee ("ทุกคน") select. Kept as a slot rather than an `employeeOptions` prop so this
 * component stays about the three fields all three tabs share.
 */
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
        <input
          type="date"
          value={values.from}
          onChange={(event) => onChange('from', event.target.value)}
        />
      </FilterField>
      <FilterField label="ถึงวันที่">
        <input
          type="date"
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
