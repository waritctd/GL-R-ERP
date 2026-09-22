import React, { useState } from 'react';
import { api } from '../../api/index.js';
import { PRODUCT_MARK, PRODUCT_PORTAL_LABEL } from '../../app/product.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';

const GENERIC_RETRY_MESSAGE = 'ส่งคำขอไม่สำเร็จ กรุณาลองใหม่อีกครั้ง';

/**
 * Self-service "forgot password" entry (ลืมรหัสผ่าน?) — step 1 of the flow. Reached from
 * {@link LoginPage}'s muted link, rendered by {@code App.jsx}'s logged-out branch alongside
 * {@link LoginPage} and {@link ResetPasswordPage} (see that file: none of these three are wired
 * through {@code <Routes>} — App.jsx switches on `location.pathname` while `user` is falsy).
 *
 * <p>There is deliberately no "email not found" branch: {@code api.auth.forgotPassword} always
 * resolves with the same generic confirmation, whether or not the address matched an employee
 * (AuthController#forgotPassword / PasswordResetService — never reveal account existence). Only a
 * genuine network/unexpected failure (a REJECTED promise) gets a different, retry-shaped message.
 */
export function ForgotPasswordPage({ onNavigateToLogin }) {
  const [email, setEmail] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [sentMessage, setSentMessage] = useState('');

  async function submit(event) {
    event.preventDefault();
    setError('');
    setLoading(true);
    try {
      const response = await api.auth.forgotPassword({ email: email.trim() });
      // The only shape on success is the generic confirmation - there is no "not found" branch
      // to handle (see class doc above). Fall back to a sensible default if the shape ever
      // changes server-side rather than rendering a blank confirmation.
      setSentMessage(response?.message || 'หากอีเมลนี้มีอยู่ในระบบ เราได้ส่งลิงก์สำหรับตั้งรหัสผ่านใหม่ไปให้แล้ว กรุณาตรวจสอบกล่องอีเมลของคุณ');
    } catch {
      // Deliberately generic and distinct from sentMessage: a thrown error here means the
      // request itself failed (network, 5xx, validation) - not that the address was unknown,
      // which never throws.
      setError(GENERIC_RETRY_MESSAGE);
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="min-h-[100dvh] grid place-items-center p-7 bg-bg-alt">
      <section className="w-[min(440px,100%)] flex flex-col bg-surface border border-border-strong rounded-md overflow-hidden shadow-lg">
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

        {sentMessage ? (
          <div className="pt-7 px-[34px] pb-[34px] flex flex-col gap-[18px] mobile:pt-5 mobile:px-6 mobile:pb-7">
            <div>
              <h1 className="m-0 text-[length:var(--text-2xl)] leading-[1.2] [overflow-wrap:anywhere] text-balance">ตรวจสอบอีเมลของคุณ</h1>
            </div>
            <div className="flex items-start gap-2.5 py-3 px-3.5 rounded-md bg-success-bg text-success-dark text-[length:var(--text-sm)]">
              <Icon name="check" className="mt-0.5 shrink-0" />
              <p className="m-0">{sentMessage}</p>
            </div>
            <Button type="button" variant="text" onClick={onNavigateToLogin}>
              กลับไปหน้าเข้าสู่ระบบ
            </Button>
          </div>
        ) : (
          <SafeForm className="pt-7 px-[34px] pb-[34px] flex flex-col gap-[18px] mobile:pt-5 mobile:px-6 mobile:pb-7" onSubmit={submit}>
            <div>
              <h1 className="m-0 text-[length:var(--text-2xl)] leading-[1.2] [overflow-wrap:anywhere] text-balance">ลืมรหัสผ่าน</h1>
              <p className="mt-1 mr-0 mb-0 ml-0 max-w-[72ch] text-text-muted [overflow-wrap:anywhere]">กรอกอีเมลที่ใช้เข้าสู่ระบบ เราจะส่งลิงก์สำหรับตั้งรหัสผ่านใหม่ไปให้คุณ</p>
            </div>

            <label>
              อีเมล
              <span className="relative block">
                <Icon name="mail" className="absolute left-3 top-1/2 -translate-y-1/2 text-text-faint" />
                <input
                  data-testid="forgot-password-email"
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                  type="text"
                  inputMode="email"
                  autoComplete="email"
                  autoCapitalize="none"
                  autoCorrect="off"
                  spellCheck={false}
                  required
                  className="pl-10"
                />
              </span>
            </label>

            {error ? <div className="py-2.5 px-3 rounded-md bg-danger-bg text-danger-dark font-bold text-[length:var(--text-sm)]">{error}</div> : null}

            <Button type="submit" data-testid="forgot-password-submit" disabled={loading}>
              <Icon name="mail" />
              {loading ? 'กำลังส่ง…' : 'ส่งลิงก์ตั้งรหัสผ่านใหม่'}
            </Button>

            <Button type="button" variant="text" className="self-start" onClick={onNavigateToLogin}>
              กลับไปหน้าเข้าสู่ระบบ
            </Button>
          </SafeForm>
        )}
      </section>
    </main>
  );
}
