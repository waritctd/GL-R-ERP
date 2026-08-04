import { Icon } from './Icon.jsx';
import { cn } from '../../utils/cn.js';

const DEFAULTS = {
  initial: { icon: 'clipboard', title: 'เริ่มจากตัวกรองหรือคำค้นหา' },
  loading: { icon: 'clock', title: 'กำลังโหลดข้อมูล' },
  empty: { icon: 'search', title: 'ยังไม่มีข้อมูล' },
  filtered: { icon: 'search', title: 'ไม่พบข้อมูลที่ตรงกับตัวกรอง' },
  error: { icon: 'close', title: 'โหลดข้อมูลไม่สำเร็จ' },
  denied: { icon: 'lock', title: 'ยังเปิดหน้านี้ไม่ได้' },
  unavailable: { icon: 'fileText', title: 'ข้อมูลนี้ยังไม่พร้อมใช้งาน' },
  notFound: { icon: 'fileText', title: 'ไม่พบข้อมูลนี้' },
};

function stateRole(state) {
  if (state === 'error') return 'alert';
  if (state === 'loading') return 'status';
  return undefined;
}

function panelTone(state) {
  if (state === 'denied') return 'border-info-border bg-info-bg-alt';
  if (state === 'error') return 'border-danger-border bg-surface';
  return 'border-border bg-surface';
}

function iconTone(state) {
  if (state === 'error') return 'text-danger';
  if (['denied', 'unavailable', 'notFound'].includes(state)) return 'text-info';
  return 'text-text-faint';
}

export function StatePanel({
  state = 'empty',
  icon,
  title,
  description,
  action,
  secondaryAction,
  children,
  compact = false,
  className = '',
  titleAnnouncedElsewhere = false,
}) {
  const config = DEFAULTS[state] ?? DEFAULTS.empty;
  const resolvedTitle = title ?? config.title;
  const role = stateRole(state);
  const classes = compact
    ? cn(
      'grid min-h-0 grid-cols-[auto_minmax(0,1fr)_auto] items-center justify-stretch gap-3 rounded-md border px-[14px] py-3 text-left text-text-muted',
      panelTone(state),
      className,
    )
    : cn(
      'grid min-h-[220px] place-items-center content-center gap-[10px] rounded-md border p-6 text-center text-text-muted',
      panelTone(state),
      className,
    );

  return (
    <section
      className={classes}
      role={role}
      aria-live={state === 'loading' ? 'polite' : undefined}
      aria-busy={state === 'loading' ? 'true' : undefined}
    >
      <span className={cn('inline-flex', iconTone(state))} aria-hidden="true">
        <Icon name={icon ?? config.icon} size={compact ? 18 : 34} />
      </span>
      <div className={compact ? 'grid max-w-none gap-1' : 'grid max-w-[56ch] gap-1'}>
        <strong
          className={compact ? 'text-md font-bold text-text' : 'text-lg font-bold text-text'}
          aria-hidden={titleAnnouncedElsewhere ? 'true' : undefined}
        >
          {resolvedTitle}
        </strong>
        {description ? <span className="leading-normal">{description}</span> : null}
      </div>
      {children ? <div className="max-w-[64ch]">{children}</div> : null}
      {action || secondaryAction ? (
        <div className={compact ? 'mt-0 flex flex-wrap justify-end gap-[10px]' : 'mt-1 flex flex-wrap justify-center gap-[10px]'}>
          {action}
          {secondaryAction}
        </div>
      ) : null}
    </section>
  );
}
