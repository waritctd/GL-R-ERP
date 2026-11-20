import { useEffect, useRef, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { PageStack } from '../../components/common/Layout.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { TabPanel, Tabs } from '../../components/common/Tabs.jsx';
import { ROLE_LABEL, allTabSections, canManageReleaseNotes, entriesForViewer } from './audiences.js';
import { FeedbackInbox, useFeedbackInbox } from './FeedbackInbox.jsx';
import { FeedbackTab } from './FeedbackTab.jsx';
import { ChangeCard } from './ChangeCard.jsx';
import { ReleaseNoticeAdmin } from './ReleaseNoticeAdmin.jsx';
import { releaseIdOfLink, releaseLink, unreadReleaseNotices, useNotifications } from './releaseNoticeHooks.js';
import { findRelease, latestRelease } from './releases/index.js';

const TAB_PREFIX = 'whats-new';

/** Opening the page is reading it: this release's unread bell row(s) are marked read once, which also clears the
 * header bubble's red dot (same cache). So are rows of the retired date-style releases (their link opens "ไม่พบประกาศนี้"), which could otherwise
 * never be cleared. Rows of any other release are left alone. */
function useMarkReleaseRead(release) {
  const queryClient = useQueryClient();
  const notifications = useNotifications();
  const marked = useRef(new Set());

  useEffect(() => {
    const pending = unreadReleaseNotices(notifications)
      .filter((n) => ((release && n.link === releaseLink(release.id)) || isRetiredDateRelease(releaseIdOfLink(n.link))) && !marked.current.has(n.id));
    if (pending.length === 0) return;
    pending.forEach((n) => marked.current.add(n.id));
    Promise.all(pending.map((n) => api.notifications.markRead(n.id)))
      .catch(() => {})
      .finally(() => queryClient.invalidateQueries({ queryKey: queryKeys.notifications() }));
  }, [notifications, queryClient, release]);
}

// Only the retired date-style ids (2026-11-16 …): any other unknown id may be a release newer than this tab's bundle.
const isRetiredDateRelease = (id) => /^\d{4}-\d{2}-\d{2}$/.test(id ?? '') && !findRelease(id);

function Contents({ items }) {
  const reduceMotion = typeof window !== 'undefined'
    && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;

  function jump(event, anchorId) {
    const target = document.getElementById(anchorId);
    if (!target) return;
    event.preventDefault();
    target.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'start' });
    target.focus?.({ preventScroll: true });
  }

  return (
    <nav aria-label="สารบัญ" className="sticky top-0 grid gap-0.5 text-sm">
      <p className="m-0 px-2 pb-1 text-xs font-bold text-text-muted">ในฉบับนี้</p>
      {items.map(({ anchorId, entry, section }) => (
        <a
          key={anchorId}
          href={`#${anchorId}`}
          onClick={(event) => jump(event, anchorId)}
          className="rounded-md px-2 py-1.5 text-text no-underline transition-colors duration-150 hover:bg-surface-subtle motion-reduce:transition-none"
        >
          {section ? <span className="block text-2xs font-bold text-text-muted">{section}</span> : null}
          {entry.page}
        </a>
      ))}
    </nav>
  );
}

function ChangeList({ release, groups, note = null }) {
  const items = groups.flatMap((group) => group.entries.map((entry) => ({
    anchorId: `c-${group.key}-${entry.id}`,
    entry,
    section: groups.length > 1 ? group.label : null,
  })));

  return (
    <div className="grid items-start gap-5 lg:grid-cols-[200px_minmax(0,1fr)]">
      <div className="hidden lg:block lg:self-stretch"><Contents items={items} /></div>
      <div className="grid min-w-0 gap-6">
        {groups.map((group) => (
          <section key={group.key} aria-labelledby={`${TAB_PREFIX}-group-${group.key}`} className="grid min-w-0 gap-4">
            <h2 id={`${TAB_PREFIX}-group-${group.key}`} className="m-0 text-base font-extrabold text-text-secondary">{group.label}</h2>
            {group.entries.map((entry) => (
              <ChangeCard key={entry.id} release={release} entry={entry} anchorId={`c-${group.key}-${entry.id}`} />
            ))}
          </section>
        ))}
        {note ? <p className="m-0 rounded-md bg-surface-muted px-3 py-2.5 text-sm text-text-muted">{note}</p> : null}
      </div>
    </div>
  );
}

/**
 * มีอะไรใหม่ — one release notice, page by page. "สำหรับแผนกของคุณ" shows only the pages the viewer's role got and
 * can open today; "ทั้งหมด" (HR, CEO, owner only) shows a section per role. `/whats-new` alone shows the latest release. Every employee
 * can open it (SELF_SERVICE_PATHS); CEO/HR also get the publish / email control.
 */
export function WhatsNewPage({ user, showToast }) {
  const { releaseId } = useParams();
  const release = releaseId ? findRelease(releaseId) : latestRelease();
  const [searchParams] = useSearchParams();
  // The feedback bell rows link to ?tab=feedback.
  const [tab, setTab] = useState(searchParams.get('tab') === 'feedback' ? 'feedback' : 'mine');
  const urlTab = searchParams.get('tab');
  // Same component serves every /whats-new URL, so a bell link clicked while open only changes the query.
  useEffect(() => { if (urlTab === 'feedback') setTab('feedback'); }, [urlTab]);
  const isAdmin = Boolean(user?.admin);
  const inbox = useFeedbackInbox(isAdmin);
  useMarkReleaseRead(release);

  if (!release) {
    return (
      <PageStack>
        <PageHeader title="มีอะไรใหม่" />
        <EmptyState icon="info" title="ไม่พบประกาศนี้" description="ลิงก์อาจไม่ถูกต้อง หรือประกาศถูกนำออกแล้ว" />
        {latestRelease() ? (
          <Link className="text-link" to={releaseLink(latestRelease().id)}>ดูประกาศล่าสุด</Link>
        ) : null}
      </PageStack>
    );
  }

  const roleLabel = ROLE_LABEL[user?.role] ?? 'คุณ';
  const mine = entriesForViewer(release, user);
  const { sections, addressOnlyRoles } = allTabSections(release);
  const mineGroups = [{ key: 'mine', label: `สำหรับ${roleLabel}`, entries: mine }];
  // Roles whose only entries are the everyone ones; the release says so itself.
  const addressOnlyNote = addressOnlyRoles.length > 0
    ? `${addressOnlyRoles.map((role) => ROLE_LABEL[role]).join(' · ')}: ${release.otherRolesNote ?? 'ฉบับนี้ไม่มีเรื่องเฉพาะแผนก'}`
    : null;
  // "ทั้งหมด" (every role's section) is for HR, CEO and the owner account (the admin flag, GLR-1001 only).
  const canSeeAllTab = user?.role === 'hr' || user?.role === 'ceo' || isAdmin;
  const tabs = [
    { id: 'mine', label: `สำหรับแผนกของคุณ (${mine.length})` },
    ...(canSeeAllTab ? [{ id: 'all', label: `ทั้งหมด (${release.entries.length})` }] : []),
    {
      id: 'feedback',
      label: isAdmin
        ? `เรื่องที่แจ้งเข้ามา (${(inbox.data ?? []).filter((f) => f.status === 'PENDING').length} รอพิจารณา)`
        : 'แจ้งปัญหา / ขอฟีเจอร์',
    },
  ];

  return (
    <PageStack>
      <PageHeader
        eyebrow="มีอะไรใหม่"
        title={release.dateLabel}
        subtitle={`${release.title} · สำหรับ${roleLabel}`}
      />
      {canManageReleaseNotes(user) ? <ReleaseNoticeAdmin release={release} showToast={showToast} /> : null}
      <div>
        <Tabs items={tabs} value={tab} onChange={setTab} ariaLabel="มุมมองประกาศ" idPrefix={TAB_PREFIX} />
        {tabs.map((item) => (
          <TabPanel key={item.id} id={item.id} idPrefix={TAB_PREFIX} active={tab === item.id}>
            {/* Keyed by tab: the incoming panel fades in, so a switch reads as a cross-fade, not a jump. */}
            <div key={item.id} className="motion-safe:animate-wn-fade">
              {item.id === 'feedback' ? (
                isAdmin ? <FeedbackInbox showToast={showToast} /> : <FeedbackTab showToast={showToast} />
              ) : item.id === 'mine' ? (
                mine.length > 0 ? <ChangeList release={release} groups={mineGroups} /> : (
                  <EmptyState icon="info" title="ฉบับนี้ไม่มีเรื่องเฉพาะแผนกของคุณ" description={canSeeAllTab ? 'ดูทุกเรื่องได้ที่แท็บ ทั้งหมด' : undefined} />
                )
              ) : (
                <ChangeList release={release} groups={sections} note={addressOnlyNote} />
              )}
            </div>
          </TabPanel>
        ))}
      </div>
    </PageStack>
  );
}
