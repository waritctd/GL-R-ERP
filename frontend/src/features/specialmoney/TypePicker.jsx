import { useState } from 'react';
import { Icon } from '../../components/common/Icon.jsx';
import { cn } from '../../utils/cn.js';

/** TypePicker — group → type (plan §"Type grouping"), replacing the flat 12-option `<select>`. Two-step
 * disclosure so twelve types never compete with equal weight: each group is a button-toggled panel, containing
 * native `<input type="radio">`s each wrapped in a real `<label>`. */
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
    <div
      className={cn(
        // Chrome harmonized with the พนักงาน select's own control styling so this reads as the same family of control,
        // even though it stays a grouped-radio disclosure underneath, not an actual <select> -- see the file-level
        // comment above.
        'divide-y divide-border-subtle rounded-md border-[1.5px] border-border-input bg-surface',
        className,
      )}
    >
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
              // `min-h-10` + `px-3` match the select's 40px height / 12px horizontal padding rhythm (styles.css: `height:
              // 40px; padding: 0 12px`) for the same reason as the border/radius/background above -- one visual family, same
              // disclosure behavior.
              className="flex min-h-10 w-full appearance-none items-center justify-between gap-2 border-0 bg-surface px-3 py-2.5 text-left text-sm font-bold text-text hover:bg-surface-subtle"
              aria-expanded={isOpen}
              aria-controls={panelId}
              onClick={() => toggleGroup(group.key)}
            >
              {group.label}
              {/* Chevron doubles as the dropdown-affordance cue the select gets for free from its native browser arrow, and
               * as the existing expand/collapse indicator -- up when open, down when collapsed, same icon this button
               * already used. */}
              <Icon name={isOpen ? 'chevronUp' : 'chevronDown'} size={16} className="shrink-0 text-icon-muted" />
            </button>
            <div
              id={panelId}
              role="radiogroup"
              aria-labelledby={buttonId}
              hidden={!isOpen}
              // The `flex`/`hidden` utility classes are mutually exclusive by construction (never both present at once) --
              // see the file-level comment on why that matters: with both present, whichever Tailwind happens to emit later
              // in its generated stylesheet wins regardless of the `hidden` DOM attribute.
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
                    // The explicit size is no longer load-bearing, only deliberate. That base rule now excludes checkbox/radio
                    // outright and resets them, so a bare radio is ~13px on its own.
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
