import { ErrorBoundary } from '../../../components/common/ErrorBoundary.jsx';
import { Figure } from '../dashboardParts.jsx';
import { FigureBand, InsightPanel, Pad } from './insightParts.jsx';
import { cn } from '../../../utils/cn.js';
import { SECTION_VIEW } from './insightSections.jsx';
import { SECTION_LABEL, SECTION_ORDER, availableSections, buildFindings, buildRecommendations, ml, num, quarterOf, rangeText } from './insightFormat.js';

const Fallback = () => <p role="alert" className="m-0 text-sm text-danger">แสดงหัวข้อนี้ไม่ได้</p>;
/** One boundary per block: a malformed optional section blanks only itself, never the month view or its siblings. */
const Guard = ({ children }) => <ErrorBoundary fallback={Fallback}>{children}</ErrorBoundary>;

/** Headline, the four figures and the findings, each only from the numbers the snapshot carries. */
function Overview({ K, have }) {
  const q = quarterOf(K);
  const findings = buildFindings(K);
  const figures = [
    K.t12_total != null && { label: 'รายรับ 12 เดือน (ไม่รวม VAT)', value: ml(K.t12_total), caption: rangeText(K), lead: true },
    q && { label: `${q.label} เทียบ${q.prev_label}`, value: `${q.chg}%`, caption: `${ml(q.cur)} จาก ${ml(q.prev)}`, danger: q.chg < 0 },
    have.pipe && { label: 'งานคาดหวัง', value: ml(K.pipe.value), caption: `${num(K.pipe.n)} งาน · ${num(K.pipe.coverage)} เท่าของรายรับ 12 เดือน` },
    have.comm && { label: 'ค่าคอมจ่ายจริง 12 เดือน', value: ml(K.comm.paid), caption: num(K.comm.rate, '% ของรายรับ') },
  ].filter(Boolean);
  const headline = [
    K.t12_total != null && `12 เดือนล่าสุดรับเงิน ${ml(K.t12_total)}`,
    q && `${q.label} ${q.chg < 0 ? 'ต่ำกว่า' : 'สูงกว่า'}${q.prev_label} ${Math.abs(q.chg)}%`,
    have.pipe && `งานคาดหวัง ${ml(K.pipe.value)}`,
  ].filter(Boolean).join(' · ');
  return (
    <>
      {figures.length ? (
        <section className="insight-rise rounded-md border border-border bg-surface">
          {headline ? <p className="m-0 border-b border-border px-5 py-4 text-base font-bold mobile:px-3.5">{headline}</p> : null}
          <FigureBand>{figures.map((f) => <Figure key={f.label} testId="insight-figure" {...f} />)}</FigureBand>
        </section>
      ) : null}
      {findings.length ? (
        <InsightPanel id="find" title="ข้อค้นพบ" side={`${findings.length} ข้อ`}>
          <Pad>
            <div className="grid gap-3">
              {findings.map(([color, head, detail]) => (
                <div key={head} className="grid gap-0.5">
                  <strong className="text-sm"><span aria-hidden="true" className={cn('mr-2 inline-block size-2 rounded-pill', color)} />{head}</strong>
                  <p className="m-0 text-xs text-text-muted">{detail}</p>
                </div>
              ))}
            </div>
          </Pad>
        </InsightPanel>
      ) : null}
    </>
  );
}

function Recommendations({ K }) {
  const recs = buildRecommendations(K);
  return recs.length ? (
    <InsightPanel id="recs" title="ข้อเสนอแนะ เรียงตามผลที่คาดว่าจะได้">
      <Pad><ol className="m-0 grid gap-3 pl-5">{recs.map(([head, detail]) => <li key={head}><b>{head}</b><br /><span className="text-xs text-text-muted">{detail}</span></li>)}</ol></Pad>
    </InsightPanel>
  ) : null;
}

/** The "ทั้งปี" part: headline, four figures, findings, section chips + sections, recommendations, data notes. */
export function YearPart({ K, role, section, onSection }) {
  const have = availableSections(K);
  const order = (SECTION_ORDER[role] ?? SECTION_ORDER.ceo).filter((k) => have[k]);
  const shown = section === 'all' || !order.includes(section) ? order : [section];
  const chips = [{ id: 'all', label: 'ทั้งหมด' }, ...order.map((k) => ({ id: k, label: SECTION_LABEL[k] }))];

  return (
    <div className="grid gap-[18px]" data-testid="insight-year">
      <h2 className="m-0 text-lg font-extrabold">ทั้งปี{rangeText(K) ? <small className="ml-2 text-xs font-normal text-text-muted">12 เดือนล่าสุด {rangeText(K)}</small> : null}</h2>
      <Guard><Overview K={K} have={have} /></Guard>
      {order.length ? (
        <div role="group" aria-label="หัวข้อวิเคราะห์" className="flex flex-wrap gap-2">
          {chips.map((c) => {
            const on = c.id === (order.includes(section) ? section : 'all');
            return (
              <button
                key={c.id}
                type="button"
                aria-pressed={on}
                onClick={() => onSection(c.id)}
                className={cn('min-h-9 rounded-pill border px-3.5 text-sm font-bold', on ? 'border-primary bg-primary text-surface' : 'border-border bg-surface text-text-secondary hover:border-border-input')}
              >
                {c.label}
              </button>
            );
          })}
        </div>
      ) : null}
      {shown.map((k) => {
        const View = SECTION_VIEW[k];
        return <div key={k} data-testid={`insight-section-${k}`} className="grid gap-[18px]"><Guard><View K={K} /></Guard></div>;
      })}
      <Guard><Recommendations K={K} /></Guard>
      <section className="rounded-md border border-border bg-surface px-5 py-4 mobile:px-3.5">
        <details>
          <summary className="cursor-pointer font-bold">หมายเหตุข้อมูล</summary>
          <ul className="mb-0 mt-2 grid gap-1.5 pl-5 text-xs text-text-muted">
            <li>รายรับมาจากไฟล์ค่าคอมของเซลส์ (ถอด VAT แล้ว)</li>
            <li>ประมาณการเทียบกับยอดรับจริง × 1.07 เพราะเซลส์ประมาณการรวม VAT</li>
            <li>งานคาดหวังนับจากไฟล์ล่าสุดของแต่ละคน ตัดรายการซ้ำแล้ว</li>
            <li>หน้านี้มีตัวเลขภายใน ห้ามส่งต่อนอก GL&amp;R</li>
          </ul>
        </details>
      </section>
    </div>
  );
}
