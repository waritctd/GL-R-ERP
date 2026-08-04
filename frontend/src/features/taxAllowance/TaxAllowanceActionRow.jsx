import { useNavigate } from 'react-router-dom';
import { Icon } from '../../components/common/Icon.jsx';

/**
 * Landing-page nudge for ล.ย.01, shown ONLY when the employee actually has something to do.
 *
 * <p>Deliberately not a stat tile: the employee landings already carry a three-across tile grid
 * (a fourth would orphan onto its own row), and a permanent card would occupy space on every
 * visit to say "nothing to do". This is the same one-wide-row shape as the attendance card above
 * it — icon, text, chevron — and it disappears entirely once the declaration is filed and live.
 *
 * <p>It exists because nothing in the ล.ย.01 workflow notifies anyone: a rejection or a yearly
 * expiry is otherwise discoverable only by opening a page the employee has no reason to visit.
 */

// Only states the form will actually accept a submission for — mirrors TaxAllowancePage's
// EDITABLE_STATUS_KEYS, so this never points at a form that would refuse the visit.
const PROMPTS = {
  NONE: {
    tone: 'amber',
    icon: 'calculator',
    title: 'ยังไม่ได้ยื่นแบบแจ้ง ล.ย.01',
    description: 'ยื่นค่าลดหย่อนเพื่อให้หักภาษีเงินเดือนได้ถูกต้อง',
    cta: 'ยื่นแบบแจ้ง ล.ย.01',
  },
  REJECTED: {
    tone: 'amber',
    icon: 'calculator',
    title: 'แบบแจ้ง ล.ย.01 ถูกปฏิเสธ',
    description: 'แก้ไขตามเหตุผลที่ HR แจ้ง แล้วยื่นฉบับใหม่',
    cta: 'ยื่นฉบับใหม่',
  },
  EXPIRED: {
    tone: 'amber',
    icon: 'calculator',
    title: 'แบบแจ้ง ล.ย.01 หมดอายุแล้ว',
    description: 'ยืนยันใหม่เพื่อให้ยังได้รับสิทธิลดหย่อนในปีภาษีนี้',
    cta: 'ยื่นฉบับใหม่',
  },
};

export function TaxAllowanceActionRow({ summary }) {
  const navigate = useNavigate();
  // No status yet (the summary query is still in flight) means we do not know whether anything is
  // needed — NOT that nothing has been filed. Defaulting to 'NONE' here would flash "you haven't
  // filed" at an employee who has, every time the landing page loads.
  const statusKey = summary?.statusInfo?.key;
  const prompt = statusKey ? PROMPTS[statusKey] : null;
  if (!prompt) return null;

  // A rejection reason is the single most useful thing to show here, so it replaces the generic
  // line when HR left one.
  const description = statusKey === 'REJECTED' && summary?.statusInfo?.note
    ? summary.statusInfo.note
    : prompt.description;

  return (
    <button
      type="button"
      onClick={() => navigate('/tax-allowance')}
      className="bg-surface border border-border rounded-md shadow-sm p-5 w-full text-left cursor-pointer flex items-center justify-between gap-4 transition-colors hover:border-primary/50 hover:bg-surface-hover focus-visible:outline-none focus-visible:shadow-[var(--shadow-focus-ring)] focus-visible:border-primary-hover max-[720px]:flex-col max-[720px]:items-start max-[720px]:gap-3"
    >
      <span className="flex items-center gap-3 min-w-0">
        <span className={`stat-icon !mb-0 stat-${prompt.tone}`}>
          <Icon name={prompt.icon} size={21} />
        </span>
        <span className="min-w-0">
          <span className="block !text-sm !font-bold !text-text">{prompt.title}</span>
          <span className="block !text-xs !text-text-muted">{description}</span>
        </span>
      </span>
      <span className="flex items-center gap-2 max-[720px]:w-full max-[720px]:justify-between">
        <span className="!text-sm !font-bold !text-primary">{prompt.cta}</span>
        <Icon name="chevronRight" size={16} className="text-text-faint" />
      </span>
    </button>
  );
}
