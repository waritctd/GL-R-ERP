import { afterEach, describe, expect, it, vi } from 'vitest';
import { RELEASES } from './releases/index.js';
import { ALL_ROLES, noticePayload, publishPayload, rolesForEntry } from './audiences.js';

// noticePayload is what the CEO/HR control sends to /api/release-notes/*: publishPayload's audience (the same role
// filter as the page, so nobody is told about a page they cannot open) plus the email copy the backend renders —
// each entry's one-sentence `why` and the release `date` for the subject. Built from the release file, never typed
// a second time, so this holds for every release in RELEASES, not one id.
describe('noticePayload', () => {
  for (const release of RELEASES) {
    describe(release.id, () => {
      const payload = noticePayload(release);

      it('names the release, its title and its date', () => {
        expect(payload.releaseId).toBe(release.id);
        expect(payload.title).toBe(release.title);
        expect(payload.date).toBe(release.date);
        expect(payload.date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
      });

      it('has exactly publishPayload’s changes and roles, each with that entry’s own why', () => {
        const audience = publishPayload(release);
        expect(payload.changes.map(({ title, roles }) => ({ title, roles }))).toEqual(audience.changes);
        for (const change of payload.changes) {
          const entry = release.entries.find((e) => e.title === change.title);
          expect(change.why, change.title).toBe(entry.why);
          expect(change.roles).toEqual(rolesForEntry(release, entry));
        }
      });

      it('wrong-way-round: a role is never listed on an entry its section does not hold', () => {
        for (const change of payload.changes) {
          const entry = release.entries.find((e) => e.title === change.title);
          for (const role of ALL_ROLES.filter((r) => !change.roles.includes(r))) {
            const toldElsewhere = rolesForEntry(release, entry).includes(role);
            expect(toldElsewhere, `${change.title} / ${role}`).toBe(false);
          }
        }
      });

      it('keeps every why within the server limit (300 characters)', () => {
        for (const change of payload.changes) {
          expect((change.why ?? '').length, change.title).toBeLessThanOrEqual(300);
        }
      });
    });
  }

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

  it('drops a blank why rather than sending an empty line', () => {
    const release = {
      id: 'x-1', date: '2026-12-01', title: 't',
      entries: [{ id: 'a', path: '/', title: 'A', why: '   ' }],
      sections: Object.fromEntries(ALL_ROLES.map((role) => [role, ['a']])),
    };
    expect(noticePayload(release).changes).toEqual([{ title: 'A', roles: ALL_ROLES, why: null }]);
  });
});
