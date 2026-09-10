import { useEffect, useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { Button, buttonVariants } from '../../components/common/Button.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { cn } from '../../utils/cn.js';

const MAX_SIGNATURE_BYTES = 1024 * 1024;
const ACCEPTED_TYPES = ['image/png', 'image/jpeg'];

/**
 * "ลายเซ็นสำหรับใบเสนอราคา" -- Quotation v2's approver signature (hr.employee_signature, V166,
 * QUOTATION-V2-PLAN.md), anchored into the approved PDF's ผู้อนุมัติ box. Visible only to the
 * user themself, and only when their role is ceo or sales_manager (the plan's approver roles) --
 * see ProfilePage.jsx's own gate before mounting this; there is no separate read-gate here
 * because `getSignature` returns a URL to render, not data this component fetches and could leak.
 *
 * #M1: whether a signature exists is a PROBE (`hasSignature`), never inferred from `getSignature`
 * being truthy -- `getSignature` is only a URL builder and is always truthy under mockApi's own
 * placeholder-anchor idiom, and even against the real backend an `<img>` never existing at all
 * was already a valid state the old code rendered unconditionally, showing a broken-image box
 * until the browser's own onError fired reactively. `exists` starts `null` (unknown/loading)
 * rather than assuming either way.
 */
export function SignatureCard({ user }) {
  const employeeId = user.employeeId;
  const [exists, setExists] = useState(null);
  // Bumped after a successful upload/delete so the `<img>` src carries a fresh `?t=` -- without
  // it, a REPLACED signature at the same URL can keep showing the browser's cached copy of the
  // OLD image (#M1). getSignature(id) itself never changes.
  const [cacheBust, setCacheBust] = useState(0);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!employeeId) return;
    let alive = true;
    api.employees.hasSignature(employeeId)
      .then((ok) => { if (alive) setExists(Boolean(ok)); })
      .catch(() => { if (alive) setExists(false); });
    return () => { alive = false; };
  }, [employeeId, cacheBust]);

  const uploadMutation = useMutation({
    mutationFn: (file) => api.employees.uploadSignature(employeeId, file),
    onSuccess: () => {
      setExists(true);
      setCacheBust((n) => n + 1);
      setError(null);
    },
    onError: (err) => setError(err.message || 'อัปโหลดไม่สำเร็จ'),
  });

  const deleteMutation = useMutation({
    mutationFn: () => api.employees.deleteSignature(employeeId),
    onSuccess: () => {
      setExists(false);
      setCacheBust((n) => n + 1);
      setError(null);
    },
    onError: (err) => setError(err.message || 'ลบไม่สำเร็จ'),
  });

  function onFileChange(event) {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    if (!ACCEPTED_TYPES.includes(file.type)) {
      setError('รองรับเฉพาะไฟล์ PNG หรือ JPEG');
      return;
    }
    if (file.size > MAX_SIGNATURE_BYTES) {
      setError('ขนาดไฟล์ต้องไม่เกิน 1 MB');
      return;
    }
    uploadMutation.mutate(file);
  }

  if (!employeeId) return null;

  const signatureUrl = exists ? `${api.employees.getSignature(employeeId)}?t=${cacheBust}` : null;

  return (
    <Panel title="ลายเซ็นสำหรับใบเสนอราคา">
      <div className="flex flex-wrap items-center gap-4">
        <div className="flex h-24 w-48 shrink-0 items-center justify-center rounded-md border border-dashed border-border bg-surface-subtle">
          {signatureUrl ? (
            <img
              key={signatureUrl}
              src={signatureUrl}
              alt="ลายเซ็นที่บันทึกไว้"
              className="max-h-full max-w-full object-contain"
              onError={() => setExists(false)}
            />
          ) : (
            <span className="text-2xs text-text-muted">{exists === null ? 'กำลังตรวจสอบ...' : 'ยังไม่มีลายเซ็น'}</span>
          )}
        </div>
        <div className="flex flex-col gap-2">
          <label className={cn(buttonVariants({ variant: 'secondary', size: 'sm' }), 'cursor-pointer')}>
            {uploadMutation.isPending ? 'กำลังอัปโหลด...' : 'อัปโหลดลายเซ็น'}
            <input
              type="file"
              accept="image/png,image/jpeg"
              className="sr-only"
              disabled={uploadMutation.isPending}
              onChange={onFileChange}
            />
          </label>
          {exists ? (
            <Button variant="danger" size="sm" disabled={deleteMutation.isPending} onClick={() => deleteMutation.mutate()}>
              ลบลายเซ็น
            </Button>
          ) : null}
          <p className="m-0 text-2xs text-text-muted">PNG หรือ JPEG ไม่เกิน 1 MB</p>
          {error ? <p className="m-0 text-xs font-bold text-danger" role="alert">{error}</p> : null}
        </div>
      </div>
    </Panel>
  );
}
