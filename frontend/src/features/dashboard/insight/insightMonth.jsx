import { Button } from '../../../components/common/Button.jsx';
import { StatusBadge } from '../../../components/common/StatusBadge.jsx';
import { Figure } from '../dashboardParts.jsx';
import { FigureBand, InsightPanel, InsightTable, Pad, ProgressBar, Small, Td } from './insightParts.jsx';
import { barWidth, ml, pctOf, ratio, signed } from './insightFormat.js';
import { cn } from '../../../utils/cn.js';
import { formatMoney } from '../../../utils/format.js';

const ratioTone = (r) => (r >= 0.8 && r <= 1.2 ? 'success' : r >= 0.5 && r <= 2 ? 'warning' : 'danger');

/** Clickable monthly revenue columns + prev/next. `sel` is a month index or 'now'. */
export function MonthPicker({ K, sel, onSelect }) {
  const n = K.months.length;
  const max = Math.max(0, ...K.monthly_total);
  const idx = sel === 'now' ? n : sel;
  return (
    <InsightPanel
      id="months"
      title="รายเดือน"
      side={(
        <span className="flex items-center gap-2">
          <Button variant="secondary" aria-label="เดือนก่อนหน้า" disabled={idx <= 0} onClick={() => onSelect(sel === 'now' ? n - 1 : sel - 1)}>‹</Button>
          <Button variant="secondary" aria-label="เดือนถัดไป" disabled={sel === 'now' || sel >= n - 1} onClick={() => onSelect(sel + 1)}>›</Button>
          {K.now ? (
            <button
              type="button"
              data-testid="insight-month-now"
              aria-pressed={sel === 'now'}
              onClick={() => onSelect('now')}
              className={cn('min-h-8 rounded-pill border px-3 text-xs font-bold', sel === 'now' ? 'border-primary bg-primary text-surface' : 'border-border bg-surface text-text-secondary')}
            >
              ยังไม่จบเดือน
            </button>
          ) : null}
        </span>
      )}
      foot="รายรับไม่รวม VAT หน่วยล้านบาท · กดแท่งเพื่อดูเดือนนั้น"
    >
      <Pad>
        <div className="flex h-40 items-end gap-1.5">
          {K.monthly_total.map((v, i) => (
            <button
              key={`${i}-${K.months[i]}`}
              type="button"
              data-testid={`insight-month-${i}`}
              aria-pressed={sel === i}
              aria-label={`${K.months[i]} ${ml(v)}`}
              title={`${K.months[i]}: ${ml(v)}`}
              onClick={() => onSelect(i)}
              className={cn('insight-grow-y min-w-0 flex-1 rounded-t border-0 p-0', sel === i ? 'bg-primary' : 'bg-primary/40 hover:bg-primary/60')}
              style={{ height: `${barWidth(v, max)}%` }}
            />
          ))}
        </div>
        <div className="mt-1 flex gap-1.5 text-center text-2xs text-text-muted">
          {K.months.map((m, i) => <span key={`${i}-${m}`} data-testid={`insight-month-label-${i}`} className={cn('min-w-0 flex-1', sel === i && 'font-extrabold text-primary', sel !== i && i % 3 !== 0 && 'mobile:sr-only')}>{m.split(' ')[0]}</span>)}
        </div>
      </Pad>
    </InsightPanel>
  );
}

/** Top customers (of a month or of the year): # / ลูกค้า / amount / share bar / % / receipts / reps, as the prototype's custList. */
export function CustomerList({ id, title, side, rows, total, testId }) {
  if (!Array.isArray(rows) || rows.length === 0) return null;
  const top = rows[0].amt;
  const sum = rows.reduce((s, c) => s + (Number(c.amt) || 0), 0);
  return (
    <InsightPanel id={id} data-testid={testId} title={title} side={side} foot={`รวม ${rows.length} รายนี้คิดเป็น ${pctOf(sum, total, 1)} ของรายรับช่วงนี้ · ชื่อลูกค้าเป็นข้อมูลภายใน ห้ามส่งต่อนอก GL&R`}>
      <InsightTable cols={[['#'], ['ลูกค้า'], ['ยอดรับ (ไม่รวม VAT)', true], ['สัดส่วน'], ['%', true], ['ใบเสร็จ', true], ['พนักงานขาย']]}>
        {rows.map((c, i) => (
          <tr key={`${c.name}-${i}`}>
            <Td>{i + 1}</Td>
            <Td><b>{c.name}</b></Td>
            <Td right>{formatMoney(c.amt)}</Td>
            <Td><ProgressBar part={c.amt} whole={top} label={`${c.name}: ${c.share}%`} /></Td>
            <Td right>{c.share}%</Td>
            <Td right>{c.n}</Td>
            <Td>{c.reps}</Td>
          </tr>
        ))}
      </InsightTable>
    </InsightPanel>
  );
}

/** A closed month: figures, per-rep table, forecast vs actual, customer figures, top customers. */
export function MonthDetail({ K, sel }) {
  const d = K.monthly[sel];
  const lead = d.reps[0];
  const top = Math.max(0, ...d.reps.map((r) => r.rev));
  const fc = Array.isArray(d.fc) ? d.fc : [];
  return (
    <div className="grid gap-[18px]" data-testid="insight-month-detail">
      <InsightPanel title={K.months[sel]} side={`${d.lines} ใบเสร็จ · ${d.cust} ลูกค้า`}>
        <FigureBand>
          <Figure lead label="รายรับเดือนนี้ (ไม่รวม VAT)" value={formatMoney(d.total)} caption={lead ? `${lead.rep} ทำ ${pctOf(lead.rev, d.total)} ของเดือน` : ''} />
          <Figure danger={d.mom < 0} label="เทียบเดือนก่อน" value={signed(d.mom)} caption={sel ? `${K.months[sel - 1]} ${ml(K.monthly_total[sel - 1])}` : 'ไม่มีไฟล์เดือนก่อนหน้า'} />
          <Figure danger={d.yoy < 0} label="เทียบเดือนเดียวกันปีก่อน" value={signed(d.yoy)} caption={d.yoy == null || !K.months[sel - 12] ? 'ไม่มีไฟล์ของปีก่อน' : `${K.months[sel - 12]} ${ml(K.monthly_total[sel - 12])}`} />
          <Figure label="ค่าคอมจ่าย" value={formatMoney(d.comm)} caption={`${pctOf(d.comm, d.total, 2)} ของรายรับ${d.corr ? ` · แจ้งแก้ไข ${d.corr} คน` : ''}`} />
        </FigureBand>
      </InsightPanel>
      <div className="grid grid-cols-2 items-start gap-[18px] nav-drawer:grid-cols-1">
        <InsightPanel title="รายรับและค่าคอมตามพนักงานขาย">
          <InsightTable cols={[['พนักงานขาย'], ['รายรับ', true], ['สัดส่วน'], ['ค่าคอม', true], ['สถานะ']]}>
            {d.reps.map((r, i) => (
              <tr key={`${i}-${r.rep}`}>
                <Td><b>{r.rep}</b></Td><Td right>{formatMoney(r.rev)}</Td>
                <Td><ProgressBar part={r.rev} whole={top} label={`${r.rep}: ${pctOf(r.rev, d.total)} ของเดือน`} /></Td>
                <Td right>{formatMoney(r.comm)}</Td>
                <Td>{r.corr ? <StatusBadge tone="warning">แจ้งแก้ไข</StatusBadge> : <StatusBadge tone="success">OK</StatusBadge>}</Td>
              </tr>
            ))}
          </InsightTable>
        </InsightPanel>
        <div className="grid gap-[18px]">
          <InsightPanel title="ประมาณการต้นเดือนเทียบรับจริง" side="รวม VAT">
            {fc.length ? (
              <InsightTable cols={[['พนักงานขาย'], ['ประมาณการ', true], ['รับจริง', true], ['% ที่รับได้', true]]}>
                {fc.map((f, i) => (
                  <tr key={`${i}-${f.rep}`}>
                    <Td><b>{f.rep}</b>{f.xl ? <Small>จากไฟล์ Excel</Small> : null}</Td>
                    <Td right>{formatMoney(f.fc)}</Td><Td right>{formatMoney(f.act)}</Td>
                    <Td right><StatusBadge tone={ratioTone(f.ratio)}>{Math.round(f.ratio * 100)}%</StatusBadge></Td>
                  </tr>
                ))}
              </InsightTable>
            ) : <Pad><p className="m-0 text-sm text-text-muted">ไม่มีประมาณการในอีเมลต้นเดือนของเดือนนี้</p></Pad>}
          </InsightPanel>
          <InsightPanel title="ลูกค้าและใบเสร็จ">
            <FigureBand>
              <Figure label="5 รายใหญ่สุด" value={`${d.top5}%`} caption="ของรายรับเดือนนี้" />
              <Figure label="รายใหญ่สุดรายเดียว" value={`${d.top1}%`} caption="ของรายรับเดือนนี้" />
              <Figure label="ใบเสร็จเกิน 500,000" value={`${d.big} ใบ`} caption={`ใบเสร็จกลาง ๆ ${formatMoney(d.med)}`} />
            </FigureBand>
          </InsightPanel>
        </div>
      </div>
      <CustomerList id="mtopc" testId="insight-customers-month" title={`ลูกค้ารายใหญ่ของเดือน ${K.months[sel]}`} side={`${d.topc?.length ?? 0} อันดับแรก`} rows={d.topc} total={d.total} />
    </div>
  );
}

/** The month in progress, from the weekly e-mails: forecast vs what has been received so far. */
export function NowDetail({ now }) {
  const rows = Array.isArray(now.rows) ? now.rows : [];
  const reported = rows.filter((r) => r.recv != null);
  const fc = reported.reduce((s, r) => s + r.fc, 0);
  const rc = reported.reduce((s, r) => s + r.recv, 0);
  return (
    <div data-testid="insight-now-detail">
      <InsightPanel title={<>{now.label} <StatusBadge tone="info">ข้อมูล ณ {now.asof}</StatusBadge></>} side="จากอีเมลรายงานประจำสัปดาห์ล่าสุด" foot="ตัวเลขเดือนนี้ยังไม่ผ่านการตรวจกับไฟล์ค่าคอม">
        <FigureBand>
          <Figure lead label="รับเงินแล้ว (รวม VAT)" value={formatMoney(rc)} caption={`${reported.length} คนที่รายงานตัวเลข`} />
          <Figure label="ประมาณการทั้งเดือน" value={formatMoney(fc)} caption="ตามที่เซลส์ประมาณเอง" />
          <Figure label="รับแล้วต่อประมาณการ" value={pctOf(rc, fc)} caption="เฉพาะคนที่รายงาน" />
          <Figure label="ยังต้องรับอีก" value={formatMoney(fc - rc)} caption="ถ้าจะถึงประมาณการ" />
        </FigureBand>
        <InsightTable testId="insight-now-table" cols={[['พนักงานขาย'], ['ประมาณการทั้งเดือน', true], ['รับแล้ว', true], ['ความคืบหน้า'], ['%', true]]}>
          {rows.map((r, i) => (r.recv != null ? (
            <tr key={`${i}-${r.rep}`}>
              <Td><b>{r.rep}</b></Td><Td right>{formatMoney(r.fc)}</Td><Td right>{formatMoney(r.recv)}</Td>
              <Td><ProgressBar part={r.recv} whole={r.fc} label={`${r.rep}: ${pctOf(r.recv, r.fc)}`} /></Td>
              <Td right>{ratio(r.recv, r.fc) != null && ratio(r.recv, r.fc) < 0.1 ? <StatusBadge tone="danger">{pctOf(r.recv, r.fc, 1)}</StatusBadge> : pctOf(r.recv, r.fc)}</Td>
            </tr>
          ) : (
            <tr key={`${i}-${r.rep}`}>
              <Td><b>{r.rep}</b></Td><Td right>{formatMoney(r.fc)}</Td>
              <Td colSpan={3} className="text-text-muted">ยังไม่มียอดที่รับแล้วในรายงาน</Td>
            </tr>
          )))}
        </InsightTable>
      </InsightPanel>
    </div>
  );
}
