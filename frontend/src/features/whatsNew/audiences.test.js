import { afterEach, describe, expect, it, vi } from 'vitest';

// noticePayload is what the CEO/HR control sends to /api/release-notes/*: publishPayload's audience (the same role
// filter as the page, so nobody is told about a page they cannot open) plus the email copy the backend renders.
describe('noticePayload', () => {
  // Production runs with the release lockdown on. A release lists an entry only for roles that can open its page there, so
  // the lockdown must drop nobody: what the email tells each role is exactly what the section lists.
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
      const release = findRelease('v2.0.0');

      const payload = locked.noticePayload(release);

      for (const entry of release.entries) {
        const listed = locked.ALL_ROLES.filter((role) => release.sections[role].includes(entry.id));
        expect(locked.rolesForEntry(release, entry), entry.id).toEqual(listed);
      }
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
