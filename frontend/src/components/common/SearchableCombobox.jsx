import { useEffect, useRef, useState } from 'react';
import { Icon } from './Icon.jsx';

/** Reuses the quotation project picker's input/listbox keyboard pattern.
 * The bounded list stays in the modal's scroll flow so it can never be clipped by its body.
 */
export function SearchableCombobox({ id, label, value, options, onChange, disabled = false,
  loading = false, placeholder = 'พิมพ์ค้นหา…', ...aria }) {
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const input = useRef(null);
  const list = useRef(null);
  const selected = options.find((item) => item.code === value);
  const filtered = options.filter((item) => `${item.nameTh} ${item.nameEn ?? ''} ${item.code}`.toLocaleLowerCase('th').includes(query.trim().toLocaleLowerCase('th')));
  useEffect(() => { setQuery(''); setActive(-1); setOpen(false); }, [value, disabled]);
  useEffect(() => { list.current?.querySelector('[aria-selected="true"]')?.scrollIntoView?.({ block: 'nearest' }); }, [active]);
  function select(item) { onChange(item?.code ?? ''); setQuery(''); setOpen(false); setActive(-1); }
  return (
    <div onBlur={(e) => { if (!e.currentTarget.contains(e.relatedTarget)) { setOpen(false); setQuery(''); } }}>
      <div className="relative">
        <input {...aria} id={id} ref={input} role="combobox" autoComplete="off" disabled={disabled}
          aria-expanded={open && !disabled} aria-controls={`${id}-options`} aria-autocomplete="list"
          aria-activedescendant={open && active >= 0 && filtered[active] ? `${id}-option-${active}` : undefined}
          className="pr-11" placeholder={placeholder}
          value={open ? query : selected?.nameTh ?? ''}
          onFocus={() => { setOpen(true); setQuery(''); }}
          onClick={() => setOpen(true)}
          onChange={(e) => { setQuery(e.target.value); setOpen(true); setActive(-1); }}
          onKeyDown={(e) => {
            if (e.key === 'Escape' && open) { e.preventDefault(); e.stopPropagation(); setOpen(false); setQuery(''); }
            if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
              e.preventDefault(); setOpen(true);
              if (filtered.length) setActive((i) => i < 0 ? (e.key === 'ArrowDown' ? 0 : filtered.length - 1) : (i + (e.key === 'ArrowDown' ? 1 : -1) + filtered.length) % filtered.length);
            }
            if (e.key === 'Enter' && open) { e.preventDefault(); if (filtered[active]) select(filtered[active]); }
          }} />
        {value && !disabled ? <button type="button" className="absolute inset-y-0 right-0 flex w-11 cursor-pointer border-0 bg-transparent items-center justify-center text-text-muted"
          aria-label={`ล้าง${label}`} onClick={() => { select(null); input.current?.focus(); }}><Icon name="close" size={16} /></button> : null}
      </div>
      {open && !disabled ? <ul ref={list} id={`${id}-options`} role="listbox" aria-label={label}
        className="m-0 mt-1 max-h-40 list-none overflow-auto rounded-md border border-border bg-surface p-0">
        {loading ? <li role="presentation" className="p-3 text-sm text-text-muted">กำลังโหลด…</li> : null}
        {!loading && !filtered.length ? <li role="presentation" className="p-3 text-sm text-text-muted">ไม่พบข้อมูล</li> : null}
        {!loading && filtered.map((item, index) => <li key={item.code} role="presentation">
          <button type="button" role="option" id={`${id}-option-${index}`} tabIndex={-1} aria-selected={index === active}
            className={`min-h-11 w-full cursor-pointer border-0 px-3 py-2 text-left text-sm hover:bg-surface-hover ${index === active ? 'bg-surface-hover' : 'bg-surface'}`}
            onMouseDown={(e) => e.preventDefault()} onClick={() => select(item)} onMouseEnter={() => setActive(index)}>{item.nameTh}</button>
        </li>)}
      </ul> : null}
    </div>
  );
}
