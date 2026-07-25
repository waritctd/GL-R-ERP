import { Icon } from '../../components/common/Icon.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { dealLifecycleLabel, dealStageLabel, formatThaiDate } from '../../utils/format.js';
import { activePricingRequestsSummary } from '../pricingRequests/pricingRequestMeta.js';
import { DEAL_WORK_STATES } from './dealWorkState.js';
import { stageMeta } from './stageMeta.js';

const ROLE_LABEL_TH = {
  sales: 'ฝ่ายขาย',
  sales_manager: 'ผู้จัดการฝ่ายขาย',
  import: 'ฝ่ายนำเข้า',
  account: 'ฝ่ายบัญชี',
  ceo: 'CEO',
};

const WORK_STATE_TONE = {
  [DEAL_WORK_STATES.NEEDS_ACTION]: 'info',
  [DEAL_WORK_STATES.WAITING]: 'warning',
  [DEAL_WORK_STATES.BLOCKED]: 'warning',
  [DEAL_WORK_STATES.RETURNED]: 'danger',
  [DEAL_WORK_STATES.OVERDUE]: 'danger',
  [DEAL_WORK_STATES.DRAFT]: 'neutral',
  [DEAL_WORK_STATES.COMPLETE]: 'success',
  [DEAL_WORK_STATES.CANCELLED]: 'danger',
  [DEAL_WORK_STATES.INFORMATIONAL]: 'neutral',
};

const BLOCKER_LABEL_TH = {
  OVERDUE_PAYMENT: 'ยอดค้างชำระเกินกำหนด',
  ON_HOLD: 'พักดีลไว้',
  DORMANT: 'พักดีลระยะยาว',
  OWNER_CANCELLED: 'เจ้าของยกเลิกโครงการ',
  PROJECT_SUSPENDED: 'โครงการถูกระงับไม่มีกำหนด',
  BUDGET_CANCELLED: 'งบประมาณถูกยกเลิก',
  OTHER: 'อื่น ๆ',
};

const PRIMARY_ACTION_LABEL_TH = {
  CREATE_PRICING_REQUEST: 'สร้างใบขอราคา',
  SUBMIT_PRICING_REQUEST: 'ส่งใบขอราคา',
  RESPOND_PRICING_INFO: 'ตอบข้อมูลที่ถูกส่งกลับ',
  REVISE_COSTING: 'แก้ไขต้นทุน',
  PICKUP_PRICING_REQUEST: 'รับงานขอราคา',
  START_CEO_REVIEW: 'เริ่มตรวจราคาขาย',
  DECIDE_PRICING: 'ตัดสินใจราคาขาย',
  CREATE_CUSTOMER_QUOTATION: 'สร้างใบเสนอราคาลูกค้า',
  CONFIRM_ORDER: 'ยืนยันคำสั่งซื้อ',
};

function DetailItem({ label, children }) {
  return (
    <div className="min-w-0">
      <dt className="text-2xs font-bold uppercase text-text-muted">{label}</dt>
      <dd className="mt-0.5 break-words text-xs font-bold leading-snug text-text-secondary">
        {children || '-'}
      </dd>
    </div>
  );
}

function formatBlocker(blocker) {
  if (!blocker) return '-';
  return BLOCKER_LABEL_TH[blocker] ?? blocker;
}

function responsibilityInfo(workState) {
  if (workState?.state === DEAL_WORK_STATES.WAITING) {
    return {
      label: 'รอ',
      value: workState.responsibleRole
        ? ROLE_LABEL_TH[workState.responsibleRole] ?? workState.responsibleRole
        : 'ไม่มีฝ่ายภายในที่ระบุ',
    };
  }
  if (workState?.responsibleRole) {
    return {
      label: 'ผู้รับผิดชอบ',
      value: ROLE_LABEL_TH[workState.responsibleRole] ?? workState.responsibleRole,
    };
  }
  return { label: 'รอ', value: 'ไม่รอฝ่ายภายใน' };
}

function latestRequiredDate(pricingRequests = []) {
  const pricingSummary = activePricingRequestsSummary(pricingRequests);
  return pricingSummary?.requests
    ?.filter((request) => request.requiredDate)
    ?.sort((a, b) => new Date(b.requiredDate) - new Date(a.requiredDate))[0]?.requiredDate ?? null;
}

function deadlineInfo(summary, pricingRequests) {
  if (summary.overdue && summary.dueDate) {
    return { label: 'กำหนด', value: `เกินกำหนดชำระ ${formatThaiDate(summary.dueDate)}` };
  }
  if (summary.dueDate) return { label: 'กำหนด', value: `ชำระ ${formatThaiDate(summary.dueDate)}` };
  if (summary.nextFollowUpAt) return { label: 'ติดตาม', value: formatThaiDate(summary.nextFollowUpAt) };
  const requiredDate = latestRequiredDate(pricingRequests);
  if (requiredDate) return { label: 'กำหนด PCR', value: formatThaiDate(requiredDate) };
  if (summary.stale) return { label: 'ความสดใหม่', value: 'ควรบันทึกกิจกรรมล่าสุด' };
  if (summary.stageUpdatedAt) return { label: 'อัปเดตขั้น', value: formatThaiDate(summary.stageUpdatedAt) };
  if (summary.updatedAt) return { label: 'อัปเดต', value: formatThaiDate(summary.updatedAt) };
  return { label: 'ความสดใหม่', value: '-' };
}

function fallbackWorkState() {
  return {
    state: DEAL_WORK_STATES.INFORMATIONAL,
    labelTh: 'ติดตามสถานะดีล',
    explanationTh: 'ยังไม่มีงานเร่งด่วนจากข้อมูลที่โหลดมา',
    responsibleRole: null,
    blocker: null,
    primaryActionKey: null,
  };
}

function primaryActionLabel(workState) {
  if (!workState?.primaryActionKey) return null;
  return PRIMARY_ACTION_LABEL_TH[workState.primaryActionKey] ?? workState.labelTh;
}

/**
 * Persistent deal header: compact identity + current stage + presentation
 * work-state, kept above the workspace so tab changes do not hide the answer
 * to "where is this deal and whose move is it?".
 *
 * `workState` comes from dealWorkState.js. The optional `primaryAction` is a
 * button the parent already gates from server-offered actions; this component
 * only places it.
 */
export function DealStateHeader({
  summary, pricingRequests = [], primaryAction, workState, onRefresh,
}) {
  const lifecycle = dealLifecycleLabel(summary.lifecycle ?? 'ACTIVE');
  const stage = dealStageLabel(summary.salesStage);
  const meta = stageMeta(summary.salesStage);
  const resolved = workState ?? fallbackWorkState();
  const deadline = deadlineInfo(summary, pricingRequests);
  const workTone = WORK_STATE_TONE[resolved.state] ?? 'neutral';
  const actionLabel = primaryActionLabel(resolved);
  const terminal = [DEAL_WORK_STATES.COMPLETE, DEAL_WORK_STATES.CANCELLED].includes(resolved.state);
  const showLifecycleBadge = (summary.lifecycle ?? 'ACTIVE') !== 'ACTIVE';
  const responsibility = responsibilityInfo(resolved);

  return (
    <section
      className="sticky top-0 z-20 flex flex-col gap-3 border-y border-border bg-surface px-3 py-3 shadow-sm sm:rounded-lg sm:border sm:px-4"
      data-testid="deal-state-header"
      aria-label={`หัวดีล ${summary.code ?? ''}`}
    >
      <div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <code className="rounded bg-surface-subtle px-2 py-0.5 text-xs text-text-muted">{summary.code}</code>
            {showLifecycleBadge ? <StatusBadge tone={lifecycle.tone}>{lifecycle.label}</StatusBadge> : null}
            {terminal ? <StatusBadge tone={workTone}>{resolved.labelTh}</StatusBadge> : null}
          </div>
          <h1 className="mt-1 break-words text-lg font-extrabold leading-snug text-text sm:text-xl">
            {summary.customerName || summary.title}
          </h1>
          <p className="mt-1 break-words text-xs leading-snug text-text-muted">
            โครงการ <strong className="text-text-secondary">{summary.projectName || '-'}</strong>
          </p>
        </div>

        <div className="flex min-w-0 flex-col gap-2 lg:max-w-xl lg:items-end">
          <div className="flex flex-wrap items-center gap-x-3 gap-y-2 lg:justify-end">
            <span className="inline-flex items-center gap-2">
              <span className="text-2xs font-bold uppercase text-text-muted">ขั้นตอน</span>
              <StatusBadge tone={stage.tone}>
                {meta ? `${meta.no}. ` : ''}{stage.label}
              </StatusBadge>
            </span>
            <span className="inline-flex items-center gap-2">
              <span className="text-2xs font-bold uppercase text-text-muted">สถานะงาน</span>
              <StatusBadge tone={workTone}>{resolved.labelTh}</StatusBadge>
            </span>
            {onRefresh ? (
              <button type="button" className="icon-button" onClick={onRefresh} title="รีเฟรช" aria-label="รีเฟรช">
                <Icon name="refresh" />
              </button>
            ) : null}
          </div>
          <p className="break-words text-xs leading-snug text-text-muted lg:text-right">
            {resolved.explanationTh}
          </p>
          {(actionLabel || primaryAction) ? (
            <div className="flex flex-wrap items-center gap-2 lg:justify-end">
              {actionLabel ? (
                <span className="inline-flex min-w-0 items-center gap-1 text-xs font-bold text-info">
                  <Icon name="chevronRight" size={14} className="shrink-0" />
                  <span className="break-words">ถัดไป: {actionLabel}</span>
                </span>
              ) : null}
              {primaryAction ? <div className="shrink-0">{primaryAction}</div> : null}
            </div>
          ) : null}
        </div>
      </div>

      <dl className="grid grid-cols-2 gap-x-4 gap-y-2 border-t border-border-subtle pt-3 sm:grid-cols-4">
        <DetailItem label="เจ้าของฝ่ายขาย">{summary.createdByName || '-'}</DetailItem>
        <DetailItem label={responsibility.label}>{responsibility.value}</DetailItem>
        <DetailItem label="ตัวติดขัด">{formatBlocker(resolved.blocker)}</DetailItem>
        <DetailItem label={deadline.label}>{deadline.value}</DetailItem>
      </dl>
    </section>
  );
}
