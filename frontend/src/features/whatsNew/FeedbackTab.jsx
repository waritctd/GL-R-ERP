import { useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatThaiDate } from '../../utils/format.js';
import { FEEDBACK_AREAS, FEEDBACK_KINDS, PROGRESS_STEPS, areaForPath, statusView } from './feedbackMeta.js';
import { getLastPage } from './lastPage.js';

/** Characters, not UTF-16 units: matches the server and Postgres char_length (an emoji counts once). */
export const charCount = (text) => [...text].length;
const clip = (text, max) => [...text].slice(0, max).join('');

export const isoDateLabel = (iso) => (iso ? formatThaiDate(`${iso}T00:00`) : 'ยังไม่กำหนด');
const areaLabel = (code) => FEEDBACK_AREAS.find(([id]) => id === code)?.[1] ?? code;
const when = (iso) => new Date(iso).toLocaleString('th-TH', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });

/** 4-step progress for an accepted request. */
export function Progress({ status }) {
  const index = Math.max(0, PROGRESS_STEPS.findIndex(([id]) => id === status));
  return (
    <ol className="m-0 flex list-none flex-wrap gap-1.5 p-0 text-xs"
      aria-label={`ความคืบหน้า ขั้นที่ ${index + 1} จาก 4: ${PROGRESS_STEPS[index][1]}`}>
      {PROGRESS_STEPS.map(([id, label], n) => (
        <li key={id} aria-current={n === index ? 'step' : undefined}
          className={`rounded-pill border px-2.5 py-1 font-bold ${n === index ? 'border-primary bg-primary text-surface'
            : n < index ? 'border-border bg-surface-muted text-text-secondary' : 'border-border text-text-muted'}`}>
          {label}
        </li>
      ))}
    </ol>
  );
}

function MyRow({ item }) {
  const view = statusView(item.status);
  const accepted = !['PENDING', 'DENIED'].includes(item.status);
  return (
    <li className="grid gap-1.5 rounded-lg border border-border bg-surface p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-xs text-text-muted">
          <b className="text-text">{FEEDBACK_KINDS[item.kind].title}</b> · #{item.id} · {areaLabel(item.area)}
        </span>
        <StatusBadge tone={view.tone}>{view.label}</StatusBadge>
      </div>
      <p className="m-0 whitespace-pre-wrap break-words">{item.details}</p>
      <p className="m-0 text-xs text-text-muted">แจ้งเมื่อ {when(item.createdAt)}</p>
      {accepted ? (
        <>
          <p className="m-0 text-sm"><b>กำหนดเสร็จ:</b> {isoDateLabel(item.eta)}</p>
          <Progress status={item.status} />
        </>
      ) : null}
      {item.status === 'DENIED' ? (
        <p className="m-0 rounded-md bg-surface-muted px-3 py-2 text-sm"><b>เหตุผลที่ไม่รับ:</b> {item.denyReason}</p>
      ) : null}
    </li>
  );
}

/** Staff view of แจ้งปัญหา / ขอฟีเจอร์: the form, and their own submissions with status. */
export function FeedbackTab({ showToast }) {
  const queryClient = useQueryClient();
  const [kind, setKind] = useState('BUG');
  const [area, setArea] = useState(() => areaForPath(getLastPage()));
  const [details, setDetails] = useState('');
  const [extra, setExtra] = useState('');
  const [impact, setImpact] = useState('');
  const [errors, setErrors] = useState({});
  const [sentId, setSentId] = useState(null);
  const areaRef = useRef(null);
  const detailsRef = useRef(null);
  const meta = FEEDBACK_KINDS[kind];

  const mineQuery = useQuery({ queryKey: queryKeys.feedbackMine(), queryFn: () => api.feedback.listMine() });
  const mine = Array.isArray(mineQuery.data) ? mineQuery.data : [];

  const submit = useMutation({
    mutationFn: (payload) => api.feedback.submit(payload),
    onSuccess: (saved) => {
      setSentId(saved.id);
      setDetails('');
      setExtra('');
      setImpact('');
      queryClient.invalidateQueries({ queryKey: queryKeys.feedbackMine() });
    },
    onError: (error) => showToast?.('error', error.message || 'ส่งไม่สำเร็จ'),
  });

  function changeKind(next) {
    setKind(next);
    setImpact('');
    setErrors({});
    setSentId(null);
  }

  function onSubmit(event) {
    event.preventDefault();
    const next = {};
    if (!area) next.area = 'เลือกหน้าที่เกี่ยวข้อง ถ้าไม่แน่ใจเลือก "อื่น ๆ / ไม่แน่ใจ"';
    if (charCount(details.trim()) < 10) next.details = 'เล่าอย่างน้อยหนึ่งประโยค เพื่อให้คนรับเรื่องเข้าใจ';
    setErrors(next);
    if (next.area || next.details) {
      setSentId(null);
      (next.area ? areaRef : detailsRef).current?.focus();
      return;
    }
    submit.mutate({
      kind, area, details: details.trim(), extra: extra.trim() || null, impact: impact || null,
      pagePath: getLastPage(),
      userAgent: typeof navigator === 'undefined' ? null : navigator.userAgent,
      viewport: typeof window === 'undefined' ? null : `${window.innerWidth}x${window.innerHeight}`,
    });
  }

  return (
    <div className="grid items-start gap-4 lg:grid-cols-2">
      <SafeForm onSubmit={onSubmit} noValidate className="grid gap-4 rounded-lg border border-border bg-surface p-4">
        {sentId ? (
          <div role="status" className="flex gap-2 rounded-md bg-surface-muted px-3 py-2.5 text-sm">
            <Icon name="check" aria-hidden="true" />
            <span>ส่งแล้ว เลขที่ #{sentId} แอดมินได้รับแจ้งเตือนแล้ว จะรับเรื่องพร้อมกำหนดเสร็จ หรือไม่รับพร้อมเหตุผล ดูได้ที่ &quot;ที่ฉันแจ้ง&quot; ด้านข้าง</span>
          </div>
        ) : null}
        <fieldset className="m-0 grid min-w-0 gap-2 border-0 p-0">
          <legend className="mb-1 p-0 text-sm font-bold text-text-secondary">เรื่องอะไร</legend>
          <div className="grid gap-2 sm:grid-cols-2">
            {Object.entries(FEEDBACK_KINDS).map(([id, k]) => (
              <label key={id} className="grid min-h-11 cursor-pointer grid-cols-[auto_1fr] items-start gap-x-2 rounded-md border border-border p-3 has-[:checked]:border-primary has-[:checked]:bg-surface-muted">
                <input type="radio" name="kind" value={id} checked={kind === id} onChange={() => changeKind(id)} className="mt-1" />
                <b className="text-sm">{k.title}</b>
                <span className="col-start-2 text-sm text-text-muted">{k.desc}</span>
              </label>
            ))}
          </div>
        </fieldset>
        <FormField label="เกี่ยวกับหน้าไหน" htmlFor="feedback-area" error={errors.area}
          hint={area ? 'เลือกให้แล้วจากหน้าที่เปิดล่าสุด เปลี่ยนได้' : undefined}>
          <select id="feedback-area" ref={areaRef} value={area} onChange={(e) => setArea(e.target.value)}>
            <option value="">เลือกหน้า</option>
            {FEEDBACK_AREAS.map(([id, label]) => <option key={id} value={id}>{label}</option>)}
          </select>
        </FormField>
        <FormField label={meta.q1} htmlFor="feedback-details" hint={meta.hint} error={errors.details}>
          <textarea id="feedback-details" ref={detailsRef} className="min-h-28" value={details}
            onChange={(e) => setDetails(clip(e.target.value, 4000))} />
        </FormField>
        <FormField label={`${meta.q2} (ไม่บังคับ)`} htmlFor="feedback-extra">
          <textarea id="feedback-extra" className="min-h-16" value={extra} onChange={(e) => setExtra(clip(e.target.value, 4000))} />
        </FormField>
        <fieldset className="m-0 grid min-w-0 gap-2 border-0 p-0">
          <legend className="mb-1 p-0 text-sm font-bold text-text-secondary">{meta.impactLabel} (ไม่บังคับ)</legend>
          {meta.impacts.map(([id, label]) => (
            <label key={id} className="flex min-h-11 cursor-pointer items-center gap-2 text-sm">
              <input type="radio" name="imp" value={id} checked={impact === id} onChange={() => setImpact(id)} />
              <span>{label}</span>
            </label>
          ))}
        </fieldset>
        <p className="m-0 text-xs text-text-muted">ระบบแนบชื่อ ฝ่าย บทบาท หน้าที่เปิดล่าสุด และขนาดจอให้อัตโนมัติ</p>
        <div className="flex flex-wrap items-center gap-3">
          <Button type="submit" loading={submit.isPending}>ส่ง</Button>
          <span className="text-xs text-text-muted">แอดมินระบบจะรับหรือไม่รับเรื่อง แล้วแจ้งกลับที่นี่</span>
        </div>
      </SafeForm>
      <section className="grid min-w-0 gap-2.5">
        <h3 className="m-0 text-base font-bold">ที่ฉันแจ้ง ({mine.length})</h3>
        {mine.length > 0 ? (
          <ul aria-label="ที่ฉันแจ้ง" className="m-0 grid list-none gap-2.5 p-0">
            {mine.map((item) => <MyRow key={item.id} item={item} />)}
          </ul>
        ) : <p className="m-0 text-sm text-text-muted">ยังไม่เคยแจ้ง</p>}
      </section>
    </div>
  );
}
