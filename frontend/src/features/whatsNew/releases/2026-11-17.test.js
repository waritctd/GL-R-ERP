import { createHash } from 'node:crypto';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
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
// 17d (#1329 lifecycle dialogs, #1330 document chain), live on production 2026-11-17.
import ticketCreateModalSrc from '../../tickets/TicketCreateModal.jsx?raw';
import dealDialogPartsSrc from '../../tickets/dealDialogParts.jsx?raw';
import formatSrc from '../../../utils/format.js?raw';
import moveToS2Src from '../../tickets/MoveToS2Modal.jsx?raw';
import pauseDealSrc from '../../tickets/PauseDealModal.jsx?raw';
import markLostSrc from '../../tickets/MarkLostModal.jsx?raw';
import cancelDealSrc from '../../tickets/CancelDealModal.jsx?raw';
import reasonModalSrc from '../../tickets/ReasonModal.jsx?raw';
import stageMetaSrc from '../../tickets/stageMeta.js?raw';
import followUpListSrc from '../../salesBoard/FollowUpList.jsx?raw';
import teamOverviewSrc from '../../salesBoard/TeamOverview.jsx?raw';
import quotationEditorSrc from '../../quotations/QuotationEditorPage.jsx?raw';
import chainCardSrc from '../../quotations/QuotationDocumentChain.jsx?raw';
import quotationChainSrc from '../../quotations/quotationChain.js?raw';
import documentChainTextSrc from '../../documentChain/documentChainText.js?raw';
import workStateSrc from '../../tickets/workState.js?raw';
import thaiDateInputSrc from '../../../components/common/ThaiDateInput.jsx?raw';
import managerOverviewSrc from '../../dashboard/ManagerOverview.jsx?raw';
import ceoOverviewSrc from '../../dashboard/CeoOverview.jsx?raw';
import { IMAGE_SHA256_2026_11_17 } from './imageHashes.fixture.js';
import { CANCEL_REASON_LABEL, LOST_REASON_LABEL } from '../../tickets/stageMeta.js';
import { CHAIN_LIST_MARK, CHAIN_STEP_TITLES } from '../../documentChain/documentChainText.js';
import { canAccessPath } from '../../../app/permissions.js';

const RELEASE_ID = '2026-11-17';
const user = (role, extra = {}) => ({ id: 1, role, employeeId: 1, ...extra });

// What each lock-exempt sales role is told, lock on and off alike (17d added the create form, the lifecycle dialogs
// and the document chain to the 17a set).
const SALES_IDS = ['menu-launcher', 'all-deals', 'deal-create', 'deal-page', 'deal-lifecycle', 'deal-documents',
  'quotation-list', 'quotation-chain', 'visits', 'today-phone'];
const MANAGER_IDS = ['menu-launcher', 'all-deals-team', 'deal-page', 'deal-lifecycle-team', 'quotation-list-approver',
  'quotation-chain-manager', 'visits-team'];
const NEW_17D_IDS = ['deal-create', 'deal-lifecycle', 'deal-lifecycle-team', 'quotation-chain', 'quotation-chain-manager',
  'quotation-chain-ceo'];
const CEO_IDS = ['menu-launcher', 'all-deals-team', 'deal-page', 'deal-lifecycle-team', 'deal-documents-ceo',
  'quotation-list-approver', 'quotation-chain-ceo', 'visits-team', 'finance-worklist-ceo'];
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
// สร้างงานใหม่: the "งานใหม่" buttons (AllDealsPage, MyWork, TodayOnPhone) and the form (เสนอแก่ is format.js's constant).
const CREATE = [...BOARD, todayOnPhoneSrc, ticketCreateModalSrc, dealDialogPartsSrc, formatSrc];
// The lifecycle dialogs open from the deal page; a returning paused deal shows in ควรตามต่อ (rep) / ภาพรวมทีมขาย (team).
// The past-date message is ThaiDateInput's own (fix round 1, L1).
const LIFECYCLE_COMMON = [...DEAL_PAGE, moveToS2Src, pauseDealSrc, markLostSrc, cancelDealSrc, reasonModalSrc,
  dealDialogPartsSrc, stageMetaSrc, thaiDateInputSrc];
// The rep's returning deal: ควรตามต่อ on งานของฉัน (FollowUpList) and on the phone's วันนี้ของฉัน (TodayOnPhone).
const LIFECYCLE = [...LIFECYCLE_COMMON, followUpListSrc, myWorkSrc, todayOnPhoneSrc];
// The team's: ดีลที่ควรโทรตาม in the ภาพรวมทีมขาย section of the landing (TeamOverview, ManagerOverview, CeoOverview).
const LIFECYCLE_TEAM = [...LIFECYCLE_COMMON, followUpListSrc, teamOverviewSrc, appShellSrc, managerOverviewSrc,
  ceoOverviewSrc];
// The chain card on the quotation page, its words, the dialogs it opens, the list mark, and the deal page's เงิน tab.
const CHAIN = [...QUOTATIONS, quotationEditorSrc, chainCardSrc, quotationChainSrc, documentChainTextSrc, depositNoticeSrc,
  remainingInvoiceSrc, ticketTabsSrc, paymentSectionSrc];

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
  'deal-create': CREATE,
  'deal-lifecycle': LIFECYCLE,
  'deal-lifecycle-team': LIFECYCLE_TEAM,
  'quotation-chain': CHAIN,
  'quotation-chain-manager': CHAIN,
  'quotation-chain-ceo': CHAIN,
}).map(([id, sources]) => [id, sources.map(code)]));

// The backend's own quiet threshold (QuietRule.DEFAULT_QUIET_DAYS; application.yml defaults app.sales.quiet-days to it).
// Vitest runs from frontend/ (vitest.config.js); public/ and ../backend are resolved from there.
const FRONTEND_ROOT = process.cwd();
const QUIET_RULE_SRC = readFileSync(
  resolve(FRONTEND_ROOT, '../backend/src/main/java/th/co/glr/hr/ticket/QuietRule.java'), 'utf8',
);
const DEFAULT_QUIET_DAYS = Number(/DEFAULT_QUIET_DAYS = (\d+);/.exec(QUIET_RULE_SRC)[1]);

// The Java rules that decide who may act in the lifecycle dialogs and the document chain (the mock is not authoritative).
const JAVA = (path) => readFileSync(resolve(FRONTEND_ROOT, '../backend/src/main/java/th/co/glr/hr', path), 'utf8');
const TICKET_SERVICE_SRC = JAVA('ticket/TicketService.java');
const CANCEL_REASON_SRC = JAVA('ticket/DealCancelReason.java');
const DEAL_QUOTATION_SERVICE_SRC = JAVA('dealquotation/DealQuotationService.java');
const DEPOSIT_NOTICE_SERVICE_SRC = JAVA('deposit/DepositNoticeService.java');
const DOCUMENT_CHAIN_SERVICE_SRC = JAVA('documentchain/DocumentChainService.java');

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
  // The 17d entries (lifecycle dialogs, document chain) have a screenshot on every step.
  it('exactly the steps that cannot be captured honestly have no screenshot', () => {
    expect(release).not.toBeNull();
    const imageless = release.entries.flatMap((entry) => entry.steps
      .map((step, index) => (step.image === null ? `${entry.id}-${index + 1}` : null)).filter(Boolean));
    expect(imageless).toEqual(['deal-documents-2', 'quotation-list-view-1', 'quotation-list-view-2', 'quotation-list-view-3']);
    for (const id of ['deal-create', 'deal-lifecycle', 'deal-lifecycle-team', 'quotation-chain', 'quotation-chain-manager',
      'quotation-chain-ceo']) {
      expect(byId(id), `${id} is missing`).toBeTruthy();
    }
  });
});

describe('2026-11-17 audiences (unlocked, as the test env runs)', () => {
  const idsFor = (viewer) => entriesForViewer(release, viewer).map((entry) => entry.id);

  it('each role is told about exactly the pages it can open that changed for it', () => {
    expect(release).not.toBeNull();
    expect(idsFor(user('sales'))).toEqual(SALES_IDS);
    expect(idsFor(user('sales_manager'))).toEqual(MANAGER_IDS);
    expect(idsFor(user('ceo'))).toEqual(CEO_IDS);
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
        'visits-team', 'today-phone', 'deal-create', 'deal-lifecycle', 'deal-lifecycle-team', 'quotation-chain',
        'quotation-chain-manager', 'quotation-chain-ceo']) {
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
    // Review wn3 M1: the gate is owner OR keyer, whatever the role (isOwner = isOwnerOrKeyer; TicketService.canCancel
    // has no role check), so a manager or the CEO who keyed a deal in (pipeline import) does see ยกเลิกงาน.
    expect(ticketDetailSrc).toContain('const isOwner = isOwnerOrKeyer(summary, user.id);');
    expect(byId('deal-page').help).toContain('ยกเลิกได้เฉพาะเจ้าของงานหรือผู้ที่คีย์งานนั้น');
    for (const entry of release.entries) {
      for (const text of allText(entry)) expect(text, entry.id).not.toContain('เฉพาะพนักงานขายเจ้าของงาน');
    }
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
    expect(ids('sales')).toEqual(SALES_IDS);
    expect(ids('sales_manager')).toEqual(MANAGER_IDS);
    expect(ids('ceo')).toEqual(CEO_IDS);
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
      expect(quotedLabels(byId(id)), id).toEqual(expect.arrayContaining(['มุมมอง', 'จัดกลุ่มตามงาน', 'วันที่อนุมัติ']));
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
    // ดีล → งาน holds on every sales screen since the 2026-11-17 sweep: no on-screen label in this notice says ดีล, and
    // the deal page is หน้ารายละเอียดงาน (หน้างาน is the job SITE).
    for (const entry of release.entries) {
      for (const label of quotedLabels(entry)) expect(label, entry.id).not.toContain('ดีล');
      for (const text of allText(entry)) expect(text, entry.id).not.toMatch(/หน้างาน(?!ทั้งหมด|การเงิน|ของฉัน)/);
    }
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

  // Replaces "does not describe the lifecycle dialogs that are not part of this release": #1329 is live on production
  // (release 2026-11-17d), so the dialogs ARE described now, but only in the lifecycle entries, and the create button
  // only to the role that has it.
  it('describes the lifecycle dialogs only in the lifecycle entries, and "งานใหม่" only to the role that has it', () => {
    expect(release).not.toBeNull();
    const LIFECYCLE_IDS = ['deal-create', 'deal-lifecycle', 'deal-lifecycle-team'];
    for (const entry of release.entries) {
      const text = allText(entry).join('\n');
      if (text.includes('งานใหม่')) {
        expect(LIFECYCLE_IDS, `${entry.id} mentions งานใหม่`).toContain(entry.id);
        // AllDealsPage / MyWork render "งานใหม่" for ROLE_PERMISSIONS.canCreateTickets only.
        for (const role of rolesForEntry(release, entry)) {
          expect(ROLE_PERMISSIONS.canCreateTickets, `${entry.id}: ${role} has no งานใหม่ button`).toContain(role);
        }
      }
      // Review wn3 M17: "เหตุผลที่เสียงาน" was in the replaced test's pattern and is restored here. The one exception is
      // the ขั้นต่อไป card's own button label "เปลี่ยนวันที่จะกลับมาดู" (deal-page step 2 names it): a button's name, not
      // the dialog's content, so that exact quoted label is removed before matching.
      if (/เหตุผลที่เสียงาน|เลือกเหตุผล|วันที่จะกลับมา/.test(text.split('"เปลี่ยนวันที่จะกลับมาดู"').join(''))) {
        expect(LIFECYCLE_IDS, `${entry.id} describes a lifecycle dialog`).toContain(entry.id);
      }
    }
    expect(allDealsSrc).toContain('const canCreate = ROLE_PERMISSIONS.canCreateTickets.includes(user.role);');
    expect(byId('deal-create'), 'deal-create is missing').toBeTruthy();
    expect(allText(byId('deal-create')).join(' ')).toContain('"งานใหม่"');
  });
});

// ── 17d: the lifecycle dialogs (#1329) and the document chain on the ใบเสนอราคา page (#1330) ─────────────────────
describe('2026-11-17 lifecycle dialogs and document chain (17d)', () => {
  // Each case first asserts the entries it reads exist, so a missing entry fails as an assertion, not a TypeError.
  beforeEach(() => {
    expect(release).not.toBeNull();
    for (const id of NEW_17D_IDS) expect(byId(id), `${id} is missing from the release`).toBeTruthy();
  });
  const roles = (id) => rolesForEntry(release, byId(id));
  const prose = (id) => [byId(id).title, byId(id).why, byId(id).help,
    ...byId(id).steps.flatMap((s) => [s.text, s.detail])].join('\n');
  /** Entries that tell their reader to press `label` (กด … "label" within a few characters). */
  const pressing = (label) => release.entries.filter((entry) => entry.steps.some(
    (step) => new RegExp(`กด[^"\\n]{0,12}"${label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}"`).test(`${step.text} ${step.detail}`),
  ));

  it('each new entry reaches exactly the roles that have the control (Java rules, not the mock)', () => {
    expect(release).not.toBeNull();
    // สร้างงานใหม่: the button is canCreateTickets (sales) only.
    expect(roles('deal-create')).toEqual(ROLE_PERMISSIONS.canCreateTickets);
    // Hold / dormant / lost / resume: TicketService.canDealOwnership = the owning sales rep, sales_manager, ceo.
    expect(TICKET_SERVICE_SRC).toContain('private static final Set<String> SALES_ROLES  = Set.of("sales");');
    expect(TICKET_SERVICE_SRC).toContain('return isOwner || "sales_manager".equals(role) || "ceo".equals(role);');
    expect(roles('deal-lifecycle')).toEqual(['sales']);
    expect(roles('deal-lifecycle-team')).toEqual(['sales_manager', 'ceo']);
    // The chain card's read gate (dealDocumentScope): sales own deals, account in scope, import never, others all.
    expect(DEPOSIT_NOTICE_SERVICE_SRC).toContain('case "sales" -> DealDocumentScope.OWNER_OR_KEYER;');
    expect(roles('quotation-chain')).toEqual(['sales']);
    expect(roles('quotation-chain-manager')).toEqual(['sales_manager']);
    expect(roles('quotation-chain-ceo')).toEqual(['ceo']);
    // Wrong-way-round: import, hr, warehouse, qc, employee are told about none of them.
    for (const role of ['import', 'hr', 'warehouse', 'qc', 'employee', 'account']) {
      for (const id of ['deal-create', 'deal-lifecycle', 'deal-lifecycle-team', 'quotation-chain', 'quotation-chain-manager',
        'quotation-chain-ceo']) {
        expect(roles(id), `${role} told about ${id}`).not.toContain(role);
      }
    }
  });

  it('nobody is told to press a lifecycle control the Java service refuses them', () => {
    expect(release).not.toBeNull();
    // Cancel is owner/keyer only (TicketService.canCancel), and only sales has the create button that makes an owner.
    expect(TICKET_SERVICE_SRC).toMatch(/private boolean canCancel\(TicketSummaryDto s, UserPrincipal actor\) \{\s*return DealOwnership\.isOwnerOrKeyer\(s, actor\.id\(\)\)/);
    expect(pressing('ยกเลิกงาน').map((e) => e.id)).toEqual(['deal-lifecycle']);
    // Review wn3 M1: owner or keyer, any role — the link on the page is the answer; otherwise ask the owner.
    expect(byId('deal-lifecycle-team').help).toContain('ยกเลิกงานได้เฉพาะเจ้าของงานหรือผู้ที่คีย์งานนั้น');
    expect(byId('deal-lifecycle-team').help).toMatch(/ถ้าเห็นลิงก์ "ยกเลิกงาน"[\s\S]*ถ้าไม่เห็น/);
    // พักงาน / ปิดงาน (เสียงาน) / เปลี่ยนวันที่จะกลับมาดู: canDealOwnership roles only.
    for (const label of ['พักงาน', 'ปิดงาน (เสียงาน)', 'เปลี่ยนวันที่จะกลับมาดู']) {
      const ids = pressing(label).map((e) => e.id);
      expect(ids.sort(), label).toEqual(['deal-lifecycle', 'deal-lifecycle-team']);
    }
    // เปลี่ยนวันที่จะกลับมาดู sits beside ดำเนินการต่อ, under the same RESUME gate.
    expect(ticketDetailSrc).toMatch(/nextStep\.kind === 'resume' && canResumeDeal\) \{[\s\S]{0,1200}เปลี่ยนวันที่จะกลับมาดู/);
    // ย้ายไป S2: TicketService.requireStageWriteAccess lets ceo, sales_manager and the owning rep write a sales stage.
    expect(TICKET_SERVICE_SRC).toMatch(/if \("ceo"\.equals\(role\)\) \{\s*return;\s*\}\s*if \(DealStage\.SALES_TARGET_STAGES\.contains\(targetStage\)\) \{\s*if \("sales_manager"\.equals\(role\)\)/);
    for (const entry of pressing('ย้ายไป S2 · สเปคผ่าน')) {
      for (const role of rolesForEntry(release, entry)) expect(['sales', 'sales_manager', 'ceo'], role).toContain(role);
    }
    // …but the button itself is the ขั้นต่อไป card's resolved action, which workState resolves for the rep only
    // (sales_manager / ceo get an approval action or nothing): the team version must not offer it.
    expect(workStateSrc).toContain("const action = role === 'sales' ? nextSalesAction(");
    expect(ticketDetailSrc).toMatch(/actionKey === 'advance_stage'\) \{[\s\S]{0,900}stickyPrimaryLabel = 'ย้ายไป S2 · สเปคผ่าน';/);
    expect(pressing('ย้ายไป S2 · สเปคผ่าน').map((e) => e.id)).toEqual(['deal-lifecycle']);
    expect(quotedLabels(byId('deal-lifecycle-team'))).not.toContain('ย้ายไป S2 · สเปคผ่าน');
  });

  it('nobody is told to press a document-chain control the Java service refuses them', () => {
    expect(release).not.toBeNull();
    // ยืนยันคำสั่งซื้อ = DealQuotationService.hasEditAccess: owning sales or any sales_manager — never the CEO.
    expect(DOCUMENT_CHAIN_SERVICE_SRC).toContain('quotations.hasEditAccess(actor, s), mayWriteDocuments, mayWriteDocuments);');
    expect(DEAL_QUOTATION_SERVICE_SRC).toContain('private static final Set<String> EDIT_ROLES = Set.of("sales", "sales_manager");');
    const confirmers = pressing('ยืนยันคำสั่งซื้อ').map((e) => e.id).sort();
    expect(confirmers).toEqual(['quotation-chain', 'quotation-chain-manager']);
    // The deposit notice and the remaining invoice: DepositNoticeService.mayWriteDealDocuments = owning sales only.
    expect(DEPOSIT_NOTICE_SERVICE_SRC).toContain('return SALES_ROLES.contains(actor.role()) && DealOwnership.isOwnerOrKeyer(summary, actor.id());');
    expect(DEPOSIT_NOTICE_SERVICE_SRC).toContain('private static final java.util.Set<String> SALES_ROLES  = java.util.Set.of("sales");');
    for (const label of ['สร้างใบแจ้งยอดเงินรับมัดจำ', 'ออกเอกสาร', 'สร้างใบแจ้งหนี้ส่วนที่เหลือ']) {
      for (const entry of pressing(label)) expect(rolesForEntry(release, entry), `${entry.id} presses ${label}`).toEqual(['sales']);
    }
    // Where the viewer cannot act the card says who it waits on (quotationChain WAITING_ON_TEXT.sales).
    expect(quotationChainSrc).toContain("const WAITING_ON_TEXT = { sales: 'ฝ่ายขายเจ้าของงาน', account: 'ฝ่ายบัญชี' };");
    for (const id of ['quotation-chain-manager', 'quotation-chain-ceo']) expect(prose(id), id).toContain('ฝ่ายขายเจ้าของงาน');
  });

  it('account is told about the chain only if it can open the quotation page under the production lock', async () => {
    expect(release).not.toBeNull();
    // Unlocked (dev / test) account can open a quotation; locked (production) it cannot, so it gets no chain entry.
    expect(canAccessPath('/quotations/1', user('account'))).toBe(true);
    vi.stubEnv('VITE_SELF_SERVICE_ONLY', 'true');
    vi.resetModules();
    try {
      const { canAccessPath: lockedAccess } = await import('../../../app/permissions.js');
      expect(lockedAccess('/quotations/1', user('account'))).toBe(false);
      for (const role of ['sales', 'sales_manager', 'ceo']) expect(lockedAccess('/quotations/1', user(role)), role).toBe(true);
    } finally {
      vi.unstubAllEnvs();
      vi.resetModules();
    }
    for (const entry of release.entries.filter((e) => e.id.startsWith('quotation-chain'))) {
      expect(release.sections.account, entry.id).not.toContain(entry.id);
    }
  });

  it('the create form is described as it renders: required fields, the start step and the collapsed details', () => {
    expect(release).not.toBeNull();
    const text = prose('deal-create');
    for (const label of ['สร้างงานใหม่', 'ชื่อโครงการ', 'ลูกค้า / บริษัท', 'มูลค่าโดยประมาณ', 'ผู้ออกแบบ',
      'เริ่มที่', 'สร้างงาน', 'รายละเอียดเพิ่มเติม (ไม่บังคับ)']) {
      expect(quotedLabels(byId('deal-create')), label).toContain(label);
    }
    // เสนอแก่ is required (no default chip) with exactly the three channels TicketCreateModal offers.
    expect(quotedLabels(byId('deal-create'))).toContain(formatSrc.match(/OFFERED_TO_LABEL = '([^']+)'/)[1]);
    for (const channel of [...ticketCreateModalSrc.matchAll(/\{ value: '[A-Z_]+', label: '([^']+)' \}/g)].map((m) => m[1])) {
      expect(quotedLabels(byId('deal-create')), channel).toContain(channel);
    }
    expect(ticketCreateModalSrc).toContain("if (!entryChannel) errors.entryChannel = 'กรุณาเลือกว่างานนี้เสนอแก่ใคร';");
    // The two start options, verbatim.
    for (const option of [...ticketCreateModalSrc.matchAll(/title: '(S[12] · [^']+)'/g)].map((m) => m[1])) {
      expect(quotedLabels(byId('deal-create')), option).toContain(option);
    }
    // A sales creator gets no items section (canEditDealItemsAfterCreate): items are added on the deal page.
    expect(ticketCreateModalSrc).toContain('const offersItems = !canEditDealItemsAfterCreate(user);');
    expect(text).toContain('รายการสินค้าเพิ่มได้ในหน้ารายละเอียดงาน');
    expect(text).not.toMatch(/"รายการสินค้า"/);
  });

  it('the pause dialog, the return date and where a returning deal shows are pinned to their source', () => {
    expect(release).not.toBeNull();
    for (const id of ['deal-lifecycle', 'deal-lifecycle-team']) {
      const labels = quotedLabels(byId(id));
      for (const label of ['พักชั่วคราว', 'ไม่เคลื่อนไหว', 'วันที่จะกลับมาดู', '2 สัปดาห์', '1 เดือน', '2 เดือน',
        'เปลี่ยนวันที่จะกลับมาดู', 'บันทึกวันที่', 'ดำเนินการต่อ', 'พักไว้ · ถึงวันกลับมาดู …']) {
        expect(labels, `${id}: ${label}`).toContain(label);
      }
      // Changing only the date sends no notification (PauseDealModal's own note; TicketService.changePauseReturnOn).
      expect(pauseDealSrc).toContain('เปลี่ยนเฉพาะวันที่ ไม่มีการแจ้งเตือน');
      expect(prose(id), id).toContain('ไม่ส่งแจ้งเตือน');
      // A date in the past is refused, with this message.
      // Review wn3 L1: the field takes min={today}, so a typed past date is refused by ThaiDateInput with ITS message
      // (ERR_RANGE); PauseDealModal's own "วันที่จะกลับมาดูต้องเป็นวันนี้…" shows only for a stale pre-filled date.
      expect(pauseDealSrc).toMatch(/id="pause-return-on"[\s\S]{0,80}min=\{today\}/);
      expect(labels, id).toContain(/const ERR_RANGE = '([^']+)';/.exec(thaiDateInputSrc)[1]);
      expect(labels, id).not.toContain('วันที่จะกลับมาดูต้องเป็นวันนี้หรือหลังจากนี้');
    }
    // The rep: back in ควรตามต่อ on งานของฉัน (PauseDealModal's hint, FollowUpList's chip).
    expect(pauseDealSrc).toContain('ถึงวันนั้นงานจะกลับมาที่ “ควรตามต่อ” เอง');
    expect(followUpListSrc).toContain('พักไว้ · ถึงวันกลับมาดู ${formatThaiDate(deal.pauseReturnOn)}');
    expect(quotedLabels(byId('deal-lifecycle'))).toEqual(expect.arrayContaining(['ควรตามต่อ', 'งานของฉัน']));
    // The team: first in ดีลที่ควรโทรตาม on ภาพรวมทีมขาย, counted in ดีลเงียบ (TeamOverview).
    expect(teamOverviewSrc).toContain('งานที่ถึงวันกลับมาดูขึ้นก่อน');
    expect(quotedLabels(byId('deal-lifecycle-team'))).toEqual(expect.arrayContaining(['ภาพรวมทีมขาย', 'งานที่ควรโทรตาม', 'งานเงียบ']));
  });

  it('the lost and cancel reasons are the ones the dialogs offer today', () => {
    expect(release).not.toBeNull();
    // Cancel: exactly DealCancelReason.OFFERED, by their Thai labels; the three retired reasons are never offered.
    const offered = /OFFERED = List\.of\(\s*([^)]*)\)/.exec(CANCEL_REASON_SRC)[1].split(',').map((code) => code.trim());
    expect(offered).toEqual(['DUPLICATE_DEAL', 'WRONG_CUSTOMER_OR_PROJECT', 'NO_REAL_PROJECT', 'OTHER']);
    const cancelLabels = quotedLabels(byId('deal-lifecycle'));
    for (const code of offered) expect(cancelLabels, code).toContain(CANCEL_REASON_LABEL[code]);
    for (const code of ['OWNER_CANCELLED', 'PROJECT_SUSPENDED', 'BUDGET_CANCELLED']) {
      for (const entry of release.entries) expect(allText(entry).join(' '), `${entry.id}: ${code}`).not.toContain(CANCEL_REASON_LABEL[code]);
    }
    // The cancel dialog's own warning: a cancelled deal never comes back.
    expect(cancelDealSrc).toContain('warning="ยกเลิกแล้วกลับมาไม่ได้ ถ้ายังมีโอกาสขาย');
    expect(cancelLabels).toContain('ยกเลิกแล้วกลับมาไม่ได้');
    // Lost: one reason, coded F1–F8 (LOST_REASON_F_CODE), submitted with ยืนยันเสียงาน.
    expect(Object.keys(LOST_REASON_LABEL)).toHaveLength(8);
    for (const id of ['deal-lifecycle', 'deal-lifecycle-team']) {
      expect(prose(id), id).toContain('F1–F8');
      expect(quotedLabels(byId(id)), id).toEqual(expect.arrayContaining(['ปิดงาน · เสียงาน', 'ยืนยันเสียงาน']));
    }
  });

  it('the S2 move says what the dialog says: by hand to S2, by documents from S3', () => {
    expect(release).not.toBeNull();
    expect(moveToS2Src).toContain('ตั้งแต่ S3 งานย้ายเอง ไม่ต้องกด');
    expect(quotedLabels(byId('deal-lifecycle'))).toEqual(expect.arrayContaining(['ย้ายไป S2 · สเปคผ่าน', 'ขั้นต่อไป', 'โน้ตสั้น ๆ']));
    expect(prose('deal-lifecycle')).toContain('ตั้งแต่ S3 งานย้ายเอง');
    // The dialog asks for a follow-up date / an activity only when the readiness gate is missing one (needsFollowUp /
    // needsActivity); the step says so with its condition.
    expect(moveToS2Src).toContain('ยังไม่มีบันทึกการคุยหลังเปลี่ยนขั้นล่าสุด');
    expect(prose('deal-lifecycle')).toContain('ยังไม่มีบันทึกการคุยหลังเปลี่ยนขั้นล่าสุด');
  });

  it('the chain card, its four steps and the list mark are named as the code names them', () => {
    expect(release).not.toBeNull();
    for (const id of ['quotation-chain', 'quotation-chain-manager', 'quotation-chain-ceo']) {
      const labels = quotedLabels(byId(id));
      expect(labels, id).toContain('เอกสารต่อจากใบเสนอราคานี้');
      for (const title of CHAIN_STEP_TITLES) expect(labels, `${id}: ${title}`).toContain(title);
      // Every list mark (DocumentChainRules#listStage → CHAIN_LIST_MARK), shown under the row's สถานะ.
      for (const mark of Object.values(CHAIN_LIST_MARK)) expect(labels, `${id}: ${mark.label}`).toContain(mark.label);
      // The deal page's เงิน tab reads the same summary.
      expect(labels, id).toContain('เงิน');
      expect(labels, id).toContain('ดาวน์โหลด PDF');
    }
    expect(paymentSectionSrc).toContain('const chainQuery = useDocumentChain(ticketId');
    expect(quotationListSrc).toContain('<ChainMark stage={row.chainStage} />');
    // The rep's deposit dialog: แก้รายละเอียด leads to the full notice page; ยกเลิก keeps the draft.
    expect(quotedLabels(byId('quotation-chain'))).toEqual(
      expect.arrayContaining(['ออกใบแจ้งยอดเงินรับมัดจำ', 'แก้รายละเอียด', 'ออกเอกสาร', 'บันทึกร่าง', 'ออกใบแจ้งหนี้']),
    );
    expect(chainCardSrc).toContain('Cancelling keeps the draft.');
  });

  it('the release title names what 17d added', () => {
    expect(release).not.toBeNull();
    expect(release.title).toContain('เอกสารต่อ');
    expect(release.title).toContain('พักงาน');
  });
});

// ── Fix round 1 (review wn3): sentences a reader would find untrue on screen, and the six surviving mutations ─────────
describe('2026-11-17 fix round 1 (review wn3)', () => {
  const step = (id, n) => `${byId(id).steps[n - 1].text} ${byId(id).steps[n - 1].detail}`;
  const prose = (id) => [byId(id).title, byId(id).why, byId(id).help,
    ...byId(id).steps.flatMap((s) => [s.text, s.detail])].join('\n');
  const CHAIN_IDS = ['quotation-chain', 'quotation-chain-manager', 'quotation-chain-ceo'];

  it('L2: the ขั้นต่อไป card has one MAIN button; a paused deal also offers เปลี่ยนวันที่จะกลับมาดู', () => {
    expect(ticketDetailSrc).toMatch(/data-action="resume"[\s\S]{0,900}data-testid="change-return-date"[\s\S]{0,300}เปลี่ยนวันที่จะกลับมาดู/);
    const text = step('deal-page', 2);
    expect(text).not.toContain('มีปุ่มเดียว');
    expect(text).toContain('ปุ่มหลักหนึ่งปุ่ม');
    expect(text).toContain('"ดำเนินการต่อ"');
    expect(text).toContain('"เปลี่ยนวันที่จะกลับมาดู"');
  });

  it('L3: each manager chain step names the waiting party its own picture shows', () => {
    // manager-1 is the deposit waiting on the rep; manager-3 the issued notice with the remaining invoice on account.
    expect(step('quotation-chain-manager', 1)).toContain('"ฝ่ายขายเจ้าของงาน"');
    expect(step('quotation-chain-manager', 3)).toContain('"ฝ่ายบัญชี"');
    expect(step('quotation-chain-manager', 3)).not.toContain('"ฝ่ายขายเจ้าของงาน"');
  });

  it('L5: says which quotations show no card or a one-line note instead (quotationChainView)', () => {
    // No card and no line: a quotation from the pricing-request flow (origin is not DEAL_DIRECT).
    expect(quotationChainSrc).toContain("if (!quotation || (quotation.origin ?? 'DEAL_DIRECT') !== 'DEAL_DIRECT' || quotation.ticketId == null) return { mode: 'none' };");
    // A one-line note instead of the card: superseded / cancelled quotation, paused / lost / cancelled deal, an order
    // confirmed from another quotation or another way.
    for (const reason of ['SUPERSEDED', 'CANCELLED', 'OTHER_QUOTATION', 'DEAL_LOST', 'DEAL_CANCELLED', 'DEAL_PAUSED',
      'CONFIRMED_ELSEWHERE', 'DEAL_PAST_DRAFT']) {
      expect(quotationChainSrc, reason).toContain(`notice('${reason}'`);
    }
    for (const id of CHAIN_IDS) {
      expect(byId(id).help, id).toContain('คำขอราคา');
      expect(byId(id).help, id).toContain('ไม่มีกล่องนี้');
      expect(byId(id).help, id).toContain('ข้อความหนึ่งบรรทัด');
      expect(byId(id).help, id).toContain('ฉบับอื่น');
    }
  });

  it('I1/I2: the PDF button and the next-step line are described so they hold on a phone too', () => {
    // Desktop: a text button; phone: an icon-only button with the same aria-label.
    expect(chainCardSrc).toContain("{download.busy ? 'กำลังดาวน์โหลด…' : 'ดาวน์โหลด PDF'}");
    expect(chainCardSrc).toMatch(/aria-label=\{download\.label\} title=\{download\.label\} className="hidden shrink-0 mobile:inline-flex"/);
    for (const id of CHAIN_IDS) {
      const text = prose(id);
      expect(text, id).toContain('ดาวน์โหลด PDF');
      expect(text, id).toContain('บนมือถือเป็นไอคอนดาวน์โหลด');
      expect(text, id).not.toContain('มุมขวาบนของกล่อง');
    }
  });

  it('I3: ภาพรวมทีมขาย is the landing (manager) or a dashboard section (CEO), never named a page of its own', () => {
    expect(managerOverviewSrc).toContain('<TeamOverview showTitle={false} />');
    expect(ceoOverviewSrc).toContain('<TeamOverview />');
    const text = step('deal-lifecycle-team', 2);
    expect(text).not.toMatch(/หน้า "ภาพรวมทีมขาย"/);
    expect(text).toContain('ในส่วน "ภาพรวมทีมขาย" ของหน้าแรก');
  });

  it('L4: the rep is told the returning deal shows, with ดำเนินการต่อ, on the phone as well (TodayOnPhone)', () => {
    expect(todayOnPhoneSrc).toContain('{returningLabel(deal)}');
    expect(todayOnPhoneSrc).toMatch(/onClick=\{\(\) => onResume\?\.\(deal\)\}>\s*ดำเนินการต่อ/);
    expect(step('deal-lifecycle', 3)).toContain('"วันนี้ของฉัน"');
  });

  it('I4: the collapsed details are listed in full, เจ้าของงาน included', () => {
    expect(ticketCreateModalSrc).toContain('<FieldLabel htmlFor="deal-owner">เจ้าของงาน');
    expect(step('deal-create', 4)).toContain('เจ้าของงาน');
  });

  it('M13: no prose gives the manager or the CEO the rep-only documents, with or without "กด"', () => {
    // Strip the quoted step titles and list marks (names of things, not actions), then look for any issuing claim.
    const names = [...CHAIN_STEP_TITLES, ...Object.values(CHAIN_LIST_MARK).map((m) => m.label)];
    for (const id of ['quotation-chain-manager', 'quotation-chain-ceo']) {
      let text = prose(id);
      for (const name of names) text = text.split(`"${name}"`).join('');
      expect(text, id).not.toMatch(/ออกแทน|แทนพนักงานขาย|สร้างใบแจ้ง|ออกใบแจ้งยอด|ออกใบแจ้งหนี้ส่วน|ออกเอกสาร|คุณออก|ออกได้เอง/);
    }
    expect(prose('quotation-chain-manager')).toContain('พนักงานขายเจ้าของงานเป็นผู้ออก');
    expect(prose('quotation-chain-ceo')).toContain('ทุกขั้นในกล่องเป็นงานของฝ่ายขายและฝ่ายบัญชี');
  });

  it('M14: the estimate is the deal value only until a quotation gives one (SalesBoardRepository precedence)', () => {
    const repo = JAVA('salesboard/SalesBoardRepository.java');
    const quote = repo.indexOf('BigDecimal value = grandTotal(rs.getBigDecimal("total_amount")');
    const imported = repo.indexOf('if (value == null && rs.getBigDecimal("imported_value") != null)');
    const estimate = repo.indexOf('if (value == null && rs.getBigDecimal("estimated_value") != null)');
    expect(quote).toBeGreaterThan(0);
    expect(imported).toBeGreaterThan(quote);
    expect(estimate).toBeGreaterThan(imported);
    const text = step('deal-create', 3);
    expect(text).toContain('ใช้มูลค่าโดยประมาณเป็นมูลค่าของงานจนกว่าจะมีตัวเลขจากใบเสนอราคา');
    expect(text).not.toMatch(/แม้มีใบเสนอราคา|แทนตัวเลขจากใบเสนอราคา|ก่อนตัวเลขจากใบเสนอราคา/);
  });

  // M5 / M20: an image swapped between steps, or overwritten with another role's frame, kept its file name and so
  // passed. Every file in the folder is pinned to its content; a re-shoot must update the list on purpose.
  const sha = (file) => createHash('sha256')
    .update(readFileSync(resolve(FRONTEND_ROOT, 'public/whats-new/2026-11-17', file))).digest('hex');

  it('M5/M20: every 17 Nov image is the exact picture reviewed for its step (content hash list)', () => {
    const onDisk = readdirSync(resolve(FRONTEND_ROOT, 'public/whats-new/2026-11-17')).sort();
    expect(onDisk).toEqual(Object.keys(IMAGE_SHA256_2026_11_17).sort());
    for (const file of onDisk) expect(sha(file), file).toBe(IMAGE_SHA256_2026_11_17[file]);
  });

  it('M5/M20: steps that show different states, or a role that sees a different frame, have different pictures', () => {
    const different = [
      // Within an entry, every step shows its own state.
      ...release.entries.flatMap((entry) => {
        const files = entry.steps.map((s) => s.image).filter(Boolean).map((image) => image.split('/').pop());
        return files.flatMap((a, i) => files.slice(i + 1).map((b) => [a, b]));
      }),
      // The CEO cannot confirm: its card waits on the rep where the rep's and the manager's carry the button.
      ['quotation-chain-ceo-1.png', 'quotation-chain-1.png'], ['quotation-chain-ceo-1.png', 'quotation-chain-manager-2.png'],
      // The manager's document steps wait; the rep's own frame has the create button.
      ['quotation-chain-manager-1.png', 'quotation-chain-5.png'], ['quotation-chain-manager-3.png', 'quotation-chain-5.png'],
      ['quotation-chain-manager-3.png', 'quotation-chain-3.png'],
      // The team's returning deal is the ดีลที่ควรโทรตาม row, not the rep's ควรตามต่อ row.
      ['deal-lifecycle-team-2.png', 'deal-lifecycle-3.png'],
    ];
    for (const [a, b] of different) expect(sha(a), `${a} must differ from ${b}`).not.toBe(sha(b));
  });
});
