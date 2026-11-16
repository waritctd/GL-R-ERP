import { afterEach, describe, expect, it, vi } from 'vitest';
import { RELEASES, findRelease, latestRelease } from './index.js';
import {
  ALL_ROLES,
  CHANGE_KINDS,
  allTabSections,
  audienceLabels,
  entriesForViewer,
  entryVisibleTo,
  isForEveryone,
  publishPayload,
} from '../audiences.js';

// The content modules are data, but data the backend acts on: publishPayload() is what the CEO/HR admin control
// sends to POST /api/release-notes/publish|email, and ReleaseNoticePolicy.normalize refuses an unknown role or a
// malformed release id with a 400. These checks pin the content to the backend's rules so a typo'd role fails
// here, not at publish time in front of the owner.
const BACKEND_RELEASE_ID = /^[a-z0-9][a-z0-9-]{0,39}$/;
const user = (role, extra = {}) => ({ id: 1, role, employeeId: 1, ...extra });

describe('release content modules', () => {
  it('ALL_ROLES is exactly the nine roles DivisionAccessPolicy.roleFor can return', () => {
    expect([...ALL_ROLES].sort()).toEqual(
      ['account', 'ceo', 'employee', 'hr', 'import', 'qc', 'sales', 'sales_manager', 'warehouse'],
    );
  });

  it('every release satisfies the backend publish rules and the page shape', () => {
    expect(RELEASES.length).toBeGreaterThan(0);
    for (const release of RELEASES) {
      expect(release.id).toMatch(BACKEND_RELEASE_ID);
      expect(release.title.trim().length).toBeGreaterThan(0);
      expect(release.title.length).toBeLessThanOrEqual(150);
      expect(release.dateLabel).toBeTruthy();
      expect(release.entries.length).toBeGreaterThan(0);
      expect(release.entries.length).toBeLessThanOrEqual(50);

      const ids = release.entries.map((entry) => entry.id);
      expect(new Set(ids).size, `${release.id}: entry ids must be unique`).toBe(ids.length);

      // A section for each of the nine roles, each naming only real entries, each used by some section.
      expect(Object.keys(release.sections).sort()).toEqual([...ALL_ROLES].sort());
      const used = new Set(Object.values(release.sections).flat());
      const hasEveryoneEntry = release.entries.some((entry) => isForEveryone(release, entry));
      for (const [role, sectionIds] of Object.entries(release.sections)) {
        expect(Array.isArray(sectionIds), `${role} section must be a list`).toBe(true);
        expect(new Set(sectionIds).size, `${release.id}: ${role} lists an entry twice`).toBe(sectionIds.length);
        for (const id of sectionIds) expect(ids, `${role} lists unknown entry ${id}`).toContain(id);
        // A role is told nothing ONLY when nothing in the release is for it: no everyone entry, and (lock off, the
        // widest a role ever gets) no changed page it can open. Landing entries are left out: each describes one
        // role's own dashboard at `/`, which every role "can open". An empty section gets the page's
        // "ฉบับนี้ไม่มีเรื่องเฉพาะแผนกของคุณ" state, never filler.
        if (sectionIds.length === 0) {
          expect(hasEveryoneEntry, `${release.id}: ${role} has no entries although the release has an everyone entry`).toBe(false);
          for (const entry of release.entries.filter((e) => !e.landing)) {
            expect(entryVisibleTo(entry, user(role)), `${release.id}: ${role} can open ${entry.path} (${entry.id}) but is told nothing`)
              .toBe(false);
          }
        }
      }
      for (const id of ids) expect(used.has(id), `entry ${id} is in no role's section`).toBe(true);
      // Each entry title is a line of its own in the bell and the release email: two alike would read as a duplicate.
      const titles = release.entries.map((entry) => entry.title);
      expect(new Set(titles).size, `${release.id}: entry titles must be unique`).toBe(titles.length);

      // Wrong-way-round, with the lock OFF (the widest a role ever gets): no role's section names an entry whose
      // page that role cannot open. Under the lock entryVisibleTo hides more; each release pins that below.
      for (const [role, sectionIds] of Object.entries(release.sections)) {
        for (const id of sectionIds) {
          const entry = release.entries.find((e) => e.id === id);
          expect(entryVisibleTo(entry, user(role)), `${release.id}: ${role} is told about ${id} (${entry.path}) but cannot open it`)
            .toBe(true);
        }
      }

      for (const entry of release.entries) {
        const where = `${release.id}/${entry.id}`;
        expect(entry.page.trim().length, where).toBeGreaterThan(0);
        expect(entry.menu.trim().length, where).toBeGreaterThan(0);
        expect(entry.path, where).toMatch(/^\//);
        expect(entry.title.trim().length, where).toBeGreaterThan(0);
        expect(entry.title.length, where).toBeLessThanOrEqual(200);
        expect(Object.keys(CHANGE_KINDS), where).toContain(entry.kind);
        expect(entry.why.trim().length, where).toBeGreaterThan(0);
        expect(typeof entry.help, where).toBe('string');
        expect(entry.steps.length, where).toBeGreaterThan(0);
        entry.steps.forEach((step, index) => {
          expect(step.text.trim().length, `${where} step ${index + 1}`).toBeGreaterThan(0);
          expect(typeof step.detail, `${where} step ${index + 1}`).toBe('string');
          // One predictable filename per step, so whoever captures the screenshots knows exactly what to save.
          expect(step.image).toBe(`/whats-new/${release.id}/${entry.id}-${index + 1}.png`);
          expect(step.alt.trim().length, `${where} step ${index + 1} alt`).toBeGreaterThan(0);
        });
      }
    }
  });

  it('lists releases newest first and finds them by id', () => {
    const dates = RELEASES.map((release) => release.date);
    expect(dates).toEqual([...dates].sort().reverse());
    expect(latestRelease()).toBe(RELEASES[0]);
    expect(findRelease('2026-11-16')?.id).toBe('2026-11-16');
    expect(findRelease('2099-01-01')).toBeNull();
    expect(findRelease(undefined)).toBeNull();
  });
});

describe('2026-11-16 audiences (unlocked, as the test env runs)', () => {
  const release = findRelease('2026-11-16');
  const idsFor = (viewer) => entriesForViewer(release, viewer).map((entry) => entry.id);

  it('each role is told about exactly the pages this release changed for it', () => {
    expect(idsFor(user('sales'))).toEqual(['address', 'my-work', 'deal-list', 'deal-page']);
    expect(idsFor(user('sales_manager'))).toEqual(['address', 'team-overview', 'sales-insights', 'deal-list', 'deal-page']);
    expect(idsFor(user('ceo'))).toEqual(['address', 'ceo-home', 'sales-insights', 'deal-list', 'deal-page-ceo', 'fulfilment']);
    expect(idsFor(user('account'))).toEqual(['address', 'finance-deal']);
    expect(idsFor(user('import'))).toEqual(['address', 'import-home', 'fulfilment', 'import-deal', 'pricing-request']);
  });

  it('every role is told at least one thing (the published notice, unchanged by later releases)', () => {
    for (const role of ALL_ROLES) {
      expect(release.sections[role].length, `${role} needs at least one entry`).toBeGreaterThan(0);
    }
  });

  it('roles whose pages did not change get only the address entry', () => {
    for (const role of ['hr', 'warehouse', 'qc', 'employee']) {
      expect(idsFor(user(role)), role).toEqual(['address']);
    }
    expect(idsFor(user('employee', { manager: true }))).toEqual(['address']);
    expect(entriesForViewer(release, null)).toEqual([]);
  });

  it('labels the everyone entry once, and a shared entry by its departments', () => {
    const byId = Object.fromEntries(release.entries.map((entry) => [entry.id, entry]));
    expect(isForEveryone(release, byId.address)).toBe(true);
    expect(isForEveryone(release, byId['deal-list'])).toBe(false);
    expect(audienceLabels(release, byId.address)).toEqual(['ทุกคน']);
    expect(audienceLabels(release, byId['deal-list'])).toEqual(['ฝ่ายขาย', 'ผู้จัดการฝ่ายขาย', 'CEO']);
    expect(audienceLabels(release, byId.fulfilment)).toEqual(['CEO', 'ฝ่ายนำเข้า']);
    expect(audienceLabels(release, byId['sales-insights'])).toEqual(['ผู้จัดการฝ่ายขาย', 'CEO']);
  });

  it('the ทั้งหมด tab has an everyone section, a section per role with its own pages, and names the address-only roles', () => {
    const { sections, addressOnlyRoles } = allTabSections(release);
    expect(sections.map((section) => section.key)).toEqual(['everyone', 'sales', 'sales_manager', 'ceo', 'account', 'import']);
    expect(sections[0].entries.map((entry) => entry.id)).toEqual(['address']);
    expect(sections.find((s) => s.key === 'account').entries.map((entry) => entry.id)).toEqual(['finance-deal']);
    expect(addressOnlyRoles).toEqual(['hr', 'warehouse', 'qc', 'employee']);
  });

  it('publishPayload carries each entry once with the roles told about it', () => {
    const payload = publishPayload(release);
    expect(payload.releaseId).toBe('2026-11-16');
    expect(payload.title).toBe(release.title);
    expect(payload.changes).toHaveLength(release.entries.length);
    const byTitle = Object.fromEntries(payload.changes.map((change) => [change.title, change.roles]));
    const address = release.entries.find((entry) => entry.id === 'address');
    expect([...byTitle[address.title]].sort()).toEqual([...ALL_ROLES].sort());
    const fulfilment = release.entries.find((entry) => entry.id === 'fulfilment');
    expect(byTitle[fulfilment.title]).toEqual(['ceo', 'import']);
    expect(Object.keys(payload.changes[0]).sort()).toEqual(['roles', 'title']);
  });
});

// Release lockdown (SELF_SERVICE_ONLY): a viewer is never told how to use a page they cannot open yet. Same
// stubEnv + resetModules pattern as permissions.test.js: the static imports above evaluated with the lock OFF.
describe('2026-11-16 audiences under the release lockdown', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  async function lockedModules() {
    vi.stubEnv('VITE_SELF_SERVICE_ONLY', 'true');
    vi.resetModules();
    const audiences = await import('../audiences.js');
    const { findRelease: find } = await import('./index.js');
    return { ...audiences, release: find('2026-11-16') };
  }

  it('hides import’s landing page (their / is the self-service landing while locked) but keeps released pages', async () => {
    const { entriesForViewer: forViewer, release } = await lockedModules();
    expect(forViewer(release, user('import')).map((entry) => entry.id))
      .toEqual(['address', 'fulfilment', 'import-deal', 'pricing-request']);
    expect(forViewer(release, user('account')).map((entry) => entry.id)).toEqual(['address', 'finance-deal']);
  });

  it('keeps the sales pages for the roles the lock exempts', async () => {
    const { entriesForViewer: forViewer, release } = await lockedModules();
    expect(forViewer(release, user('sales')).map((entry) => entry.id)).toEqual(['address', 'my-work', 'deal-list', 'deal-page']);
    expect(forViewer(release, user('ceo')).map((entry) => entry.id))
      .toEqual(['address', 'ceo-home', 'sales-insights', 'deal-list', 'deal-page-ceo', 'fulfilment']);
  });

  it('the publish payload drops a hidden page from the bell and email of the role that cannot open it', async () => {
    const { publishPayload: payloadOf, release } = await lockedModules();
    const titles = payloadOf(release).changes.map((change) => change.title);
    const importHome = release.entries.find((entry) => entry.id === 'import-home');
    expect(titles).not.toContain(importHome.title);
  });
});
