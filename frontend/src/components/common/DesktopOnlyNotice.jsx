// Payroll processing and attendance import are desktop-first admin flows: they still
// reflow to cards on mobile, but nothing else says so. This is a small, non-blocking
// heads-up — it never disables or hides any functionality.
//
// Icon.jsx (components/common/Icon.jsx) has no 'info'/'monitor' glyph in its registry
// yet, so — following the same fallback InfoTip.jsx uses — this renders without an icon
// rather than pulling in a new lucide-react import for one banner.
export function DesktopOnlyNotice({ message, children }) {
  return (
    <div className="desktop-only-notice" role="note">
      {children ?? message ?? (
        <>
          <span>หน้านี้ออกแบบมาสำหรับเดสก์ท็อป — แนะนำให้ใช้งานบนคอมพิวเตอร์</span>
          <span>Optimized for desktop — best used on a larger screen.</span>
        </>
      )}
    </div>
  );
}
