import { useEffect, useState } from 'react';
import { api } from '../../api/index.js';

/**
 * Test-site-only strip (build flag VITE_TEST_SITE) for switching the whole session between the
 * demo accounts. Unlike ViewAsBanner this is a real re-sign-in with full write access.
 */
export function TestSiteSwitcher({ user }) {
  const enabled = import.meta.env.VITE_TEST_SITE === 'true';
  const [accounts, setAccounts] = useState([]);
  const [error, setError] = useState('');
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (!enabled) return;
    api.testSite.accounts().then((res) => setAccounts(res?.accounts ?? []), () => setFailed(true));
  }, [enabled]);

  if (!enabled) return null;

  const current = user.email?.trim().toLowerCase() ?? '';
  const listed = accounts.some((a) => a.email === current);

  async function change(e) {
    setError('');
    try {
      await api.testSite.switchTo(e.target.value);
    } catch (err) {
      setError(err?.message || 'สลับบัญชีไม่สำเร็จ');
      return;
    }
    window.location.assign('/');
  }

  return (
    <div
      role="status"
      className="flex flex-none flex-wrap items-center justify-center gap-3 border-b border-warning-border bg-warning-bg px-4 py-2 text-sm font-semibold text-warning-dark"
    >
      <span>โหมดทดสอบ · กำลังใช้งานเป็น {user.name}</span>
      {/* A view-as session is read-only, so the backend refuses every switch there: offer none. */}
      {accounts.length > 0 && !user.viewAs ? (
        <select aria-label="สลับบัญชีทดสอบ" value={listed ? current : ''} onChange={change} className="rounded border border-warning-border bg-surface px-2 py-1">
          {listed ? null : <option value="" disabled>เลือกบัญชี</option>}
          {accounts.map((a) => <option key={a.email} value={a.email}>{a.name}</option>)}
        </select>
      ) : null}
      {failed ? <span className="font-normal">บัญชีนี้สลับบทบาทไม่ได้</span> : null}
      {error ? <span role="alert" className="text-danger">{error}</span> : null}
    </div>
  );
}
