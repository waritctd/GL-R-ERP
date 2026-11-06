import { useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../../api/index.js';
import { roleLabel } from '../../utils/format.js';
import { Modal } from '../common/Modal.jsx';

/** Owner-only: pick an employee to view the portal as, read-only. Starting reloads at '/'. */
export function ViewAsPickerModal({ onClose }) {
  const [candidates, setCandidates] = useState(null);
  const [error, setError] = useState('');
  const [search, setSearch] = useState('');
  const searchRef = useRef(null);

  useEffect(() => {
    let alive = true;
    api.viewAs.candidates()
      .then((response) => { if (alive) setCandidates(response?.candidates ?? response ?? []); })
      .catch((e) => { if (alive) setError(e?.message || 'โหลดรายชื่อพนักงานไม่สำเร็จ'); });
    return () => { alive = false; };
  }, []);

  const shown = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!candidates) return [];
    if (!q) return candidates;
    return candidates.filter((c) => `${c.name} ${c.employeeCode}`.toLowerCase().includes(q));
  }, [candidates, search]);

  async function choose(candidate) {
    setError('');
    try {
      await api.viewAs.start(candidate.employeeId);
      window.location.assign('/');
    } catch (e) {
      setError(e?.message || 'เปลี่ยนมุมมองไม่สำเร็จ');
    }
  }

  return (
    <Modal title="ดูในมุมมองพนักงาน" subtitle="อ่านอย่างเดียว — ทำรายการแทนพนักงานไม่ได้" onClose={onClose} initialFocusRef={searchRef}>
      <input
        ref={searchRef}
        type="search"
        value={search}
        onChange={(event) => setSearch(event.target.value)}
        placeholder="ค้นหาชื่อหรือรหัสพนักงาน"
        aria-label="ค้นหาพนักงาน"
        className="mb-3 w-full min-h-11 rounded-md border border-border-input bg-surface px-3 text-sm"
      />
      {error ? <p role="alert" className="text-danger">{error}</p> : null}
      {candidates === null && !error ? <p className="text-text-muted">กำลังโหลด…</p> : null}
      {candidates !== null && shown.length === 0 ? <p className="text-text-muted">ไม่พบพนักงาน</p> : null}
      <ul className="m-0 flex list-none flex-col p-0">
        {shown.map((c) => (
          <li key={c.employeeId}>
            <button
              type="button"
              onClick={() => choose(c)}
              className="flex w-full min-h-11 cursor-pointer items-center justify-between gap-3 border-0 bg-transparent px-2 text-left hover:bg-surface-hover"
            >
              <span>{c.name} <span className="text-xs text-text-muted">{c.employeeCode}</span></span>
              <span className="text-xs text-text-muted">{roleLabel(c.role)}{c.divisionName ? ` · ${c.divisionName}` : ''}</span>
            </button>
          </li>
        ))}
      </ul>
    </Modal>
  );
}
