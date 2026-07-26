import { Icon } from '../../../components/common/Icon.jsx';
import {
  dealLifecycleLabel,
  dealStageLabel,
  depositPolicyLabel,
  formatMoney,
  formatThaiDate,
  paymentStageLabel,
  pricingRequestStatusLabel,
  quotationStatusLabel,
} from '../../../utils/format.js';
import { activePricingRequestsSummary, pricingRequestRecipientLabel } from '../../pricingRequests/pricingRequestMeta.js';
import { DEAL_WORK_STATES } from '../dealWorkState.js';
import { PROCUREMENT_SUBSTEPS, stageMeta } from '../stageMeta.js';

const EVENT_LABEL = {
  CREATED: 'สร้างดีล',
  STAGE_CHANGED: 'เปลี่ยนสถานะดีล',
  MARKED_LOST: 'เสียงาน',
  REOPENED: 'เปิดดีลอีกครั้ง',
  ON_HOLD: 'พักดีลไว้',
  DORMANT: 'พักดีลระยะยาว',
  RESUMED: 'ดำเนินการต่อ',
  PAYMENT_RECORDED: 'บันทึกรับชำระเงิน',
  BILLING_UPDATED: 'อัปเดตข้อมูลวางบิล',
  SUBMITTED: 'ส่งเรื่องเข้าระบบ',
  QUOTATION_ISSUED: 'ออกใบเสนอราคา',
  QUOTATION_ACCEPTED: 'ลูกค้ารับใบเสนอราคา',
  QUOTATION_REJECTED: 'ลูกค้าปฏิเสธใบเสนอราคา',
  DOCUMENT_ISSUED: 'ออกเอกสาร',
  COMMENT: 'ความคิดเห็น',
};

function display(value) {
  return value == null || value === '' ? '-' : value;
}

function DetailItem({ label, children }) {
  return (
    <div className="min-w-0">
      <dt className="text-2xs font-bold uppercase text-text-muted">{label}</dt>
      <dd className="mt-1 break-words text-sm font-bold leading-snug text-text-secondary">{display(children)}</dd>
    </div>
  );
}

function meaningfulWorkState(workState) {
  return workState && ![
    DEAL_WORK_STATES.INFORMATIONAL,
    DEAL_WORK_STATES.COMPLETE,
    DEAL_WORK_STATES.CANCELLED,
  ].includes(workState.state);
}

function pricingSummaryText(pricingRequests) {
  const pricingSummary = activePricingRequestsSummary(pricingRequests);
  if (!pricingSummary) return { state: 'ยังไม่มีใบขอราคา', owner: 'ฝ่ายขาย', tone: 'muted' };
  const activeRequest = pricingSummary.requests[0];
  const status = pricingRequestStatusLabel(activeRequest.status);
  const owner = activeRequest.recipientType
    ? pricingRequestRecipientLabel(activeRequest.recipientType).label
    : 'ฝ่ายที่รับเรื่อง';
  return {
    state: `${status.label} · ${pricingSummary.total} รายการ`,
    owner,
    tone: status.tone,
  };
}

function importSummaryText(summary) {
  if (!summary.fulfillmentStatus) return { state: 'ยังไม่เริ่ม', owner: 'ฝ่ายนำเข้า', tone: 'muted' };
  const step = PROCUREMENT_SUBSTEPS.find((item) => item.code === summary.fulfillmentStatus);
  return { state: step?.label ?? summary.fulfillmentStatus, owner: 'ฝ่ายนำเข้า', tone: 'info' };
}

function deliverySummaryText(deliveryProgress) {
  const ordered = Number(deliveryProgress?.ordered || 0);
  const delivered = Number(deliveryProgress?.delivered || 0);
  if (!ordered) return { state: 'ยังไม่มีจำนวนส่งมอบ', owner: 'ฝ่ายนำเข้า', tone: 'muted' };
  return {
    state: `${delivered.toLocaleString('th-TH')} / ${ordered.toLocaleString('th-TH')}`,
    owner: delivered >= ordered ? 'ครบแล้ว' : 'ฝ่ายนำเข้า',
    tone: delivered >= ordered ? 'success' : 'warning',
  };
}

function workflowRows({ summary, pricingRequests, latestQuotation, deliveryProgress }) {
  const pricing = pricingSummaryText(pricingRequests);
  const quotationStatus = latestQuotation ? quotationStatusLabel(latestQuotation.docStatus) : null;
  const deposit = depositPolicyLabel(summary.depositPolicy);
  const payment = paymentStageLabel(summary.paymentStage);
  const lifecycle = dealLifecycleLabel(summary.lifecycle ?? 'ACTIVE');
  return [
    { id: 'pricing', label: 'ราคา', tab: 'pricing', ...pricing },
    {
      id: 'quotations',
      label: 'ใบเสนอราคา',
      tab: 'quotations',
      state: latestQuotation ? `${quotationStatus.label} · ${latestQuotation.number}` : 'ยังไม่มีใบเสนอราคา',
      owner: latestQuotation?.issuedByName || 'ฝ่ายขาย',
      tone: quotationStatus?.tone ?? 'muted',
    },
    {
      id: 'deposit',
      label: 'มัดจำ',
      tab: 'fulfilment',
      state: deposit.label,
      owner: 'ฝ่ายขาย / บัญชี',
      tone: deposit.tone,
    },
    { id: 'import', label: 'จัดซื้อ/นำเข้า', tab: 'fulfilment', ...importSummaryText(summary) },
    { id: 'delivery', label: 'ส่งมอบ', tab: 'fulfilment', ...deliverySummaryText(deliveryProgress) },
    {
      id: 'money',
      label: 'ชำระส่วนที่เหลือ',
      tab: 'money',
      state: summary.amountOutstanding ? `${payment.label} · คงเหลือ ${formatMoney(summary.amountOutstanding)}` : payment.label,
      owner: 'ฝ่ายบัญชี',
      tone: summary.overdue ? 'danger' : payment.tone,
    },
    {
      id: 'close',
      label: 'ปิดงาน',
      tab: 'activity',
      state: lifecycle.label,
      owner: summary.closeConfirmedByName || 'บัญชี / CEO',
      tone: lifecycle.tone,
    },
  ];
}

function rowToneClass(tone, emphasized) {
  if (emphasized || tone === 'danger') return 'bg-danger-bg text-danger-dark';
  if (tone === 'warning') return 'bg-warning-bg-soft text-warning-dark';
  if (tone === 'success') return 'bg-success-bg text-success-dark';
  if (tone === 'info') return 'bg-info-bg text-info';
  return 'bg-transparent text-text-secondary';
}

export function DealOverviewPanel({
  summary,
  events = [],
  pricingRequests = [],
  latestQuotation = null,
  deliveryProgress = null,
  workState = null,
  visibleTabIds = [],
  onSelectTab,
}) {
  const stage = dealStageLabel(summary.salesStage);
  const lifecycle = dealLifecycleLabel(summary.lifecycle ?? 'ACTIVE');
  const meta = stageMeta(summary.salesStage);
  const recentEvents = [...events]
    .filter((event) => event.kind !== 'CREATED' || events.length <= 3)
    .sort((a, b) => new Date(b.createdAt ?? 0) - new Date(a.createdAt ?? 0))
    .slice(0, 3);
  const visibleTabs = new Set(visibleTabIds);
  const rows = workflowRows({ summary, pricingRequests, latestQuotation, deliveryProgress });

  return (
    <section className="panel" data-testid="deal-overview-panel">
      <div className="panel-header">
        <h2>ภาพรวมการทำงาน</h2>
      </div>
      <div className="flex flex-col gap-5 px-4 pb-4 sm:px-5">
        {meaningfulWorkState(workState) ? (
          <div className="flex items-start gap-2 rounded-md bg-info-bg px-3 py-3 text-sm text-info" role="status">
            <Icon name="chevronRight" size={16} className="mt-0.5 shrink-0" />
            <div className="min-w-0">
              <div className="font-extrabold">{workState.labelTh}</div>
              <div className="mt-1 text-xs leading-snug">{workState.explanationTh}</div>
            </div>
          </div>
        ) : null}

        <dl className="grid gap-x-6 gap-y-3 sm:grid-cols-4" aria-label="รายละเอียดดีล">
          <DetailItem label="ลูกค้า">{summary.customerName || summary.title}</DetailItem>
          <DetailItem label="โครงการ">{summary.projectName}</DetailItem>
          <DetailItem label="ผู้ติดต่อ">{summary.contactName}</DetailItem>
          <DetailItem label="ผู้ดูแลดีล">{summary.createdByName}</DetailItem>
          <DetailItem label="วันที่สร้าง">{formatThaiDate(summary.createdAt)}</DetailItem>
          <DetailItem label="อัปเดตล่าสุด">{formatThaiDate(summary.updatedAt)}</DetailItem>
          <DetailItem label="Stage">{meta ? `${meta.no}. ${stage.label}` : stage.label}</DetailItem>
          <DetailItem label="Lifecycle">{lifecycle.label}</DetailItem>
        </dl>

        <div>
          <div className="mb-3 flex items-center justify-between gap-3">
            <h3 className="m-0 text-sm font-extrabold text-text">สถานะงานแต่ละสาย</h3>
          </div>
          <div className="divide-y divide-border-subtle border-y border-border-subtle" data-testid="overview-workflow-summary">
            {rows.map((row) => {
              const emphasized = row.id === 'money' && summary.overdue;
              const canNavigate = row.tab && visibleTabs.has(row.tab) && typeof onSelectTab === 'function';
              return (
                <div key={row.id} className="grid gap-2 py-2.5 text-sm sm:grid-cols-[1fr_1.4fr_1fr_auto] sm:items-center">
                  <div className="font-extrabold text-text">{row.label}</div>
                  <div className={`inline-flex w-fit max-w-full rounded px-2 py-1 text-xs font-bold ${rowToneClass(row.tone, emphasized)}`}>
                    {row.state}
                  </div>
                  <div className="text-xs text-text-muted">ผู้รับผิดชอบ: <strong className="text-text-secondary">{row.owner}</strong></div>
                  {canNavigate ? (
                    <button type="button" className="text-button justify-self-start text-xs sm:justify-self-end" onClick={() => onSelectTab(row.tab)}>
                      ดูรายละเอียด
                    </button>
                  ) : null}
                </div>
              );
            })}
          </div>
        </div>

        <div>
          <div className="mb-3 flex items-center justify-between gap-3">
            <h3 className="m-0 text-sm font-extrabold text-text">กิจกรรมล่าสุด</h3>
            {visibleTabs.has('activity') && typeof onSelectTab === 'function' ? (
              <button type="button" className="text-button text-xs" onClick={() => onSelectTab('activity')}>
                ดูกิจกรรมทั้งหมด
              </button>
            ) : null}
          </div>
          {recentEvents.length === 0 ? (
            <div className="rounded-md bg-surface-subtle px-3 py-3 text-sm text-text-muted">ยังไม่มีกิจกรรมล่าสุด</div>
          ) : (
            <div className="divide-y divide-border-subtle border-y border-border-subtle">
              {recentEvents.map((event) => (
                <div key={event.id} className="grid gap-1 py-2.5 text-sm sm:grid-cols-[1fr_auto] sm:items-start">
                  <div className="min-w-0">
                    <div className="font-bold text-text">{EVENT_LABEL[event.kind] ?? event.kind}</div>
                    <div className="break-words text-xs text-text-muted">{event.message || event.actorName || '-'}</div>
                  </div>
                  <time className="text-xs text-text-muted" dateTime={event.createdAt}>{formatThaiDate(event.createdAt)}</time>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </section>
  );
}
