import { useEffect, useRef, useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { FormField, fieldErrorId } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';

const FOCUSABLE = 'a[href], button:not([disabled]), textarea, input, select, [tabindex]:not([tabindex="-1"])';

/**
 * Self-service password change. Used in two modes:
 *  - forced: shown as a blocking gate when the session principal has
 *    mustChangePassword = true (no dismiss; only "change" or "logout").
 *  - optional: dismissable, launched voluntarily by the user.
 */
export function ChangePasswordModal({ forced = false, loading = false, onSubmit, onClose, onLogout }) {
  const panelRef = useRef(null);
  const previouslyFocused = useRef(null);
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [submitError, setSubmitError] = useState('');

  const newPasswordTooShort = newPassword.length > 0 && newPassword.length < 8;
  const passwordMismatch = confirmPassword.length > 0 && newPassword.length > 0 && newPassword !== confirmPassword;
  const passwordReused = newPassword.length > 0 && currentPassword.length > 0 && newPassword === currentPassword;
  const hasValidationError = newPasswordTooShort || passwordMismatch || passwordReused;
  const formError = passwordMismatch
    ? 'รหัสผ่านใหม่และการยืนยันไม่ตรงกัน'
    : passwordReused
      ? 'รหัสผ่านใหม่ต้องไม่ซ้ำกับรหัสผ่านเดิม'
      : '';

  useEffect(() => {
    previouslyFocused.current = document.activeElement;
    const panel = panelRef.current;
    const focusables = () => Array.from(panel?.querySelectorAll(FOCUSABLE) ?? []);

    const initial = focusables();
    (initial[0] ?? panel)?.focus();

    function onKeyDown(event) {
      if (event.key === 'Escape') {
        if (!forced && onClose) {
          event.stopPropagation();
          onClose();
        }
        return;
      }
      if (event.key !== 'Tab') return;
      const items = focusables();
      if (items.length === 0) return;
      const first = items[0];
      const last = items[items.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }

    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      if (previouslyFocused.current instanceof HTMLElement) {
        previouslyFocused.current.focus();
      }
    };
  }, [forced, onClose]);

  async function submit(event) {
    event.preventDefault();
    setSubmitError('');
    if (hasValidationError) return;
    try {
      await onSubmit({ currentPassword, newPassword });
    } catch (err) {
      setSubmitError(err?.message || 'เปลี่ยนรหัสผ่านไม่สำเร็จ');
    }
  }

  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={forced ? undefined : onClose}>
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions -- dialog only stops backdrop click-through; it is not an interactive control */}
      <section
        ref={panelRef}
        className="modal-panel"
        role="dialog"
        aria-modal="true"
        aria-label="เปลี่ยนรหัสผ่าน"
        tabIndex={-1}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="modal-header">
          <div>
            <h2>เปลี่ยนรหัสผ่าน</h2>
            <p>{forced ? 'กรุณาตั้งรหัสผ่านใหม่เพื่อความปลอดภัยก่อนเริ่มใช้งาน' : 'อัปเดตรหัสผ่านของคุณ'}</p>
          </div>
          {!forced && onClose ? (
            <Button type="button" variant="icon" onClick={onClose} title="ปิด" aria-label="ปิด">
              <Icon name="close" />
            </Button>
          ) : null}
        </header>
        <div className="modal-body">
          <form id="change-password-form" className="form-grid single" onSubmit={submit}>
            <label>
              รหัสผ่านปัจจุบัน
              <input
                type="password"
                value={currentPassword}
                onChange={(event) => setCurrentPassword(event.target.value)}
                autoComplete="current-password"
                required
              />
            </label>
            <FormField
              label="รหัสผ่านใหม่"
              htmlFor="change-password-new"
              error={newPasswordTooShort ? 'รหัสผ่านใหม่ต้องมีอย่างน้อย 8 ตัวอักษร' : undefined}
              hint={!newPasswordTooShort ? 'อย่างน้อย 8 ตัวอักษร' : undefined}
            >
              <input
                id="change-password-new"
                type="password"
                value={newPassword}
                onChange={(event) => setNewPassword(event.target.value)}
                autoComplete="new-password"
                minLength={8}
                className={newPasswordTooShort ? 'is-invalid' : ''}
                aria-invalid={newPasswordTooShort}
                aria-describedby={newPasswordTooShort ? fieldErrorId('change-password-new') : undefined}
                required
              />
            </FormField>
            <label>
              ยืนยันรหัสผ่านใหม่
              <input
                type="password"
                value={confirmPassword}
                onChange={(event) => setConfirmPassword(event.target.value)}
                autoComplete="new-password"
                required
              />
            </label>
            {(formError || submitError) ? <div className="form-error" role="alert">{formError || submitError}</div> : null}
          </form>
        </div>
        <footer className="modal-footer">
          {forced ? (
            <Button type="button" variant="secondary" onClick={onLogout}>ออกจากระบบ</Button>
          ) : (
            <Button type="button" variant="secondary" onClick={onClose}>ยกเลิก</Button>
          )}
          <Button type="submit" form="change-password-form" disabled={loading || hasValidationError}>
            <Icon name="check" />
            {loading ? 'กำลังบันทึก...' : 'บันทึกรหัสผ่าน'}
          </Button>
        </footer>
      </section>
    </div>
  );
}
