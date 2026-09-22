import React, { useEffect, useMemo, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { api } from '../../api/index.js';
import { PRODUCT_MARK, PRODUCT_PORTAL_LABEL } from '../../app/product.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';

const PASSWORD_TOO_SHORT_MESSAGE = 'รหัสผ่านใหม่ต้องมีอย่างน้อย 8 ตัวอักษร';
const PASSWORD_MISMATCH_MESSAGE = 'รหัสผ่านใหม่และการยืนยันไม่ตรงกัน';
const INVALID_LINK_MESSAGE = 'ลิงก์สำหรับตั้งรหัสผ่านใหม่ไม่ถูกต้องหรือหมดอายุแล้ว กรุณาขอลิงก์ใหม่อีกครั้ง';
const GENERIC_RETRY_MESSAGE = 'ตั้งรหัสผ่านใหม่ไม่สำเร็จ กรุณาลองใหม่อีกครั้ง';

// Non-jarring auto-redirect after a successful reset: long enough to read the confirmation,
// short enough not to feel stuck. The manual "เข้าสู่ระบบ" button below always works immediately
// regardless of this timer.
const AUTO_REDIRECT_MS = 4000;

/**
 * Self-service "forgot password" step 2 — lands here from the emailed
 * {@code /reset-password?token=...} link. Rendered by {@code App.jsx}'s logged-out branch
 * alongside {@link LoginPage} / {@link ForgotPasswordPage} (see that file's routing comment).
 *
 * <p>Reads {@code token} from the query string rather than a route param, since this page is
 * reached outside the authenticated {@code <Routes>} tree entirely (App.jsx switches on
 * `location.pathname` while `user` is falsy — there is no router-parsed param here to read).
 */
export function ResetPasswordPage({ onNavigateToLogin, onNavigateToForgotPassword }) {
  const location = useLocation();
  const token = useMemo(() => new URLSearchParams(location.search).get('token'), [location.search]);

  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [succeeded, setSucceeded] = useState(false);

  useEffect(() => {
    if (!succeeded) return undefined;
    const timer = setTimeout(onNavigateToLogin, AUTO_REDIRECT_MS);
    return () => clearTimeout(timer);
  }, [succeeded, onNavigateToLogin]);

  // No token at all in the URL: this is the same invalid/expired state a rejected token gets,
  // shown immediately rather than attempting a call with an empty token.
  if (!token) {
    return (
      <InvalidLinkCard onNavigateToForgotPassword={onNavigateToForgotPassword} onNavigateToLogin={onNavigateToLogin} />
    );
  }

  async function submit(event) {
    event.preventDefault();
    setError('');

    if (newPassword.length < 8) {
      setError(PASSWORD_TOO_SHORT_MESSAGE);
      return;
    }
    if (newPassword !== confirmPassword) {
      setError(PASSWORD_MISMATCH_MESSAGE);
      return;
    }

    setLoading(true);
    try {
      await api.auth.resetPassword({ token, newPassword });
      setSucceeded(true);
    } catch (err) {
      // The server's own message is safe to show verbatim here (unlike login): it reveals only
      // whether THIS caller's token is currently valid, never whether any account exists.
      setError(err?.message || GENERIC_RETRY_MESSAGE);
    } finally {
      setLoading(false);
    }
  }

  if (succeeded) {
    return (
      <main className="min-h-[100dvh] grid place-items-center p-7 bg-bg-alt">
        <section className="w-[min(440px,100%)] flex flex-col bg-surface border border-border-strong rounded-md overflow-hidden shadow-lg">
          <BrandHeader />
          <div className="pt-7 px-[34px] pb-[34px] flex flex-col gap-[18px] mobile:pt-5 mobile:px-6 mobile:pb-7">
            <div>
              <h1 className="m-0 text-[length:var(--text-2xl)] leading-[1.2] [overflow-wrap:anywhere] text-balance">ตั้งรหัสผ่านใหม่เรียบร้อย</h1>
            </div>
            <div className="flex items-start gap-2.5 py-3 px-3.5 rounded-md bg-success-bg text-success-dark text-[length:var(--text-sm)]">
              <Icon name="check" className="mt-0.5 shrink-0" />
              <p className="m-0">ตั้งรหัสผ่านใหม่เรียบร้อยแล้ว กรุณาเข้าสู่ระบบด้วยรหัสผ่านใหม่ของคุณ</p>
            </div>
            <Button type="button" variant="text" onClick={onNavigateToLogin} data-testid="reset-password-go-to-login">
              เข้าสู่ระบบ
            </Button>
          </div>
        </section>
      </main>
    );
  }

  return (
    <main className="min-h-[100dvh] grid place-items-center p-7 bg-bg-alt">
      <section className="w-[min(440px,100%)] flex flex-col bg-surface border border-border-strong rounded-md overflow-hidden shadow-lg">
        <BrandHeader />

        <SafeForm className="pt-7 px-[34px] pb-[34px] flex flex-col gap-[18px] mobile:pt-5 mobile:px-6 mobile:pb-7" onSubmit={submit}>
          <div>
            <h1 className="m-0 text-[length:var(--text-2xl)] leading-[1.2] [overflow-wrap:anywhere] text-balance">ตั้งรหัสผ่านใหม่</h1>
            <p className="mt-1 mr-0 mb-0 ml-0 max-w-[72ch] text-text-muted [overflow-wrap:anywhere]">กรุณากำหนดรหัสผ่านใหม่สำหรับบัญชีของคุณ</p>
          </div>

          <label>
            รหัสผ่านใหม่
            <span className="relative block">
              <Icon name="lock" className="absolute left-3 top-1/2 -translate-y-1/2 text-text-faint" />
              <input
                data-testid="reset-password-new"
                value={newPassword}
                onChange={(event) => setNewPassword(event.target.value)}
                type="password"
                autoComplete="new-password"
                minLength={8}
                required
                className="pl-10"
              />
            </span>
          </label>

          <label>
            ยืนยันรหัสผ่านใหม่
            <span className="relative block">
              <Icon name="lock" className="absolute left-3 top-1/2 -translate-y-1/2 text-text-faint" />
              <input
                data-testid="reset-password-confirm"
                value={confirmPassword}
                onChange={(event) => setConfirmPassword(event.target.value)}
                type="password"
                autoComplete="new-password"
                minLength={8}
                required
                className="pl-10"
              />
            </span>
          </label>

          {error ? <div className="py-2.5 px-3 rounded-md bg-danger-bg text-danger-dark font-bold text-[length:var(--text-sm)]">{error}</div> : null}

          <Button type="submit" data-testid="reset-password-submit" disabled={loading}>
            <Icon name="check" />
            {loading ? 'กำลังบันทึก…' : 'ตั้งรหัสผ่านใหม่'}
          </Button>

          <Button type="button" variant="text" className="self-start" onClick={onNavigateToLogin}>
            กลับไปหน้าเข้าสู่ระบบ
          </Button>
        </SafeForm>
      </section>
    </main>
  );
}

function BrandHeader() {
  return (
    <div className="bg-surface-muted pt-7 px-[34px] pb-6 flex gap-3 items-start border-b border-border mobile:pt-6 mobile:px-6 mobile:pb-5">
      <span className="brand-mark">
        <i />
        <i />
        <i />
        <i />
      </span>
      <span>
        <strong className="block text-[length:var(--text-xl)] leading-none">{PRODUCT_MARK}</strong>
        <small className="text-text-muted text-[length:var(--text-xs)]">{PRODUCT_PORTAL_LABEL}</small>
      </span>
    </div>
  );
}

function InvalidLinkCard({ onNavigateToForgotPassword, onNavigateToLogin }) {
  return (
    <main className="min-h-[100dvh] grid place-items-center p-7 bg-bg-alt">
      <section className="w-[min(440px,100%)] flex flex-col bg-surface border border-border-strong rounded-md overflow-hidden shadow-lg">
        <BrandHeader />
        <div className="pt-7 px-[34px] pb-[34px] flex flex-col gap-[18px] mobile:pt-5 mobile:px-6 mobile:pb-7">
          <div>
            <h1 className="m-0 text-[length:var(--text-2xl)] leading-[1.2] [overflow-wrap:anywhere] text-balance">ลิงก์ไม่ถูกต้อง</h1>
          </div>
          <div className="flex items-start gap-2.5 py-3 px-3.5 rounded-md bg-danger-bg text-danger-dark text-[length:var(--text-sm)]">
            <Icon name="triangleAlert" className="mt-0.5 shrink-0" />
            <p className="m-0" data-testid="reset-password-invalid">{INVALID_LINK_MESSAGE}</p>
          </div>
          <Button type="button" onClick={onNavigateToForgotPassword} data-testid="reset-password-request-new">
            <Icon name="mail" />
            ขอลิงก์ใหม่
          </Button>
          <Button type="button" variant="text" className="self-start" onClick={onNavigateToLogin}>
            กลับไปหน้าเข้าสู่ระบบ
          </Button>
        </div>
      </section>
    </main>
  );
}
