/**
 * "ขั้นต่อไป" (drawing a-deal): the ONE next step for the deal and the ONE primary button for it. `title` names what the
 * button does (the page passes the resolved action's own label when there is one; nextStep.js's stage title
 * otherwise); `facts` are what a closed or paused deal must still say (why and when it was lost, the stage it was left
 * at); `bannerText` is the page's own work-state line (e.g. "รอชำระมัดจำ"); `nextLine` is the page's one "ถัดไป: …"
 * statement (DealStagePanel's NextStageLine), which replaces the stage table's `hint` when present. `primaryAction` is
 * the button the page already resolved for this viewer — nothing here decides who may act. Renders nothing when there
 * is neither a step to name nor a button to press.
 *
 * On a phone the button sits in a bar fixed to the bottom of the screen (as the old sticky action bar did), so it
 * stays in thumb reach while the tab content scrolls; the page adds matching bottom room whenever it does.
 */
export function NextStepCard({
  title, hint, facts = [], nextLine = null, bannerText, primaryAction,
}) {
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
        {facts.length > 0 ? (
          <p data-testid="next-step-facts" className="m-0 flex flex-wrap gap-x-2 text-sm leading-snug text-text-secondary [&>*+*]:before:mr-2 [&>*+*]:before:content-['·']">
            {facts.map((fact) => <span key={fact}>{fact}</span>)}
          </p>
        ) : null}
        {bannerText ? <p className="m-0 text-sm font-bold leading-snug text-info-dark">{bannerText}</p> : null}
        {nextLine ?? (hint ? <p className="m-0 text-xs leading-snug text-text-muted">{hint}</p> : null)}
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
