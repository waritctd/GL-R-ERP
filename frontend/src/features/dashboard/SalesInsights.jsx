import { Fragment, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { ErrorBoundary } from '../../components/common/ErrorBoundary.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { MonthDetail, MonthPicker, NowDetail } from './insight/insightMonth.jsx';
import { isUsable } from './insight/insightFormat.js';
import { YearPart } from './insight/insightYear.jsx';

// Mirrors SalesAnalyticsService.MAX_BYTES, so an oversize file is refused before any request is made.
const MAX_UPLOAD_BYTES = 2 * 1024 * 1024;

/** CEO-only: reads a snapshot JSON file in the browser and sends the parsed document. */
function UploadControl({ onError, onDone }) {
  const queryClient = useQueryClient();
  async function handle(event) {
    const input = event.target;
    const file = input.files?.[0];
    if (!file) return;
    if (file.size > MAX_UPLOAD_BYTES) {
      onError('ไฟล์ใหญ่เกิน 2 MB');
      input.value = '';
      return;
    }
    let document;
    try {
      document = JSON.parse(await file.text());
      if (!document || typeof document !== 'object' || Array.isArray(document)) throw new Error('not an object');
    } catch {
      onError('อ่านไฟล์ไม่ได้ ไฟล์ต้องเป็น JSON ของข้อมูลวิเคราะห์');
      return;
    } finally {
      input.value = '';
    }
    try {
      await api.salesAnalytics.upload(document);
      onError('');
      onDone('อัปโหลดข้อมูลวิเคราะห์แล้ว');
      await queryClient.invalidateQueries({ queryKey: queryKeys.salesAnalyticsSnapshot() });
    } catch (error) {
      onError(error?.message || 'อัปโหลดไม่สำเร็จ');
    }
  }
  return (
    <label className="inline-flex min-h-[38px] cursor-pointer items-center rounded-md border border-border-input bg-surface px-[13px] text-sm font-bold text-icon-muted focus-within:outline focus-within:outline-2 focus-within:outline-primary">
      อัปโหลดข้อมูลวิเคราะห์
      <input type="file" accept="application/json,.json" className="sr-only" onChange={handle} />
    </label>
  );
}

/** The "วิเคราะห์ยอดขาย" view of the CEO / sales-manager landing page: a stored analytics snapshot. */
export function SalesInsights({ user, showToast }) {
  const isCeo = user?.role === 'ceo';
  const query = useQuery({ queryKey: queryKeys.salesAnalyticsSnapshot(), queryFn: () => api.salesAnalytics.snapshot() });
  const stored = query.data?.snapshot?.payload ?? null;
  const asOf = query.data?.snapshot?.asOf;
  const uploadedAt = query.data?.snapshot?.uploadedAt;
  // A stored document the page cannot draw (no months, malformed month entries) reads as "no data", never a crash.
  const K = isUsable(stored) ? stored : null;
  const [picked, setPicked] = useState(null);
  const [section, setSection] = useState('all');
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  const last = K ? K.months.length - 1 : 0;
  const sel = picked === 'now' && K?.now ? 'now' : picked != null && picked >= 0 && picked <= last ? picked : last;
  const done = (text) => { setMessage(text); showToast?.('success', text); };

  return (
    <div className="grid gap-[18px]" data-testid="sales-insights">
      {isCeo || message || error || asOf ? (
        <div className="flex flex-wrap items-center gap-3">
          {isCeo ? <UploadControl onError={setError} onDone={done} /> : null}
          {error ? <span role="alert" className="text-sm text-danger">{error}</span> : null}
          {message && !error ? <span role="status" className="text-sm text-text-muted">{message}</span> : null}
          {asOf ? <small className="text-xs text-text-muted">ข้อมูล ณ {asOf}</small> : null}
        </div>
      ) : null}
      {query.isLoading ? <Skeleton height={160} /> : null}
      {query.isError && !query.data ? <p role="alert" className="m-0 text-sm text-danger">โหลดข้อมูลวิเคราะห์ไม่สำเร็จ</p> : null}
      {!query.isLoading && !query.isError && !K ? (
        <section data-testid="insight-empty" className="rounded-md border border-border bg-surface p-5 text-sm text-text-muted">
          {stored ? 'ข้อมูลวิเคราะห์ที่อัปโหลดไว้อ่านไม่ได้' : 'ยังไม่มีข้อมูลวิเคราะห์'}{isCeo ? ' — อัปโหลดไฟล์ข้อมูลวิเคราะห์ประจำเดือนเพื่อเริ่มใช้งาน' : ' — รอผู้บริหารอัปโหลดข้อมูล'}
        </section>
      ) : null}
      {K ? (
        // Keyed on the upload: a re-upload remounts every boundary below, so a snapshot that failed can recover.
        <Fragment key={uploadedAt}>
          <MonthPicker K={K} sel={sel} onSelect={setPicked} />
          {/* Keyed on the selection too: picking another month remounts the detail, so a malformed month can be left. */}
          <ErrorBoundary key={sel} fallback={() => <p role="alert" className="m-0 text-sm text-danger">แสดงเดือนนี้ไม่ได้ เลือกเดือนอื่น หรืออัปโหลดไฟล์ใหม่</p>}>
            {sel === 'now' ? <NowDetail now={K.now} /> : <MonthDetail K={K} sel={sel} />}
          </ErrorBoundary>
          <YearPart K={K} role={user?.role} section={section} onSection={setSection} />
        </Fragment>
      ) : null}
    </div>
  );
}
