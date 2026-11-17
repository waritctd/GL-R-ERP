import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ROLE_PERMISSIONS } from '../../../api/routes.js';
import { hasPermission } from '../../../app/permissions.js';
import { canPerformFinanceStep } from '../../finance/financeActionPerformers.js';
import { FINANCE_WORK_GROUPS, OTHER_WORK_GROUP } from '../../finance/financeWorkGroups.js';
import {
  canApproveDealQuotation, canCreateDealQuotationStandalone, isDealQuotationReadOnlyViewer,
} from '../../quotations/quotationMeta.js';
import { BOARD_COLUMNS } from '../../salesBoard/boardFormat.js';
import { MOVER_LABEL, REP_STEPS } from '../../tickets/dealSteps.js';
import { TICKET_DETAIL_TABS } from '../../tickets/ticketDetailTabs.js';
import {
  ALL_ROLES, allTabSections, entriesForViewer, isForEveryone, publishPayload, rolesForEntry,
} from '../audiences.js';
import { RELEASES, findRelease, latestRelease } from './index.js';

// The source of every component this release describes. A label the notice puts in "quotes" must exist in the source
// of the screen it describes, so the notice breaks here, not in front of staff, when the UI wording changes.
import appShellSrc from '../../../components/layout/AppShell.jsx?raw';
import launcherSrc from '../../../components/layout/Launcher.jsx?raw';
import userMenuSrc from '../../../components/layout/UserMenu.jsx?raw';
import allDealsSrc from '../../tickets/AllDealsPage.jsx?raw';
import dealBoardSrc from '../../salesBoard/DealBoard.jsx?raw';
import boardFormatSrc from '../../salesBoard/boardFormat.js?raw';
import ticketListSrc from '../../tickets/TicketListPage.jsx?raw';
import myWorkSrc from '../../salesBoard/MyWork.jsx?raw';
import ticketDetailSrc from '../../tickets/TicketDetailPage.jsx?raw';
import dealHeaderSrc from '../../tickets/DealHeader.jsx?raw';
import nextStepCardSrc from '../../tickets/NextStepCard.jsx?raw';
import dealStepperSrc from '../../tickets/DealStepper.jsx?raw';
import dealStepsSrc from '../../tickets/dealSteps.js?raw';
import quietLinksSrc from '../../tickets/DealQuietLinks.jsx?raw';
import dealTabBodiesSrc from '../../tickets/DealTabBodies.jsx?raw';
import ticketTabsSrc from '../../tickets/ticketDetailTabs.js?raw';
import quickActionsSrc from '../../tickets/DealQuickActions.jsx?raw';
import dealInfoCardSrc from '../../tickets/DealInfoCard.jsx?raw';
import dealHistorySrc from '../../tickets/DealHistoryPanel.jsx?raw';
import paymentSectionSrc from '../../tickets/DealPaymentSection.jsx?raw';
import depositNoticeSrc from '../../deposits/DepositNoticePage.jsx?raw';
import remainingInvoiceSrc from '../../tickets/RemainingInvoiceDialog.jsx?raw';
import fulfilmentSrc from '../../tickets/DealFulfilmentPanel.jsx?raw';
import quotationListSrc from '../../quotations/QuotationListPage.jsx?raw';
import quotationMetaSrc from '../../quotations/quotationMeta.js?raw';
import visitsPageSrc from '../../salesBoard/VisitsPage.jsx?raw';
import visitsThisWeekSrc from '../../salesBoard/VisitsThisWeek.jsx?raw';
import visitWeekGridSrc from '../../salesBoard/VisitWeekGrid.jsx?raw';
import visitWeekSrc from '../../salesBoard/visitWeek.js?raw';
import visitDialogsSrc from '../../salesBoard/VisitDialogs.jsx?raw';
import todayOnPhoneSrc from '../../salesBoard/TodayOnPhone.jsx?raw';
import financePageSrc from '../../finance/AccountFinancePage.jsx?raw';
import financeGroupsSrc from '../../finance/financeWorkGroups.js?raw';

const RELEASE_ID = '2026-11-17';
const user = (role, extra = {}) => ({ id: 1, role, employeeId: 1, ...extra });
const release = findRelease(RELEASE_ID);
const byId = (id) => release?.entries.find((entry) => entry.id === id);

/** A source with its comments removed: a label that survives only in a comment is not on any screen (review M4). Line
 * comments go first, so a `/*` written inside one (e.g. "dist/assets/*.css") cannot open a fake block comment. URLs
 * keep their `//` because the line-comment arm needs whitespace or a line start before it. */
function code(src) {
  return src.replace(/(^|\s)\/\/.*$/gm, '$1').replace(/\/\*[\s\S]*?\*\//g, '');
}

// The launcher (#1324) replaced the sidebar: the "เมนู" button opens it, and SECTIONS names its headings.
const NAV = [appShellSrc, launcherSrc];
const BOARD = [...NAV, allDealsSrc, dealBoardSrc, boardFormatSrc, ticketListSrc, myWorkSrc];
const DEAL_PAGE = [ticketDetailSrc, dealHeaderSrc, nextStepCardSrc, dealStepperSrc, dealStepsSrc, quietLinksSrc,
  ticketTabsSrc, quickActionsSrc, dealInfoCardSrc, dealHistorySrc];
// NextStepCard: the deposit notice's create button can sit in ขั้นต่อไป instead of the เงิน tab (mainButtonCreatesNotice).
const DOCUMENTS = [ticketTabsSrc, paymentSectionSrc, depositNoticeSrc, remainingInvoiceSrc, fulfilmentSrc, nextStepCardSrc];
const QUOTATIONS = [...NAV, quotationListSrc, quotationMetaSrc];
const VISITS = [...NAV, visitsPageSrc, visitsThisWeekSrc, visitWeekGridSrc, visitWeekSrc, visitDialogsSrc];
const FINANCE = [...NAV, financePageSrc, financeGroupsSrc];

/** Entry id → the sources of the screens it describes, comments stripped. Every entry must be listed: an entry with no
 * sources would skip the label check. */
const ENTRY_SOURCES = Object.fromEntries(Object.entries({
  'menu-launcher': [...NAV, userMenuSrc],
  'all-deals': BOARD,
  'all-deals-team': BOARD,
  'deal-page': DEAL_PAGE,
  'deal-documents': DOCUMENTS,
  'deal-documents-ceo': DOCUMENTS,
  'quotation-list': QUOTATIONS,
  'quotation-list-approver': QUOTATIONS,
  'quotation-list-view': QUOTATIONS,
  visits: VISITS,
  'visits-team': VISITS,
  'today-phone': [...NAV, todayOnPhoneSrc, myWorkSrc, visitDialogsSrc, visitsThisWeekSrc],
  'finance-worklist': FINANCE,
  'finance-worklist-ceo': FINANCE,
}).map(([id, sources]) => [id, sources.map(code)]));

// The backend's own quiet threshold (QuietRule.DEFAULT_QUIET_DAYS; application.yml defaults app.sales.quiet-days to it).
// Vitest runs from frontend/ (vitest.config.js); public/ and ../backend are resolved from there.
const FRONTEND_ROOT = process.cwd();
const QUIET_RULE_SRC = readFileSync(
  resolve(FRONTEND_ROOT, '../backend/src/main/java/th/co/glr/hr/ticket/QuietRule.java'), 'utf8',
);
const DEFAULT_QUIET_DAYS = Number(/DEFAULT_QUIET_DAYS = (\d+);/.exec(QUIET_RULE_SRC)[1]);

/** Every "quoted" on-screen label in an entry's prose (title, why, each step's text and detail, help). Alt text is
 * not on-screen copy, so it is left out. */
function quotedLabels(entry) {
  const prose = [entry.title, entry.why, entry.help, ...entry.steps.flatMap((step) => [step.text, step.detail])].join('\n');
  return [...prose.matchAll(/"([^"\n]+)"/g)].map((match) => match[1]);
}

/** A label may stand for a counted one ("ค้าง … นัด" for `ค้าง ${n} นัด`): each fixed fragment must be in source. */
function labelInSources(label, sources) {
  return label.split('…').map((part) => part.trim()).filter(Boolean)
    .every((part) => sources.some((src) => src.includes(part)));
}

// alt is null on an image-less step.
const allText = (entry) => [entry.title, entry.page, entry.menu, entry.why, entry.help,
  ...entry.steps.flatMap((step) => [step.text, step.detail, step.alt])].filter((text) => text != null);

describe('2026-11-17 release: registration', () => {
  it('is registered and is the latest release; the 16 Nov release stays reachable', () => {
    expect(release, 'releases/index.js must register 2026-11-17').not.toBeNull();
    expect(latestRelease()?.id).toBe(RELEASE_ID);
    expect(release.date).toBe('2026-11-17');
    expect(release.dateLabel).toBe('17 พ.ย. 2569');
    expect(findRelease('2026-11-16')?.id).toBe('2026-11-16');
    expect(RELEASES.map((r) => r.id)).toEqual(['2026-11-17', '2026-11-16']);
  });

  it('every screenshot is its own file under /whats-new/2026-11-17/, never a 16 Nov image', () => {
    expect(release).not.toBeNull();
    const older = new Set(findRelease('2026-11-16').entries.flatMap((entry) => entry.steps.map((step) => step.image)));
    const images = release.entries.flatMap((entry) => entry.steps.map((step) => step.image)).filter(Boolean);
    expect(new Set(images).size).toBe(images.length);
    for (const image of images) {
      expect(image.startsWith('/whats-new/2026-11-17/'), image).toBe(true);
      expect(older.has(image), image).toBe(false);
    }
  });

  // Captured screenshots ship with the notice: a missing file renders a placeholder in front of staff (review M22).
  it('every referenced screenshot exists in frontend/public', () => {
    expect(release).not.toBeNull();
    const onDisk = (image) => existsSync(resolve(FRONTEND_ROOT, `public${image}`));
    // Guard the root: the published 16 Nov images must resolve, or this check would pass/fail for the wrong reason.
    expect(onDisk('/whats-new/2026-11-16/address-1.png'), 'cwd is not frontend/').toBe(true);
    const missing = release.entries.flatMap((entry) => entry.steps.map((step) => step.image)).filter(Boolean)
      .filter((image) => !onDisk(image));
    expect(missing).toEqual([]);
  });

  it('no file in public/whats-new/2026-11-17 is left that no step shows', () => {
    expect(release).not.toBeNull();
    const referenced = new Set(release.entries.flatMap((entry) => entry.steps.map((step) => step.image)).filter(Boolean));
    const strays = readdirSync(resolve(FRONTEND_ROOT, 'public/whats-new/2026-11-17'))
      .map((file) => `/whats-new/2026-11-17/${file}`).filter((image) => !referenced.has(image));
    expect(strays).toEqual([]);
  });

  // No honest capture exists for these, so they carry no image rather than a misleading one:
  //   - deal-documents step 2: every mock remaining invoice deducts a ฿0 deposit (mockApi remainingInvoiceOptions and
  //     mockRemainingInvoiceSnapshot hardcode 0), beside a step about the deposit.
  //   - quotation-list-view: in mock mode /quotations has no rows for account / import, so the list is empty.
  it('exactly the steps that cannot be captured honestly have no screenshot', () => {
    expect(release).not.toBeNull();
    const imageless = release.entries.flatMap((entry) => entry.steps
      .map((step, index) => (step.image === null ? `${entry.id}-${index + 1}` : null)).filter(Boolean));
    expect(imageless).toEqual(['deal-documents-2', 'quotation-list-view-1', 'quotation-list-view-2', 'quotation-list-view-3']);
  });
});

describe('2026-11-17 audiences (unlocked, as the test env runs)', () => {
  const idsFor = (viewer) => entriesForViewer(release, viewer).map((entry) => entry.id);

  it('each role is told about exactly the pages it can open that changed for it', () => {
    expect(release).not.toBeNull();
    expect(idsFor(user('sales'))).toEqual(
      ['menu-launcher', 'all-deals', 'deal-page', 'deal-documents', 'quotation-list', 'visits', 'today-phone'],
    );
    expect(idsFor(user('sales_manager'))).toEqual(
      ['menu-launcher', 'all-deals-team', 'deal-page', 'quotation-list-approver', 'visits-team'],
    );
    expect(idsFor(user('ceo'))).toEqual(
      ['menu-launcher', 'all-deals-team', 'deal-page', 'deal-documents-ceo', 'quotation-list-approver', 'visits-team',
        'finance-worklist-ceo'],
    );
    // account and import read the ใบเสนอราคา list (canViewDealQuotations) when the lock is off, read-only.
    expect(idsFor(user('account'))).toEqual(['menu-launcher', 'finance-worklist', 'quotation-list-view']);
    expect(idsFor(user('import'))).toEqual(['menu-launcher', 'quotation-list-view']);
  });

  it('the launcher (#1324) is for everyone; roles with no changed sales page get only that', () => {
    expect(release).not.toBeNull();
    expect(isForEveryone(release, byId('menu-launcher'))).toBe(true);
    expect(byId('menu-launcher').landing).toBeFalsy();
    for (const role of ['hr', 'warehouse', 'qc', 'employee']) {
      expect(release.sections[role], role).toEqual(['menu-launcher']);
      expect(idsFor(user(role)), role).toEqual(['menu-launcher']);
    }
    expect(idsFor(user('employee', { manager: true }))).toEqual(['menu-launcher']);
    // Wrong-way-round: a quotation-grant holder outside the sales roles is not told about the sales pages.
    expect(idsFor(user('qc', { canCreateQuotation: true }))).toEqual(['menu-launcher']);
    // Wrong-way-round: import and account are never told about the board, the deal page, visits or the phone screen.
    for (const role of ['import', 'account']) {
      for (const id of ['all-deals', 'all-deals-team', 'deal-page', 'deal-documents', 'deal-documents-ceo', 'visits',
        'visits-team', 'today-phone']) {
        expect(idsFor(user(role)), `${role} told about ${id}`).not.toContain(id);
      }
    }
    expect(idsFor(user('import'))).not.toContain('finance-worklist');
  });

  it('the ใบเสนอราคา entries split by the list’s own role branches and together reach exactly its viewers', () => {
    expect(release).not.toBeNull();
    const roles = (id) => rolesForEntry(release, byId(id));
    // QuotationListPage: approvers (canApproveDealQuotation) land on รออนุมัติ with the พนักงานขาย / ยอดรวม filters;
    // read-only viewers (isDealQuotationReadOnlyViewer) get "(ดูอย่างเดียว)"; a rep gets its own list.
    expect(roles('quotation-list')).toEqual(['sales']);
    expect(roles('quotation-list-approver')).toEqual(ALL_ROLES.filter((role) => canApproveDealQuotation({ role })));
    expect(roles('quotation-list-view')).toEqual(ALL_ROLES.filter((role) => isDealQuotationReadOnlyViewer({ role })));
    expect([...roles('quotation-list'), ...roles('quotation-list-approver'), ...roles('quotation-list-view')].sort())
      .toEqual(ALL_ROLES.filter((role) => hasPermission(role, 'canViewDealQuotations')).sort());
    // The approver steps name the รออนุมัติ landing and the see-all filters.
    expect(quotedLabels(byId('quotation-list-approver'))).toEqual(
      expect.arrayContaining(['รออนุมัติ', 'ทั้งหมด', 'พนักงานขาย', 'ยอดรวม']),
    );
    // Read-only viewers have no see-all filters (dealQuotationListSeesAll is approvers only).
    expect(quotedLabels(byId('quotation-list-view'))).not.toContain('พนักงานขาย');
  });

  it('no entry tells a role to use a control that role does not have', () => {
    expect(release).not.toBeNull();
    const pressed = (entry, label) => entry.steps.some((step) => new RegExp(`กด[^"\\n]{0,12}"${label}"`).test(`${step.text} ${step.detail}`));
    for (const entry of release.entries) {
      const roles = rolesForEntry(release, entry);
      // สร้างใบเสนอราคา: canCreateDealQuotationStandalone (sales, sales_manager, grant) — not the CEO, account or import.
      if (allText(entry).some((text) => text.includes('สร้างใบเสนอราคา'))) {
        for (const role of roles) expect(canCreateDealQuotationStandalone({ role }), `${entry.id}: ${role}`).toBe(true);
      }
      // The finance verbs are performed only by the roles financeActionPerformers lists (review M10).
      for (const group of FINANCE_WORK_GROUPS.filter((g) => g.buttonLabel !== 'เปิด')) {
        if (!pressed(entry, group.buttonLabel)) continue;
        for (const role of roles) {
          expect(group.actionKeys.some((key) => canPerformFinanceStep(key, role)), `${entry.id}: ${role} presses ${group.buttonLabel}`)
            .toBe(true);
        }
      }
      // The deposit notice's ออกเอกสาร is the owning rep's alone (DealPaymentSection isOwnerSales; review M17).
      if (pressed(entry, 'ออกเอกสาร')) expect(roles, entry.id).toEqual(['sales']);
    }
    expect(paymentSectionSrc).toMatch(/\{isOwnerSales \? \([\s\S]{0,700}ออกเอกสาร/);
    // Cancel is owner-only (TicketDetailPage can.cancel … && isOwner); the help must say so (review M9).
    expect(ticketDetailSrc).toMatch(/cancel:\s+hasAction\('CANCEL'\) && !TERMINAL\.includes\(st\)\s+&& isOwner,/);
    expect(byId('deal-page').help).toContain('ยกเลิกได้เฉพาะพนักงานขายเจ้าของงาน');
  });

  it('who gets which version of a page follows the page’s own role branch', () => {
    expect(release).not.toBeNull();
    const roles = (id) => rolesForEntry(release, byId(id));

    // งานทั้งหมด: AllDealsBoard's isTeam is `role !== 'sales'` among the board roles (canViewDealPipeline).
    expect(roles('all-deals')).toEqual(['sales']);
    for (const role of roles('all-deals-team')) {
      expect(hasPermission(role, 'canViewDealPipeline') && role !== 'sales', role).toBe(true);
    }
    expect(roles('all-deals-team')).toEqual(['sales_manager', 'ceo']);

    // นัดเข้าพบ: VisitsPage shows the team grid exactly for canViewTeamVisits, the rep's own week otherwise.
    for (const role of roles('visits')) expect(hasPermission(role, 'canViewTeamVisits'), role).toBe(false);
    for (const role of roles('visits-team')) expect(hasPermission(role, 'canViewTeamVisits'), role).toBe(true);
    expect(roles('visits')).toEqual(['sales']);
    expect(roles('visits-team')).toEqual(['sales_manager', 'ceo']);

    // วันนี้ของฉัน is MyWork's phone screen, and MyWork is only the sales landing (SalesOverview).
    expect(roles('today-phone')).toEqual(['sales']);

    // Document dialogs: the deposit notice and remaining invoice are the owning rep's (role 'sales', the
    // canCreateTickets role); the CEO's version describes only the IR and delivery dialogs it can open.
    for (const role of roles('deal-documents')) expect(ROLE_PERMISSIONS.canCreateTickets.includes(role), role).toBe(true);
    expect(roles('deal-documents-ceo')).toEqual(['ceo']);
    expect(ROLE_PERMISSIONS.canCreateTickets.includes('ceo')).toBe(false);

    // งานการเงิน: the action-named row buttons are for whoever the server lets perform the step; the CEO is on none of
    // those lists, so its rows say "เปิด" and it gets the version that says so.
    for (const role of roles('finance-worklist')) expect(canPerformFinanceStep('confirmCloseReady', role), role).toBe(true);
    expect(roles('finance-worklist-ceo')).toEqual(['ceo']);
    for (const group of FINANCE_WORK_GROUPS) {
      for (const key of group.actionKeys) expect(canPerformFinanceStep(key, 'ceo'), key).toBe(false);
    }
    expect(quotedLabels(byId('finance-worklist-ceo'))).toContain('เปิด');
  });

  it('the ทั้งหมด tab leads with the launcher for everyone and names the roles that got only that', () => {
    expect(release).not.toBeNull();
    const { sections, addressOnlyRoles } = allTabSections(release);
    expect(sections.map((s) => s.key)).toEqual(['everyone', 'sales', 'sales_manager', 'ceo', 'account', 'import']);
    expect(sections[0].entries.map((entry) => entry.id)).toEqual(['menu-launcher']);
    expect(addressOnlyRoles).toEqual(['hr', 'warehouse', 'qc', 'employee']);
    // The page's note for those roles is this release's own, not 16 Nov's address-move sentence.
    expect(release.otherRolesNote).toBe('ฉบับนี้มีเฉพาะเมนูแบบใหม่ (ด้านบน)');
  });

  it('publishPayload carries each entry once with the roles told about it', () => {
    expect(release).not.toBeNull();
    const payload = publishPayload(release);
    expect(payload.releaseId).toBe(RELEASE_ID);
    expect(payload.changes).toHaveLength(release.entries.length);
    const byTitle = Object.fromEntries(payload.changes.map((change) => [change.title, change.roles]));
    expect(byTitle[byId('menu-launcher').title]).toEqual(ALL_ROLES);
    expect(byTitle[byId('deal-page').title]).toEqual(['sales', 'sales_manager', 'ceo']);
    expect(byTitle[byId('quotation-list').title]).toEqual(['sales']);
    expect(byTitle[byId('quotation-list-approver').title]).toEqual(['sales_manager', 'ceo']);
    expect(byTitle[byId('quotation-list-view').title]).toEqual(['account', 'import']);
    expect(byTitle[byId('finance-worklist').title]).toEqual(['account']);
  });
});

// Release lockdown on (production): same stubEnv + resetModules pattern as releases.test.js.
describe('2026-11-17 audiences under the release lockdown', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  async function lockedModules() {
    vi.stubEnv('VITE_SELF_SERVICE_ONLY', 'true');
    vi.resetModules();
    const audiences = await import('../audiences.js');
    const { findRelease: find } = await import('./index.js');
    return { ...audiences, locked: find(RELEASE_ID) };
  }

  it('the lock-exempt sales roles keep every page; account keeps only งานการเงิน; nobody else is told anything', async () => {
    const { entriesForViewer: forViewer, locked } = await lockedModules();
    expect(locked).not.toBeNull();
    const ids = (role, extra) => forViewer(locked, user(role, extra)).map((entry) => entry.id);
    expect(ids('sales')).toEqual(
      ['menu-launcher', 'all-deals', 'deal-page', 'deal-documents', 'quotation-list', 'visits', 'today-phone'],
    );
    expect(ids('sales_manager')).toEqual(['menu-launcher', 'all-deals-team', 'deal-page', 'quotation-list-approver', 'visits-team']);
    expect(ids('ceo')).toEqual(
      ['menu-launcher', 'all-deals-team', 'deal-page', 'deal-documents-ceo', 'quotation-list-approver', 'visits-team',
        'finance-worklist-ceo'],
    );
    // Locked, account opens /finance only and import neither /finance nor /quotations: the ใบเสนอราคา entry is hidden.
    expect(ids('account')).toEqual(['menu-launcher', 'finance-worklist']);
    for (const role of ['import', 'hr', 'warehouse', 'qc', 'employee']) expect(ids(role), role).toEqual(['menu-launcher']);
    expect(ids('employee', { manager: true })).toEqual(['menu-launcher']);
  });

  it('under the lock the only listed entry a role cannot open is ใบเสนอราคา for import and account', async () => {
    const { entryVisibleTo: visible, locked } = await lockedModules();
    expect(locked).not.toBeNull();
    const hidden = [];
    for (const role of ALL_ROLES) {
      for (const id of locked.sections[role]) {
        const entry = locked.entries.find((e) => e.id === id);
        if (!visible(entry, user(role))) hidden.push(`${role}:${id}`);
      }
    }
    expect(hidden.sort()).toEqual(['account:quotation-list-view', 'import:quotation-list-view']);
  });

  it('the publish payload names only roles that can open the page', async () => {
    const { publishPayload: payloadOf, locked } = await lockedModules();
    expect(locked).not.toBeNull();
    const payload = payloadOf(locked);
    // The read-only quotation entry reaches nobody under the lock, so it is left out of the bell and the email.
    const view = locked.entries.find((e) => e.id === 'quotation-list-view');
    expect(payload.changes).toHaveLength(locked.entries.length - 1);
    expect(payload.changes.map((change) => change.title)).not.toContain(view.title);
    const byTitle = Object.fromEntries(payload.changes.map((change) => [change.title, change.roles]));
    expect(byTitle[locked.entries.find((e) => e.id === 'menu-launcher').title]).toEqual(ALL_ROLES);
    expect(byTitle[locked.entries.find((e) => e.id === 'finance-worklist').title]).toEqual(['account']);
  });
});

// The bell row and the backend's release email (ReleaseNoticePolicy.emailBody, today) carry each entry's TITLE alone,
// with a link to the page and no steps or screenshots, so a title must read on its own. The why is the first line a
// reader sees on the page under that title, so it is held to the same standard: one sentence, nothing that points
// at steps or images.
describe('2026-11-17 titles and reasons read on their own', () => {
  // "ภาพ" alone would also catch the tab name ภาพรวม, so only the screenshot phrasings are listed.
  const POINTS_AT_THE_PAGE = /ด้านล่าง|ด้านบน|ขั้นตอน|ในภาพ|ตามภาพ|ดังภาพ|ภาพประกอบ|ภาพหน้าจอ|ดังนี้|ต่อไปนี้|ที่นี่/;

  it('each title is one plain line, not a "page: fragment" heading', () => {
    expect(release).not.toBeNull();
    for (const entry of release.entries) {
      expect(entry.title, entry.id).not.toMatch(/[:\n]/);
      expect(entry.title, entry.id).not.toMatch(POINTS_AT_THE_PAGE);
      expect(entry.title.length, entry.id).toBeLessThanOrEqual(90);
      expect(entry.title, entry.id).not.toContain('…');
    }
  });

  it('each why is one sentence that does not point at the page', () => {
    expect(release).not.toBeNull();
    for (const entry of release.entries) {
      expect(entry.why, entry.id).not.toMatch(/[\n:]/);
      expect(entry.why, entry.id).not.toMatch(POINTS_AT_THE_PAGE);
      expect(entry.why.length, entry.id).toBeLessThanOrEqual(160);
      // Thai separates sentences with a space; a why of more than a few clauses is a paragraph, not a reason.
      expect(entry.why.split(' ').length, `${entry.id}: why runs on`).toBeLessThanOrEqual(6);
    }
  });
});

describe('2026-11-17 wording matches the screens', () => {
  it('every entry names the sources it describes and at least one on-screen label', () => {
    expect(release).not.toBeNull();
    expect(Object.keys(ENTRY_SOURCES).sort()).toEqual(release.entries.map((entry) => entry.id).sort());
    for (const entry of release.entries) {
      expect(quotedLabels(entry).length, `${entry.id} quotes no on-screen label`).toBeGreaterThan(0);
    }
  });

  it('every "quoted" label exists in the source of the screen it describes', () => {
    expect(release).not.toBeNull();
    for (const entry of release.entries) {
      for (const label of quotedLabels(entry)) {
        expect(labelInSources(label, ENTRY_SOURCES[entry.id]), `${entry.id}: "${label}" is not on the screen`).toBe(true);
      }
    }
  });

  it('every menu path is the nav as AppShell / Launcher render it', () => {
    expect(release).not.toBeNull();
    // 'เมนู' is the launcher button's own text (pinned below); 'เลือกงาน' is an instruction, not a label.
    const instructions = new Set(['เลือกงาน', 'เมนู']);
    for (const entry of release.entries) {
      for (const segment of entry.menu.split(' › ')) {
        if (instructions.has(segment)) continue;
        expect(appShellSrc.includes(`'${segment}'`) || launcherSrc.includes(`'${segment}'`), `${entry.id}: menu "${segment}"`)
          .toBe(true);
      }
    }
  });

  it('every way to a page goes through the launcher as it is today (#1324)', () => {
    expect(release).not.toBeNull();
    // The top-bar button that opens the launcher reads "เมนู".
    expect(appShellSrc).toMatch(/<\/span>\s*เมนู\s*<Icon name="chevronDown"/);
    const sections = [...launcherSrc.matchAll(/label: '([^']+)'/g)].map((match) => match[1]);
    for (const entry of release.entries) {
      for (const step of entry.steps) {
        // The sidebar-era "กดเมนู งานขาย › …" is gone: a reader presses the "เมนู" button, then picks the page.
        expect(step.text, entry.id).not.toMatch(/กดเมนู\s/);
        for (const match of `${step.text} ${step.detail}`.matchAll(/ในหมวด (\S+)/g)) {
          expect(sections, `${entry.id}: launcher has no section ${match[1]}`).toContain(match[1]);
        }
      }
    }
    for (const id of ['all-deals', 'all-deals-team', 'quotation-list', 'quotation-list-approver', 'quotation-list-view',
      'visits', 'visits-team', 'finance-worklist', 'finance-worklist-ceo']) {
      expect(byId(id).steps[0].text, id).toContain('กด "เมนู"');
    }
    // มีอะไรใหม่ moved from the nav to the user menu (UserMenu, "เมนูผู้ใช้").
    expect(userMenuSrc).toContain("onNavigate('/whats-new')");
    expect(quotedLabels(byId('menu-launcher'))).toContain('มีอะไรใหม่');
  });

  it('the profile-menu step names exactly the items UserMenu renders, each with its condition (review N1)', () => {
    expect(release).not.toBeNull();
    // Every item label in UserMenu: the text after its icon, closing the button.
    const items = [...code(userMenuSrc).matchAll(/<Icon [^>]*\/>\s*([^<{\s][^<{]*?)\s*<\/button>/g)].map((match) => match[1]);
    expect(items).toEqual(['ข้อมูลของฉัน', 'ค่าลดหย่อนภาษี', 'มีอะไรใหม่', 'ดูในมุมมองพนักงาน', 'ออกจากระบบ']);
    const step = byId('menu-launcher').steps[1];
    const named = [...`${step.text} ${step.detail}`.matchAll(/"([^"]+)"/g)].map((match) => match[1]);
    // ดูในมุมมองพนักงาน is the view-as capability (user.canViewAs), not a reader's item: never named.
    expect([...new Set(named)].sort()).toEqual(items.filter((item) => item !== 'ดูในมุมมองพนักงาน').sort());
    // ข้อมูลของฉัน and ค่าลดหย่อนภาษี show only for an account linked to an employee record: the step says so.
    expect(appShellSrc).toContain('canViewProfile={!!user.employeeId}');
    expect(userMenuSrc).toMatch(/\{user\.employeeId \? \([\s\S]{0,400}ค่าลดหย่อนภาษี/);
    expect(step.detail).toContain('ข้อมูลพนักงาน');
    // Those pages are NOT in the launcher, so the reason must not say every page is.
    expect(byId('menu-launcher').why).not.toContain('ทุกหน้า');
  });

  it('the closed-deal links name พักยาว with its condition (AllDealsPage ClosedLinks)', () => {
    expect(release).not.toBeNull();
    expect(allDealsSrc).toContain("{ life: 'DORMANT', label: 'พักยาว', always: false }");
    for (const id of ['all-deals', 'all-deals-team']) {
      const step = byId(id).steps.find((s) => s.detail.includes('"พักไว้"'));
      expect(step.detail, id).toMatch(/"พักยาว" เมื่อมีงานที่พักยาว/);
    }
  });

  it('pins the labels staff act on most', () => {
    expect(release).not.toBeNull();
    // The five board columns, on both board entries.
    for (const id of ['all-deals', 'all-deals-team']) {
      for (const column of BOARD_COLUMNS) expect(quotedLabels(byId(id)), `${id}: ${column.label}`).toContain(column.label);
    }
    // The five deal-page tabs and the stepper's two "who moves it" labels.
    for (const tab of TICKET_DETAIL_TABS) expect(quotedLabels(byId('deal-page')), tab.label).toContain(tab.label);
    for (const label of Object.values(MOVER_LABEL)) expect(quotedLabels(byId('deal-page')), label).toContain(label);
    // The three quiet links.
    for (const label of ['พักงาน', 'ปิดงาน (เสียงาน)', 'ยกเลิกงาน']) expect(quotedLabels(byId('deal-page')), label).toContain(label);
    // The finance groups, on both finance entries, including the catch-all group that appears when it has rows.
    for (const id of ['finance-worklist', 'finance-worklist-ceo']) {
      for (const group of [...FINANCE_WORK_GROUPS, OTHER_WORK_GROUP]) {
        expect(quotedLabels(byId(id)), `${id}: ${group.label}`).toContain(group.label);
      }
      expect(allText(byId(id)).join(' '), id).not.toContain('สามกลุ่ม');
    }
    // The quotation list's view switch.
    for (const id of ['quotation-list', 'quotation-list-approver', 'quotation-list-view']) {
      expect(quotedLabels(byId(id)), id).toEqual(expect.arrayContaining(['มุมมอง', 'จัดกลุ่มตามดีล', 'วันที่อนุมัติ']));
    }
  });

  it('numbers and step codes come from their source constants (review M7, M8)', () => {
    expect(release).not.toBeNull();
    // Delivery completion moves the deal to the stepper's LAST step, as the dialog itself says.
    const last = REP_STEPS[REP_STEPS.length - 1].code;
    expect(fulfilmentSrc).toContain(`เลื่อนงานไป ${last}`);
    for (const id of ['deal-documents', 'deal-documents-ceo']) {
      expect(byId(id).steps.find((s) => s.text.includes('บันทึกการส่งสินค้า')).detail, id).toContain(`ไป ${last} `);
    }
    // The quiet threshold is QuietRule's default, and the rule's two exclusions are named (QuietRule javadoc).
    expect(QUIET_RULE_SRC).toContain('not in the deliver &amp; collect board column');
    expect(QUIET_RULE_SRC).toContain('has no booked visit ahead');
    for (const id of ['all-deals', 'all-deals-team']) {
      const quiet = byId(id).steps[1].detail;
      expect(quiet, id).toContain(`เกิน ${DEFAULT_QUIET_DAYS} วัน`);
      expect(quiet, id).toContain('ส่งของ · เก็บเงิน');
      expect(quiet, id).toContain('นัดเข้าพบ');
    }
    for (const entry of release.entries) {
      for (const text of allText(entry)) {
        for (const match of text.matchAll(/เกิน (\d+) วัน/g)) expect(Number(match[1]), entry.id).toBe(DEFAULT_QUIET_DAYS);
      }
    }
    // วันนี้ของฉัน is the phone screen only (MyWork: phoneToday = isPhone && …); the help must keep saying so (M21).
    expect(myWorkSrc).toContain('const phoneToday = isPhone && !fullOnPhone;');
    expect(byId('today-phone').help).toContain('เฉพาะบนจอมือถือ');
  });

  // Screenshot review (2026-11-17): steps whose ORDER or CONDITION did not match the screen. Each pin is tied to the
  // component fact it rests on, so the notice and the screen fail together.
  const stepText = (id, n) => `${byId(id).steps[n - 1].text} ${byId(id).steps[n - 1].detail}`;

  it('the stepper is named as the eight steps it shows, S4 left out (dealSteps REP_STEPS)', () => {
    expect(release).not.toBeNull();
    const THAI_COUNT = { 8: 'แปด' };
    expect(REP_STEPS.map((s) => s.code)).not.toContain('S4');
    const step = stepText('deal-page', 1);
    expect(step).toContain(`${THAI_COUNT[REP_STEPS.length]}ขั้น`);
    expect(step).toContain(REP_STEPS.map((s) => s.code).join(' '));
    for (const entry of release.entries) for (const text of allText(entry)) expect(text, entry.id).not.toMatch(/S1\s*[–-]\s*S9/);
  });

  it('the remaining invoice: บันทึกร่าง, then the SAME dialog shows the draft with ออกใบแจ้งหนี้ (review F2/F5)', () => {
    expect(release).not.toBeNull();
    // CreateForm's only write button is บันทึกร่าง; on save it only refetches the list, and the same Modal then renders
    // DraftEditor (draft && canWrite), whose footer carries ออกใบแจ้งหนี้.
    expect(remainingInvoiceSrc).toMatch(/data-testid="remaining-invoice-create-draft">\s*\{creating \? 'กำลังบันทึกร่าง…' : 'บันทึกร่าง'\}/);
    expect(remainingInvoiceSrc).toContain('<CreateForm ticketId={ticketId} onCreated={invalidateList} />');
    expect(remainingInvoiceSrc).toMatch(/\{draft && canWrite \? \(\s*<DraftEditor/);
    // จัดการเอกสาร shows on the เงิน tab once an invoice exists (DealPaymentSection) — the way BACK to a draft later.
    expect(paymentSectionSrc).toMatch(/\{invoice && canManageRemainingInvoice \? \([\s\S]{0,200}จัดการเอกสาร/);
    const step = stepText('deal-documents', 2);
    const save = step.indexOf('"บันทึกร่าง"');
    const issue = step.indexOf('"ออกใบแจ้งหนี้"');
    const manage = step.indexOf('"จัดการเอกสาร"');
    expect(save).toBeGreaterThanOrEqual(0);
    expect(issue, 'ออกใบแจ้งหนี้ follows บันทึกร่าง in the same dialog').toBeGreaterThan(save);
    expect(manage, 'จัดการเอกสาร is the way back later, after the issue step').toBeGreaterThan(issue);
    expect(step).toContain('หน้าต่างเดิม');
  });

  it('a visit is ticked only on its day or later (VisitState canTick = plannedDate <= today)', () => {
    expect(release).not.toBeNull();
    expect(visitsThisWeekSrc).toContain('const canTick = visit.plannedDate <= todayIso;');
    expect(stepText('visits', 3)).toContain('ในวันนัดหรือหลังจากนั้น');
    // On a phone (≤520px, mobile-xs) the tick button is the ✓ icon alone: its "เข้าพบแล้ว" text is hidden.
    expect(visitsThisWeekSrc).toContain('<span className="mobile-xs:hidden">เข้าพบแล้ว</span>');
    expect(stepText('today-phone', 4)).toContain('✓');
  });

  it('the top-right buttons named are the header quick actions themselves (review M4)', () => {
    expect(release).not.toBeNull();
    // "บันทึกกิจกรรม" is a real button too (DealHistoryPanel's activity form), so a source-wide label check cannot see a
    // label moved to the wrong place: the step about the header's top right must quote DealQuickActions' own labels.
    const step = byId('deal-page').steps.find((s) => s.text.includes('มุมขวาบน'));
    const labels = [...step.text.matchAll(/"([^"]+)"/g)].map((match) => match[1]);
    expect(labels.length).toBeGreaterThan(0);
    for (const label of labels) expect(code(quickActionsSrc), label).toMatch(new RegExp(`>\\s*${label}\\s*<`));
  });

  it('the quiet links are placed where DealOverviewTab puts them: last in the right-hand column', () => {
    expect(release).not.toBeNull();
    expect(dealTabBodiesSrc).toMatch(/\{info\}\s*\{parties\}\s*\{quietLinks\}/);
    const step = stepText('deal-page', 5);
    expect(step).toContain('ล่างสุดของคอลัมน์ขวา');
    expect(step).not.toMatch(/อยู่ใต้กล่อง "ข้อมูลงาน"/);
  });

  it('a conditional control is described with its condition', () => {
    expect(release).not.toBeNull();
    // NextStepCard renders a button only when the page resolved one for this viewer.
    expect(stepText('deal-page', 2)).toContain('ถ้ามีปุ่ม');
    // The IR button waits for the deposit, a factory on every line, and (when sourcing is pending) จัดซื้อสินค้า.
    expect(fulfilmentSrc).toContain('disabled={createDraftsMutation.isPending || !allLinesPicked || !depositReady || sourcePending}');
    expect(stepText('deal-documents', 3)).toContain('"จัดซื้อสินค้า"');
    expect(stepText('deal-documents', 3)).toContain('ทุกรายการมีโรงงาน');
    // บันทึกการส่งสินค้า is offered only when the server lists RECORD_PARTIAL_DELIVERY, in the ส่งมอบสินค้า block.
    expect(stepText('deal-documents', 4)).toContain('"ส่งมอบสินค้า"');
    // ค้างจากสัปดาห์ก่อน renders on the current week only (VisitsPage weekOffset === 0).
    expect(visitsPageSrc).toMatch(/\{weekOffset === 0 \? \(\s*<CarriedOverVisits/);
    expect(stepText('visits', 4)).toContain('สัปดาห์ปัจจุบัน');
  });

  it('says what is true and nothing more (review F7, F8, F9)', () => {
    expect(release).not.toBeNull();
    // No endpoint corrects a delivery record and the sales manager cannot write deliveries: promise no fix.
    for (const id of ['deal-documents', 'deal-documents-ceo']) expect(byId(id).help, id).not.toContain('ผู้จัดการฝ่ายขาย');
    // A remembered view is per browser (localStorage), not "the system".
    for (const entry of release.entries) for (const text of allText(entry)) expect(text, entry.id).not.toContain('ระบบจำ');
    expect(stepText('all-deals', 4)).toContain('เบราว์เซอร์นี้');
    // ดีล → งาน is true on the board and the deal page, not on the quotation list ("จัดกลุ่มตามดีล", "เปิดดีล").
    for (const entry of release.entries) {
      for (const text of allText(entry)) {
        expect(text, entry.id).not.toContain('หน้าจอฝ่ายขายเรียก');
        expect(text, entry.id).not.toContain('ตามงานแล้ว');
      }
    }
    expect(byId('deal-page').why).not.toContain('แท็บที่เหลือ');
  });

  it('does not claim a label that is in the source but not on that role’s screen', () => {
    expect(release).not.toBeNull();
    // "… งานเงียบ" is the ภาพรวมทีม summary under the TABLE, rendered for sales_manager / ceo only
    // (TicketListPage MANAGER_PIPELINE_ROLES); a rep's table has no such badge.
    expect(quotedLabels(byId('all-deals'))).not.toContain('งานเงียบ');
    // "เข้าพบแล้ว … จาก … นัด" is VisitDots, the dots column of ภาพรวมทีมขาย's table, not the /visits team grid.
    expect(quotedLabels(byId('visits-team')).join('|')).not.toContain('เข้าพบแล้ว … จาก');
  });

  it('the retired menu name รายการดีล is never presented as the current name', () => {
    expect(release).not.toBeNull();
    for (const entry of release.entries) {
      for (const field of [entry.title, entry.page, entry.menu]) expect(field, entry.id).not.toContain('รายการดีล');
      expect(quotedLabels(entry), entry.id).not.toContain('รายการดีล');
      for (const text of allText(entry)) {
        if (text.includes('รายการดีล')) expect(text, `${entry.id}: "${text}" must say รายการดีล is the old name`).toContain('เดิม');
      }
    }
  });

  it('does not describe the lifecycle dialogs that are not part of this release', () => {
    expect(release).not.toBeNull();
    // The create / move-to-S2 / pause / lost / cancel dialogs ship later: the quiet links may be named, never their
    // dialogs' contents, and "งานใหม่" (the create dialog) is not offered.
    for (const entry of release.entries) {
      for (const text of allText(entry)) {
        expect(text, entry.id).not.toContain('งานใหม่');
        expect(text, entry.id).not.toMatch(/เหตุผลที่เสียงาน|เลือกเหตุผล|วันที่จะกลับมา/);
      }
    }
  });
});
