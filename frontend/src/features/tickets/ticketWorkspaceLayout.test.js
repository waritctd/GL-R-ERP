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
const ticketDetailPageJsx = readFileSync(join(process.cwd(), 'src/features/tickets/TicketDetailPage.jsx'), 'utf8');

describe('ticket workspace sticky layout source guards', () => {
  it('clears the sticky deal header via a desktop scroll-margin on the content descendants, not shell scroll-padding or per-target margins', () => {
    expect(indexCss).toContain('--app-topbar-h: 66px;');
    expect(indexCss).toContain('--deal-header-h: 18rem;');
    // Desktop scroll-padding-top on the shell scroller made native focus into the sticky deal chrome
    // (and, via the 18rem fallback, the top ~300px of every page) scroll the page.
    expect(appShellJsx).not.toContain('scroll-pt-[calc(var(--deal-header-h');
    expect(ticketDetailPageJsx).not.toContain('scroll-mt-[300px]');
    // The clearance lives once, on the content wrapper's descendants (never on the header's own controls).
    const bare = 'scroll-mt-[calc(var(--deal-header-h,18rem)_+_var(--space-4))]';
    const wrapperToken = `min-[721px]:[&_*:not([role=dialog]_*)]:${bare}`; // non-portalled Modal bodies are excluded
    expect(ticketDetailPageJsx.split(wrapperToken)).toHaveLength(2);
    expect(ticketDetailPageJsx.split(bare)).toHaveLength(2); // only inside the wrapper token
  });

  it('opts the shell scroller out of scroll anchoring for any page that mounts a sticky chrome', () => {
    expect(appShellJsx).toContain('has-[[data-sticky-chrome]]:[overflow-anchor:none]');
    expect(ticketDetailPageJsx).toContain('data-sticky-chrome');
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
