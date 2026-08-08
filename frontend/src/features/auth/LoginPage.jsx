import React, { useState } from 'react';
import { PRODUCT_MARK, PRODUCT_PORTAL_LABEL } from '../../app/product.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';

const quickAccounts = [
  { role: 'hr', label: 'ฝ่ายบุคคล', helper: 'พนักงานทั้งหมด · อนุมัติคำขอ', icon: 'badgeCheck' },
  { role: 'employee', label: 'พนักงาน', helper: 'โปรไฟล์ของฉัน · ส่งคำขอแก้ไข', icon: 'user' },
  { role: 'sales', label: 'ฝ่ายขาย', helper: 'สร้างคำขอราคา · ออกใบเสนอราคา', icon: 'briefcase' },
  { role: 'sales_manager', label: 'ผู้จัดการฝ่ายขาย', helper: 'อนุมัติค่าคอม · แก้ไขค่าหัก', icon: 'badgeDollar' },
  { role: 'import', label: 'ฝ่ายนำเข้า', helper: 'รับเรื่อง · เสนอราคาสินค้า', icon: 'clipboard' },
  { role: 'account', label: 'ฝ่ายบัญชี', helper: 'ยืนยันรับมัดจำ · รับชำระเงิน', icon: 'badgeDollar' },
  { role: 'ceo', label: 'CEO', helper: 'อนุมัติคำขอราคา · ภาพรวมทั้งหมด', icon: 'shield' },
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
            <strong>{PRODUCT_MARK}</strong>
            <small>{PRODUCT_PORTAL_LABEL}</small>
          </span>
        </div>

        <SafeForm className="login-form" onSubmit={submit}>
          <div>
            <h1>เข้าสู่ระบบ</h1>
            <p>เข้าสู่ระบบด้วยบัญชี GL&R</p>
          </div>

          <label>
            อีเมล
            <span className="input-with-icon">
              <Icon name="mail" />
              <input
                data-testid="login-email"
                value={form.email}
                onChange={(event) => updateField('email', event.target.value)}
                type="text"
                inputMode="email"
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
                data-testid="login-password"
                value={form.password}
                onChange={(event) => updateField('password', event.target.value)}
                type="password"
                autoComplete="current-password"
                required
              />
            </span>
          </label>

          {error ? <div className="form-error">{error}</div> : null}

          <Button type="submit" data-testid="login-submit" disabled={loading}>
            <Icon name="check" />
            เข้าสู่ระบบ
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
        </SafeForm>
      </section>
    </main>
  );
}
