import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack } from '../../components/common/Layout.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { Tabs } from '../../components/common/Tabs.jsx';
import { formatThaiDate } from '../../utils/format.js';
import { cn } from '../../utils/cn.js';
import { Figure } from '../dashboard/dashboardParts.jsx';
import { Bars, FigureBand, InsightPanel, InsightTable, Pad, ProgressBar, Small, Td } from '../dashboard/insight/insightParts.jsx';
import { barWidth, pctOf } from '../dashboard/insight/insightFormat.js';
import { MONTH_LABELS, latestMonthIndex, monthKey, monthTotals, personRows, summarise, teamRows } from './peopleInsightsAggregate.js';

const days = (v) => (v ? `${Number(v.toFixed(2))}` : '0');
const clock = (mins) => (mins == null ? '—' : `${String(Math.floor(mins / 60)).padStart(2, '0')}:${String(Math.round(mins % 60)).padStart(2, '0')}`);
const EXEMPT_NOTE = 'พนักงานขายนอกสถานที่ · ไม่นับเวลาเข้างาน';

function MonthColumns({ totals, year, sel, onSelect }) {
  const max = Math.max(0, ...totals);
  return (
    <InsightPanel title={`วันลารายเดือน ปี ${year + 543}`} side="กดแท่งเพื่อดูเดือนนั้น" foot="นับเฉพาะใบลาที่อนุมัติแล้ว หน่วยเป็นวัน">
      <Pad>
        <div className="flex h-40 items-end gap-1.5">
          {totals.map((v, i) => (
            <button key={MONTH_LABELS[i]} type="button" aria-pressed={sel === i} aria-label={`${MONTH_LABELS[i]} ${days(v)} วัน`} title={`${MONTH_LABELS[i]}: ${days(v)} วัน`} onClick={() => onSelect(i)} className="group flex h-full min-w-0 flex-1 items-end border-0 bg-transparent p-0">
              <span aria-hidden="true" className={cn('insight-grow-y block w-full rounded-t', sel === i ? 'bg-primary' : 'bg-primary/40 group-hover:bg-primary/60')} style={{ height: `${barWidth(v, max)}%` }} />
            </button>
          ))}
        </div>
        <div className="mt-1 flex gap-1.5 text-center text-2xs text-text-muted">
          {totals.map((_, i) => <span key={MONTH_LABELS[i]} className={cn('min-w-0 flex-1', sel === i && 'font-extrabold text-primary')}>{MONTH_LABELS[i]}</span>)}
        </div>
      </Pad>
    </InsightPanel>
  );
}

function Findings({ rows, year }) {
  if (!rows.length) return null;
  return (
    <InsightPanel title="สิ่งที่ควรดู" side={`ตั้งแต่ต้นปี ${year + 543}`}>
      <ul className="m-0 list-none p-0">
        {rows.map(([dot, head, detail]) => (
          <li key={head} className="flex gap-3 border-b border-border-subtle px-5 py-3 last:border-b-0 mobile:px-3.5">
            <span aria-hidden="true" className={cn('mt-1.5 size-2 shrink-0 rounded-full', dot)} />
            <div className="min-w-0"><strong className="block text-sm">{head}</strong><span className="text-xs text-text-muted">{detail}</span></div>
          </li>
        ))}
      </ul>
    </InsightPanel>
  );
}

function buildFindings(people, year, quota) {
  const out = [];
  people.filter((p) => p.scanDays >= 20 && p.late / p.scanDays >= 0.3).sort((a, b) => b.late / b.scanDays - a.late / a.scanDays).slice(0, 3)
    .forEach((p) => out.push(['bg-danger', `${p.nick} มาสาย ${p.late} จาก ${p.scanDays} วันที่สแกน`, `รวม ${p.lateMin} นาที · เข้างานเฉลี่ย ${clock(p.avgIn)}`]));
  [['PERSONAL', 'ลากิจ'], ['SICK', 'ลาป่วย']].forEach(([code, name]) => {
    if (quota[code] == null) return;
    people.filter((p) => (p.quotaUsed[code] || 0) > quota[code])
      .forEach((p) => out.push(['bg-warning', `${p.nick} ${name}เกินสิทธิ์`, `ใช้ ${days(p.quotaUsed[code])} จาก ${days(quota[code])} วัน`]));
  });
  if (year.byType.SICK) out.push(['bg-text-muted', `ลาป่วย ${pctOf(year.sickEdge, year.byType.SICK)} เริ่มวันจันทร์ ศุกร์ หรือเสาร์`, `${days(year.sickEdge)} จาก ${days(year.byType.SICK)} วัน`]);
  if (year.noOut) out.push(['bg-text-muted', `ไม่สแกนออก ${year.noOut} ครั้ง`, `จาก ${year.scanDays} วันที่สแกนเข้า`]);
  if (year.lateFiled) out.push(['bg-text-muted', `ส่งใบลาหลังวันลา ${year.lateFiled} ใบ`, 'ไม่นับลาป่วย และรายการที่ฝ่ายบุคคลบันทึกย้อนหลัง']);
  return out;
}

function Report({ data, scope, onScope }) {
  const [picked, setPicked] = useState(null);
  const last = latestMonthIndex(data);
  const month = Math.min(picked ?? last, last);
  const key = monthKey(data.year, month);
  const yearBE = data.year + 543;
  const quota = Object.fromEntries(data.leaveTypes.map((t) => [t.code, Number(t.annualQuotaDays)]));
  const typeName = Object.fromEntries(data.leaveTypes.map((t) => [t.code, t.nameTh]));
  const withQuota = (name, code) => (quota[code] == null ? name : `${name} (จาก ${days(quota[code])})`);

  const cur = summarise(data, null, key);
  const prev = month > 0 ? summarise(data, null, monthKey(data.year, month - 1)) : null;
  const year = summarise(data, null, null);
  const monthPeople = personRows(data, null, key);
  const yearPeople = personRows(data, null, null);
  const exemptPeople = monthPeople.filter((p) => p.timeExempt);
  const lateFolk = monthPeople.filter((p) => p.late > 0).length;
  const leaveTop = Math.max(0, ...monthPeople.map((p) => p.leaveDays));
  const typeRows = Object.entries(cur.byType).sort((a, b) => b[1] - a[1]).map(([t, d]) => [typeName[t] ?? t, d, `${days(d)} วัน`]);
  const teams = teamRows(data);

  return (
    <PageStack>
      <PageHeader
        title="วิเคราะห์การลาและเวลาเข้างาน"
        subtitle={data.scope.label}
        actions={data.scopes.length > 1
          ? <Tabs items={data.scopes} value={scope ?? data.scope.id} onChange={onScope} ariaLabel="ขอบเขตข้อมูล" idPrefix="people-scope" onSurface />
          : null}
      />
      <div className="grid gap-[18px]" data-testid="people-insights">
        {data.asOf ? <small className="text-xs text-text-muted">ข้อมูล ณ {formatThaiDate(data.asOf)}</small> : null}
        <MonthColumns totals={monthTotals(data, null, last + 1)} year={data.year} sel={month} onSelect={setPicked} />
        <InsightPanel title={`${MONTH_LABELS[month]} ${yearBE}`} side={`${data.people.length} คน`}>
          <FigureBand>
            <Figure testId="fig-leave" lead label="วันลารวม" value={`${days(cur.leaveDays)} วัน`} caption={prev ? `${MONTH_LABELS[month - 1]} ${days(prev.leaveDays)} วัน` : 'ไม่มีเดือนก่อนหน้า'} />
            <Figure label="ลาป่วย" value={`${days(cur.byType.SICK || 0)} วัน`} caption={`${pctOf(cur.byType.SICK || 0, cur.leaveDays)} ของวันลาเดือนนี้`} />
            <Figure testId="fig-late" danger={cur.late > 0 && cur.late / cur.scanDays >= 0.1} label="มาสาย" value={`${cur.late} ครั้ง`} caption={`${lateFolk} คน · ${pctOf(cur.late, cur.scanDays)} ของวันที่สแกน`} />
            <Figure label="ไม่สแกนออก" value={`${cur.noOut} ครั้ง`} caption={`กลับก่อนเวลา ${cur.early} ครั้ง`} />
          </FigureBand>
        </InsightPanel>
        <div className="grid grid-cols-2 items-start gap-[18px] nav-drawer:grid-cols-1">
          <InsightPanel title="การลารายคน" side={MONTH_LABELS[month]}>
            <InsightTable cols={[['พนักงาน'], ['ป่วย', true], ['กิจ', true], ['พักร้อน', true], ['รวม (วัน)', true], ['สัดส่วน']]}>
              {monthPeople.filter((p) => p.leaveDays > 0).sort((a, b) => b.leaveDays - a.leaveDays).map((p) => (
                <tr key={p.employeeId}>
                  <Td><b>{p.nick}</b><Small>{p.code}</Small></Td>
                  <Td right>{days(p.byType.SICK || 0)}</Td><Td right>{days(p.byType.PERSONAL || 0)}</Td><Td right>{days(p.byType.VACATION || 0)}</Td>
                  <Td right><b>{days(p.leaveDays)}</b></Td>
                  <Td><ProgressBar part={p.leaveDays} whole={leaveTop} label={`${p.nick}: ${days(p.leaveDays)} วัน`} /></Td>
                </tr>
              ))}
              {cur.leaveDays === 0 ? <tr><Td colSpan={6} className="text-text-muted">ไม่มีการลาในเดือนนี้</Td></tr> : null}
            </InsightTable>
          </InsightPanel>
          <InsightPanel title="เวลาเข้างานรายคน" side={MONTH_LABELS[month]} foot="สาย = เข้างานหลังเวลาผ่อนผัน 15 นาทีของตารางงาน · เวลาเฉลี่ยนับเฉพาะวันที่สแกนเข้าก่อนเที่ยง · พนักงานขายนอกสถานที่สแกนวันละครั้ง จึงไม่นับสายและไม่นับการสแกนออก">
            <InsightTable testId="checkin-table" cols={[['พนักงาน'], ['วันที่สแกน', true], ['เข้างานเฉลี่ย', true], ['สาย (ครั้ง)', true], ['นาทีรวม', true]]}>
              {monthPeople.filter((p) => p.scanDays > 0).sort((a, b) => b.late - a.late || a.nick.localeCompare(b.nick, 'th')).map((p) => (
                <tr key={p.employeeId}>
                  <Td><b>{p.nick}</b><Small>{p.code}</Small></Td>
                  <Td right>{p.scanDays}</Td><Td right>{clock(p.avgIn)}</Td>
                  <Td right>{p.late ? <StatusBadge tone={p.late / p.scanDays >= 0.3 ? 'danger' : 'warning'}>{p.late}</StatusBadge> : '0'}</Td>
                  <Td right>{p.lateMin || '—'}</Td>
                </tr>
              ))}
              {exemptPeople.map((p) => (
                <tr key={p.employeeId}>
                  <Td><b>{p.nick}</b><Small>{p.code}</Small></Td>
                  <Td colSpan={4} className="text-right text-text-muted">{EXEMPT_NOTE}</Td>
                </tr>
              ))}
              {cur.scanDays === 0 && !exemptPeople.length ? <tr><Td colSpan={5} className="text-text-muted">ไม่มีข้อมูลสแกนในเดือนนี้</Td></tr> : null}
            </InsightTable>
          </InsightPanel>
        </div>
        {typeRows.length ? <InsightPanel title="วันลาตามประเภท" side={MONTH_LABELS[month]}><Pad><Bars rows={typeRows} /></Pad></InsightPanel> : null}
        <Findings rows={buildFindings(yearPeople, year, quota)} year={data.year} />
        {teams.length ? (
          <InsightPanel title="เทียบตามสายงาน" side={`ตั้งแต่ต้นปี ${yearBE}`} foot="สายงาน = ผู้ใต้บังคับบัญชาทุกระดับตามช่อง “รายงานต่อ” ในทะเบียนพนักงาน">
            <InsightTable cols={[['หัวหน้า / ทีม'], ['จำนวนคน', true], ['วันลาต่อคน', true], ['ลาป่วยต่อคน', true], ['มาสาย', true], ['% วันที่สาย', true]]}>
              {teams.map((t) => (
                <tr key={t.id}>
                  <Td><b>{t.label}</b><Small>{t.heads.join(' + ')}</Small></Td><Td right>{t.n}</Td>
                  <Td right>{t.n ? days(t.leaveDays / t.n) : '—'}</Td><Td right>{t.n ? days((t.byType.SICK || 0) / t.n) : '—'}</Td>
                  <Td right>{t.late}</Td><Td right>{pctOf(t.late, t.scanDays)}</Td>
                </tr>
              ))}
            </InsightTable>
          </InsightPanel>
        ) : null}
        <InsightPanel title="สิทธิ์ลาที่ใช้ไป" side={`ปี ${yearBE}`} foot="ลาพักร้อนแสดงเฉพาะวันที่ใช้ เพราะสิทธิ์แต่ละคนรวมยอดยกมาไม่เท่ากัน">
          <InsightTable testId="quota-table" cols={[['พนักงาน'], [withQuota('ลาป่วย', 'SICK'), true], [withQuota('ลากิจ', 'PERSONAL'), true], ['ลาพักร้อน', true], ['ไม่รับค่าจ้าง', true], ['มาสายทั้งปี', true]]}>
            {[...yearPeople].sort((a, b) => b.leaveDays - a.leaveDays).map((p) => (
              <tr key={p.employeeId}>
                <Td><b>{p.nick}</b><Small>{p.code}</Small></Td>
                {['SICK', 'PERSONAL'].map((t) => (
                  <Td key={t} right>{quota[t] != null && (p.quotaUsed[t] || 0) > quota[t] ? <StatusBadge tone="danger">{days(p.quotaUsed[t])}</StatusBadge> : days(p.quotaUsed[t] || 0)}</Td>
                ))}
                <Td right>{days(p.quotaUsed.VACATION || 0)}</Td><Td right>{days(p.unpaid)}</Td><Td right>{p.timeExempt ? '—' : p.late}</Td>
              </tr>
            ))}
          </InsightTable>
        </InsightPanel>
      </div>
    </PageStack>
  );
}

/** วิเคราะห์การลาและเวลาเข้างาน: ceo/hr see the whole company or any team, a team head only their team (the server decides). */
export function PeopleInsightsPage() {
  const [scope, setScope] = useState(undefined);
  const query = useQuery({
    queryKey: queryKeys.peopleInsights(scope),
    queryFn: () => api.peopleInsights.get(scope ? { scope } : {}),
    placeholderData: keepPreviousData,
    retry: (count, error) => error?.status !== 403 && count < 1,
  });

  if (query.error?.status === 403) {
    return (
      <PageStack>
        <PageHeader title="วิเคราะห์การลาและเวลาเข้างาน" />
        <section data-testid="people-insights-no-access" className="rounded-md border border-border bg-surface p-5 text-sm text-text-muted">คุณไม่มีสิทธิ์ดูหน้านี้</section>
      </PageStack>
    );
  }
  if (query.isError && !query.data) {
    return (
      <PageStack>
        <PageHeader title="วิเคราะห์การลาและเวลาเข้างาน" />
        <p role="alert" className="m-0 text-sm text-danger">โหลดข้อมูลไม่สำเร็จ</p>
      </PageStack>
    );
  }
  if (!query.data) {
    return (
      <PageStack>
        <PageHeader title="วิเคราะห์การลาและเวลาเข้างาน" />
        <div data-testid="people-insights-loading" className="grid gap-[18px]"><Skeleton height={200} /><Skeleton height={120} /><Skeleton height={240} /></div>
      </PageStack>
    );
  }
  return <Report data={query.data} scope={scope} onScope={setScope} />;
}
