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

// The backend's own "no value" placeholder, which reaches us as a real string and therefore
// survives `||`. EmployeeRepository#fullName returns "-" when an employee has no English first or
// last name, and #initials feeds that same string into its English branch: `hasText("-")` is true,
// so it takes the first character and returns "-" without ever reaching the Thai fallback below it.
// Nobody in the UAT seed has an English name, so EVERY avatar in the app — topbar, employee list,
// profile, approval queues — rendered a dash for every user, on every page.
//
// Treated here rather than in Java on purpose: the value is display-only, the field already exists
// and keeps its type, and the frontend deploys on its own. Fixing #initials in the backend is still
// the root fix (it should not accept its sibling's placeholder as a name) but that ships with the
// Render service, not with this branch.
const NO_VALUE = '-';

export function Avatar({ employee, name, size = 'md' }) {
  const supplied = employee?.initials;
  const label = (supplied && supplied !== NO_VALUE ? supplied : null)
    || initialsFromName(name || employee?.nameTh);
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
