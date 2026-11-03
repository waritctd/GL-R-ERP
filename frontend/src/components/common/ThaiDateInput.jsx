import React, { forwardRef, useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Controller } from 'react-hook-form';
import CalendarDays from 'lucide-react/dist/esm/icons/calendar-days.mjs';
import ChevronLeft from 'lucide-react/dist/esm/icons/chevron-left.mjs';
import ChevronRight from 'lucide-react/dist/esm/icons/chevron-right.mjs';
import { cn } from '../../utils/cn.js';
import { thaiMonthsLong } from '../../utils/format.js';

// A day-date field that SHOWS and ACCEPTS Buddhist-era years. A native <input type="date"> renders
// ค.ศ. in every locale, so a rep typing 2570 stored CE 2570 and the PDF (which adds 543) printed
// 3113. The value contract stays the native one -- '' or ISO 'YYYY-MM-DD' (ค.ศ.) -- so call sites
// keep their state and payloads; only what the user sees and types is พ.ศ.

const BE_OFFSET = 543;
const MIN_BE_YEAR = 2400; // below this a slash date is almost certainly a ค.ศ. year typed by mistake
const ERR_FORMAT = 'กรอกวันที่เป็น วว/ดด/ปปปป (ปี พ.ศ.)';
const ERR_YEAR = 'กรอกปี พ.ศ. เช่น 2570';
const WEEKDAYS = ['จ', 'อ', 'พ', 'พฤ', 'ศ', 'ส', 'อา'];
const POPOVER_W = 288;
const POPOVER_H = 330;

const pad = (n) => String(n).padStart(2, '0');
const toIso = (y, m, d) => `${String(y).padStart(4, '0')}-${pad(m)}-${pad(d)}`;

function isRealDate(y, m, d) {
  const dt = new Date(Date.UTC(y, m - 1, d));
  return dt.getUTCFullYear() === y && dt.getUTCMonth() === m - 1 && dt.getUTCDate() === d;
}

function isoToBe(iso) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso || '');
  return match ? `${match[3]}/${match[2]}/${Number(match[1]) + BE_OFFSET}` : '';
}

// -> { status: 'empty' | 'valid' | 'partial' | 'invalid', iso?, error? }. `partial` is still being
// typed (digits and separators only): no error is shown until blur.
function parseText(raw) {
  const text = raw.trim();
  if (!text) return { status: 'empty', iso: '' };
  const iso = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text);
  if (iso) {
    const [y, m, d] = iso.slice(1).map(Number);
    if (y >= MIN_BE_YEAR) return { status: 'invalid', error: ERR_YEAR }; // a พ.ศ. year in ISO shape
    return isRealDate(y, m, d) ? { status: 'valid', iso: text } : { status: 'invalid', error: ERR_FORMAT };
  }
  const be = /^(\d{1,2})\/(\d{1,2})\/(\d{4})$/.exec(text);
  if (be) {
    const [d, m, by] = be.slice(1).map(Number);
    if (by < MIN_BE_YEAR) return { status: 'invalid', error: ERR_YEAR };
    return isRealDate(by - BE_OFFSET, m, d)
      ? { status: 'valid', iso: toIso(by - BE_OFFSET, m, d) }
      : { status: 'invalid', error: ERR_FORMAT };
  }
  return /^[\d/-]*$/.test(text) ? { status: 'partial' } : { status: 'invalid', error: ERR_FORMAT };
}

function todayBangkok() {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Bangkok' }).format(new Date());
}

function eventLike(name, value) {
  return { target: { name, value }, currentTarget: { name, value }, type: 'change' };
}

function Calendar({ view, setView, selected, today, min, max, onPick, popRef, style }) {
  const { y, m } = view;
  const offset = (new Date(Date.UTC(y, m, 1)).getUTCDay() + 6) % 7; // Monday first
  const days = new Date(Date.UTC(y, m + 1, 0)).getUTCDate();
  const shift = (delta) => {
    const next = new Date(Date.UTC(y, m + delta, 1));
    setView({ y: next.getUTCFullYear(), m: next.getUTCMonth() });
  };
  const navBtn = 'inline-flex h-9 w-9 items-center justify-center rounded-md border-0 bg-transparent p-0 text-icon-muted hover:bg-surface-subtle';
  const cells = [];
  for (let i = 0; i < offset; i += 1) cells.push(<span key={`b${i}`} />);
  for (let d = 1; d <= days; d += 1) {
    const iso = toIso(y, m + 1, d);
    const isSelected = iso === selected;
    cells.push(
      <button
        key={iso}
        type="button"
        aria-label={`${d} ${thaiMonthsLong[m]} ${y + BE_OFFSET}`}
        aria-pressed={isSelected}
        aria-current={iso === today ? 'date' : undefined}
        disabled={(min && iso < min) || (max && iso > max)}
        onClick={() => onPick(iso)}
        className={cn(
          'h-9 w-full rounded-md border-0 p-0 text-sm font-semibold hover:bg-surface-subtle',
          iso === today && 'ring-1 ring-primary',
          isSelected ? 'bg-primary text-surface hover:bg-primary-hover' : 'bg-transparent text-text',
        )}
      >
        {d}
      </button>,
    );
  }
  return (
    <div
      ref={popRef}
      role="dialog"
      aria-label="เลือกวันที่"
      className="fixed z-[60] rounded-md border border-border bg-surface p-3 shadow-[var(--shadow-lg-heavy)]"
      style={{ width: POPOVER_W, ...style }}
    >
      <div className="mb-2 flex items-center justify-between">
        <button type="button" className={navBtn} aria-label="เดือนก่อนหน้า" onClick={() => shift(-1)}>
          <ChevronLeft size={18} aria-hidden="true" />
        </button>
        <strong className="text-sm">{thaiMonthsLong[m]} {y + BE_OFFSET}</strong>
        <button type="button" className={navBtn} aria-label="เดือนถัดไป" onClick={() => shift(1)}>
          <ChevronRight size={18} aria-hidden="true" />
        </button>
      </div>
      <div className="grid grid-cols-7 gap-0.5 text-center">
        {WEEKDAYS.map((w) => <span key={w} className="py-1 text-xs font-bold text-text-muted">{w}</span>)}
        {cells}
      </div>
    </div>
  );
}

export const ThaiDateInput = forwardRef(function ThaiDateInput({
  value,
  onChange,
  onBlur,
  name,
  id,
  className,
  wrapperClassName,
  disabled,
  min,
  max,
  placeholder = 'วว/ดด/ปปปป',
  'aria-describedby': describedBy,
  'aria-invalid': ariaInvalid,
  ...rest
}, ref) {
  const current = value ?? '';
  const autoId = useId();
  const errorId = `${id ?? autoId}-date-error`;
  const [text, setText] = useState(() => isoToBe(current));
  const [synced, setSynced] = useState(current);
  const [error, setError] = useState('');
  const [open, setOpen] = useState(false);
  const [view, setView] = useState({ y: 2000, m: 0 });
  const [pos, setPos] = useState(null);
  const wrapRef = useRef(null);
  const popRef = useRef(null);
  const btnRef = useRef(null);
  const inputRef = useRef(null);
  const setInputRef = useCallback((node) => {
    inputRef.current = node;
    if (typeof ref === 'function') ref(node);
    else if (ref) ref.current = node;
  }, [ref]);
  const parsedText = parseText(text);
  // What the text means to the parent: a date, or '' for empty/partial/invalid text.
  const textValue = parsedText.iso ?? '';

  // Follow a value changed from outside (form reset, copy-from) -- unless the text already means
  // that value, which is the echo of what the user just typed and must not be reformatted mid-edit.
  if (current !== synced) {
    setSynced(current);
    if (textValue !== current) {
      setText(isoToBe(current));
      setError('');
    }
  }

  function handleChange(event) {
    const next = event.target.value;
    setText(next);
    const parsed = parseText(next);
    setError(parsed.status === 'invalid' ? parsed.error : '');
    // Invalid/partial text clears the value (typed text stays), so required/zod cannot pass on the
    // previous date while the field shows something else.
    onChange?.(eventLike(name, parsed.iso ?? ''));
  }

  function handleBlur(event) {
    const parsed = parseText(text);
    if (parsed.status === 'valid') setText(isoToBe(parsed.iso));
    else if (parsed.status === 'partial') setError(ERR_FORMAT);
    onBlur?.(event);
  }

  const validityMessage = parsedText.status === 'invalid' ? parsedText.error : parsedText.status === 'partial' ? ERR_FORMAT : '';
  useEffect(() => {
    inputRef.current?.setCustomValidity?.(validityMessage);
  }, [validityMessage]);

  function openCalendar() {
    const base = textValue || todayBangkok();
    setView({ y: Number(base.slice(0, 4)), m: Number(base.slice(5, 7)) - 1 });
    setOpen(true);
  }

  function pick(iso) {
    setText(isoToBe(iso));
    setError('');
    onChange?.(eventLike(name, iso));
    setOpen(false);
    btnRef.current?.focus();
  }

  useLayoutEffect(() => {
    if (!open) return undefined;
    function place() {
      const rect = wrapRef.current?.getBoundingClientRect();
      if (!rect) return;
      const vw = document.documentElement.clientWidth;
      const vh = document.documentElement.clientHeight;
      const left = Math.max(8, Math.min(rect.left, vw - POPOVER_W - 8));
      setPos(rect.bottom + 4 + POPOVER_H > vh && rect.top > POPOVER_H
        ? { left, bottom: vh - rect.top + 4 }
        : { left, top: rect.bottom + 4 });
    }
    place();
    window.addEventListener('scroll', place, true);
    window.addEventListener('resize', place);
    return () => {
      window.removeEventListener('scroll', place, true);
      window.removeEventListener('resize', place);
      setPos(null);
    };
  }, [open]);

  // Once the popover is actually mounted (it only is after `pos` is measured), move focus into it.
  // Keyed on whether `pos` exists, not on `pos` itself: it is a new object on every scroll/resize
  // (a phone keyboard resizes), and re-focusing then would steal focus from the text field.
  const placed = Boolean(pos);
  useEffect(() => {
    const pop = popRef.current;
    if (!open || !placed || !pop) return;
    (pop.querySelector('[aria-pressed="true"]:not(:disabled)')
      ?? pop.querySelector('[aria-current="date"]:not(:disabled)')
      ?? pop.querySelector('[aria-pressed]:not(:disabled)'))?.focus();
  }, [open, placed]);

  // Escape closes only the calendar. Capture phase on document, so it also works when the browser
  // left focus on <body> and a surrounding Modal's own document listener never sees the key.
  useEffect(() => {
    if (!open) return undefined;
    function onEscape(event) {
      if (event.key !== 'Escape') return;
      event.stopPropagation();
      setOpen(false);
      btnRef.current?.focus();
    }
    document.addEventListener('keydown', onEscape, true);
    return () => document.removeEventListener('keydown', onEscape, true);
  }, [open]);

  useEffect(() => {
    if (!open) return undefined;
    function onDown(event) {
      if (!popRef.current?.contains(event.target) && !wrapRef.current?.contains(event.target)) setOpen(false);
    }
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [open]);

  // Tabbing/clicking focus out of the calendar to somewhere else closes it.
  // Also on the wrapper (React blur bubbles, portal included): leaving the text field or the button for anywhere outside
  // both the field and the calendar closes it, so the Escape listener never outlives the focus.
  function handleFocusLeave(event) {
    const next = event.relatedTarget;
    if (next && !popRef.current?.contains(next) && !wrapRef.current?.contains(next)) setOpen(false);
  }

  const describedByAll = [describedBy, error ? errorId : null].filter(Boolean).join(' ') || undefined;

  return (
    <div ref={wrapRef} className={cn('relative w-full min-w-0', wrapperClassName)} onBlur={handleFocusLeave}>
      <input
        {...rest}
        ref={setInputRef}
        id={id}
        name={name}
        type="text"
        inputMode="numeric"
        autoComplete="off"
        placeholder={placeholder}
        disabled={disabled}
        // Inert on a text input; kept as attributes so the bounds stay inspectable. The calendar enforces them.
        min={min}
        max={max}
        value={text}
        onChange={handleChange}
        onBlur={handleBlur}
        aria-invalid={error ? true : ariaInvalid}
        aria-describedby={describedByAll}
        className={cn(className, 'pr-11')}
      />
      <button
        ref={btnRef}
        type="button"
        aria-label="เลือกวันที่"
        aria-haspopup="dialog"
        aria-expanded={open}
        disabled={disabled}
        onClick={() => (open ? setOpen(false) : openCalendar())}
        className="absolute right-0 top-0 inline-flex h-10 w-10 items-center justify-center border-0 bg-transparent p-0 text-icon-muted mobile:h-11 mobile:w-11"
      >
        <CalendarDays size={18} aria-hidden="true" />
      </button>
      {error ? <p id={errorId} className="m-0 mt-1 text-xs font-bold text-danger">{error}</p> : null}
      {open && pos
        ? createPortal(
          <Calendar
            view={view}
            setView={setView}
            selected={textValue}
            today={todayBangkok()}
            min={min}
            max={max}
            onPick={pick}
            popRef={popRef}
            style={pos}
          />,
          document.body,
        )
        : null}
    </div>
  );
});

// react-hook-form binding. A thin component (not an inline <Controller>) so that a parent FormField
// still finds the field by `id` among its children and injects aria-invalid/-describedby/-required.
// `onChange` is an extra handler run after the form's own (what `register(name, { onChange })` did).
export function ThaiDateController({ control, name, rules, onChange, ...props }) {
  return (
    <Controller
      control={control}
      name={name}
      rules={rules}
      render={({ field }) => (
        <ThaiDateInput
          {...props}
          {...field}
          onChange={(event) => { field.onChange(event); onChange?.(event); }}
        />
      )}
    />
  );
}
