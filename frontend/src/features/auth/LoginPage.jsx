import { useState } from 'react';
import { Icon } from '../../components/common/Icon.jsx';

const quickAccounts = [
  { role: 'hr', label: 'HR', helper: 'พนักงานทั้งหมด · อนุมัติคำขอ', icon: 'badgeCheck' },
  { role: 'employee', label: 'Employee', helper: 'โปรไฟล์ของฉัน · ส่งคำขอแก้ไข', icon: 'user' },
  { role: 'admin', label: 'Admin', helper: 'จัดการบัญชีผู้ใช้งาน', icon: 'userCog' },
];

const demoLoginEnabled = import.meta.env.DEV
  || import.meta.env.VITE_USE_MOCKS === 'true'
  || import.meta.env.VITE_DEMO_LOGIN === 'true';

export function LoginPage({ onLogin, loading, error }) {
  const [form, setForm] = useState(() => ({
    email: demoLoginEnabled ? 'hr@glr.co.th' : '',
    password: demoLoginEnabled ? 'demo1234' : '',
  }));

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
        </form>

        {demoLoginEnabled ? (
          <div className="quick-login">
            {quickAccounts.map((account) => (
              <button key={account.role} type="button" onClick={() => onLogin({ role: account.role })} disabled={loading}>
                <Icon name={account.icon} />
                <span>
                  <strong>{account.label}</strong>
                  <small>{account.helper}</small>
                </span>
              </button>
            ))}
          </div>
        ) : null}
      </section>
    </main>
  );
}
