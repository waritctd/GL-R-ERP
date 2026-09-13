import { useEffect, useRef, useState } from 'react';
import { api } from '../../api/index.js';

/**
 * ผู้ออกแบบ (designer) picker for the quotation editor's combined designer/D.Co. field. Picking a
 * designer displays NAME + CODE to the user, while the parent receives the CODE ONLY for saving.
 *
 * 🚨 THE NAME IS CONFIDENTIAL ("เป็นความลับ") AND MUST NEVER REACH A PRINTED DOCUMENT. This
 * component only ever calls `onSelectCode(code)` -- it never hands the parent a name, and the
 * parent (QuotationEditorPage) writes that code into `terms.unitCode`. The name is used HERE ONLY,
 * to search and to show which designer a code currently resolves to; see DesignerDto's backend
 * Javadoc for the structural half of this guarantee (there is nowhere in the render path for a
 * name to travel through even by accident).
 *
 * Interaction mirrors DealCustomerCard's โครงการ combobox -- the standard ARIA 1.2 pattern (input
 * `role="combobox"`, `aria-expanded`/`aria-controls`/`aria-autocomplete="list"`/
 * `aria-activedescendant`, arrow keys move a highlighted INDEX rather than DOM focus, Enter picks
 * the highlighted row, Escape closes) -- combined with a debounced SERVER search, the same 280ms
 * debounce DealCustomerCard's ลูกค้า combobox uses, because the designer directory (~1,100 rows,
 * V173) is too large for the โครงการ picker's client-side-filter-over-an-already-loaded-array
 * approach. This is deliberately the ONE extra combobox variant CLAUDE.md's "do not build a third
 * variant" note allows: it reuses BOTH existing patterns' mechanics rather than inventing new ones.
 *
 * The designer is OPTIONAL (owner ruling 2026-09-14 -- the backend blankToNull's unit_code and
 * never required it) and CLEARABLE: typing the box down to blank and leaving it (blur) clears a
 * previously-picked code back to '', but only when the rep actually edited the text -- a plain
 * focus-then-blur, or an Enter/click pick followed by blur, never clears. A `value` the directory
 * does not resolve (a legacy code, or a lookup that failed) is not an error state here: closed, the
 * picker shows that raw code as-is rather than going blank, and reopening seeds the search box with
 * it so the rep can search onward from it.
 */
export function DesignerPicker({ value, onSelectCode, disabled = false, idPrefix = 'designer-picker', label = 'ค้นหาผู้ออกแบบ' }) {
  const [search, setSearch] = useState('');
  const [results, setResults] = useState([]);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);
  const timer = useRef(null);
  useEffect(() => () => clearTimeout(timer.current), []);
  // Whether the rep has typed into the box since it was last opened or a designer was last
  // selected — the signal that distinguishes "cleared on purpose" (blur below) from a plain
  // focus-then-blur or an Enter/click pick that also leaves `search` blank.
  const editedRef = useRef(false);

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
    // Seed from the current code whenever there is one — not only once the directory has
    // resolved it to a name — so reopening a legacy/unresolved code still searches onward from
    // it instead of starting blank.
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

  // Blur commits a deliberate clear: the rep edited the box (not just opened and left it, and not
  // just picked a result) and left it blank while a code was set. Anything else -- a plain
  // focus/blur, or an Enter/click selection whose own reset already zeroed `editedRef` -- leaves
  // `value` exactly as it was.
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
                className={`block w-full px-3 py-2 text-left text-xs hover:bg-surface-hover ${idx === activeIndex ? 'bg-surface-hover' : ''}`}
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
    </div>
  );
}

/**
 * What the closed field shows: the resolved name + code (with a cancelled-designer suffix, since
 * `useDesignerHint` resolves ANY status), or -- when there is a code but the directory has not
 * resolved it (a legacy code, or a lookup that failed) -- the raw code rather than an empty field,
 * or '' when there is no code at all.
 */
function closedDisplayValue(hint, value) {
  if (hint) return `${hint.name} (${value})${hint.active === false ? ' · ยกเลิกแล้ว' : ''}`;
  return value || '';
}

/**
 * Resolves the CURRENT unit_code to a designer, for the picker's own display hint -- ANY status
 * (active or not), mirroring `DesignerRepository.findByCode` exactly, so reopening an old
 * quotation whose D.Co. names a since-cancelled (ยกเลิก) designer still shows who that was. Not
 * exported -- this state belongs to the picker's own hint, nowhere else.
 */
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
