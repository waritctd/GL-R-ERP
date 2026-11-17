import { Fragment, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { ErrorBoundary } from '../../components/common/ErrorBoundary.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { formatThaiDate } from '../../utils/format.js';
import { MonthDetail, MonthPicker, NowDetail } from './insight/insightMonth.jsx';
import { isUsable } from './insight/insightFormat.js';
import { YearPart } from './insight/insightYear.jsx';

/** The "วิเคราะห์ยอดขาย" view of the CEO / sales-manager landing page: a stored analytics snapshot. */
export function SalesInsights({ user }) {
  const query = useQuery({ queryKey: queryKeys.salesAnalyticsSnapshot(), queryFn: () => api.salesAnalytics.snapshot(),
    // An open tab should pick up the weekly refresh: the app default is no focus refetch.
    refetchOnWindowFocus: true,
    staleTime: 10 * 60_000,
  });
  const stored = query.data?.snapshot?.payload ?? null;
  const asOf = query.data?.snapshot?.asOf;
  const uploadedAt = query.data?.snapshot?.uploadedAt;
  // A stored document the page cannot draw (no months, malformed month entries) reads as "no data", never a crash.
  const K = isUsable(stored) ? stored : null;
  const [picked, setPicked] = useState(null);
  const [section, setSection] = useState('all');

  const last = K ? K.months.length - 1 : 0;
  const sel = picked === 'now' && K?.now ? 'now' : picked != null && picked >= 0 && picked <= last ? picked : last;

  return (
    <div className="grid gap-[18px]" data-testid="sales-insights">
      {asOf || uploadedAt ? (
        <small className="text-xs text-text-muted" data-testid="insight-updated">
          {[asOf ? `ข้อมูล ณ ${asOf}` : null, uploadedAt ? `อัปเดตล่าสุด ${formatThaiDate(uploadedAt)}` : null].filter(Boolean).join(' · ')}
        </small>
      ) : null}
      {query.isLoading ? <Skeleton height={160} /> : null}
      {query.isError && !K ? <p role="alert" className="m-0 text-sm text-danger">โหลดข้อมูลวิเคราะห์ไม่สำเร็จ</p> : null}
      {!query.isLoading && !query.isError && !K ? (
        <section data-testid="insight-empty" className="rounded-md border border-border bg-surface p-5 text-sm text-text-muted">
          {stored ? 'ข้อมูลวิเคราะห์ที่มีอยู่อ่านไม่ได้' : 'ยังไม่มีข้อมูลวิเคราะห์'} — ข้อมูลอัปเดตทุกสัปดาห์
        </section>
      ) : null}
      {K ? (
        // Keyed on the snapshot: a newer one remounts every boundary below, so a snapshot that failed can recover.
        <Fragment key={uploadedAt}>
          <MonthPicker K={K} sel={sel} onSelect={setPicked} />
          {/* Keyed on the selection too: picking another month remounts the detail, so a malformed month can be left. */}
          <ErrorBoundary key={sel} fallback={() => <p role="alert" className="m-0 text-sm text-danger">แสดงเดือนนี้ไม่ได้{K.months.length > 1 || K.now ? ' เลือกเดือนอื่น' : ''}</p>}>
            {sel === 'now' ? <NowDetail now={K.now} /> : <MonthDetail K={K} sel={sel} />}
          </ErrorBoundary>
          <YearPart K={K} role={user?.role} section={section} onSection={setSection} />
        </Fragment>
      ) : null}
    </div>
  );
}
