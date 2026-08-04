import { initialsFromName } from '../../utils/format.js';
import { cn } from '../../utils/cn.js';

// Tailwind port of `.avatar` / `.avatar-*` (styles.css, retired). `flex-none`
// is `flex: 0 0 auto`, the exact declaration the legacy rule carried — the
// avatar must never shrink inside the flex rows it sits in (`.request-feed-item`,
// `.employee-cell`, the profile strip).
const AVATAR_BASE = 'avatar grid place-items-center flex-none rounded-md font-extrabold';

// `xs` is the compact variant for dense mobile record cards, where a 38px
// avatar sets the whole row's height and pushes the card taller than the text
// needs. Sizes carry no font-size of their own where the legacy rule didn't
// either (`md`), so they keep inheriting.
// Line-height note (applies to every `text-*/[inherit]` below): Tailwind's
// `text-xs`/`-sm`/`-base`/`-lg`/`-xl`/`-2xl`/`-3xl` utilities ALSO set a
// line-height, from the `--text-*--line-height` keys Tailwind's own default
// theme ships (this app's `@theme static` overrides the sizes but not those).
// The legacy rules being ported here set `font-size` ONLY and left line-height
// inherited, so a bare `text-xs` silently re-spaces the text — measured as a
// 1-2px shift, and a ~6px page-wide reflow where a caption wraps.
//
// The `/[inherit]` modifier is the fix, NOT `leading-[inherit]`: `leading-*`
// works by setting `--tw-leading`, and `--tw-leading: inherit` is parsed as the
// CSS-wide keyword (inherit the custom property itself), which leaves the var()
// unresolved so `text-xs` falls back to Tailwind's line-height anyway. The
// modifier form emits a literal `line-height: inherit` in the same rule.
//
// (`text-2xs` and `text-md` are this app's own theme keys with no paired
// line-height, so they are already font-size-only and need no modifier.)
const AVATAR_SIZES = {
  xs: 'w-7 h-7 text-2xs',
  sm: 'w-[38px] h-[38px] text-sm/[inherit]',
  md: 'w-[46px] h-[46px]',
  lg: 'w-16 h-16 text-xl/[inherit]',
  xl: 'w-[78px] h-[78px] text-3xl/[inherit]',
};

export function Avatar({ employee, name, size = 'md' }) {
  const label = employee?.initials || initialsFromName(name || employee?.nameTh);
  return (
    <div
      className={cn(AVATAR_BASE, AVATAR_SIZES[size] ?? AVATAR_SIZES.md)}
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
