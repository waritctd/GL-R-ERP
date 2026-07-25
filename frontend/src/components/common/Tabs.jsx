import { useEffect, useId, useRef } from 'react';
import { cn } from '../../utils/cn.js';

function normalizeIndex(index, length) {
  if (length <= 0) return -1;
  if (index < 0) return length - 1;
  if (index >= length) return 0;
  return index;
}

export function Tabs({ tabs = [], activeId, onChange, ariaLabel, children, className }) {
  const baseId = useId();
  const tabRefs = useRef({});
  const activeIndex = Math.max(0, tabs.findIndex((tab) => tab.id === activeId));
  const activeTab = tabs[activeIndex] ?? null;

  useEffect(() => {
    tabRefs.current[activeId]?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' });
  }, [activeId]);

  function selectByIndex(index) {
    const next = tabs[normalizeIndex(index, tabs.length)];
    if (!next || next.disabled) return;
    onChange?.(next.id);
    requestAnimationFrame(() => tabRefs.current[next.id]?.focus());
  }

  function onKeyDown(event) {
    if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
      event.preventDefault();
      selectByIndex(activeIndex + 1);
    } else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
      event.preventDefault();
      selectByIndex(activeIndex - 1);
    } else if (event.key === 'Home') {
      event.preventDefault();
      selectByIndex(0);
    } else if (event.key === 'End') {
      event.preventDefault();
      selectByIndex(tabs.length - 1);
    }
  }

  if (!activeTab) return null;

  const panelId = `${baseId}-${activeTab.id}-panel`;
  const activeTabId = `${baseId}-${activeTab.id}-tab`;

  return (
    <div className={cn('min-w-0 max-w-full', className)}>
      <div className="-mx-1 overflow-x-auto px-1" data-testid="tabs-scroll-container">
        <div
          role="tablist"
          aria-label={ariaLabel}
          tabIndex={-1}
          className="flex min-w-max gap-1 border-b border-border"
          onKeyDown={onKeyDown}
        >
          {tabs.map((tab) => {
            const selected = tab.id === activeTab.id;
            return (
              <button
                key={tab.id}
                ref={(node) => { tabRefs.current[tab.id] = node; }}
                type="button"
                role="tab"
                id={`${baseId}-${tab.id}-tab`}
                aria-controls={`${baseId}-${tab.id}-panel`}
                aria-selected={selected}
                tabIndex={selected ? 0 : -1}
                className={cn(
                  'shrink-0 border-b-2 px-3 py-2.5 text-sm font-bold transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
                  selected
                    ? 'border-primary text-primary'
                    : 'border-transparent text-text-muted hover:text-text',
                )}
                onClick={() => onChange?.(tab.id)}
              >
                {tab.label}
              </button>
            );
          })}
        </div>
      </div>

      <div
        role="tabpanel"
        id={panelId}
        aria-labelledby={activeTabId}
        tabIndex={0}
        className="min-w-0 pt-4 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus"
      >
        {typeof children === 'function' ? children(activeTab) : children}
      </div>
    </div>
  );
}
