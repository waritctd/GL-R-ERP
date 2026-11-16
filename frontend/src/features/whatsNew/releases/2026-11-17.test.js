import { afterEach, describe, expect, it, vi } from 'vitest';
import { ROLE_PERMISSIONS } from '../../../api/routes.js';
import { hasPermission } from '../../../app/permissions.js';
import { canPerformFinanceStep } from '../../finance/financeActionPerformers.js';
import { FINANCE_WORK_GROUPS } from '../../finance/financeWorkGroups.js';
import { BOARD_COLUMNS } from '../../salesBoard/boardFormat.js';
import { MOVER_LABEL, REP_STEPS } from '../../tickets/dealSteps.js';
import { TICKET_DETAIL_TABS } from '../../tickets/ticketDetailTabs.js';
import { ALL_ROLES, allTabSections, entriesForViewer, publishPayload, rolesForEntry } from '../audiences.js';
import { RELEASES, findRelease, latestRelease } from './index.js';

// The source of every component this release describes. A label the notice puts in "quotes" must exist in the source
// of the screen it describes, so the notice breaks here, not in front of staff, when the UI wording changes.
import appShellSrc from '../../../components/layout/AppShell.jsx?raw';
import sidebarSrc from '../../../components/layout/Sidebar.jsx?raw';
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

const BOARD = [appShellSrc, allDealsSrc, dealBoardSrc, boardFormatSrc, ticketListSrc, myWorkSrc];
const DEAL_PAGE = [ticketDetailSrc, dealHeaderSrc, nextStepCardSrc, dealStepperSrc, dealStepsSrc, quietLinksSrc,
  ticketTabsSrc, quickActionsSrc, dealInfoCardSrc, dealHistorySrc];
// NextStepCard: the deposit notice's create button can sit in ขั้นต่อไป instead of the เงิน tab (mainButtonCreatesNotice).
const DOCUMENTS = [ticketTabsSrc, paymentSectionSrc, depositNoticeSrc, remainingInvoiceSrc, fulfilmentSrc, nextStepCardSrc];
const VISITS = [appShellSrc, visitsPageSrc, visitsThisWeekSrc, visitWeekGridSrc, visitWeekSrc, visitDialogsSrc];
const FINANCE = [appShellSrc, financePageSrc, financeGroupsSrc];

/** Entry id → the sources of the screens it describes. Every entry must be listed: an entry with no sources would
 * skip the label check. */
const ENTRY_SOURCES = {
  'all-deals': BOARD,
  'all-deals-team': BOARD,
  'deal-page': DEAL_PAGE,
  'deal-documents': DOCUMENTS,
  'deal-documents-ceo': DOCUMENTS,
  'quotation-list': [appShellSrc, quotationListSrc, quotationMetaSrc],
  visits: VISITS,
  'visits-team': VISITS,
  'today-phone': [appShellSrc, todayOnPhoneSrc, myWorkSrc, visitDialogsSrc, visitsThisWeekSrc],
  'finance-worklist': FINANCE,
  'finance-worklist-ceo': FINANCE,
};

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

const allText = (entry) => [entry.title, entry.page, entry.menu, entry.why, entry.help,
  ...entry.steps.flatMap((step) => [step.text, step.detail, step.alt])];

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
    const images = release.entries.flatMap((entry) => entry.steps.map((step) => step.image));
    expect(new Set(images).size).toBe(images.length);
    for (const image of images) {
      expect(image.startsWith('/whats-new/2026-11-17/'), image).toBe(true);
      expect(older.has(image), image).toBe(false);
    }
  });
});

describe('2026-11-17 audiences (unlocked, as the test env runs)', () => {
  const idsFor = (viewer) => entriesForViewer(release, viewer).map((entry) => entry.id);

  it('each role is told about exactly the pages it can open that changed for it', () => {
    expect(release).not.toBeNull();
    expect(idsFor(user('sales'))).toEqual(['all-deals', 'deal-page', 'deal-documents', 'quotation-list', 'visits', 'today-phone']);
    expect(idsFor(user('sales_manager'))).toEqual(['all-deals-team', 'deal-page', 'quotation-list', 'visits-team']);
    expect(idsFor(user('ceo'))).toEqual(
      ['all-deals-team', 'deal-page', 'deal-documents-ceo', 'quotation-list', 'visits-team', 'finance-worklist-ceo'],
    );
    // account and import read the ใบเสนอราคา list (canViewDealQuotations) when the lock is off; nothing else changed for
    // them but งานการเงิน for account.
    expect(idsFor(user('account'))).toEqual(['finance-worklist', 'quotation-list']);
    expect(idsFor(user('import'))).toEqual(['quotation-list']);
  });

  it('roles with no changed page get nothing, not filler', () => {
    expect(release).not.toBeNull();
    for (const role of ['hr', 'warehouse', 'qc', 'employee']) {
      expect(release.sections[role], role).toEqual([]);
      expect(idsFor(user(role)), role).toEqual([]);
    }
    expect(idsFor(user('employee', { manager: true }))).toEqual([]);
    // Wrong-way-round: a quotation-grant holder outside the sales roles is not told about the sales pages.
    expect(idsFor(user('qc', { canCreateQuotation: true }))).toEqual([]);
    // Wrong-way-round: import and account are never told about the board, the deal page, visits or the phone screen.
    for (const role of ['import', 'account']) {
      for (const id of ['all-deals', 'all-deals-team', 'deal-page', 'deal-documents', 'deal-documents-ceo', 'visits',
        'visits-team', 'today-phone']) {
        expect(idsFor(user(role)), `${role} told about ${id}`).not.toContain(id);
      }
    }
    expect(idsFor(user('import'))).not.toContain('finance-worklist');
  });

  it('the ใบเสนอราคา entry reaches exactly the roles that can open the list', () => {
    expect(release).not.toBeNull();
    expect(rolesForEntry(release, byId('quotation-list')))
      .toEqual(ALL_ROLES.filter((role) => hasPermission(role, 'canViewDealQuotations')));
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

  it('the ทั้งหมด tab has no empty everyone section and names the roles with nothing', () => {
    expect(release).not.toBeNull();
    const { sections, addressOnlyRoles } = allTabSections(release);
    expect(sections.filter((s) => s.entries.length > 0).map((s) => s.key))
      .toEqual(['sales', 'sales_manager', 'ceo', 'account', 'import']);
    expect(sections.find((s) => s.key === 'everyone').entries).toEqual([]);
    expect(addressOnlyRoles).toEqual(['hr', 'warehouse', 'qc', 'employee']);
  });

  it('publishPayload carries each entry once with the roles told about it', () => {
    expect(release).not.toBeNull();
    const payload = publishPayload(release);
    expect(payload.releaseId).toBe(RELEASE_ID);
    expect(payload.changes).toHaveLength(release.entries.length);
    const byTitle = Object.fromEntries(payload.changes.map((change) => [change.title, change.roles]));
    expect(byTitle[byId('deal-page').title]).toEqual(['sales', 'sales_manager', 'ceo']);
    expect(byTitle[byId('quotation-list').title]).toEqual(['sales', 'sales_manager', 'ceo', 'account', 'import']);
    expect(byTitle[byId('finance-worklist').title]).toEqual(['account']);
    const told = new Set(payload.changes.flatMap((change) => change.roles));
    for (const role of ['hr', 'warehouse', 'qc', 'employee']) expect(told.has(role), role).toBe(false);
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
    expect(ids('sales')).toEqual(['all-deals', 'deal-page', 'deal-documents', 'quotation-list', 'visits', 'today-phone']);
    expect(ids('sales_manager')).toEqual(['all-deals-team', 'deal-page', 'quotation-list', 'visits-team']);
    expect(ids('ceo')).toEqual(
      ['all-deals-team', 'deal-page', 'deal-documents-ceo', 'quotation-list', 'visits-team', 'finance-worklist-ceo'],
    );
    // Locked, account opens /finance only and import neither /finance nor /quotations: the ใบเสนอราคา entry is hidden.
    expect(ids('account')).toEqual(['finance-worklist']);
    for (const role of ['import', 'hr', 'warehouse', 'qc', 'employee']) expect(ids(role), role).toEqual([]);
    expect(ids('employee', { manager: true })).toEqual([]);
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
    expect(hidden.sort()).toEqual(['account:quotation-list', 'import:quotation-list']);
  });

  it('the publish payload names only roles that can open the page', async () => {
    const { publishPayload: payloadOf, locked } = await lockedModules();
    expect(locked).not.toBeNull();
    const payload = payloadOf(locked);
    expect(payload.changes).toHaveLength(locked.entries.length);
    const told = new Set(payload.changes.flatMap((change) => change.roles));
    expect([...told].sort()).toEqual(['account', 'ceo', 'sales', 'sales_manager']);
    const quotationList = locked.entries.find((e) => e.id === 'quotation-list');
    expect(payload.changes.find((change) => change.title === quotationList.title).roles).toEqual(['sales', 'sales_manager', 'ceo']);
  });
});

// The release email (separate work) sends each entry's title and its one-line why, with a link to the page and no
// steps or screenshots. Each must therefore read on its own: one plain sentence, nothing that points at the page.
describe('2026-11-17 titles and reasons read on their own in an email', () => {
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

  it('every menu path is the nav as AppShell / Sidebar render it', () => {
    expect(release).not.toBeNull();
    const instructions = new Set(['เลือกงาน']);
    for (const entry of release.entries) {
      for (const segment of entry.menu.split(' › ')) {
        if (instructions.has(segment)) continue;
        expect(appShellSrc.includes(`'${segment}'`) || sidebarSrc.includes(`'${segment}'`), `${entry.id}: menu "${segment}"`)
          .toBe(true);
      }
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
    // The finance groups, on both finance entries.
    for (const id of ['finance-worklist', 'finance-worklist-ceo']) {
      for (const group of FINANCE_WORK_GROUPS) expect(quotedLabels(byId(id)), `${id}: ${group.label}`).toContain(group.label);
    }
    // The quotation list's view switch.
    expect(quotedLabels(byId('quotation-list'))).toEqual(expect.arrayContaining(['มุมมอง', 'จัดกลุ่มตามดีล', 'วันที่อนุมัติ']));
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

  it('the remaining invoice is saved as a draft before it can be issued (RemainingInvoiceDialog CreateForm → DraftEditor)', () => {
    expect(release).not.toBeNull();
    // CreateForm's only write button is บันทึกร่าง; ออกใบแจ้งหนี้ lives in DraftEditor, shown once a DRAFT exists.
    expect(remainingInvoiceSrc).toMatch(/data-testid="remaining-invoice-create-draft">\s*\{creating \? 'กำลังบันทึกร่าง…' : 'บันทึกร่าง'\}/);
    const step = stepText('deal-documents', 2);
    const save = step.indexOf('"บันทึกร่าง"');
    const manage = step.indexOf('"จัดการเอกสาร"');
    const issue = step.indexOf('"ออกใบแจ้งหนี้"');
    expect(save).toBeGreaterThanOrEqual(0);
    expect(manage, 'จัดการเอกสาร appears only once a draft exists').toBeGreaterThan(save);
    expect(issue).toBeGreaterThan(manage);
  });

  it('a visit is ticked only on its day or later (VisitState canTick = plannedDate <= today)', () => {
    expect(release).not.toBeNull();
    expect(visitsThisWeekSrc).toContain('const canTick = visit.plannedDate <= todayIso;');
    expect(stepText('visits', 3)).toContain('ในวันนัดหรือหลังจากนั้น');
    // On a phone (≤520px, mobile-xs) the tick button is the ✓ icon alone: its "เข้าพบแล้ว" text is hidden.
    expect(visitsThisWeekSrc).toContain('<span className="mobile-xs:hidden">เข้าพบแล้ว</span>');
    expect(stepText('today-phone', 4)).toContain('✓');
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
    // The IR button waits for the deposit and, when sourcing is pending, for จัดซื้อสินค้า.
    expect(stepText('deal-documents', 3)).toContain('"จัดซื้อสินค้า"');
    // บันทึกการส่งสินค้า is offered only when the server lists RECORD_PARTIAL_DELIVERY, in the ส่งมอบสินค้า block.
    expect(stepText('deal-documents', 4)).toContain('"ส่งมอบสินค้า"');
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
