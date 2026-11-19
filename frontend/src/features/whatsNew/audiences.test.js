import { afterEach, describe, expect, it, vi } from 'vitest';

// noticePayload is what the CEO/HR control sends to /api/release-notes/*: publishPayload's audience (the same role
// filter as the page, so nobody is told about a page they cannot open) plus the email copy the backend renders.
describe('noticePayload', () => {
  // Production runs with the release lockdown on. There, account and import cannot open /quotations, so an entry about
  // that page must not reach them by email (it reaches nobody, so it is left out). This is the case that tells the
  // page's visibility filter apart from a plain "whoever's section lists it", which the unlocked runs above cannot.
  describe('under the release lockdown (production)', () => {
    afterEach(() => {
      vi.unstubAllEnvs();
      vi.resetModules();
    });

    it('a role is never told, by email, about a page it cannot open', async () => {
      vi.stubEnv('VITE_SELF_SERVICE_ONLY', 'true');
      vi.resetModules();
      const locked = await import('./audiences.js');
      const { findRelease } = await import('./releases/index.js');
      const release = findRelease('2026-11-17');
      const view = release.entries.find((entry) => entry.id === 'quotation-list-view');

      const payload = locked.noticePayload(release);

      expect(payload.changes.map((change) => change.title)).not.toContain(view.title);
      expect(payload.changes).toEqual(locked.publishPayload(release).changes
        .map((change) => ({ ...change, why: release.entries.find((e) => e.title === change.title).why })));
      for (const change of payload.changes) {
        const entry = release.entries.find((e) => e.title === change.title);
        for (const role of change.roles) {
          expect(locked.entryVisibleTo(entry, { id: 0, role, employeeId: 0 }), `${change.title} / ${role}`).toBe(true);
        }
      }
    });
  });
});
