import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import process from 'node:process';
import { describe, expect, it } from 'vitest';

const indexCss = readFileSync(join(process.cwd(), 'src/index.css'), 'utf8');
const dealStateHeaderJsx = readFileSync(join(process.cwd(), 'src/features/tickets/DealStateHeader.jsx'), 'utf8');
// AppShell.test.jsx renders shell markup; these two guards specifically pin the
// exact `calc()` chrome-clearance expressions, so a source-text check on the
// Tailwind port of `.content-scroll` (moved off styles.css in the Tailwind-first
// shell conversion — see AppShell.jsx's CONTENT_SCROLL_CLASS comment) is the
// direct equivalent of the old styles.css text guard.
const appShellJsx = readFileSync(join(process.cwd(), 'src/components/layout/AppShell.jsx'), 'utf8');

describe('ticket workspace sticky layout source guards', () => {
  it('keeps one measured ticket chrome height token with a safe fallback', () => {
    expect(indexCss).toContain('--app-topbar-h: 66px;');
    expect(indexCss).toContain('--deal-header-h: 18rem;');
    expect(appShellJsx).toContain('scroll-pt-[calc(var(--deal-header-h,18rem)_+_var(--space-4))]');
  });

  it('keeps mobile focus scrolling clear of the fixed bottom action bar', () => {
    expect(appShellJsx).toContain('mobile:scroll-pb-[calc(112px_+_env(safe-area-inset-bottom))]');
  });

  it('keeps the ticket action bar fixed to the bottom only on mobile', () => {
    expect(dealStateHeaderJsx).toContain('data-testid="ticket-action-bar"');
    expect(dealStateHeaderJsx).toContain('mobile:fixed mobile:inset-x-0 mobile:bottom-0');
    expect(dealStateHeaderJsx).toContain('mobile:[padding-bottom:max(18px,env(safe-area-inset-bottom))]');
  });
});
