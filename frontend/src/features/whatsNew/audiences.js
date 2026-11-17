import { canAccessPath, landsOnSelfService } from '../../app/permissions.js';

/** Every role DivisionAccessPolicy.roleFor can return, in the order the ทั้งหมด tab lists their sections. Must equal
 * the backend's ReleaseNoticePolicy.ROLES: an unknown role is a 400 at publish time. */
export const ALL_ROLES = ['sales', 'sales_manager', 'ceo', 'account', 'import', 'hr', 'warehouse', 'qc', 'employee'];

/** Department names as employees say them. Deliberately not utils/format.js roleLabel (SALES, SALES MANAGER …),
 * which is the topbar's role badge, not prose. */
export const ROLE_LABEL = {
  sales: 'ฝ่ายขาย',
  sales_manager: 'ผู้จัดการฝ่ายขาย',
  ceo: 'CEO',
  account: 'ฝ่ายบัญชี',
  import: 'ฝ่ายนำเข้า',
  hr: 'ฝ่ายบุคคล',
  warehouse: 'คลังสินค้า',
  qc: 'QC',
  employee: 'พนักงานทั่วไป',
};

export const CHANGE_KINDS = { new: 'ใหม่', changed: 'เปลี่ยนแปลง', data: 'ปรับข้อมูล' };

/** Who sees the publish / email control. A UI hint only: ReleaseNoticeService enforces the same set. */
export const MANAGE_ROLES = ['ceo', 'hr'];

export function canManageReleaseNotes(user) {
  return MANAGE_ROLES.includes(user?.role);
}

/** A stand-in user of `role`, for deciding what that role can open when the actual viewer is someone else (the
 * ทั้งหมด tab, and the publish payload the backend fans out per role). */
function representativeUser(role) {
  return { id: 0, role, employeeId: 0 };
}

/** Whether `user` can open the page `entry` describes TODAY: the route guard and the release lockdown both apply
 * (canAccessPath), and a landing entry describes the role's own dashboard, which a viewer whose `/` is the
 * self-service landing does not get. */
export function entryVisibleTo(entry, user) {
  if (!user) return false;
  if (entry.landing && landsOnSelfService(user)) return false;
  return canAccessPath(entry.path, user);
}

function entriesOfSection(release, role, viewer) {
  const byId = new Map(release.entries.map((entry) => [entry.id, entry]));
  return (release.sections[role] ?? [])
    .map((id) => byId.get(id))
    .filter((entry) => entry && entryVisibleTo(entry, viewer));
}

/** The viewer's own section: what changed on the pages they can open. */
export function entriesForViewer(release, user) {
  if (!release || !user) return [];
  return entriesOfSection(release, user.role, user);
}

/** The roles told about `entry`: those whose section lists it and who can open its page. */
export function rolesForEntry(release, entry) {
  return ALL_ROLES.filter((role) => (release.sections[role] ?? []).includes(entry.id)
    && entryVisibleTo(entry, representativeUser(role)));
}

export function isForEveryone(release, entry) {
  return ALL_ROLES.every((role) => (release.sections[role] ?? []).includes(entry.id));
}

export function audienceLabels(release, entry) {
  if (isForEveryone(release, entry)) return ['ทุกคน'];
  return rolesForEntry(release, entry).map((role) => ROLE_LABEL[role]);
}

/**
 * The ทั้งหมด tab: one section for what everyone got, then one section per role that has pages of its own (each as
 * that role sees it today), and the roles whose only change is the everyone section.
 */
export function allTabSections(release) {
  const everyone = release.entries.filter((entry) => isForEveryone(release, entry));
  const everyoneIds = new Set(everyone.map((entry) => entry.id));
  const sections = [{ key: 'everyone', label: 'ทุกแผนก', entries: everyone }];
  const addressOnlyRoles = [];
  for (const role of ALL_ROLES) {
    const own = entriesOfSection(release, role, representativeUser(role)).filter((entry) => !everyoneIds.has(entry.id));
    if (own.length > 0) sections.push({ key: role, label: ROLE_LABEL[role], entries: own });
    else addressOnlyRoles.push(role);
  }
  return { sections, addressOnlyRoles };
}

/**
 * What the CEO/HR control sends to POST /api/release-notes/{audience,publish,email}: each entry once, with the roles
 * that are told about it. Computed under the same lockdown as the page, so the bell and the email never list a
 * page its reader cannot open.
 */
export function publishPayload(release) {
  return {
    releaseId: release.id,
    title: release.title,
    changes: release.entries
      .map((entry) => ({ title: entry.title, roles: rolesForEntry(release, entry) }))
      .filter((change) => change.roles.length > 0),
  };
}

/**
 * The request body the CEO/HR control actually sends to /api/release-notes/{audience,publish-with-email,email}:
 * publishPayload's audience (same role filter, so the email never names a page its reader cannot open) plus the
 * email's copy, read from the same release file — the release `date` (the subject names it) and each entry's
 * one-sentence `why` (shown under its title). The backend validates and caps both (ReleaseNoticePolicy.normalize)
 * and escapes them in the HTML. Nothing here is typed twice: a new release file needs no backend change.
 */
export function noticePayload(release) {
  return {
    ...publishPayload(release),
    date: release.date,
    changes: release.entries
      .map((entry) => ({
        title: entry.title,
        roles: rolesForEntry(release, entry),
        why: entry.why?.trim() ? entry.why : null,
      }))
      .filter((change) => change.roles.length > 0),
  };
}
