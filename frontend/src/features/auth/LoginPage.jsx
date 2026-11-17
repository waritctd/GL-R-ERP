import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PRODUCT_MARK, PRODUCT_PORTAL_LABEL } from '../../app/product.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';

// WHY ONE OF THESE LOGS IN BY EMAIL AND THE REST BY ROLE ────────────────── The mock resolves `{ role }` with
// `db.users.find(u => u.role === r && u.active)` — the FIRST match.
const quickAccounts = [
  { role: 'hr', label: 'ฝ่ายบุคคล', helper: 'พนักงานทั้งหมด · อนุมัติคำขอ', icon: 'badgeCheck' },
  { role: 'employee', label: 'พนักงาน', helper: 'โปรไฟล์ของฉัน · ส่งคำขอแก้ไข', icon: 'user' },
  {
    role: 'division_manager',
    email: 'warehouse.manager@glr.co.th',
    password: 'demo1234',
    label: 'ผู้จัดการฝ่าย',
    helper: 'อนุมัติ OT / วันลา ของทีม',
    icon: 'users',
  },
  { role: 'sales', label: 'ฝ่ายขาย', helper: 'สร้างคำขอราคา · ออกใบเสนอราคา', icon: 'briefcase' },
  { role: 'sales_manager', label: 'ผู้จัดการฝ่ายขาย', helper: 'อนุมัติค่าคอม · แก้ไขค่าหัก', icon: 'badgeDollar' },
  { role: 'import', label: 'ฝ่ายนำเข้า', helper: 'รับเรื่อง · เสนอราคาสินค้า', icon: 'clipboard' },
  { role: 'account', label: 'ฝ่ายบัญชี', helper: 'ยืนยันรับมัดจำ · รับชำระเงิน', icon: 'badgeDollar' },
  { role: 'ceo', label: 'CEO', helper: 'อนุมัติคำขอราคา · ภาพรวมทั้งหมด', icon: 'shield' },
];

// One-click sign-in for the accounts the backend's `demo` profile seeds (db/migration-demo V21 + V139, one per
// role, shared password — a committed showcase credential, not a real one). The mock row above covers mock
// mode, so the two never render together.
const DEMO_PASSWORD = 'Demo@2026';
const devQuickAccounts = [
  { key: 'employee', label: 'พนักงาน' },
  { key: 'hr', label: 'ฝ่ายบุคคล' },
  { key: 'sales', label: 'ฝ่ายขาย' },
  { key: 'salesmanager', label: 'ผู้จัดการฝ่ายขาย' },
  { key: 'import', label: 'ฝ่ายนำเข้า' },
  { key: 'account', label: 'ฝ่ายบัญชี' },
  { key: 'warehouse', label: 'คลังสินค้า' },
  { key: 'qc', label: 'QC' },
  { key: 'ceo', label: 'CEO' },
].map((account) => ({ ...account, email: `demo.${account.key}@demo.invalid` }));

export function LoginPage({ onLogin, loading, error }) {
  const [form, setForm] = useState({ email: '', password: '' });
  const isMock = import.meta.env.VITE_USE_MOCKS === 'true';
  const showDevQuickLogin = import.meta.env.DEV && !isMock;
  const navigate = useNavigate();

  function updateField(field, value) {
    setForm((current) => ({ ...current, [field]: value }));
  }

  function submit(event) {
    event.preventDefault();
    // Trim here as well as in LoginRequest's constructor, because the two halves of this repo ship on different
    // clocks: merging to `main` deploys this bundle immediately, while the backend runs a pinned pre-built image
    // that only changes when someone builds and deploys a new tag.
    onLogin({ ...form, email: form.email.trim() });
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

        <SafeForm className="pt-7 px-[34px] pb-[34px] flex flex-col gap-[18px] mobile:pt-5 mobile:px-6 mobile:pb-7" onSubmit={submit}>
          <div>
            <h1 className="m-0 text-[length:var(--text-2xl)] leading-[1.2] [overflow-wrap:anywhere] text-balance">เข้าสู่ระบบ</h1>
            <p className="mt-1 mr-0 mb-0 ml-0 max-w-[72ch] text-text-muted [overflow-wrap:anywhere]">เข้าสู่ระบบด้วยบัญชี GL&R</p>
          </div>

          <label>
            อีเมล
            <span className="relative block">
              <Icon name="mail" className="absolute left-3 top-1/2 -translate-y-1/2 text-text-faint" />
              <input
                data-testid="login-email"
                value={form.email}
                onChange={(event) => updateField('email', event.target.value)}
                type="text"
                inputMode="email"
                autoComplete="email"
                // A phone keyboard capitalises the first letter of a plain text field and runs autocorrect over it, so
                // `somchai@glr.co.th` is offered back as `Somchai@glr.co.th` — often with a trailing space once the correction
                // is accepted.
                autoCapitalize="none"
                autoCorrect="off"
                spellCheck={false}
                required
                className="pl-10"
              />
            </span>
          </label>

          <label>
            รหัสผ่าน
            <span className="relative block">
              <Icon name="lock" className="absolute left-3 top-1/2 -translate-y-1/2 text-text-faint" />
              <input
                data-testid="login-password"
                value={form.password}
                onChange={(event) => updateField('password', event.target.value)}
                type="password"
                autoComplete="current-password"
                required
                className="pl-10"
              />
            </span>
          </label>

          {error ? <div className="py-2.5 px-3 rounded-md bg-danger-bg text-danger-dark font-bold text-[length:var(--text-sm)]">{error}</div> : null}

          <Button type="submit" data-testid="login-submit" disabled={loading}>
            <Icon name="check" />
            เข้าสู่ระบบ
          </Button>

          {/* Bottom-of-card nav link, not inline with the password field: a plain-text variant="text" Button, sized to
           * match the field labels' own font-size token (index.css's base `label` rule: font-size: var) rather than a
           * smaller muted caption size, since this is a primary navigation option, not decoration. */}
          <Button
            type="button"
            variant="text"
            data-testid="login-forgot-password"
            className="self-end !font-normal text-[length:var(--text-sm)]"
            onClick={() => navigate('/forgot-password')}
          >
            ลืมรหัสผ่าน?
          </Button>

          {isMock && (
            <div className="border-t border-border pt-[14px]">
              <p className="!m-0 !mb-[10px] text-xs !text-text-muted font-semibold">เดโม — เข้าสู่ระบบด่วน</p>
              <div className="grid gap-[6px]">
                {quickAccounts.map((account) => (
                  <Button
                    key={account.role}
                    type="button"
                    variant="secondary"
                    className="justify-start gap-[10px] py-[6px] px-3"
                    data-testid={`login-role-${account.role}`}
                    disabled={loading}
                    onClick={() => onLogin(account.email
                      ? { email: account.email, password: account.password }
                      : { role: account.role })}
                  >
                    <Icon name={account.icon} size={15} />
                    <span>
                      <strong className="text-sm">{account.label}</strong>
                      <small className="block text-text-muted text-2xs">{account.helper}</small>
                    </span>
                  </Button>
                ))}
              </div>
            </div>
          )}

          {showDevQuickLogin && (
            <div className="border-t border-border pt-[14px]">
              <p className="!m-0 !mb-[10px] text-xs !text-text-muted font-semibold">เดโม (เซิร์ฟเวอร์จริง) — เข้าสู่ระบบด่วน</p>
              <div className="grid grid-cols-2 gap-[6px]">
                {devQuickAccounts.map((account) => (
                  <Button
                    key={account.key}
                    type="button"
                    variant="secondary"
                    className="justify-start py-[6px] px-3"
                    data-testid={`login-dev-${account.email}`}
                    disabled={loading}
                    title={account.email}
                    onClick={() => onLogin({ email: account.email, password: DEMO_PASSWORD })}
                  >
                    <strong className="text-sm">{account.label}</strong>
                  </Button>
                ))}
              </div>
            </div>
          )}
        </SafeForm>
      </section>
    </main>
  );
}
