import { useState } from 'react';
import { Icon } from '../../components/common/Icon.jsx';

export function LoginPage({ onLogin, loading, error }) {
  const [form, setForm] = useState({ email: '', password: '' });

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

      </section>
    </main>
  );
}
