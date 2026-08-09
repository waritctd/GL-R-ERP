import { cn } from '../../utils/cn.js';
import { initialsFromName } from '../../utils/format.js';

/**
 * Size -> dimension/font-size utilities. Ported from `.avatar-xs/-sm/-md/-lg/-xl`
 * in styles.css. Sizes use arbitrary pixel values (28/38/46/64/78px) rather
 * than the spacing scale to match byte-for-byte without depending on whether
 * Tailwind's dynamic spacing multipliers cover non-standard steps like 9.5 or
 * 19.5. Font-size deliberately uses the `text-[length:...]` form: the named
 * `text-*` scale utilities (text-sm/text-xl/text-3xl) bundle a Tailwind
 * default line-height via `--text-*--line-height` (confirmed in the built
 * CSS), which the legacy `.avatar-*` rules never set. `md` sets no font-size
 * at all on purpose — it inherits, matching the legacy rule exactly.
 */
const AVATAR_SIZE_CLASSES = {
  xs: 'h-[28px] w-[28px] text-[length:var(--text-2xs)]',
  sm: 'h-[38px] w-[38px] text-[length:var(--text-sm)]',
  md: 'h-[46px] w-[46px]',
  lg: 'h-[64px] w-[64px] text-[length:var(--text-xl)]',
  xl: 'h-[78px] w-[78px] text-[length:var(--text-3xl)]',
};

export function Avatar({ employee, name, size = 'md' }) {
  const label = employee?.initials || initialsFromName(name || employee?.nameTh);
  return (
    <div
      className={cn('grid flex-none place-items-center rounded-md font-extrabold', AVATAR_SIZE_CLASSES[size])}
      style={{
        background: employee?.avatarBg || '#e2e8f0',
        color: employee?.avatarFg || '#475569',
      }}
      title={employee?.nameTh || name}
    >
      {label}
    </div>
  );
}
