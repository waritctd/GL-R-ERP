import { useEffect, useRef, useState } from 'react';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';

/** ผู้ออกแบบ (designer) picker for the quotation editor's combined designer/D.Co. Picking a designer displays
 * NAME + CODE to the user, while the parent receives the CODE ONLY for saving. 🚨 THE NAME IS CONFIDENTIAL
 * ("เป็นความลับ") AND MUST NEVER REACH A PRINTED DOCUMENT. */
export function DesignerPicker({ value, onSelectCode, disabled = false, idPrefix = 'designer-picker', label = 'ค้นหาผู้ออกแบบ', showToast }) {
  const [search, setSearch] = useState('');
  const [results, setResults] = useState([]);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);
  const timer = useRef(null);
  useEffect(() => () => clearTimeout(timer.current), []);
  // Whether the rep has typed into the box since it was last opened or a designer was last selected — the signal
  // that distinguishes "cleared on purpose" (blur below) from a plain focus-then-blur or an Enter/click pick
  // that also leaves `search` blank.
  const editedRef = useRef(false);

  // ── "+ เพิ่มผู้ออกแบบใหม่" (reversal, see this file's class doc) ─────────────────────────────
  const [showNewDesigner, setShowNewDesigner] = useState(false);
  const [newDesigner, setNewDesigner] = useState({ code: '', name: '' });
  const [savingDesigner, setSavingDesigner] = useState(false);

  const hint = useDesignerHint(value);

  function closeDropdown() {
    setOpen(false);
    setActiveIndex(-1);
  }

  function debouncedSearch(q) {
    clearTimeout(timer.current);
    timer.current = setTimeout(async () => {
      setLoading(true);
      try {
        const res = await api.designers.search(q);
        setResults(res.items ?? []);
      } catch {
        // typeahead only -- a failed search just shows no results, not an error toast
        setResults([]);
      } finally {
        setLoading(false);
      }
    }, 280);
  }

  function openDropdown() {
    if (disabled) return;
    setOpen(true);
    editedRef.current = false;
    // Seed from the current code whenever there is one — not only once the directory has resolved it to a name —
    // so reopening a legacy/unresolved code still searches onward from it instead of starting blank.
    const trimmedValue = (value ?? '').trim();
    const query = trimmedValue ? value : search;
    if (trimmedValue) setSearch(value);
    debouncedSearch(query);
  }

  function selectDesigner(d) {
    onSelectCode(d.code);
    setSearch('');
    setResults([]);
    editedRef.current = false;
    closeDropdown();
  }

  // Keep the text the rep already typed into the search box when opening the create form, so adding a new
  // designer does not require retyping it — mirrors DealCustomerCard's own เพิ่มลูกค้าใหม่ seed exactly.
  function openNewDesignerFromDropdown() {
    setNewDesigner({ code: '', name: search.trim() });
    setShowNewDesigner(true);
    closeDropdown();
  }

  function closeNewDesigner() {
    if (savingDesigner) return;
    setShowNewDesigner(false);
    setNewDesigner({ code: '', name: '' });
  }

  async function handleCreateDesigner() {
    const code = newDesigner.code.trim();
    const name = newDesigner.name.trim();
    if (!code || !name) return;
    setSavingDesigner(true);
    try {
      const res = await api.designers.create({ code, name });
      selectDesigner(res.designer);
      setShowNewDesigner(false);
      setNewDesigner({ code: '', name: '' });
    } catch (error) {
      showToast?.('error', error.message || 'เพิ่มผู้ออกแบบใหม่ไม่สำเร็จ');
    } finally {
      setSavingDesigner(false);
    }
  }

  // Blur commits a deliberate clear: the rep edited the box (not just opened and left it, and not just picked a
  // result) and left it blank while a code was set. Anything else -- a plain focus/blur, or an Enter/click
  // selection whose own reset already zeroed `editedRef` -- leaves `value` exactly as it was.
  function handleBlur() {
    setTimeout(() => {
      if (editedRef.current && !search.trim() && (value ?? '').trim()) {
        editedRef.current = false;
        onSelectCode('');
      }
      closeDropdown();
    }, 150);
  }

  function handleKeyDown(e) {
    if (disabled) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      if (!open) { openDropdown(); return; }
      if (results.length === 0) return;
      setActiveIndex((i) => (i + 1) % results.length);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      if (!open) { openDropdown(); return; }
      if (results.length === 0) return;
      setActiveIndex((i) => (i - 1 + results.length) % results.length);
    } else if (e.key === 'Enter') {
      if (!open || activeIndex === -1 || !results[activeIndex]) return;
      e.preventDefault();
      selectDesigner(results[activeIndex]);
    } else if (e.key === 'Escape' && open) {
      e.preventDefault();
      closeDropdown();
    }
  }

  return (
    <div className="relative">
      <label className="m-0 block" htmlFor={idPrefix}>
        <span className="mb-1 block text-xs">{label}</span>
        <input
          id={idPrefix}
          role="combobox"
          autoComplete="off"
          disabled={disabled}
          aria-expanded={open}
          aria-controls={`${idPrefix}-list`}
          aria-autocomplete="list"
          aria-activedescendant={open && activeIndex >= 0 ? `${idPrefix}-option-${activeIndex}` : undefined}
          placeholder="พิมพ์รหัสหรือชื่อผู้ออกแบบ…"
          value={open ? search : closedDisplayValue(hint, value)}
          onChange={(e) => {
            editedRef.current = true;
            setSearch(e.target.value); setActiveIndex(-1); setOpen(true); debouncedSearch(e.target.value);
          }}
          onFocus={openDropdown}
          onBlur={handleBlur}
          onKeyDown={handleKeyDown}
        />
      </label>
      {open ? (
        <ul
          id={`${idPrefix}-list`}
          role="listbox"
          aria-label="ผลการค้นหาผู้ออกแบบ"
          className="absolute z-10 mt-1 max-h-64 w-full list-none overflow-auto rounded-md border border-border bg-surface pl-0 shadow-[var(--shadow-lg-heavy)]"
        >
          <li className="sticky top-0 z-10 border-b border-border-subtle bg-surface-muted">
            <button
              type="button"
              className="flex w-full items-center gap-1.5 border-0 bg-transparent px-3 py-2 text-left text-xs font-bold text-link"
              onMouseDown={(e) => { e.preventDefault(); openNewDesignerFromDropdown(); }}
            >
              <Icon name="plus" size={13} />
              เพิ่มผู้ออกแบบใหม่
            </button>
          </li>
          {loading ? <li role="presentation" className="px-3 py-2 text-xs text-text-muted">กำลังค้นหา…</li> : null}
          {!loading && results.length === 0 ? (
            <li role="presentation" className="px-3 py-2 text-xs text-text-muted">ไม่พบผู้ออกแบบ</li>
          ) : null}
          {results.map((d, idx) => (
            <li key={d.code} role="presentation">
              <button
                type="button"
                id={`${idPrefix}-option-${idx}`}
                role="option"
                aria-selected={idx === activeIndex}
                className={`block w-full border-0 px-3 py-2 text-left text-xs text-text hover:bg-surface-hover ${idx === activeIndex ? 'bg-surface-hover' : 'bg-transparent'}`}
                onMouseEnter={() => setActiveIndex(idx)}
                onMouseDown={(e) => { e.preventDefault(); selectDesigner(d); }}
              >
                <strong>{d.code}</strong>
                <span className="ml-1.5 text-text-muted">{d.name}</span>
              </button>
            </li>
          ))}
        </ul>
      ) : null}
      {showNewDesigner ? (
        <Modal title="เพิ่มผู้ออกแบบใหม่" onClose={closeNewDesigner}>
          <div className="grid grid-cols-2 gap-2 mobile:grid-cols-1">
            <label className="m-0">
              <span className="text-2xs">รหัสผู้ออกแบบ *</span>
              <input
                value={newDesigner.code}
                disabled={savingDesigner}
                placeholder="เช่น A001"
                onChange={(e) => setNewDesigner((p) => ({ ...p, code: e.target.value }))}
              />
            </label>
            <label className="m-0">
              <span className="text-2xs">ชื่อผู้ออกแบบ *</span>
              <input
                value={newDesigner.name}
                disabled={savingDesigner}
                placeholder="ชื่อบริษัท / ผู้ออกแบบ"
                onChange={(e) => setNewDesigner((p) => ({ ...p, name: e.target.value }))}
              />
            </label>
          </div>
          <div className="mt-4 flex flex-wrap justify-end gap-2">
            <Button
              variant="primary"
              size="sm"
              loading={savingDesigner}
              disabled={!newDesigner.code.trim() || !newDesigner.name.trim() || savingDesigner}
              onClick={handleCreateDesigner}
            >
              บันทึกผู้ออกแบบใหม่
            </Button>
            <Button variant="secondary" size="sm" disabled={savingDesigner} onClick={closeNewDesigner}>
              ยกเลิก
            </Button>
          </div>
        </Modal>
      ) : null}
    </div>
  );
}

/** What the closed field shows: the resolved name + code, or -- when there is a code but the directory has not
 * resolved it -- the raw code rather than an empty field, or '' when there is no code at all. */
function closedDisplayValue(hint, value) {
  if (hint) return `${hint.name} (${value})${hint.active === false ? ' · ยกเลิกแล้ว' : ''}`;
  return value || '';
}

/** Resolves the CURRENT unit_code to a designer, for the picker's own display hint -- ANY status (active or
 * not), mirroring `DesignerRepository.findByCode` exactly, so reopening an old quotation whose D.Co. names a
 * since-cancelled (ยกเลิก) designer still shows who that was. */
function useDesignerHint(code) {
  const [hint, setHint] = useState(null);
  useEffect(() => {
    const trimmed = (code ?? '').trim();
    if (!trimmed) { setHint(null); return undefined; }
    let cancelled = false;
    api.designers.getByCode(trimmed)
      .then((d) => { if (!cancelled) setHint(d); })
      .catch(() => { if (!cancelled) setHint(null); });
    return () => { cancelled = true; };
  }, [code]);
  return hint;
}
