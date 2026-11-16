/**
 * The quiet links at the foot of ภาพรวม (drawing a-deal: "พักงาน · ปิดงาน (เสียงาน) · ยกเลิกงาน"). They replace the red
 * "จัดการดีล" danger zone and the ⋯ menu's "พักดีลไว้": the same three actions, behind the same gates (the page passes
 * each flag exactly as it gated the old control), opening the same existing dialogs. Renders nothing when none
 * applies. Plain text buttons, not red: these are rare, deliberate moves, not the page's work.
 */
export function DealQuietLinks({ canHold, canLose, canCancel, disabled = false, onHold, onLose, onCancel }) {
  const links = [
    canHold && { key: 'hold', label: 'พักงาน', onClick: onHold, tone: 'text-text-muted hover:text-text' },
    canLose && { key: 'lost', label: 'ปิดงาน (เสียงาน)', onClick: onLose, tone: 'text-danger-dark hover:text-danger' },
    canCancel && { key: 'cancel', label: 'ยกเลิกงาน', onClick: onCancel, tone: 'text-text-muted hover:text-text' },
  ].filter(Boolean);
  if (links.length === 0) return null;
  return (
    <nav aria-label="จัดการงาน" className="flex flex-wrap items-center justify-end gap-x-1 gap-y-1 pt-1">
      {links.map((link) => (
        <button
          key={link.key}
          type="button"
          disabled={disabled}
          onClick={link.onClick}
          className={`min-h-[44px] rounded-md border-0 bg-transparent px-3 text-sm font-bold underline-offset-4 hover:underline focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary disabled:cursor-not-allowed disabled:opacity-60 ${link.tone}`}
        >
          {link.label}
        </button>
      ))}
    </nav>
  );
}
