import { EmptyState } from '../../components/common/EmptyState.jsx';
import { QuotaBar } from '../../components/common/QuotaBar.jsx';
import { MEDICAL_ANNUAL_CAP, ONCE_PER_LIFETIME_TYPES } from './specialMoneyRules.js';

function money(value) {
  return `฿${Number(value || 0).toLocaleString('th-TH')}`;
}

/**
 * EntitlementPanel — "entitlement before intent" (layer A of LeavePage's four-layer model, plan
 * §"Model: the leave page's four layers"): per-type remaining/used/consumed straight off GET
 * /usage, shown BEFORE the employee picks a type below. The capped money type (medical) gets the
 * shared QuotaBar; once-only / once-a-year types get a plain typography+divider row instead of a
 * second bordered box — the card-diet rule this whole redesign is following (LeavePage.jsx
 * :572-583): QuotaBar is the one sanctioned bordered primitive here, not a pattern to repeat per
 * type.
 *
 * `usage.activeCountThisYearByType` (added for this redesign — see
 * SpecialMoneyUsageDto/UsageSnapshot) is what makes the "เบิกชุดฟอร์มปีนี้ไปแล้ว" line possible at
 * all: the amount map alone can't distinguish "not claimed yet" from "claimed but still pending
 * CEO decision, so no APPROVED amount exists yet".
 */
export function EntitlementPanel({ usage, typeOptions, year, loading }) {
  if (loading) {
    return <EmptyState icon="badgeDollar" title="กำลังโหลดโควตา" />;
  }
  if (!usage) {
    return <EmptyState icon="badgeDollar" title="เลือกพนักงานเพื่อดูโควตาคงเหลือ" />;
  }

  const byType = (requestType) => typeOptions.find((item) => item.requestType === requestType);
  const medicalMeta = byType('MEDICAL');
  const uniformAnnualMeta = byType('UNIFORM_ANNUAL');
  const newStaffMeta = byType('UNIFORM_NEW_STAFF');

  const medicalUsed = Number(usage.approvedAmountThisYearByType?.MEDICAL || 0);
  const uniformClaimedThisYear = Number(usage.approvedAmountThisYearByType?.UNIFORM_ANNUAL || 0);
  const uniformClaimedFlag = Number(usage.activeCountThisYearByType?.UNIFORM_ANNUAL || 0) >= 1;
  const newStaffUsed = Number(usage.approvedCountLifetimeByType?.UNIFORM_NEW_STAFF || 0) >= 1;

  const lifetimeRows = ONCE_PER_LIFETIME_TYPES
    .map((requestType) => ({ requestType, meta: byType(requestType) }))
    .filter((row) => row.meta)
    .map((row) => ({
      ...row,
      used: Number(usage.approvedCountLifetimeByType?.[row.requestType] || 0) >= 1,
    }));

  const plainRows = [
    uniformAnnualMeta ? {
      key: 'UNIFORM_ANNUAL',
      label: uniformAnnualMeta.thaiLabel,
      value: uniformClaimedFlag ? `เบิกไปแล้ว ${money(uniformClaimedThisYear)} ในปีนี้` : 'ยังไม่ได้เบิกในปีนี้',
      helper: 'ปีละ 1 ครั้ง',
    } : null,
    ...lifetimeRows.map((row) => ({
      key: row.requestType,
      label: row.meta.thaiLabel,
      value: row.used ? 'ใช้สิทธิแล้ว' : 'ยังไม่ใช้สิทธิ',
      helper: 'ครั้งเดียวตลอดอายุงาน',
    })),
    newStaffMeta ? {
      key: 'UNIFORM_NEW_STAFF',
      label: newStaffMeta.thaiLabel,
      value: newStaffUsed ? 'ใช้สิทธิแล้ว' : 'ยังไม่ใช้สิทธิ',
      helper: 'เฉพาะปีแรกที่เข้าทำงาน',
    } : null,
  ].filter(Boolean);

  if (!medicalMeta && plainRows.length === 0) {
    return <EmptyState icon="badgeDollar" title="ไม่มีข้อมูลโควตาสำหรับพนักงานนี้" />;
  }

  return (
    <div className="grid grid-cols-1 gap-4 min-[1041px]:grid-cols-3">
      {medicalMeta ? (
        <QuotaBar
          label="ค่ารักษาพยาบาล"
          caption={`ค่ารักษาพยาบาล — ปี ${year}`}
          used={medicalUsed}
          cap={MEDICAL_ANNUAL_CAP}
          formatValue={money}
          overMessage="ใช้วงเงินค่ารักษาพยาบาลของปีนี้ครบแล้ว"
        />
      ) : null}

      {plainRows.length > 0 ? (
        <div className="min-w-0 min-[1041px]:col-span-2 min-[1041px]:border-l min-[1041px]:border-border min-[1041px]:pl-4">
          <div className="grid grid-cols-1 gap-3 min-[721px]:grid-cols-2">
            {plainRows.map((row) => (
              <div key={row.key} className="min-w-0">
                <div className="text-xs font-bold text-text-muted">{row.label}</div>
                <div className="mt-0.5 truncate text-sm font-bold tabular-nums text-text">{row.value}</div>
                <div className="mt-0.5 text-xs text-text-muted">{row.helper}</div>
              </div>
            ))}
          </div>
        </div>
      ) : null}
    </div>
  );
}
