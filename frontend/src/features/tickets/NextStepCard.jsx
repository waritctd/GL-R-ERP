/**
 * "ขั้นต่อไป" (drawing a-deal): the ONE next step for the deal and the ONE primary button for it. The step's words
 * come from nextStep.js's stage table; `bannerText` is the page's own work-state line (e.g. "รอชำระมัดจำ"), shown
 * when there is one; `primaryAction` is the button the page already resolved for this viewer — nothing here decides
 * who may act. Renders nothing when there is neither a step to name nor a button to press.
 *
 * On a phone the button sits in a bar fixed to the bottom of the screen (as the old sticky action bar did), so it
 * stays in thumb reach while the tab content scrolls.
 */
export function NextStepCard({ title, hint, bannerText, primaryAction }) {
  if (!title && !bannerText && !primaryAction) return null;
  return (
    <section
      data-testid="next-step-card"
      aria-label="ขั้นต่อไป"
      className="flex flex-wrap items-center gap-x-4 gap-y-3 rounded-md border border-info-border bg-info-bg px-4 py-3"
    >
      <div className="flex min-w-0 flex-1 basis-64 flex-col gap-0.5">
        <span className="text-2xs font-extrabold tracking-wide text-info">ขั้นต่อไป</span>
        {title ? <p data-testid="next-step-title" className="m-0 text-md font-extrabold leading-snug text-text">{title}</p> : null}
        {bannerText ? <p className="m-0 text-sm font-bold leading-snug text-info-dark">{bannerText}</p> : null}
        {hint ? <p className="m-0 text-xs leading-snug text-text-muted">{hint}</p> : null}
      </div>
      {primaryAction ? (
        <div
          data-testid="ticket-action-bar"
          className="flex shrink-0 items-center gap-2 mobile:fixed mobile:inset-x-0 mobile:bottom-0 mobile:z-20 mobile:justify-end mobile:border-t mobile:border-border mobile:bg-surface mobile:px-4 mobile:py-3 mobile:shadow-lg mobile:[padding-bottom:max(18px,env(safe-area-inset-bottom))]"
        >
          {primaryAction}
        </div>
      ) : null}
    </section>
  );
}
