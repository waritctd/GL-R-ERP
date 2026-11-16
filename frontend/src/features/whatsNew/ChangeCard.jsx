import { useState } from 'react';
import { Icon } from '../../components/common/Icon.jsx';
import { cn } from '../../utils/cn.js';
import { CHANGE_KINDS, audienceLabels } from './audiences.js';
import { useReveal } from './releaseNoticeHooks.js';

const KIND_TONE = {
  new: 'bg-primary-bg text-primary',
  changed: 'bg-warning-bg text-warning',
  data: 'bg-warning-bg text-warning',
};

/** Steps rise in after their card, 60ms apart, capped so a long list never drags. */
const stepDelay = (index) => ({ animationDelay: `${Math.min(index + 1, 5) * 60}ms` });

/** One step's screenshot. A missing file (screenshots are captured after the page ships) becomes a labelled
 * placeholder rather than a broken image; a loaded one fades in. */
function StepScreenshot({ step }) {
  const [state, setState] = useState('loading');

  if (state === 'missing') {
    return (
      <div className="grid aspect-[16/10] w-full place-items-center rounded-md border border-dashed border-border-muted bg-surface-muted p-4 text-center">
        <div className="grid justify-items-center gap-1.5">
          <Icon name="fileText" size={20} className="text-text-muted" />
          <span className="text-sm font-bold text-text-secondary">ภาพหน้าจอจะเพิ่มเร็ว ๆ นี้</span>
          <span className="text-xs text-text-muted">{step.alt}</span>
        </div>
      </div>
    );
  }

  return (
    <img
      src={step.image}
      alt={step.alt}
      loading="lazy"
      decoding="async"
      onLoad={() => setState('loaded')}
      onError={() => setState('missing')}
      className={cn(
        'block aspect-[16/10] w-full rounded-md border border-border bg-surface-muted object-contain object-top',
        state === 'loaded' && 'motion-safe:animate-wn-fade',
      )}
    />
  );
}

/** One page this release changed: what, where, why, numbered steps with a screenshot each, and who to ask. */
export function ChangeCard({ release, entry, anchorId }) {
  const [ref, revealed] = useReveal();
  const labels = audienceLabels(release, entry);

  return (
    <article
      ref={ref}
      id={anchorId}
      className={cn(
        'min-w-0 scroll-mt-4 overflow-hidden rounded-lg border border-border bg-surface',
        'transition-shadow duration-200 ease-out hover:shadow-[0_6px_20px_rgba(15,23,42,0.08)] motion-reduce:transition-none',
        revealed && 'motion-safe:animate-wn-rise',
      )}
    >
      <header className="grid gap-1.5 px-[18px] pb-3 pt-4 mobile:px-4">
        <div className="flex flex-wrap gap-1.5">
          <span className={cn('rounded-full px-2.5 py-0.5 text-xs font-bold', KIND_TONE[entry.kind])}>{CHANGE_KINDS[entry.kind]}</span>
          {labels.map((label) => (
            <span key={label} className="rounded-full bg-phase-4-bg px-2.5 py-0.5 text-xs font-bold text-accent-dark">{label}</span>
          ))}
        </div>
        <h3 className="m-0 text-lg font-extrabold leading-snug text-text [text-wrap:balance]">{entry.title}</h3>
        <p className="m-0 text-sm text-text-muted">
          <span className="font-bold text-text-secondary">หน้า: {entry.page}</span>
          {' · '}
          เมนู: {entry.menu}
        </p>
        <p className="m-0 max-w-[68ch] text-text-secondary">{entry.why}</p>
      </header>

      <ol className="m-0 grid list-none gap-4 px-[18px] pb-4 pt-1 mobile:px-4">
        {entry.steps.map((step, index) => (
          <li
            key={step.image}
            style={revealed ? stepDelay(index) : undefined}
            className={cn(
              'grid min-w-0 items-start gap-3.5 md:grid-cols-[minmax(0,1fr)_minmax(0,1.15fr)]',
              revealed && 'motion-safe:animate-wn-rise',
            )}
          >
            <div className="grid grid-cols-[28px_minmax(0,1fr)] gap-2.5">
              <span aria-hidden="true" className="grid size-7 place-items-center rounded-full bg-primary text-sm font-extrabold text-surface">{index + 1}</span>
              <p className="m-0 mt-0.5 text-text">
                <span className="sr-only">ขั้นที่ </span>
                {step.text}
                {step.detail ? <span className="mt-0.5 block text-sm text-text-muted">{step.detail}</span> : null}
              </p>
            </div>
            <StepScreenshot step={step} />
          </li>
        ))}
      </ol>

      {entry.help ? (
        <p className="mx-[18px] mb-4 mt-0 rounded-md bg-surface-muted px-3 py-2.5 text-sm text-text-muted mobile:mx-4">
          <strong className="text-text">ถ้าเจอปัญหา:</strong> {entry.help}
        </p>
      ) : null}
    </article>
  );
}
