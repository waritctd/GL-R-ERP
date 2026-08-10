import { useEffect, useRef } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Button } from './Button.jsx';
import { Icon } from './Icon.jsx';
import { Panel } from './Layout.jsx';

// Thai labels for the guarded routes in app/permissions.js's PATH_GUARDS —
// copy only, used to name the refused page in the message below. Mirrors the
// same paths' nav labels in AppShell.jsx's navItems so the wording matches
// what the user would have clicked in the sidebar. NEVER the source of an
// allow/deny decision — PATH_GUARDS in app/permissions.js stays the single
// source of truth for who may reach what; this map only decides what the
// refusal is called.
//
// Not derived from AppShell.jsx's navItems: that array is rebuilt per-render
// from `user`/`hasPermission`/`SALES_ENABLED` (it's a dynamic, per-viewer nav
// model, not a static label table), and several PATH_GUARDS entries — the
// `:id` detail sub-paths (`/tickets/:id`, `/employees/:id`,
// `/pricing-requests/:id`, `/factory-purchase-orders/:id`) — have no
// top-level nav item at all, only their list/queue parent does. Pulling a
// clean static label source out of AppShell would mean restructuring that
// component, which is out of scope for this fix; the fallback below (raw
// pathname, no crash) keeps this map's staleness harmless if a guarded route
// is ever added here without a matching entry.
const PATH_LABELS = {
  '/hr': 'ภาพรวม HR',
  '/employees': 'พนักงานทั้งหมด',
  '/requests': 'คำขอแก้ไขข้อมูล',
  '/my-requests': 'ข้อมูลส่วนตัว',
  '/profile': 'ข้อมูลส่วนตัว',
  '/tickets': 'รายการดีล',
  '/catalog': 'แคตตาล็อกสินค้า',
  '/commissions': 'ค่าคอมมิชชัน',
  '/finance': 'งานการเงิน',
  '/payroll': 'เงินเดือน',
  '/price-import': 'นำเข้าราคา',
  '/pricing-requests': 'คิวคำขอราคา',
  '/ceo-settings': 'ตั้งค่าราคา',
  '/factory-purchase-orders': 'ใบสั่งซื้อโรงงาน',
  '/procurement': 'จัดซื้อ & นำเข้า',
  '/employee-requests': 'คำขอ (OT / สวัสดิการ)',
  '/overtime': 'คำขอ (OT / สวัสดิการ)',
  '/leave': 'วันลา',
};

function labelForPath(pathname) {
  if (PATH_LABELS[pathname]) return PATH_LABELS[pathname];
  const prefix = Object.keys(PATH_LABELS)
    .filter((candidate) => pathname.startsWith(`${candidate}/`))
    .sort((a, b) => b.length - a.length)[0];
  return prefix ? PATH_LABELS[prefix] : null;
}

/**
 * Rendered by RequireAccess IN PLACE of the guarded route (issue #391) when
 * the logged-in user lacks the permission for the current path. Deliberately
 * does not redirect: the URL stays exactly as typed or shared, so a deep
 * link stays shareable and the refusal survives a reload — see
 * app/RequireAccess.jsx for the guard this backs.
 *
 * A 403-shaped state (authenticated, insufficient privilege), not a 404 —
 * the heading and copy say so explicitly instead of a generic "not found".
 */
export function AccessDeniedPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const headingRef = useRef(null);
  const pageLabel = labelForPath(location.pathname);

  // WCAG 2.2 §3.3.1 Error Identification: move focus to the heading on mount
  // so a keyboard/screen-reader user lands directly on "ไม่มีสิทธิ์เข้าถึงหน้านี้"
  // instead of silently staying wherever focus was before this view swapped
  // in. Mirrors AppShell.jsx's skip-link idiom (`tabIndex={-1}` target +
  // `.focus()`), applied at route-mount instead of a click handler.
  //
  // Deliberately NOT role="alert": that role is an assertive live region for
  // a message appearing inside an already-rendered page. Wrapping this
  // entire view (heading + copy + two buttons) in one would announce the
  // whole subtree assertively on every mount — interrupting whatever the
  // screen reader was mid-sentence on — and nesting interactive controls
  // inside a live region is a known anti-pattern. A real, focused `<h1>`
  // already satisfies "identified in text" without either problem.
  useEffect(() => {
    headingRef.current?.focus();
  }, [location.pathname]);

  // A denied route is most often reached via an externally shared deep link
  // (chat/email), not by clicking through the app — so there is frequently
  // no in-app history entry to go back to. `location.key === 'default'` is
  // react-router 7's marker for the initial history entry (no prior SPA
  // navigation happened this session): `navigate(-1)` in that case either
  // no-ops or exits the SPA entirely back to whatever opened the link, which
  // is worse than not offering the button. Hide "ย้อนกลับ" in that case and
  // leave "ไปที่แดชบอร์ด" as the one, always-safe action.
  const hasInAppHistory = location.key !== 'default';

  return (
    <div className="grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px]">
      <div className="grid grid-cols-[minmax(0,1fr)_auto] items-start gap-[18px] mobile:grid-cols-[minmax(0,1fr)] mobile:gap-2.5">
        <div>
          <h1 ref={headingRef} tabIndex={-1} className="m-0 text-2xl leading-[1.2] [overflow-wrap:anywhere] text-balance outline-none">
            ไม่มีสิทธิ์เข้าถึงหน้านี้
          </h1>
        </div>
      </div>
      <Panel className="grid min-h-[220px] place-items-center content-center gap-2 text-center text-text-muted">
        <Icon name="lock" size={34} />
        <p>
          บัญชีของคุณไม่มีสิทธิ์เข้าถึงหน้า
          {pageLabel ? (
            <>
              {' '}
              &ldquo;<strong>{pageLabel}</strong>&rdquo;
            </>
          ) : null}{' '}
          (<code>{location.pathname}</code>)
        </p>
        <span>
          หากคิดว่าคุณควรมีสิทธิ์เข้าถึงหน้านี้ กรุณาติดต่อหัวหน้างานหรือผู้ดูแลระบบเพื่อขอสิทธิ์เพิ่มเติม
        </span>
        <div className="page-actions flex flex-wrap items-center justify-end gap-2.5 mobile:w-full mobile:justify-start">
          {hasInAppHistory ? (
            <Button type="button" variant="secondary" onClick={() => navigate(-1)}>
              ย้อนกลับ
            </Button>
          ) : null}
          <Button type="button" variant="primary" onClick={() => navigate('/')}>
            ไปที่แดชบอร์ด
          </Button>
        </div>
      </Panel>
    </div>
  );
}
