import { useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';

/**
 * HR's two-step reset-password flow for a single employee (issue #744).
 *
 * Step 1 confirms, because the reset is destructive: `EmployeeAuthRepository#setTemporaryPassword`
 * replaces the password hash immediately, so the employee's existing password stops working at
 * once. Their current session is not what breaks — their next login is.
 *
 * Step 2 shows the plaintext temporary password. `EmployeeService#resetPassword` returns it exactly
 * once and stores only its BCrypt hash, so there is no second chance to read it: if HR loses the
 * value the only remedy is another reset. That is why this step is a blocking dialog with a copy
 * button and a loud one-time warning rather than a toast.
 *
 * Handling rules for the plaintext, all deliberate:
 *   - it lives in this component's state only, and this component is conditionally rendered, so
 *     unmounting drops it. `dismiss()` also clears it explicitly before calling `onClose`.
 *   - it is never logged, never put in a URL/query, never written to storage, and never passed to
 *     `showToast` (toast text is retained and re-rendered by the app shell).
 *   - it is never written into the react-query cache — the caller uses a plain async handler, not
 *     a `useQuery`, so nothing persists it past this dialog.
 *   - it renders as a `<code>` block rather than an `<input>`, so browser password managers are
 *     never offered a credential to save.
 */
export function ResetPasswordDialog({ employee, onConfirm, onClose }) {
  const [temporaryPassword, setTemporaryPassword] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [copied, setCopied] = useState(false);

  async function runReset() {
    setBusy(true);
    setError(null);
    try {
      const issued = await onConfirm();
      setTemporaryPassword(issued);
    } catch (err) {
      // `err.message || fallback` is the repo's established shape for surfacing an API failure, and
      // it matters here: EmployeeService throws user-facing Thai (404 "ไม่พบข้อมูลพนักงาน",
      // 403 "ไม่มีสิทธิ์เข้าถึงรายการนี้"), which a generic fallback would throw away.
      // Deliberately no console logging anywhere in this component — the success body is the
      // credential itself, and a logger here would be one refactor away from capturing it.
      setError(err?.message || 'ตั้งรหัสผ่านชั่วคราวไม่สำเร็จ');
    } finally {
      setBusy(false);
    }
  }

  function dismiss() {
    setTemporaryPassword(null);
    setCopied(false);
    setError(null);
    onClose();
  }

  async function copyPassword() {
    try {
      await navigator.clipboard.writeText(temporaryPassword);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard access fails on a non-secure context or when the user denies permission. The
      // password is already on screen and selectable, so this is recoverable — say so instead of
      // throwing, and never fall back to logging the value.
      setError('คัดลอกอัตโนมัติไม่สำเร็จ กรุณาเลือกข้อความแล้วคัดลอกด้วยตนเอง');
    }
  }

  if (temporaryPassword) {
    return (
      <Modal
        title="รหัสผ่านชั่วคราว"
        subtitle={`${employee.nameTh} · ${employee.code}`}
        onClose={dismiss}
        testId="reset-password-result"
        footer={<Button onClick={dismiss}>เสร็จสิ้น</Button>}
      >
        <div className="grid gap-4">
          <div
            className="flex items-start gap-3 rounded-md border border-warning-border bg-warning-bg p-4"
            role="alert"
          >
            <Icon name="triangleAlert" className="mt-[2px] shrink-0 text-warning-dark" />
            <div className="grid gap-1">
              <strong className="text-warning-dark">ระบบจะแสดงรหัสผ่านนี้เพียงครั้งเดียว</strong>
              <span className="text-text-secondary leading-normal">
                กรุณาคัดลอกและส่งให้พนักงานก่อนปิดหน้าต่างนี้ ระบบเก็บเฉพาะค่าที่เข้ารหัสไว้
                จึงไม่สามารถเปิดดูย้อนหลังได้ หากทำหาย ต้องตั้งรหัสผ่านชั่วคราวใหม่อีกครั้ง
              </span>
            </div>
          </div>

          <div className="grid gap-2">
            <span className="text-text-secondary text-[length:var(--text-sm)] font-bold">
              รหัสผ่านชั่วคราวของ {employee.nameTh}
            </span>
            <div className="flex flex-wrap items-center gap-3">
              <code
                className="flex-1 min-w-[220px] select-all rounded-md border border-border-input bg-surface-muted px-4 py-3 font-mono text-2xl tracking-[0.08em] break-all"
                data-testid="temporary-password-value"
              >
                {temporaryPassword}
              </code>
              <Button variant="secondary" onClick={copyPassword}>
                <Icon name={copied ? 'check' : 'clipboard'} />
                {copied ? 'คัดลอกแล้ว' : 'คัดลอก'}
              </Button>
            </div>
          </div>

          <p className="text-text-secondary leading-normal">
            พนักงานจะต้องใช้รหัสผ่านนี้เข้าสู่ระบบครั้งถัดไป
            และระบบจะบังคับให้ตั้งรหัสผ่านใหม่ทันทีก่อนใช้งานต่อ
          </p>

          {error ? (
            <p className="text-danger" role="alert">
              {error}
            </p>
          ) : null}
        </div>
      </Modal>
    );
  }

  return (
    <ConfirmDialog
      open
      tone="danger"
      busy={busy}
      title="ตั้งรหัสผ่านชั่วคราว"
      confirmLabel="ตั้งรหัสผ่านชั่วคราว"
      onConfirm={runReset}
      onCancel={dismiss}
      message={(
        <div className="grid gap-3">
          <p className="text-text-secondary leading-normal">
            ระบบจะสร้างรหัสผ่านชั่วคราวให้ <strong>{employee.nameTh}</strong> ({employee.code})
            และรหัสผ่านเดิมจะใช้ไม่ได้ทันที
          </p>
          <p className="text-text-secondary leading-normal">
            ระบบจะแสดงรหัสผ่านใหม่เพียงครั้งเดียว กรุณาเตรียมส่งให้พนักงานทันทีหลังจากกดยืนยัน
          </p>
          {error ? (
            <p className="text-danger" role="alert">
              {error}
            </p>
          ) : null}
        </div>
      )}
    />
  );
}
