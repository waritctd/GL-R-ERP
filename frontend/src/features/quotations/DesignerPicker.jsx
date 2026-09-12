import { useEffect, useRef, useState } from 'react';
import { api } from '../../api/index.js';
import { Icon } from '../../components/common/Icon.jsx';

/**
 * ผู้ออกแบบ (designer) picker for the quotation editor's D.Co. field (owner ask, 2026-09-12).
 * Picking a designer fills the adjacent "หน่วยงาน" / D.Co. input (`sales.quotation.unit_code`)
 * with that designer's CODE ONLY -- see this file's own {@link resolveDesignerHint} for the other
 * half of the requirement.
 *
 * 🚨 THE NAME IS CONFIDENTIAL ("เป็นความลับ") AND MUST NEVER REACH A PRINTED DOCUMENT. This
 * component only ever calls `onSelectCode(code)` -- it never hands the parent a name, and the
 * parent (QuotationEditorPage) writes that code into the SAME free-text `terms.unitCode` state a
 * rep could always type into by hand. The name is used HERE ONLY, to search and to show which
 * designer a code currently resolves to; see DesignerDto's backend Javadoc for the structural
 * half of this guarantee (there is nowhere in the render path for a name to travel through even by
 * accident).
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
 * This is a SEARCH-AND-FILL assistant, not a controlled value holder -- unlike the ลูกค้า/โครงการ
 * pickers, it does not own "the current designer" as a chip. `sales.designer` is read-only from
 * this app, so there is no create-new-designer affordance either. The rep may still type a D.Co.
 * by hand directly into the existing input; this widget only ever offers to fill it faster.
 */
export function DesignerPicker({ value, onSelectCode, disabled = false, idPrefix = 'designer-picker' }) {
  const [search, setSearch] = useState('');
  const [results, setResults] = useState([]);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);
  const timer = useRef(null);
  useEffect(() => () => clearTimeout(timer.current), []);

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
    debouncedSearch(search);
  }

  function selectDesigner(d) {
    onSelectCode(d.code);
    setSearch('');
    setResults([]);
    closeDropdown();
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
        <span className="mb-1 block text-xs">ค้นหาผู้ออกแบบ</span>
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
          value={search}
          onChange={(e) => { setSearch(e.target.value); setActiveIndex(-1); setOpen(true); debouncedSearch(e.target.value); }}
          onFocus={openDropdown}
          onBlur={() => setTimeout(closeDropdown, 150)}
          onKeyDown={handleKeyDown}
        />
      </label>
      {/* The resolved NAME is shown here ONLY, as a hint for the rep -- never written into any
          field that reaches the document. Covers both "just picked" (search cleared, code just
          landed in the sibling input) and "reopened an old quotation whose unit_code already names
          a designer, active or not" (DesignerRepository.findByCode resolves either). */}
      {!open && hint ? (
        <span className="mt-1 block text-2xs text-text-muted" data-testid={`${idPrefix}-hint`}>
          <Icon name="info" size={11} /> {hint.active ? hint.name : `${hint.name} (ยกเลิกแล้ว)`}
        </span>
      ) : null}
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
