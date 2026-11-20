import { useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { ThaiDateInput } from '../../components/common/ThaiDateInput.jsx';
import { FEEDBACK_AREAS, FEEDBACK_KINDS, PROGRESS_STEPS, statusView } from './feedbackMeta.js';
import { ROLE_LABEL, withRole } from './audiences.js';
import { Progress, charCount, isoDateLabel } from './FeedbackTab.jsx';

const areaLabel = (code) => FEEDBACK_AREAS.find(([id]) => id === code)?.[1] ?? code;

/** The admin's inbox query, shared with the tab label's pending count. Only call it for the admin. */
export function useFeedbackInbox(enabled) {
  return useQuery({
    queryKey: queryKeys.feedbackAll(), queryFn: () => api.feedback.listAll(), enabled,
  });
}

function SilentToggle({ checked, onChange }) {
  return (
    <label className="flex items-center gap-2 text-sm">
      <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      ไม่ต้องแจ้งผู้ขอ (ไม่ส่งอีเมล)
    </label>
  );
}

function DecisionPanel({ item, mode, silent, onSilent, onDone, showToast }) {
  const queryClient = useQueryClient();
  const [eta, setEta] = useState('');
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const fieldRef = useRef(null);
  const decide = useMutation({
    mutationFn: (payload) => api.feedback.decide(item.id, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.feedbackAll() });
      onDone();
    },
    onError: (e) => showToast?.('error', e.message || 'บันทึกไม่สำเร็จ'),
  });
  const accept = mode === 'ACCEPT';

  function save() {
    if (accept && !eta) {
      setError('เลือกวันกำหนดเสร็จ');
    } else if (!accept && charCount(reason.trim()) < 5) {
      setError('เขียนเหตุผลให้ผู้แจ้งเข้าใจ');
    } else {
      setError('');
      decide.mutate({ ...(accept ? { decision: 'ACCEPT', eta } : { decision: 'DENY', reason: reason.trim() }), silent });
      return;
    }
    fieldRef.current?.focus();
  }

  return (
    <div className="grid gap-2.5 rounded-md bg-surface-muted p-3">
      {accept ? (
        <FormField label="กำหนดเสร็จ" htmlFor={`eta-${item.id}`} hint="ผู้แจ้งจะเห็นวันนี้ แก้ภายหลังได้" error={error}>
          <ThaiDateInput id={`eta-${item.id}`} ref={fieldRef} value={eta} onChange={(e) => setEta(e.target.value)} />
        </FormField>
      ) : (
        <FormField label="เหตุผลที่ไม่รับ" htmlFor={`why-${item.id}`} hint="ผู้แจ้งจะเห็นข้อความนี้ทั้งหมด" error={error}>
          <textarea id={`why-${item.id}`} ref={fieldRef} className="min-h-16" value={reason}
            onChange={(e) => setReason([...e.target.value].slice(0, 2000).join(''))} />
        </FormField>
      )}
      <SilentToggle checked={silent} onChange={onSilent} />
      <div className="flex flex-wrap gap-2">
        <Button onClick={save} loading={decide.isPending}>{accept ? 'ยืนยันรับเรื่อง' : 'ยืนยันไม่รับ'}</Button>
        <Button variant="secondary" onClick={onDone}>ยกเลิก</Button>
      </div>
    </div>
  );
}

/** Admin edits who asked for this request: team chips add a whole role at once, the list picks individuals. */
function RequestersPanel({ item, onDone, showToast }) {
  const queryClient = useQueryClient();
  const reporterId = item.reporterEmployeeId;
  const [selected, setSelected] = useState(() => new Set([reporterId, ...(item.requesters ?? []).map((r) => r.employeeId)]));
  const [search, setSearch] = useState('');
  const { data } = useQuery({ queryKey: queryKeys.feedbackPeople(), queryFn: () => api.feedback.people() });
  const people = Array.isArray(data) ? data : [];
  const teams = [...new Set(people.map((p) => p.role))];
  const needle = search.trim().toLowerCase();
  const shown = needle
    ? people.filter((p) => `${p.name} ${p.department ?? ''}`.toLowerCase().includes(needle))
    : people;
  const save = useMutation({
    mutationFn: () => api.feedback.setRequesters(item.id, [...selected]),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.feedbackAll() });
      onDone();
    },
    onError: (e) => showToast?.('error', e.message || 'บันทึกไม่สำเร็จ'),
  });

  function toggle(id) {
    const next = new Set(selected);
    if (!next.delete(id)) next.add(id);
    setSelected(next);
  }

  function toggleTeam(role) {
    const members = people.filter((p) => p.role === role).map((p) => p.employeeId);
    const all = members.every((id) => selected.has(id));
    const next = new Set(selected);
    members.forEach((id) => (all && id !== reporterId ? next.delete(id) : next.add(id)));
    setSelected(next);
  }

  return (
    <div className="grid gap-2.5 rounded-md bg-surface-muted p-3">
      <div className="flex flex-wrap gap-2">
        {teams.map((role) => {
          const members = people.filter((p) => p.role === role);
          const all = members.every((p) => selected.has(p.employeeId));
          return (
            <button key={role} type="button" aria-pressed={all} onClick={() => toggleTeam(role)}
              className={`min-h-11 rounded-pill border px-3 text-sm font-bold ${all ? 'border-primary bg-primary text-surface' : 'border-border-input bg-surface text-icon-muted'}`}>
              {`${withRole('ทั้ง', ROLE_LABEL[role] ?? role)} (${members.length})`}
            </button>
          );
        })}
      </div>
      <input type="search" aria-label="ค้นหาชื่อหรือฝ่าย" placeholder="ค้นหาชื่อหรือฝ่าย" value={search}
        onChange={(e) => setSearch(e.target.value)} />
      <ul className="m-0 grid max-h-[60vh] list-none gap-1 overflow-y-auto p-0">
        {shown.map((p) => (
          <li key={p.employeeId}>
            <label className="flex min-h-11 w-full items-center gap-2 rounded-md px-2 text-sm">
              <input type="checkbox" checked={selected.has(p.employeeId)} disabled={p.employeeId === reporterId}
                onChange={() => toggle(p.employeeId)} />
              <span className="min-w-0 break-words">
                {p.name}{p.department ? <span className="text-text-muted"> · {p.department}</span> : null}
                {p.employeeId === reporterId ? <span className="text-text-muted"> (ผู้แจ้ง)</span> : null}
              </span>
            </label>
          </li>
        ))}
      </ul>
      <div className="flex flex-wrap gap-2">
        <Button onClick={() => save.mutate()} loading={save.isPending}>{`บันทึก (${selected.size} คน)`}</Button>
        <Button variant="secondary" onClick={onDone}>ยกเลิก</Button>
      </div>
    </div>
  );
}

function InboxRow({ item, showToast }) {
  const queryClient = useQueryClient();
  const [mode, setMode] = useState(null);
  const [silent, setSilent] = useState(false);
  const [editing, setEditing] = useState(false);
  const view = statusView(item.status);
  const requesters = item.requesters?.length ? item.requesters : [{ employeeId: item.reporterEmployeeId, name: item.reporterName }];
  const accepted = !['PENDING', 'DENIED'].includes(item.status);
  const update = useMutation({
    mutationFn: (payload) => api.feedback.update(item.id, payload),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.feedbackAll() }),
    onError: (e) => showToast?.('error', e.message || 'บันทึกไม่สำเร็จ'),
  });

  return (
    <li className="grid gap-2 rounded-lg border border-border bg-surface p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-xs text-text-muted">
          <b className="text-text">{FEEDBACK_KINDS[item.kind].title}</b> · #{item.id} · {areaLabel(item.area)}
        </span>
        <StatusBadge tone={view.tone}>{view.label}</StatusBadge>
      </div>
      <p className="m-0 whitespace-pre-wrap break-words">{item.details}</p>
      {item.extra ? <p className="m-0 whitespace-pre-wrap break-words text-sm text-text-secondary">{item.extra}</p> : null}
      <p className="m-0 break-words text-xs text-text-muted">
        {item.reporterName} · {item.reporterDepartment ?? '-'} · {ROLE_LABEL[item.reporterRole] ?? item.reporterRole}
        {item.pagePath ? ` · หน้า ${item.pagePath}` : ''}
        {item.impact ? ` · ${FEEDBACK_KINDS[item.kind].impacts.find(([id]) => id === item.impact)?.[1] ?? ''}` : ''}
      </p>
      {requesters.length > 1 ? (
        <p className="m-0 break-words text-xs text-text-muted">ผู้ขอ ({requesters.length}): {requesters.map((r) => r.name).join(', ')}</p>
      ) : null}
      {editing ? <RequestersPanel item={item} onDone={() => setEditing(false)} showToast={showToast} /> : (
        <div><Button variant="secondary" onClick={() => setEditing(true)}>แก้ไขผู้ขอ</Button></div>
      )}
      {item.status === 'PENDING' && !mode ? (
        <div className="flex flex-wrap gap-2">
          <Button onClick={() => setMode('ACCEPT')}>รับเรื่อง</Button>
          <Button variant="secondary" onClick={() => setMode('DENY')}>ไม่รับ</Button>
        </div>
      ) : null}
      {mode ? <DecisionPanel item={item} mode={mode} silent={silent} onSilent={setSilent} onDone={() => setMode(null)} showToast={showToast} /> : null}
      {accepted ? (
        <div className="grid gap-2.5 rounded-md bg-surface-muted p-3">
          <div className="grid gap-2.5 sm:grid-cols-2">
            <FormField label="กำหนดเสร็จ" htmlFor={`eta-${item.id}`}>
              <ThaiDateInput id={`eta-${item.id}`} value={item.eta ?? ''}
                onChange={(e) => e.target.value && update.mutate({ eta: e.target.value, silent })} />
            </FormField>
            <FormField label="ความคืบหน้า" htmlFor={`pg-${item.id}`}>
              <select id={`pg-${item.id}`} value={item.status} onChange={(e) => update.mutate({ status: e.target.value, silent })}>
                {PROGRESS_STEPS.map(([id, label], n) => <option key={id} value={id}>{`${n + 1}. ${label}`}</option>)}
              </select>
            </FormField>
          </div>
          <SilentToggle checked={silent} onChange={setSilent} />
          <Progress status={item.status} />
        </div>
      ) : null}
      {item.status === 'DENIED' ? (
        <p className="m-0 rounded-md bg-surface-muted px-3 py-2 text-sm"><b>เหตุผลที่ไม่รับ:</b> {item.denyReason}</p>
      ) : null}
      {!accepted && item.eta ? <p className="m-0 text-sm">กำหนดเสร็จ {isoDateLabel(item.eta)}</p> : null}
    </li>
  );
}

/** Admin view: every submission, decide the PENDING ones, move accepted ones along. Visibility is a UI hint. */
export function FeedbackInbox({ showToast }) {
  const { data } = useFeedbackInbox(true);
  const items = Array.isArray(data) ? data : [];
  return (
    <section className="grid gap-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="m-0 text-base font-bold">เรื่องที่แจ้งเข้ามา ({items.length})</h3>
        <span className="text-xs text-text-muted">เห็นเฉพาะแอดมินระบบ</span>
      </div>
      <ul className="m-0 grid list-none gap-2.5 p-0">
        {items.map((item) => <InboxRow key={item.id} item={item} showToast={showToast} />)}
      </ul>
    </section>
  );
}
