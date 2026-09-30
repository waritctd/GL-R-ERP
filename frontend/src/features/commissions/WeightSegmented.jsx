import { useRef } from 'react';
import { cn } from '../../utils/cn.js';

export const WEIGHT_OPTIONS = [1, 2, 3];

/**
 * ×1 / ×2 / ×3 segmented control for a commission weight (WAI-ARIA radio group, automatic
 * activation: moving with the arrow keys both moves focus and selects, exactly like native radios).
 *
 * It is built from `role="radio"` buttons rather than native radios so a segment can be styled as a
 * segment, and it uses `aria-disabled` instead of the `disabled` attribute on purpose: a natively
 * disabled button drops focus, which would throw a keyboard user out of the control every time a
 * save starts. `aria-disabled` keeps the segment focusable and the handlers below simply refuse to
 * act, so the locked / read-only / saving states are all announced and none of them eject focus.
 *
 * The eight states the control has to express:
 *   default   — neutral segments, the selected one on the selection tint with indigo text
 *   hover     — a tonal step on the segment under the pointer
 *   focus     — the shared global `:focus-visible` ring (index.css), lifted above its neighbours
 *   active    — a deeper tonal step while pressed
 *   disabled  — `disabled`: locked (import line), read-only (CEO) or a sibling save; dimmed, not-allowed
 *   loading   — `busy`: this line's own save is in flight; `aria-busy`, dimmed (the caller adds text)
 *   error     — `invalid`: danger border; the caller names the failure next to it
 *   success   — the caller's "บันทึกแล้ว" status text; the control itself just returns to default
 * Colour is never the only signal: state is also carried by aria-checked / aria-disabled / aria-busy
 * / aria-invalid and by the caller's Thai status words.
 */
export function WeightSegmented({
  label,
  value,
  onChange,
  options = WEIGHT_OPTIONS,
  disabled = false,
  busy = false,
  invalid = false,
  describedBy,
}) {
  const segmentRefs = useRef([]);

  function select(option) {
    if (disabled || option === value) return;
    onChange(option);
  }

  function handleKeyDown(event, index) {
    let next = null;
    if (event.key === 'ArrowRight' || event.key === 'ArrowDown') next = Math.min(index + 1, options.length - 1);
    else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') next = Math.max(index - 1, 0);
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = options.length - 1;
    else if (event.key === 'Enter' || event.key === ' ') {
      // preventDefault so the native Enter/Space "click" does not select a second time.
      event.preventDefault();
      select(options[index]);
      return;
    }
    if (next === null) return;
    event.preventDefault();
    if (next === index) return;
    segmentRefs.current[next]?.focus();
    select(options[next]);
  }

  return (
    <div
      role="radiogroup"
      aria-label={label}
      aria-busy={busy ? 'true' : undefined}
      aria-invalid={invalid ? 'true' : undefined}
      aria-describedby={describedBy}
      className={cn(
        'inline-flex w-fit max-w-full divide-x divide-border-input rounded-md border-[1.5px] bg-surface',
        invalid ? 'border-danger' : 'border-border-input',
        busy && 'opacity-80',
      )}
    >
      {options.map((option, index) => {
        const checked = option === value;
        return (
          <button
            key={option}
            ref={(node) => { segmentRefs.current[index] = node; }}
            type="button"
            role="radio"
            aria-checked={checked}
            aria-disabled={disabled ? 'true' : undefined}
            tabIndex={checked ? 0 : -1}
            onClick={() => select(option)}
            onKeyDown={(event) => handleKeyDown(event, index)}
            className={cn(
              'relative min-h-9 min-w-11 cursor-pointer border-0 px-3 text-sm font-bold tabular-nums',
              'transition-colors duration-[var(--motion-fast)] ease-[var(--ease-standard)] motion-reduce:transition-none',
              'first:rounded-l-[6px] last:rounded-r-[6px] focus-visible:z-10',
              'pointer-coarse:min-h-11 mobile:min-h-11',
              checked
                ? 'bg-primary-bg text-primary hover:bg-primary-bg active:bg-primary-bg'
                : 'bg-surface text-text-secondary hover:bg-surface-subtle active:bg-info-bg-alt',
              'aria-disabled:cursor-not-allowed aria-disabled:opacity-55 aria-disabled:hover:bg-inherit aria-disabled:active:bg-inherit',
            )}
          >
            {`×${option}`}
          </button>
        );
      })}
    </div>
  );
}
