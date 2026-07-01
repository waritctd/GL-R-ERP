import { useState } from 'react';
import { Icon } from '../../components/common/Icon.jsx';

/**
 * Self-service password change. Used in two modes:
 *  - forced: shown as a blocking gate when the session principal has
 *    mustChangePassword = true (no dismiss; only "change" or "logout").
 *  - optional: dismissable, launched voluntarily by the user.
 */
export function ChangePasswordModal({ forced = false, loading = false, onSubmit, onClose, onLogout }) {
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState('');

  async function submit(event) {
    event.preventDefault();
    setError('');
    if (newPassword.length < 8) {
      setError('รหัสผ่านใหม่ต้องมีอย่างน้อย 8 ตัวอักษร');
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
        className="modal-panel"
        role="dialog"
        aria-modal="true"
        aria-label="เปลี่ยนรหัสผ่าน"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="modal-header">
          <div>
            <h2>เปลี่ยนรหัสผ่าน</h2>
            <p>{forced ? 'กรุณาตั้งรหัสผ่านใหม่เพื่อความปลอดภัยก่อนเริ่มใช้งาน' : 'อัปเดตรหัสผ่านของคุณ'}</p>
          </div>
          {!forced && onClose ? (
            <button type="button" className="icon-button" onClick={onClose} title="ปิด">
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
            <label>
              รหัสผ่านใหม่
              <input
                type="password"
                value={newPassword}
                onChange={(event) => setNewPassword(event.target.value)}
                autoComplete="new-password"
                minLength={8}
                required
              />
            </label>
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
