import { useState } from 'react';
import { Icon } from '../../components/common/Icon.jsx';
import { cn } from '../../utils/cn.js';

/**
 * TypePicker — group → type (plan §"Type grouping"), replacing the flat 12-option `<select>`.
 * Two-step disclosure so twelve types never compete with equal weight: each group is a
 * button-toggled panel, containing native `<input type="radio">`s each wrapped in a real
 * `<label>`. `groups[0]` is open by default per the plan ("group 1 open by default").
 *
 * ONE bordered surface for the whole control (not one box per group -- card-diet rule, plan
 * §"Craft rules"): groups are separated by a divider (`divide-y`), matching the
 * typography-plus-divider pattern `LeavePage.jsx:572-583` uses for its own grouped rows, rather
 * than nesting four bordered boxes inside the Panel that already wraps this whole form.
 *
 * A collapsed group's content is driven from STATE, not the `hidden` attribute alone. `[hidden] {
 * display: none }` is a rule in Tailwind's own PREFLIGHT (base) layer, and any `display` utility --
 * `flex` here -- sits in the utilities layer, which by `@layer` precedence always wins over base.
 * So `hidden` + `className="flex"` on one element does NOT hide it: the "collapsed" group stays
 * visually and interactively open, with `aria-expanded="false"` lying to assistive tech while every
 * radio underneath is still tabbable. (This is NOT about `styles.css` load order -- that file
 * governs a different cascade problem; see the radio sizing note below.) `hidden={!isOpen}` is kept
 * alongside the `hidden`/`flex` class branch as a belt-and-suspenders semantic signal, but the
 * class branch is what actually controls rendering.
 *
 * Each group gets its own native radio `name` (not one name shared across all twelve): with the
 * collapse now actually working, `Tab`/arrow-key roving-tabindex on a single shared `name` would
 * only ever reach the checked radio's own group, permanently trapping keyboard users out of every
 * other group. Cross-group mutual exclusivity still holds because `checked` is fully controlled
 * from `value` (React re-renders every radio's `checked` prop on every selection, regardless of
 * `name`) -- the native same-`name` auto-exclusivity was never load-bearing.
 *
 * Keyboard operable end to end: group headers are real `<button>`s (Enter/Space toggles,
 * `aria-expanded`/`aria-controls` wired), and radios are native inputs (arrow-key selection
 * within a group, Tab between groups) — nothing here is a div with an onClick standing in for a
 * real control.
 */
export function TypePicker({ groups, value, onSelect, className }) {
  const [openKeys, setOpenKeys] = useState(() => new Set(groups[0] ? [groups[0].key] : []));

  function toggleGroup(key) {
    setOpenKeys((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  return (
    <div className={cn('divide-y divide-border-subtle rounded-md border border-border', className)}>
      {groups.map((group) => {
        if (group.items.length === 0) return null;
        const isOpen = openKeys.has(group.key);
        const panelId = `smr-type-group-panel-${group.key}`;
        const buttonId = `smr-type-group-button-${group.key}`;
        return (
          <div key={group.key}>
            <button
              type="button"
              id={buttonId}
              className="flex w-full items-center justify-between gap-2 bg-surface px-3.5 py-2.5 text-left text-sm font-bold text-text hover:bg-surface-subtle"
              aria-expanded={isOpen}
              aria-controls={panelId}
              onClick={() => toggleGroup(group.key)}
            >
              {group.label}
              <Icon name={isOpen ? 'chevronUp' : 'chevronDown'} size={16} />
            </button>
            <div
              id={panelId}
              role="radiogroup"
              aria-labelledby={buttonId}
              hidden={!isOpen}
              // The `flex`/`hidden` utility classes are mutually exclusive by construction (never
              // both present at once) -- see the file-level comment on why that matters: with
              // both present, whichever Tailwind happens to emit later in its generated
              // stylesheet wins regardless of the `hidden` DOM attribute.
              className={cn(
                'flex-col gap-0.5 border-t border-border-subtle p-1.5',
                isOpen ? 'flex' : 'hidden',
              )}
            >
              {group.items.map((type) => (
                <label
                  key={type.requestType}
                  className={cn(
                    'flex cursor-pointer items-center gap-2 rounded-md px-2.5 py-2 text-sm',
                    value === type.requestType ? 'bg-surface-subtle font-bold text-primary' : 'text-text hover:bg-surface-subtle',
                  )}
                >
                  <input
                    type="radio"
                    name={`smr-request-type-${group.key}`}
                    value={type.requestType}
                    checked={value === type.requestType}
                    onChange={() => onSelect(type.requestType)}
                    // The explicit size is no longer load-bearing, only deliberate. It used to be
                    // the workaround for `styles.css`'s blanket `input, select, textarea
                    // { width: 100% }`, which a bare radio inherited like any other input and which
                    // ballooned it to fill the label (measured: 1040px wide inside a 1060px label
                    // at 1440 viewport, pushing the Thai text off the right edge). That base rule
                    // now excludes checkbox/radio outright and resets them, so a bare radio is
                    // ~13px on its own. `h-4 w-4` is kept because 16px reads better beside 14px
                    // Thai text than the 13px UA default, and `shrink-0` because a flex row full
                    // of long Thai labels would otherwise squash it.
                    className="h-4 w-4 shrink-0"
                  />
                  {type.thaiLabel}
                </label>
              ))}
            </div>
          </div>
        );
      })}
    </div>
  );
}
