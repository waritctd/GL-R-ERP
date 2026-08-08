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
