import { useState } from 'react';
import { api } from '../../api/index.js';
import { Button } from '../common/Button.jsx';

/**
 * Full-width strip shown for the whole time the owner is viewing as an employee. Leaving does a
 * full page load so no cached query from the viewed employee survives into the owner's own view.
 */
export function ViewAsBanner({ user }) {
  const [leaving, setLeaving] = useState(false);
  const [error, setError] = useState('');

  async function leave() {
    setLeaving(true);
    setError('');
    try {
      await api.viewAs.stop();
    } catch (e) {
      // Reloading now would land straight back in the view with no explanation.
      setError(e?.message || 'ออกจากมุมมองไม่สำเร็จ ลองอีกครั้ง');
      setLeaving(false);
      return;
    }
    window.location.assign('/');
  }

  return (
    <div
      role="status"
      className="flex flex-none flex-wrap items-center justify-center gap-3 border-b border-warning-border bg-warning-bg px-4 py-2 text-sm font-semibold text-warning-dark"
    >
      <span>กำลังดูในมุมมองของ {user.name} · อ่านอย่างเดียว</span>
      {error ? <span role="alert" className="text-danger">{error}</span> : null}
      <Button variant="secondary" onClick={leave} disabled={leaving}>ออกจากมุมมองนี้</Button>
    </div>
  );
}
