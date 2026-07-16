import React, { useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';

const quickAccounts = [
  { role: 'hr', label: 'HR', helper: 'พนักงานทั้งหมด · อนุมัติคำขอ', icon: 'badgeCheck' },
  { role: 'employee', label: 'Employee', helper: 'โปรไฟล์ของฉัน · ส่งคำขอแก้ไข', icon: 'user' },
  { role: 'sales', label: 'Sales', helper: 'สร้างใบขอราคา · ออกใบเสนอราคา', icon: 'briefcase' },
  { role: 'sales_manager', label: 'Sales Manager', helper: 'อนุมัติค่าคอม · แก้ไขค่าหัก', icon: 'badgeDollar' },
  { role: 'import', label: 'Import', helper: 'รับเรื่อง · เสนอราคาสินค้า', icon: 'clipboard' },
  { role: 'account', label: 'Account', helper: 'ยืนยันรับมัดจำ · รับชำระเงิน', icon: 'badgeDollar' },
  { role: 'ceo', label: 'CEO', helper: 'อนุมัติใบขอราคา · ภาพรวมทั้งหมด', icon: 'shield' },
];

export function LoginPage({ onLogin, loading, error }) {
  const [form, setForm] = useState({ email: '', password: '' });
  const isMock = import.meta.env.VITE_USE_MOCKS === 'true';

  function updateField(field, value) {
    setForm((current) => ({ ...current, [field]: value }));
  }

  function submit(event) {
    event.preventDefault();
    onLogin(form);
  }

  return (
    <main className="login-screen">
      <section className="login-panel">
        <div className="login-brand">
          <span className="brand-mark">
            <i />
            <i />
            <i />
            <i />
          </span>
          <span>
            <strong>GL&R</strong>
            <small>Human Resources</small>
          </span>
        </div>

        <form className="login-form" onSubmit={submit}>
          <div>
            <h1>เข้าสู่ระบบ</h1>
            <p>Sign in with your GL&R account</p>
          </div>

          <label>
            อีเมล
            <span className="input-with-icon">
              <Icon name="mail" />
              <input
                value={form.email}
                onChange={(event) => updateField('email', event.target.value)}
                type="email"
                autoComplete="email"
                required
              />
            </span>
          </label>

          <label>
            รหัสผ่าน
            <span className="input-with-icon">
              <Icon name="lock" />
              <input
                value={form.password}
                onChange={(event) => updateField('password', event.target.value)}
                type="password"
                autoComplete="current-password"
                required
              />
            </span>
          </label>

          {error ? <div className="form-error">{error}</div> : null}

          <Button type="submit" disabled={loading}>
            <Icon name="check" />
            เข้าสู่ระบบ
          </Button>

          {isMock && (
            <div className="border-t border-border pt-[14px]">
              <p className="!m-0 !mb-[10px] text-xs !text-text-muted font-semibold">DEMO — เข้าสู่ระบบด่วน</p>
              <div className="grid gap-[6px]">
                {quickAccounts.map((account) => (
                  <Button
                    key={account.role}
                    type="button"
                    variant="secondary"
                    className="justify-start gap-[10px] py-[6px] px-3"
                    disabled={loading}
                    onClick={() => onLogin({ role: account.role })}
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
        </form>
      </section>
    </main>
  );
}
