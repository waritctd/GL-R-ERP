import { Icon } from '../../components/common/Icon.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { evidenceLabel, ruleCardFor, taxChip } from './specialMoneyRules.js';

/** RuleCard — the redesign's core anti-cognitive-overload move (plan §"The rule card"): once a type is chosen,
 * restate ONLY that type's rules, generated from the single RULE_CARD table in specialMoneyRules.js so the
 * copy here cannot drift from that table independently. */
export function RuleCard({ requestType, typeMeta, className }) {
  const rule = ruleCardFor(requestType);
  if (!rule) return null;
  const chip = typeMeta ? taxChip(typeMeta.payrollBucket) : null;
  const evidenceRequired = typeMeta?.evidenceRequired !== false;

  const rows = [
    { key: 'cap', label: 'เพดาน', value: rule.cap },
    { key: 'deadline', label: 'กำหนดเวลา', value: rule.deadline },
    { key: 'frequency', label: 'ความถี่', value: rule.frequency },
    {
      key: 'evidence',
      label: 'หลักฐานที่ต้องแนบ',
      value: evidenceRequired ? evidenceLabel(requestType) : 'ไม่บังคับแนบหลักฐาน (ประเภทเดียวที่ไม่บังคับ)',
    },
    { key: 'eligibility', label: 'ใครเบิกได้', value: rule.eligibility },
  ].filter((row) => Boolean(row.value));

  return (
    <div
      className={className}
      data-testid="rule-card"
      aria-label={`เงื่อนไขสำหรับ${typeMeta?.thaiLabel ? ` ${typeMeta.thaiLabel}` : ''}`}
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="m-0 flex items-center gap-1.5 text-sm font-extrabold text-text">
          <Icon name="info" size={15} />
          เงื่อนไขของประเภทนี้
        </h3>
        {chip ? <StatusBadge tone={chip.tone}>{chip.label}</StatusBadge> : null}
      </div>
      <dl className="m-0 mt-2.5 grid grid-cols-1 gap-x-5 gap-y-2.5 min-[721px]:grid-cols-2">
        {rows.map((row) => (
          <div key={row.key} className="min-w-0">
            <dt className="text-xs font-bold text-text-muted">{row.label}</dt>
            <dd className="m-0 mt-0.5 text-sm text-text">{row.value}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}
