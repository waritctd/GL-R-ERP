import { useEffect, useRef, useState } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { useForm, useWatch } from 'react-hook-form';
import { z } from 'zod';
import { Button } from '../../components/common/Button.jsx';
import { FormField, fieldErrorId } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { FormGrid } from '../../components/common/Layout.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';

const FOCUSABLE = 'a[href], button:not([disabled]), textarea, input, select, [tabindex]:not([tabindex="-1"])';

const CURRENT_PASSWORD_REQUIRED_MESSAGE = 'กรุณาระบุรหัสผ่านปัจจุบัน';
const CONFIRM_PASSWORD_REQUIRED_MESSAGE = 'กรุณายืนยันรหัสผ่านใหม่';
const NEW_PASSWORD_TOO_SHORT_MESSAGE = 'รหัสผ่านใหม่ต้องมีอย่างน้อย 8 ตัวอักษร';
const PASSWORD_MISMATCH_MESSAGE = 'รหัสผ่านใหม่และการยืนยันไม่ตรงกัน';
const PASSWORD_REUSED_MESSAGE = 'รหัสผ่านใหม่ต้องไม่ซ้ำกับรหัสผ่านเดิม';

function defaultForm() {
  return {
    currentPassword: '',
    newPassword: '',
    confirmPassword: '',
  };
}

const changePasswordFormSchema = z.object({
  currentPassword: z.string().min(1, CURRENT_PASSWORD_REQUIRED_MESSAGE),
  newPassword: z.string(),
  confirmPassword: z.string().min(1, CONFIRM_PASSWORD_REQUIRED_MESSAGE),
}).superRefine((data, context) => {
  if (data.newPassword.length > 0 && data.newPassword.length < 8) {
    context.addIssue({
      code: z.ZodIssueCode.custom,
      path: ['newPassword'],
      message: NEW_PASSWORD_TOO_SHORT_MESSAGE,
    });
  }
  if (data.confirmPassword.length > 0 && data.newPassword.length > 0 && data.newPassword !== data.confirmPassword) {
    context.addIssue({
      code: z.ZodIssueCode.custom,
      path: ['confirmPassword'],
      message: PASSWORD_MISMATCH_MESSAGE,
    });
  }
  if (data.newPassword.length > 0 && data.currentPassword.length > 0 && data.newPassword === data.currentPassword) {
    context.addIssue({
      code: z.ZodIssueCode.custom,
      path: ['newPassword'],
      message: PASSWORD_REUSED_MESSAGE,
    });
  }
});

/**
 * Self-service password change. Used in two modes:
 *  - forced: shown as a blocking gate when the session principal has
 *    mustChangePassword = true (no dismiss; only "change" or "logout").
 *  - optional: dismissable, launched voluntarily by the user.
 */
export function ChangePasswordModal({ forced = false, loading = false, onSubmit, onClose, onLogout }) {
  const panelRef = useRef(null);
  const previouslyFocused = useRef(null);
  const [submitError, setSubmitError] = useState('');

  const {
    register,
    handleSubmit,
    control,
    formState: { errors },
  } = useForm({
    resolver: zodResolver(changePasswordFormSchema),
    defaultValues: defaultForm(),
    mode: 'onChange',
    reValidateMode: 'onChange',
  });

  const newPassword = useWatch({ control, name: 'newPassword' });
  const newPasswordTooShort = newPassword.length > 0 && newPassword.length < 8;
  const formError = errors.confirmPassword?.message === PASSWORD_MISMATCH_MESSAGE
    ? PASSWORD_MISMATCH_MESSAGE
    : errors.newPassword?.message === PASSWORD_REUSED_MESSAGE
      ? PASSWORD_REUSED_MESSAGE
      : '';
  // The confirm field can carry either the required error (shown inline) or the
  // mismatch error (shown in the form-level formError banner below); render only
  // the required message inline to avoid duplicating the mismatch message.
  const confirmPasswordFieldError = errors.confirmPassword?.message === CONFIRM_PASSWORD_REQUIRED_MESSAGE
    ? CONFIRM_PASSWORD_REQUIRED_MESSAGE
    : undefined;
  const hasValidationError = Boolean(newPasswordTooShort || formError);

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

  async function submitPassword(values) {
    setSubmitError('');
    if (hasValidationError) return;
    try {
      await onSubmit({ currentPassword: values.currentPassword, newPassword: values.newPassword });
    } catch (err) {
      setSubmitError(err?.message || 'เปลี่ยนรหัสผ่านไม่สำเร็จ');
    }
  }

  return (
    <div
      className="modal-backdrop fixed inset-0 z-50 grid place-items-center p-5 bg-overlay"
      role="presentation"
      onMouseDown={forced ? undefined : onClose}
    >
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions -- dialog only stops backdrop click-through; it is not an interactive control */}
      <section
        ref={panelRef}
        className="modal-panel flex flex-col overflow-hidden rounded-md bg-surface shadow-[var(--shadow-lg-heavy)] w-[min(720px,100%)] max-h-[min(760px,calc(100dvh-40px))]"
        role="dialog"
        aria-modal="true"
        aria-label="เปลี่ยนรหัสผ่าน"
        tabIndex={-1}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="modal-header flex items-center justify-between gap-4 px-5 py-[18px] border-b border-border">
          <div>
            <h2 className="m-0">เปลี่ยนรหัสผ่าน</h2>
            <p className="m-0 text-text-muted text-[length:var(--text-sm)]">{forced ? 'กรุณาตั้งรหัสผ่านใหม่เพื่อความปลอดภัยก่อนเริ่มใช้งาน' : 'อัปเดตรหัสผ่านของคุณ'}</p>
          </div>
          {!forced && onClose ? (
            <Button type="button" variant="icon" onClick={onClose} title="ปิด" aria-label="ปิด">
              <Icon name="close" />
            </Button>
          ) : null}
        </header>
        <div className="modal-body overflow-auto p-5">
          <SafeForm id="change-password-form" onSubmit={handleSubmit(submitPassword)} noValidate>
            <FormGrid single>
              <FormField
                label="รหัสผ่านปัจจุบัน"
                htmlFor="change-password-current"
                error={errors.currentPassword?.message}
                required
              >
                <input
                  id="change-password-current"
                  type="password"
                  {...register('currentPassword')}
                  autoComplete="current-password"
                  className={errors.currentPassword ? 'border-danger focus:border-danger focus:[box-shadow:0_0_0_3px_var(--color-danger-bg)]' : ''}
                  aria-invalid={Boolean(errors.currentPassword)}
                  aria-describedby={errors.currentPassword ? fieldErrorId('change-password-current') : undefined}
                  required
                />
              </FormField>
              <FormField
                label="รหัสผ่านใหม่"
                htmlFor="change-password-new"
                error={newPasswordTooShort ? NEW_PASSWORD_TOO_SHORT_MESSAGE : undefined}
                hint={!newPasswordTooShort ? 'อย่างน้อย 8 ตัวอักษร' : undefined}
              >
                <input
                  id="change-password-new"
                  type="password"
                  {...register('newPassword')}
                  autoComplete="new-password"
                  minLength={8}
                  className={newPasswordTooShort ? 'border-danger focus:border-danger focus:[box-shadow:0_0_0_3px_var(--color-danger-bg)]' : ''}
                  aria-invalid={newPasswordTooShort}
                  aria-describedby={newPasswordTooShort ? fieldErrorId('change-password-new') : undefined}
                  required
                />
              </FormField>
              <FormField
                label="ยืนยันรหัสผ่านใหม่"
                htmlFor="change-password-confirm"
                error={confirmPasswordFieldError}
                required
              >
                <input
                  id="change-password-confirm"
                  type="password"
                  {...register('confirmPassword')}
                  autoComplete="new-password"
                  className={confirmPasswordFieldError ? 'border-danger focus:border-danger focus:[box-shadow:0_0_0_3px_var(--color-danger-bg)]' : ''}
                  aria-invalid={Boolean(confirmPasswordFieldError)}
                  aria-describedby={confirmPasswordFieldError ? fieldErrorId('change-password-confirm') : undefined}
                  required
                />
              </FormField>
              {(formError || submitError) ? <div className="py-2.5 px-3 rounded-md bg-danger-bg text-danger-dark font-bold text-[length:var(--text-sm)]" role="alert">{formError || submitError}</div> : null}
            </FormGrid>
          </SafeForm>
        </div>
        <footer className="modal-footer flex items-center justify-end gap-4 px-5 py-[18px] border-t border-border">
          {forced ? (
            <Button type="button" variant="secondary" onClick={onLogout}>ออกจากระบบ</Button>
          ) : (
            <Button type="button" variant="secondary" onClick={onClose}>ยกเลิก</Button>
          )}
          <Button type="submit" form="change-password-form" disabled={loading || hasValidationError}>
            <Icon name="check" />
            {loading ? 'กำลังบันทึก…' : 'บันทึกรหัสผ่าน'}
          </Button>
        </footer>
      </section>
    </div>
  );
}
