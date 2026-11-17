import { useQuery } from '@tanstack/react-query';
import { api } from '../../../api/index.js';
import { queryKeys } from '../../../api/queryKeys.js';
import { Skeleton } from '../../../components/common/Skeleton.jsx';
import { formatThaiDate } from '../../../utils/format.js';
import { Figure, failed } from '../dashboardParts.jsx';
import { isNum, ml, num, pct, pctOf } from './insightFormat.js';
import { FigureBand, InsightPanel, InsightTable, Td } from './insightParts.jsx';

const NOWRAP = '[&_td]:whitespace-nowrap [&_th]:whitespace-nowrap';
const sqm = (v) => (isNum(v) ? `${Math.round(v).toLocaleString('en-US')} ตร.ม.` : '—');

// The four checks of analytics.dq_check (V223). An unknown key is shown as it comes.
const CHECK_LABEL = {
  stage_mapped: 'ดีลที่ยังไม่ได้จับคู่ขั้นงาน',
  quote_line_brand_present: 'รายการสินค้าในใบเสนอราคาที่ไม่มีแบรนด์',
  won_deal_has_value: 'ดีลที่ได้งานแล้วแต่ไม่มีมูลค่า',
  test_flag_reviewed: 'ดีลที่ชื่อเหมือนดีลทดสอบแต่ยังไม่ได้ตรวจสอบ',
};

/** "เทียบสัปดาห์ก่อน +2.0 ล้าน": only when this week and the week before are both numbers. */
function change(value, previous, fmt, delta = (v, p) => v - p) {
  if (!isNum(value) || !isNum(previous)) return '';
  const d = delta(value, previous);
  return `เทียบสัปดาห์ก่อน ${d > 0 ? '+' : ''}${fmt(d)}`;
}
// Percentage points between the two rounded percentages, so the change agrees with the two figures shown.
const STALE = [(d) => `${d} จุด`, (v, p) => Math.round(v * 100) - Math.round(p * 100)];

/** The weekly figures computed inside the portal (analytics.metric_weekly). Every number is shown as served; none is derived here. */
export function PortalMetrics() {
  const query = useQuery({ queryKey: queryKeys.salesAnalyticsMetrics(), queryFn: () => api.salesAnalytics.metrics(),
    refetchOnWindowFocus: true, staleTime: 10 * 60_000 });
  const d = query.data;
  return (
    <section className="grid gap-[18px]" data-testid="portal-metrics">
      <h2 className="m-0 text-lg">ตัวเลขจาก Portal</h2>
      {query.isLoading ? <Skeleton height={120} /> : null}
      {failed(query) ? <p role="alert" className="m-0 text-sm text-danger">โหลดตัวเลขจาก Portal ไม่สำเร็จ</p> : null}
      {d && !d.weekStart ? (
        <p data-testid="portal-metrics-empty" className="m-0 rounded-md border border-border bg-surface p-5 text-sm text-text-muted">
          ยังไม่มีตัวเลขรายสัปดาห์ — ระบบคำนวณทุกเช้าวันจันทร์
        </p>
      ) : null}
      {d?.weekStart ? <Week d={d} /> : null}
    </section>
  );
}

function Week({ d }) {
  const metrics = d.metrics ?? [];
  const by = new Map(metrics.map((m) => [`${m.key}|${m.dimension}`, m]));
  const get = (key, dim = '') => by.get(`${key}|${dim}`);
  const val = (key, dim) => get(key, dim)?.value;
  const withLabel = (prefix) => [...new Map(metrics.filter((m) => m.dimension.startsWith(prefix)).map((m) => [m.dimension, m.label])).entries()];
  const failedChecks = (d.checks ?? []).filter((c) => !c.passed);
  const fig = (key, label, fmt, [diff, delta] = [ml]) => {
    const m = get(key);
    return <Figure lead={key === 'pipeline_value_dedup'} label={label} value={fmt(m?.value)} caption={change(m?.value, m?.previous, diff, delta)} />;
  };
  const active = (dim) => [val('rep_quiet_deal_share', dim), val('hygiene_score', dim)].some(isNum)
    || [val('rep_stage_moves_week', dim), val('rep_activities_week', dim)].some((v) => isNum(v) && v > 0);
  const reps = withLabel('rep=').filter(([dim]) => active(dim)).sort((a, b) => a[1].localeCompare(b[1], 'th'));
  const quoted = (dim) => val('quoted_value', dim);
  const brands = withLabel('brand=').sort((a, b) => (isNum(quoted(b[0])) ? quoted(b[0]) : -1) - (isNum(quoted(a[0])) ? quoted(a[0]) : -1));
  const quotedTotal = brands.reduce((sum, [dim]) => sum + (isNum(quoted(dim)) ? quoted(dim) : 0), 0);
  return (
    <>
      <div className="grid gap-1">
        <p data-testid="portal-week" className="m-0 text-sm">
          {`สัปดาห์เริ่ม ${formatThaiDate(`${d.weekStart}T00:00:00`)} · คำนวณเมื่อ ${formatThaiDate(d.computedAt)}`}
        </p>
        <small className="text-xs text-text-muted">มูลค่าทั้งหมดไม่รวม VAT</small>
      </div>
      {failedChecks.length ? (
        <div role="status" data-testid="portal-checks" className="rounded-md border border-warning bg-surface p-4 text-sm">
          <b>ตรวจพบข้อมูลที่ต้องตรวจสอบก่อนใช้ตัวเลขนี้</b>
          <ul className="m-0 mt-1 list-disc pl-5">{failedChecks.map((c) => <li key={c.key}>{CHECK_LABEL[c.key] ?? c.key}: {c.detail}</li>)}</ul>
        </div>
      ) : null}
      <InsightPanel title="มูลค่า pipeline">
        <FigureBand>
          {fig('pipeline_value_dedup', 'มูลค่า pipeline (นับโครงการละครั้ง)', ml)}
          {fig('pipeline_value_raw', 'มูลค่า pipeline (รวมทุกดีล)', ml)}
          {fig('pipeline_stale_share', 'สัดส่วน pipeline ที่เงียบเกิน 14 วัน', pct, STALE)}
        </FigureBand>
      </InsightPanel>
      {reps.length ? (
        <InsightPanel className={NOWRAP} title="ทีมขายสัปดาห์นี้">
          <InsightTable testId="portal-team" cols={[['พนักงาน'], ['เลื่อนขั้นดีล (ครั้ง)', true], ['กิจกรรมที่บันทึก', true], ['ดีลที่เงียบเกิน 14 วัน', true], ['ข้อมูลดีลครบถ้วน', true]]}>
            {reps.map(([dim, label]) => (
              <tr key={dim}>
                <Td><b>{label}</b></Td><Td right>{num(val('rep_stage_moves_week', dim))}</Td><Td right>{num(val('rep_activities_week', dim))}</Td>
                <Td right>{pct(val('rep_quiet_deal_share', dim))}</Td><Td right>{pct(val('hygiene_score', dim))}</Td>
              </tr>
            ))}
          </InsightTable>
        </InsightPanel>
      ) : null}
      {brands.length ? (
        <InsightPanel className={NOWRAP} data-testid="portal-brands-panel" title="เสนอราคาตามแบรนด์"
          foot="ได้รับคำสั่งซื้อ = ดีลที่อยู่ขั้นรับคำสั่งซื้อหรือหลังจากนั้น · นับโครงการละครั้ง">
          <InsightTable testId="portal-brands" cols={[['แบรนด์'], ['มูลค่าที่เสนอราคา', true], ['สัดส่วนของยอดที่เสนอ', true], ['พื้นที่ที่เสนอราคา', true], ['มูลค่าที่ได้รับคำสั่งซื้อ', true]]}>
            {brands.map(([dim, label]) => (
              <tr key={dim}>
                <Td><b>{label}</b></Td><Td right>{ml(quoted(dim))}</Td><Td right>{pctOf(quoted(dim), quotedTotal)}</Td>
                <Td right>{sqm(val('quoted_sqm', dim))}</Td><Td right>{ml(val('ordered_value', dim))}</Td>
              </tr>
            ))}
          </InsightTable>
        </InsightPanel>
      ) : null}
    </>
  );
}
