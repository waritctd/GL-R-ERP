import { useState } from 'react';
import { Icon } from '../../components/common/Icon.jsx';

const quickAccounts = [
  { role: 'hr', label: 'HR', helper: 'พนักงานทั้งหมด · อนุมัติคำขอ', icon: 'badgeCheck' },
  { role: 'employee', label: 'Employee', helper: 'โปรไฟล์ของฉัน · ส่งคำขอแก้ไข', icon: 'user' },
  { role: 'sales', label: 'Sales', helper: 'สร้างใบขอราคา · ออกใบเสนอราคา', icon: 'briefcase' },
  { role: 'sales_manager', label: 'Sales Manager', helper: 'อนุมัติค่าคอม · แก้ไขค่าหัก', icon: 'badgeDollar' },
  { role: 'import', label: 'Import', helper: 'รับเรื่อง · เสนอราคาสินค้า', icon: 'clipboard' },
  { role: 'ceo', label: 'CEO', helper: 'อนุมัติใบขอราคา · ภาพรวมทั้งหมด', icon: 'shield' },
];

// Demo-branch only: real accounts seeded by V21__demo_seed_accounts.sql, all sharing
// the same password. These buttons submit real email+password through the normal
// /api/auth/login flow (full BCrypt check) — this is NOT the old role-bypass login.
const DEMO_PASSWORD = 'Demo@2026';
const demoAccounts = [
  { email: 'demo.employee@demo.invalid', label: 'Employee', helper: 'โปรไฟล์ของฉัน · ส่งคำขอแก้ไข', icon: 'user' },
  { email: 'demo.hr@demo.invalid', label: 'HR', helper: 'พนักงานทั้งหมด · อนุมัติคำขอ', icon: 'badgeCheck' },
  { email: 'demo.sales@demo.invalid', label: 'Sales', helper: 'สร้างใบขอราคา · ออกใบเสนอราคา', icon: 'briefcase' },
  { email: 'demo.salesmanager@demo.invalid', label: 'Sales Manager', helper: 'อนุมัติค่าคอม · แก้ไขค่าหัก', icon: 'badgeDollar' },
  { email: 'demo.import@demo.invalid', label: 'Import', helper: 'รับเรื่อง · เสนอราคาสินค้า', icon: 'clipboard' },
  { email: 'demo.ceo@demo.invalid', label: 'CEO', helper: 'อนุมัติใบขอราคา · ภาพรวมทั้งหมด', icon: 'shield' },
  { email: 'demo.admin@demo.invalid', label: 'Admin', helper: 'สิทธิ์สูงสุดทุกโมดูล', icon: 'shield' },
];

export function LoginPage({ onLogin, loading, error }) {
  const [form, setForm] = useState({ email: '', password: '' });
  const isMock = import.meta.env.VITE_USE_MOCKS === 'true';
  const isDemo = import.meta.env.VITE_DEMO_LOGIN === 'true';

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

          <button className="primary-button" type="submit" disabled={loading}>
            <Icon name="check" />
            เข้าสู่ระบบ
          </button>

          {isMock && (
            <div style={{ borderTop: '1px solid #e6eaf0', paddingTop: 14 }}>
              <p style={{ margin: '0 0 10px', fontSize: 12, color: '#94a3b8', fontWeight: 600 }}>DEMO — เข้าสู่ระบบด่วน</p>
              <div style={{ display: 'grid', gap: 6 }}>
                {quickAccounts.map((account) => (
                  <button
                    key={account.role}
                    type="button"
                    className="secondary-button"
                    style={{ justifyContent: 'flex-start', gap: 10, padding: '6px 12px' }}
                    disabled={loading}
                    onClick={() => onLogin({ role: account.role })}
                  >
                    <Icon name={account.icon} size={15} />
                    <span>
                      <strong style={{ fontSize: 13 }}>{account.label}</strong>
                      <small style={{ display: 'block', color: '#94a3b8', fontSize: 11 }}>{account.helper}</small>
                    </span>
                  </button>
                ))}
              </div>
            </div>
          )}

          {isDemo && (
            <div style={{ borderTop: '1px solid #e6eaf0', paddingTop: 14 }}>
              <p style={{ margin: '0 0 10px', fontSize: 12, color: '#94a3b8', fontWeight: 600 }}>
                DEMO — ลองเข้าสู่ระบบทุกสิทธิ์ (รหัสผ่านเดียวกันทุกบัญชี: {DEMO_PASSWORD})
              </p>
              <div style={{ display: 'grid', gap: 6 }}>
                {demoAccounts.map((account) => (
                  <button
                    key={account.email}
                    type="button"
                    className="secondary-button"
                    style={{ justifyContent: 'flex-start', gap: 10, padding: '6px 12px' }}
                    disabled={loading}
                    onClick={() => onLogin({ email: account.email, password: DEMO_PASSWORD })}
                  >
                    <Icon name={account.icon} size={15} />
                    <span>
                      <strong style={{ fontSize: 13 }}>{account.label}</strong>
                      <small style={{ display: 'block', color: '#94a3b8', fontSize: 11 }}>{account.helper}</small>
                    </span>
                  </button>
                ))}
              </div>
            </div>
          )}
        </form>
      </section>
    </main>
  );
}
