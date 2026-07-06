import { useEffect, useRef, useState } from 'react';
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
  const [error, setError] = useState('');
  const [newPasswordError, setNewPasswordError] = useState('');

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
    setError('');
    setNewPasswordError('');
    if (newPassword.length < 8) {
      setNewPasswordError('รหัสผ่านใหม่ต้องมีอย่างน้อย 8 ตัวอักษร');
      return;
    }
    if (newPassword !== confirmPassword) {
      setError('รหัสผ่านใหม่และการยืนยันไม่ตรงกัน');
      return;
    }
    if (newPassword === currentPassword) {
      setError('รหัสผ่านใหม่ต้องไม่ซ้ำกับรหัสผ่านเดิม');
      return;
    }
    try {
      await onSubmit({ currentPassword, newPassword });
    } catch (submitError) {
      setError(submitError?.message || 'เปลี่ยนรหัสผ่านไม่สำเร็จ');
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
            <button type="button" className="icon-button" onClick={onClose} title="ปิด" aria-label="ปิด">
              <Icon name="close" />
            </button>
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
              error={newPasswordError}
              hint={!newPasswordError ? 'อย่างน้อย 8 ตัวอักษร' : undefined}
            >
              <input
                id="change-password-new"
                type="password"
                value={newPassword}
                onChange={(event) => setNewPassword(event.target.value)}
                autoComplete="new-password"
                minLength={8}
                className={newPasswordError ? 'is-invalid' : ''}
                aria-invalid={Boolean(newPasswordError)}
                aria-describedby={newPasswordError ? fieldErrorId('change-password-new') : undefined}
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
            {error ? <div className="form-error" role="alert">{error}</div> : null}
          </form>
        </div>
        <footer className="modal-footer">
          {forced ? (
            <button type="button" className="secondary-button" onClick={onLogout}>ออกจากระบบ</button>
          ) : (
            <button type="button" className="secondary-button" onClick={onClose}>ยกเลิก</button>
          )}
          <button type="submit" form="change-password-form" className="primary-button" disabled={loading}>
            <Icon name="check" />
            {loading ? 'กำลังบันทึก...' : 'บันทึกรหัสผ่าน'}
          </button>
        </footer>
      </section>
    </div>
  );
}
