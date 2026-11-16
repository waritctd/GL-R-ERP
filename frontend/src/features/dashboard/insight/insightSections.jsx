import { StatusBadge } from '../../../components/common/StatusBadge.jsx';
import { formatMoney } from '../../../utils/format.js';
import { Bars, InsightPanel, InsightTable, Pad, Pairs, ProgressBar, Small, Td } from './insightParts.jsx';
import { CustomerList } from './insightMonth.jsx';
import { isNum, ml, num, pct, quarterOf, rangeText, signed } from './insightFormat.js';

const range = (K) => (rangeText(K) ? <span>{rangeText(K)}</span> : null);
const fcTone = (r) => (r >= 80 && r <= 120 ? 'success' : r >= 50 && r <= 200 ? 'warning' : 'danger');
const fillTone = (v) => (v >= 80 ? 'success' : v >= 30 ? 'warning' : 'danger');

function Sales({ K }) {
  const sorted = K.reps.slice().sort((a, b) => b.rev - a.rev);
  const q = quarterOf(K);
  return (
    <>
      <InsightPanel id="sales-table" title="ยอดขายตามพนักงานขาย" side={range(K)} foot="ความแกว่ง = ส่วนเบี่ยงเบนของรายรับรายเดือนหารค่าเฉลี่ย">
        <InsightTable cols={[['พนักงานขาย'], ['รายรับ 12 เดือน', true], ['สัดส่วน', true], ...(q ? [[q.prev_label, true], [q.label, true], ['เปลี่ยนแปลง', true]] : []), ['เดือนสูงสุด', true], ['ความแกว่ง', true], ['ใบเสร็จกลาง ๆ', true]]}>
          {sorted.map((x, i) => (
            <tr key={`${i}-${x.rep}`}>
              <Td><b>{x.rep}</b></Td><Td right>{ml(x.rev)}</Td><Td right>{num(x.share, '%')}</Td>
              {q ? (
                <>
                  <Td right>{ml(x.q_prev)}</Td><Td right>{ml(x.q_cur)}</Td>
                  <Td right><StatusBadge tone={x.qchg < 0 ? 'danger' : 'success'}>{signed(x.qchg)}</StatusBadge></Td>
                </>
              ) : null}
              <Td right>{ml(x.max)}<Small>{x.maxm}</Small></Td>
              <Td right>{x.cv >= 0.7 ? <StatusBadge tone="warning">สูง · {x.cv}</StatusBadge> : num(x.cv)}</Td>
              <Td right>{formatMoney(x.med_ticket)}</Td>
            </tr>
          ))}
        </InsightTable>
      </InsightPanel>
      <InsightPanel id="sales-share" title="สัดส่วนรายรับ 12 เดือน">
        <Pad><Bars rows={sorted.map((x) => [x.rep, x.rev, `${ml(x.rev)} · ${num(x.share, '%')}`])} max={sorted[0].rev} /></Pad>
      </InsightPanel>
    </>
  );
}

function Comm({ K }) {
  const c = K.comm;
  return (
    <InsightPanel id="comm-panel" title="ค่าคอมมิชชัน 12 เดือน" side={range(K)}>
      <Pad>
        <dl className="m-0 grid grid-cols-3 gap-4 mobile:grid-cols-1">
          <div><dt className="text-xs font-bold text-text-muted">จ่ายจริง</dt><dd className="m-0 text-xl font-extrabold">{ml(c.paid)}</dd><small className="text-xs text-text-muted">{num(c.rate, '% ของรายรับ')}</small></div>
          <div><dt className="text-xs font-bold text-text-muted">ตามสูตรขั้นบันได</dt><dd className="m-0 text-xl font-extrabold">{ml(c.formula)}</dd></div>
          <div><dt className="text-xs font-bold text-text-muted">รายการแจ้งแก้ไข</dt><dd className="m-0 text-xl font-extrabold">{num(c.corr)} จาก {num(c.n)}</dd></div>
        </dl>
      </Pad>
      {K.reps?.length ? (
        <InsightTable cols={[['พนักงานขาย'], ['รายรับ', true], ['ค่าคอมจ่ายจริง', true], ['% ของรายรับ', true], ['ตามสูตร', true], ['เดือนที่แจ้งแก้ไข', true]]}>
          {K.reps.map((x, i) => (
            <tr key={`${i}-${x.rep}`}>
              <Td><b>{x.rep}</b></Td><Td right>{ml(x.rev)}</Td><Td right>{formatMoney(x.comm)}</Td><Td right>{num(x.rate, '%')}</Td><Td right>{formatMoney(x.formula)}</Td>
              <Td right>{x.corr ? <StatusBadge tone={x.corr >= 9 ? 'warning' : 'neutral'}>{x.corr} จาก {num(x.n)}</StatusBadge> : '—'}</Td>
            </tr>
          ))}
        </InsightTable>
      ) : null}
    </InsightPanel>
  );
}

function Cust({ K }) {
  const c = K.cust;
  return (
    <>
      <div className="grid grid-cols-2 items-start gap-[18px] nav-drawer:grid-cols-1">
        <InsightPanel id="cust-panel" title="ลูกค้า" side="12 เดือนล่าสุด" foot={`ใบเสร็จกลาง ๆ ${formatMoney(c.med)} · ลูกค้า ${num(c.n80)} รายให้รายรับ 80%`}>
          <Pad>
            <dl className="m-0 grid grid-cols-3 gap-4 mobile:grid-cols-1">
              <div><dt className="text-xs font-bold text-text-muted">ลูกค้าที่รับเงิน</dt><dd className="m-0 text-xl font-extrabold">{num(c.n, ' ราย')}</dd><small className="text-xs text-text-muted">{isNum(c.lines) ? `${c.lines.toLocaleString()} ใบเสร็จ` : '—'}</small></div>
              <div><dt className="text-xs font-bold text-text-muted">10 รายใหญ่สุด</dt><dd className="m-0 text-xl font-extrabold">{num(c.top10, '%')}</dd><small className="text-xs text-text-muted">ของรายรับ</small></div>
              <div><dt className="text-xs font-bold text-text-muted">ซื้อซ้ำ</dt><dd className="m-0 text-xl font-extrabold">{num(c.repeat_n, ' ราย')}</dd><small className="text-xs text-text-muted">{num(c.repeat_share, '% ของรายรับ')}</small></div>
            </dl>
          </Pad>
        </InsightPanel>
        {c.buckets ? <InsightPanel id="ticket" title="ขนาดใบเสร็จ (บาท)"><Pad><Pairs rows={c.buckets} a="สัดส่วนจำนวน" b="สัดส่วนยอดเงิน" /></Pad></InsightPanel> : null}
      </div>
      <CustomerList id="topc" testId="insight-customers-year" title={`ลูกค้า ${K.top_customers?.length ?? 0} อันดับแรก`} side="12 เดือนล่าสุด" rows={K.top_customers} total={K.t12_total} />
    </>
  );
}

function Pipe({ K }) {
  const p = K.pipe;
  const fcByRep = K.fc?.by_rep ?? {};
  return (
    <>
      {p.by_rep?.length ? (
        <InsightPanel id="pipe" title="งานในมือตามพนักงานขาย" side="จากไฟล์ล่าสุดของแต่ละคน" foot={`รวม ${ml(p.value)} จาก ${num(p.n)} งาน = ${num(p.coverage)} เท่าของรายรับ 12 เดือน`}>
          <InsightTable cols={[['พนักงานขาย'], ['งานคาดหวัง', true], ['เทียบรายรับ 12 เดือน', true], ['ขายไม่ได้', true], ['ประมาณการแม่น', true], ['รายงานล่าสุด']]}>
            {p.by_rep.map((x, i) => {
              const f = fcByRep[x.rep];
              const c = K.coverage?.[x.rep];
              return (
                <tr key={`${i}-${x.rep}`}>
                  <Td><b>{x.rep}</b></Td>
                  <Td right>{ml(x.exp)}<Small>{num(x.n, ' งาน')}</Small></Td>
                  <Td right>{!isNum(x.cover) ? '—' : x.cover >= 0.1 ? `${x.cover} เท่า` : 'ต่ำกว่า 0.1 เท่า'}</Td>
                  <Td right>{x.lost_n ? ml(x.lost) : '—'}<Small>{x.lost_n ? `${x.lost_n} งาน` : 'ไม่ได้บันทึก'}</Small></Td>
                  <Td right>{pct(f)}</Td>
                  <Td>{!c ? '—' : c.stale_wk > 3 ? <StatusBadge tone="danger">ขาด {c.stale_wk} สัปดาห์</StatusBadge> : c.weeks / c.of >= 0.8 ? <StatusBadge tone="success">ส่งครบ {num(c.weeks)}/{num(c.of)}</StatusBadge> : <StatusBadge tone="warning">ส่ง {num(c.weeks)}/{num(c.of)} สัปดาห์</StatusBadge>}</Td>
                </tr>
              );
            })}
          </InsightTable>
        </InsightPanel>
      ) : null}
      <div className="grid grid-cols-2 items-start gap-[18px] nav-drawer:grid-cols-1">
        {p.fill ? (
          <InsightPanel id="fill" title="ข้อมูลในงานคาดหวังกรอกครบแค่ไหน">
            <Pad><Bars max={100} rows={[['บริษัทออกแบบ', p.fill.designer], ['ไตรมาสที่คาดว่าจะได้', p.fill.q], ['ขั้นงานขาย', p.fill.stage], ['จะขายได้ %', p.fill.prob]].map(([n, v]) => [n, v, num(v, '%')])} /></Pad>
          </InsightPanel>
        ) : null}
        {p.buckets ? <InsightPanel id="size" title="ขนาดงานคาดหวัง (บาท)"><Pad><Pairs rows={p.buckets} a="สัดส่วนจำนวน" b="สัดส่วนมูลค่า" /></Pad></InsightPanel> : null}
      </div>
      {K.fc_stats?.length ? (
        <InsightPanel id="fcs" title="ความแม่นของประมาณการรายคน" side="รับจริง × 1.07 เทียบประมาณการต้นเดือน" foot="เป้าหมายที่ใช้ได้คือ 85–115%">
          <InsightTable cols={[['พนักงานขาย'], ['เดือนที่มีประมาณการ', true], ['รับต่อประมาณ', true], ['คลาดเคลื่อน', true], ['ส่งรายงาน', true]]}>
            {K.fc_stats.map((x, i) => (
              <tr key={`${i}-${x.rep}`}>
                <Td><b>{x.rep}</b>{x.xl ? <Small>จากไฟล์ Excel</Small> : null}</Td><Td right>{num(x.n)}</Td>
                <Td right><StatusBadge tone={fcTone(x.ratio)}>{num(x.ratio, '%')}</StatusBadge></Td><Td right>{isNum(x.err) ? `±${x.err}%` : '—'}</Td><Td right>{num(x.weeks, '%')}</Td>
              </tr>
            ))}
          </InsightTable>
        </InsightPanel>
      ) : null}
      {K.pipe_quality?.length ? (
        <InsightPanel id="pq" title="คุณภาพข้อมูลงานคาดหวังรายคน" side="สัดส่วนรายการที่กรอกช่องนั้น">
          <InsightTable cols={[['พนักงานขาย'], ['รายการ', true], ['มูลค่า', true], ['ไตรมาส', true], ['ขั้นงาน', true], ['จะขายได้ %', true], ['ผู้ออกแบบ', true], ['งานใหญ่สุด', true]]}>
            {K.pipe_quality.map((x, i) => (
              <tr key={`${i}-${x.rep}`}>
                <Td><b>{x.rep}</b></Td><Td right>{num(x.n)}</Td><Td right>{ml(x.value)}</Td>
                {['q', 'stage', 'prob', 'designer'].map((k) => <Td key={k} right><StatusBadge tone={fillTone(x[k])}>{num(x[k], '%')}</StatusBadge></Td>)}
                <Td right>{num(x.largest, '%')}</Td>
              </tr>
            ))}
          </InsightTable>
        </InsightPanel>
      ) : null}
      {K.top_deals?.length ? (
        <InsightPanel id="deals" title="10 งานใหญ่สุดในงานคาดหวัง">
          <InsightTable cols={[['#'], ['โครงการ'], ['พนักงานขาย'], ['มูลค่า', true], ['สัดส่วนของงานคาดหวัง'], ['%', true]]}>
            {K.top_deals.map((d, i) => (
              <tr key={`${d.project}-${i}`}>
                <Td>{i + 1}</Td><Td><b>{d.project}</b></Td><Td>{d.rep}</Td><Td right>{ml(d.value)}</Td>
                <Td><ProgressBar part={d.share} whole={K.top_deals[0].share} label={`${d.project ?? '—'}: ${num(d.share, '%')}`} /></Td>
                <Td right>{num(d.share, '%')}</Td>
              </tr>
            ))}
          </InsightTable>
        </InsightPanel>
      ) : null}
    </>
  );
}

function Des({ K }) {
  const top = K.designers?.[0];
  const tu = Object.entries(K.time ?? {}).map(([k, v]) => {
    const [visit, logistics, internal] = [v.visit, v.logistics, v.internal].map((x) => Number(x) || 0);
    const s = visit + logistics + internal;
    return [k, s, visit / (s || 1), logistics / (s || 1), internal / (s || 1)];
  }).sort((a, b) => b[2] - a[2]);
  return (
    <>
      {K.designers?.length ? (
        <InsightPanel id="des" title="ผู้ออกแบบ: มูลค่างานเทียบการเข้าพบ" side="สั่งซื้อแล้ว + คาดหวัง" foot={K.designer_meta ? `ผู้ออกแบบ 5 รายแรกถือ ${num(K.designer_meta.top5, '%')} ของมูลค่าจากทั้งหมด ${num(K.designer_meta.n)} ราย` : null}>
          <InsightTable cols={[['บริษัทออกแบบ'], ['พนักงานขาย'], ['สั่งซื้อแล้ว', true], ['คาดหวัง', true], ['โครงการ', true], ['รวม'], ['เข้าพบ', true]]}>
            {K.designers.map((d, i) => (
              <tr key={`${i}-${d.name}`}>
                <Td><b>{d.name}</b></Td><Td>{d.reps}</Td><Td right>{ml(d.ordered)}</Td><Td right>{ml(d.expected)}</Td><Td right>{num(d.n)}</Td>
                <Td><ProgressBar part={d.ordered + d.expected} whole={top.ordered + top.expected} label={`${d.name ?? '—'}: ${ml(d.ordered + d.expected)}`} /></Td>
                <Td right>{d.visits || <StatusBadge tone="warning">ไม่พบในบันทึก</StatusBadge>}</Td>
              </tr>
            ))}
          </InsightTable>
        </InsightPanel>
      ) : null}
      {tu.length ? (
        <InsightPanel id="time" title="เซลส์ใช้เวลากับอะไร" side="รายการที่บันทึกว่าไปจริง">
          <Pad>
            <Bars max={1} rows={tu.map(([k, s, visit, logistics, internal]) => [k, visit, `เข้าพบ ${Math.round(visit * 100)}% · ขนส่ง ${Math.round(logistics * 100)}% · งานภายใน ${Math.round(internal * 100)}% (${s} รายการ)`])} />
          </Pad>
        </InsightPanel>
      ) : null}
    </>
  );
}

export const SECTION_VIEW = { sales: Sales, comm: Comm, cust: Cust, pipe: Pipe, des: Des };
