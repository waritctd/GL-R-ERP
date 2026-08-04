import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { CollapsibleSection } from '../../components/common/CollapsibleSection.jsx';
import { FieldList } from '../../components/common/FieldList.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { StatePanel } from '../../components/common/StatePanel.jsx';
import { downloadBlob } from '../../utils/download.js';
import { formatDays } from './leaveFormatting.js';
import {
  EVERYDAY_LEAVE_TYPE_CODES, LEAVE_POLICY_SECTIONS, LEAVE_RULE_FIELD_LABELS, NAMED_PERSONAL_PURPOSES,
} from './leavePolicySections.js';

// Leave-surface IA rebuild, Phase A3: fills the "กฎการลา" tab's TODO(A3) placeholder
// (LeaveSurfacePage.jsx / leaveSurfaceTabs.js) with real per-leave-type rule disclosure. The owner's
// stated priority ("some leave types are requested often, some are not... the rules should be
// restated to them so they won't have cognitive overload") drives the everyday/rare split below —
// see EVERYDAY_LEAVE_TYPE_CODES's own comment for why it is a hand-kept duplicate of
// MyLeaveTab.jsx's identically-named constant rather than an import.

// Part 3 (owner's explicit, repeated request): the title+tabs bar this tab
// sits under (LeaveSurfacePage.jsx) is no longer sticky, so the 132px/168px
// scroll-margin this used to carry — sized to clear that sticky bar's
// measured height — no longer has anything to clear. `.content-scroll`'s own
// `scroll-padding-top` (AppShell.jsx) already gives every scroll target in
// this app a baseline top clearance (16px at ≤720px; a larger value on wider
// viewports, shared with the ticket workspace's measured chrome), so no
// per-section override is needed here any more. CollapsibleSection itself
// has no `id` on its outer element (its `id` prop only feeds
// `aria-controls`/`aria-labelledby`), so the anchor still lives on this
// wrapper — only the scroll-margin was dropped.
const SECTION_ANCHOR_CLASS = 'outline-none';

const DAY_COUNT_BASIS_TEXT = {
  CALENDAR_DAYS: 'นับเป็นวันปฏิทิน (รวมวันหยุด)',
};

/**
 * Formats one LeaveTypeDto rule field's live value into the `<dd>` half of a FieldList row, or
 * returns `null` to suppress the row entirely — a field with no meaningful value for THIS type
 * (zero/false/null) is noise, not information, so it is simply not rendered rather than shown as
 * "0 วัน"/"ไม่มี". See leavePolicySections.js's LEAVE_RULE_FIELD_LABELS for the paired `<dt>` label
 * and its comment on matching MyLeaveTab.jsx's vocabulary exactly for the three overlapping fields
 * (minServiceMonths, oncePerEmployment, paidDaysCap).
 */
function formatRuleFieldValue(field, leaveType) {
  const value = leaveType[field];
  switch (field) {
    case 'annualQuotaDays':
      // MILITARY's 366 is a deliberate sentinel ("no annual ceiling — the paid cap below is the
      // real rule"), not a policy number — never render it. See LeaveTypeDto's own Javadoc and
      // mockApi.js's db.leaveTypes comment; MyLeaveTab.jsx suppresses this same value the same way
      // in three other places (PrimaryLeaveBalanceCard's headline, rareBalanceSummary, and the
      // totals.remainingDays sum) — this is the fourth.
      if (leaveType.code === 'MILITARY' || !value) return null;
      return `${formatDays(value)} ต่อปี`;
    case 'requiresAttachment':
      return value ? 'ต้องแนบ' : null;
    case 'paidDaysCap':
      return value != null ? formatDays(value) : null;
    case 'advanceNoticeDays':
      return Number(value) > 0 ? `${value} วัน` : null;
    case 'minServiceMonths':
      return Number(value) > 0 ? `${value} เดือน` : null;
    case 'maxConsecutiveDays':
      return value != null ? formatDays(value) : null;
    case 'oncePerEmployment':
      return value ? 'ใช้ได้ครั้งเดียวตลอดการทำงาน' : null;
    case 'dayCountBasis':
      return DAY_COUNT_BASIS_TEXT[value] ?? null;
    case 'proratedFirstYear':
      return value ? 'คำนวณตามสัดส่วนอายุงาน' : null;
    case 'firstYearMaxDays':
      return value != null ? formatDays(value) : null;
    case 'certificateFilingWindowDays':
      return value != null ? `${value} วันทำการ` : null;
    case 'noCertificateMonthlyTolerance':
      return Number(value) > 0 ? `ไม่เกิน ${value} ครั้ง/เดือน` : null;
    case 'emergencyMonthlyAllowance':
      return value != null ? `ไม่เกิน ${value} ครั้ง/เดือน` : null;
    case 'carriesForward':
      return value ? 'ยกยอดไปปีถัดไปได้' : null;
    default:
      return null;
  }
}

// A section's `fields` are only ever meaningful against a SINGLE leave type — 5.3.2/5.3.3/5.3.4
// (department coverage, contiguous-leave, post-resignation) are relational/type-agnostic rules with
// no backing hr.leave_type column at all (see leavePolicySections.js's header comment), so they
// declare `fields: []` and render prose only.
function SectionRuleFields({ section, leaveTypesByCode }) {
  if (section.types.length !== 1 || section.fields.length === 0) return null;
  const leaveType = leaveTypesByCode.get(section.types[0]);
  if (!leaveType) return null;
  const rows = section.fields
    .map((field) => ({ field, value: formatRuleFieldValue(field, leaveType) }))
    .filter((row) => row.value != null);
  if (rows.length === 0) return null;
  return (
    <FieldList>
      {rows.map((row) => (
        <div key={row.field}>
          <dt>{LEAVE_RULE_FIELD_LABELS[row.field]}</dt>
          <dd>{row.value}</dd>
        </div>
      ))}
    </FieldList>
  );
}

function PurposeList() {
  return (
    <ul className="mt-3 list-disc pl-5 text-sm text-text">
      {NAMED_PERSONAL_PURPOSES.map((option) => (
        <li key={option.value}>{option.label}</li>
      ))}
    </ul>
  );
}

function PolicyDocumentLink({ availableQuery }) {
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState(null);

  async function handleDownload() {
    setDownloading(true);
    setError(null);
    try {
      const blob = await api.leave.downloadPolicyDocument();
      downloadBlob(blob, 'glr-leave-policy-announcement', 'pdf');
    } catch (err) {
      setError(err.message || 'ดาวน์โหลดไม่สำเร็จ');
    } finally {
      setDownloading(false);
    }
  }

  if (availableQuery.isPending) {
    return (
      <StatePanel state="loading" compact title="กำลังตรวจสอบเอกสารประกาศ" />
    );
  }

  // Both "the server told us it isn't there" (isSuccess && data === false) and "we couldn't even
  // ask" (isError — e.g. offline) render the same disabled/explained state: a user with no working
  // connection to the endpoint has no more use for a live download link than one where the document
  // genuinely was never uploaded, and neither case should show a control that then breaks on click.
  const available = availableQuery.isSuccess && availableQuery.data === true;

  if (!available) {
    return (
      <StatePanel
        compact
        state="unavailable"
        icon="fileText"
        title="ยังไม่มีไฟล์ประกาศฉบับเต็ม"
        description="ฝ่ายบุคคลยังไม่ได้อัปโหลดไฟล์ประกาศ §5 ฉบับเต็ม (PDF) กรุณาติดต่อฝ่ายบุคคลหากต้องการเอกสารต้นฉบับ"
      />
    );
  }

  // Genuinely available -- a plain actionable row, not StatePanel (that component's `state` prop
  // has no literal for "here is a real, working link"; every one of its states is a degraded/empty/
  // loading condition, and reusing "unavailable" just for its bordered-box styling would say the
  // opposite of what this branch means).
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 rounded-md border border-border bg-surface px-4 py-3">
      <span className="inline-flex items-center gap-2 text-sm text-text">
        <Icon name="fileText" size={16} className="text-icon-muted" />
        ประกาศ §5 ฉบับเต็ม (PDF) — เอกสารต้นฉบับที่ใช้ตั้งกฎการลาทั้งหมดนี้
      </span>
      <Button type="button" variant="secondary" onClick={handleDownload} disabled={downloading}>
        <Icon name="fileText" size={14} />
        {downloading ? 'กำลังดาวน์โหลด…' : 'ดาวน์โหลด'}
      </Button>
      {error ? <span className="w-full text-sm text-danger">{error}</span> : null}
    </div>
  );
}

export function RulesTab() {
  const leaveTypesQuery = useQuery({
    queryKey: queryKeys.leaveTypes(),
    queryFn: () => api.leave.types().then((response) => response.leaveTypes || []),
  });
  const leaveTypes = useMemo(() => leaveTypesQuery.data ?? [], [leaveTypesQuery.data]);
  const leaveTypesByCode = useMemo(
    () => new Map(leaveTypes.map((type) => [type.code, type])),
    [leaveTypes],
  );

  // Only sections whose type(s) actually exist in what the backend returned render — this keeps
  // the tab honest under VITE_USE_MOCKS=true, where mockApi.js's db.leaveTypes seeds 6 of the real
  // backend's 7 hr.leave_type rows (LEAVE_WITHOUT_PAY is absent from the mock; see mockApi.js's own
  // leaveTypes comment) rather than rendering a section with an empty FieldList for a type that
  // was never loaded. A type-agnostic section (empty `types`, e.g. 5.3.2) always renders.
  const visibleSections = useMemo(
    () => LEAVE_POLICY_SECTIONS.filter(
      (section) => section.types.length === 0 || section.types.some((code) => leaveTypesByCode.has(code)),
    ),
    [leaveTypesByCode],
  );

  const availableQuery = useQuery({
    queryKey: queryKeys.leavePolicyDocumentAvailable(),
    queryFn: () => api.leave.policyDocumentAvailable(),
  });

  if (leaveTypesQuery.isPending) {
    return <StatePanel state="loading" />;
  }

  if (leaveTypesQuery.isError) {
    return (
      <StatePanel
        state="error"
        description={leaveTypesQuery.error?.message}
        action={<Button type="button" variant="secondary" onClick={() => leaveTypesQuery.refetch()}>ลองใหม่</Button>}
      />
    );
  }

  if (leaveTypes.length === 0) {
    return <StatePanel state="empty" title="ยังไม่มีข้อมูลประเภทการลา" />;
  }

  return (
    <>
      <PolicyDocumentLink availableQuery={availableQuery} />

      <div className="grid gap-4">
        {visibleSections.map((section) => {
          // "SICK/PERSONAL/VACATION render expanded" (the owner's everyday priority) applies to
          // every clause grouped under those types, including the §5.3.x subsections — all of
          // which sit under VACATION. A type-agnostic section (5.3.2) defaults open too: it is the
          // single rule most likely to block an everyday request, not a rare-type technicality.
          const defaultOpen = section.types.length === 0
            || section.types.some((code) => EVERYDAY_LEAVE_TYPE_CODES.has(code));
          return (
            <div key={section.id} id={section.id} tabIndex={-1} className={SECTION_ANCHOR_CLASS}>
              <CollapsibleSection
                title={section.title}
                defaultOpen={defaultOpen}
                headerRight={defaultOpen ? undefined : (
                  <span className="text-xs font-medium text-text-muted">พบไม่บ่อย</span>
                )}
              >
                <p className="m-0 text-sm leading-relaxed text-text-secondary">{section.body}</p>
                {section.id === '5.2' ? <PurposeList /> : null}
                <SectionRuleFields section={section} leaveTypesByCode={leaveTypesByCode} />
              </CollapsibleSection>
            </div>
          );
        })}
      </div>
    </>
  );
}
