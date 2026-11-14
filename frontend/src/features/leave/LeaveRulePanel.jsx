import { Link } from 'react-router-dom';
import { Icon } from '../../components/common/Icon.jsx';
import { cn } from '../../utils/cn.js';

// Leave-request composer, Phase A2. The backend's own LeaveRuleMessages already composes both halves into one
// sentence (its own class Javadoc: "Closing clause mirrors the English original it replaces, site for site")
// -- this component never re-derives, re-splits, or re-words that sentence.
const RULE_META = {
  ONCE_PER_EMPLOYMENT: { label: 'สิทธิ์ครั้งเดียวตลอดการทำงาน', sectionRef: '5.6' },
  RESIGNATION_GATE: { label: 'ยื่นใบลาออกแล้ว', sectionRef: '5.3.4' },
  HIRE_DATE_MISSING_PRORATED: { label: 'ไม่มีข้อมูลวันเริ่มงาน', sectionRef: '5.2/3' },
  HIRE_DATE_MISSING_MIN_SERVICE: { label: 'ไม่มีข้อมูลวันเริ่มงาน', sectionRef: '5.3' },
  MIN_SERVICE_MONTHS: { label: 'อายุงานไม่ถึงเกณฑ์', sectionRef: '5.3' },
  PROBATION_HIRE_DATE_MISSING: { label: 'ไม่มีข้อมูลวันเริ่มงาน/ทดลองงาน', sectionRef: '5.2' },
  PROBATION_NOT_PASSED: { label: 'ยังไม่ผ่านทดลองงาน', sectionRef: '5.2' },
  FIRST_YEAR_MAX_DAYS: { label: 'เพดานวันลากิจปีแรก', sectionRef: '5.2' },
  WEDDING_MAX_DAYS: { label: 'เพดานวันลาพิธีสมรส', sectionRef: '5.2' },
  MAX_CONSECUTIVE_DAYS: { label: 'จำนวนวันลาติดต่อกันเกินกำหนด', sectionRef: '5.2' },
  CONTIGUOUS_LEAVE_PAIR: { label: 'ลาต่อเนื่องกับวันลาอื่น', sectionRef: '5.3.3' },
  SICK_CERTIFICATE_WINDOW: { label: 'ยื่นใบรับรองแพทย์ล่าช้า', sectionRef: '5.1' },
  SICK_CERTIFICATE_REQUIRED: { label: 'ต้องแนบใบรับรองแพทย์', sectionRef: '5.1' },
  SICK_NO_CERT_TOLERANCE_EXHAUSTED: { label: 'ใช้สิทธิ์ลาป่วยไม่มีใบรับรองแพทย์ครบแล้ว', sectionRef: '5.1' },
  EMERGENCY_TOLERANCE_EXHAUSTED: { label: 'ใช้สิทธิ์ลากิจฉุกเฉินครบแล้ว', sectionRef: '5.2' },
  ADVANCE_NOTICE: { label: 'แจ้งล่วงหน้าไม่ครบกำหนด', sectionRef: '5' },
  DEPARTMENT_COVERAGE: { label: 'กระทบความครอบคลุมของแผนก', sectionRef: '5.3.2' },
};

// tone -> style lookup (V164 follow-up, 2026-09-09): was a chain of `isBlocking ? danger : info` ternaries,
// which had no room for a third tone. Unknown tones fall back to 'blocking' (the pre-existing default) rather
// than rendering unstyled.
const TONE_STYLES = {
  // The ORIGINAL outcome: this request would be REJECTED outright (LeaveRuleEnforcement.BLOCK).
  blocking: {
    container: 'border-danger-border bg-surface',
    icon: 'triangleAlert',
    accent: 'text-danger',
  },
  // §5 WARN_UNPAID_* gates (V164): the request still SUBMITS, but the days it covers become unpaid if approved
  // -- see LeaveRuleEnforcement's class Javadoc.
  warning: {
    container: 'border-warning-border bg-warning-bg-soft',
    icon: 'triangleAlert',
    accent: 'text-warning-dark',
  },
  // A non-blocking heads-up shown ahead of time (e.g. an advisory), not a §5 rule verdict at all.
  info: {
    container: 'border-info-border bg-info-bg-alt',
    icon: 'info',
    accent: 'text-info',
  },
};

/** `tone`: 'blocking' (this outcome would stop submission -- danger styling), 'warning' (a §5 WARN_UNPAID_*
 * gate -- the request still submits, but carries an unpaid-days consequence -- amber styling), or 'info' (a
 * non-blocking heads-up, e.g. an advisory shown ahead of time). */
export function LeaveRulePanel({ outcome, tone = 'blocking', className }) {
  if (!outcome) return null;
  const meta = RULE_META[outcome.code] ?? { label: outcome.code, sectionRef: null };
  const style = TONE_STYLES[tone] ?? TONE_STYLES.blocking;

  return (
    <div className={cn('rounded-md border p-3', style.container, className)}>
      <div className="flex items-start gap-2.5">
        <Icon name={style.icon} size={16} className={cn('mt-0.5 shrink-0', style.accent)} />
        <div className="grid min-w-0 gap-1">
          <strong className={cn('text-sm font-bold', style.accent)}>
            {meta.label}
          </strong>
          <p className="m-0 text-sm text-text">{outcome.messageTh}</p>
          {meta.sectionRef ? (
            <Link to="/leave?tab=rules" className="w-fit text-xs font-semibold text-text-muted underline">
              อ้างอิงระเบียบ §{meta.sectionRef}
            </Link>
          ) : null}
        </div>
      </div>
    </div>
  );
}

/** §5 WARN_UNPAID_* gates (V164) can accumulate SEVERAL warnings on one request (unlike `blocking`, where the
 * rule chain stops at the first hit) -- owner ruling: show ALL of them, not just the first. Renders nothing
 * for an empty/missing list -- callers do not need their own guard. */
export function LeaveRuleWarningList({ warnings, className }) {
  if (!warnings || warnings.length === 0) return null;
  return (
    <div className={cn('grid gap-2', className)}>
      {warnings.map((warning, index) => (
        // `code` is not unique across a list (the same gate cannot fire twice, but this stays defensive against a
        // future duplicate) -- index is a safe tiebreaker for a list that only ever re-renders wholesale from a fresh
        // preview response, never reordered in place.
        <LeaveRulePanel key={`${warning.code}-${index}`} outcome={warning} tone="warning" />
      ))}
    </div>
  );
}
