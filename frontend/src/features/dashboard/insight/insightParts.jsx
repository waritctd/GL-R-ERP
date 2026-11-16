import { Panel } from '../../../components/common/Layout.jsx';
import { cn } from '../../../utils/cn.js';
import { barWidth } from './insightFormat.js';

/** The shared Panel (flush: header rule, body padded by the caller) + a footnote slot Panel does not have. */
export function InsightPanel({ title, side, children, foot, className, ...rest }) {
  return (
    <Panel flush title={title} actions={side ? <span className="text-xs text-text-muted">{side}</span> : null} className={cn('insight-rise', className)} {...rest}>
      {children}
      {foot ? <div className="border-t border-border-subtle px-5 py-2.5 text-xs text-text-muted mobile:px-3.5">{foot}</div> : null}
    </Panel>
  );
}

export const Pad = ({ children }) => <div className="px-5 py-4 mobile:px-3.5">{children}</div>;

/** The ONE progress bar: `part` of `whole`, grows in once (inert under reduced motion). A 0 / undefined ratio is an empty bar. */
export function ProgressBar({ part, whole, tone = 'bg-primary', label }) {
  return (
    <div className="h-2 rounded-pill bg-surface-subtle" role="img" aria-label={label}>
      <i className={cn('insight-grow block h-full rounded-pill', tone)} style={{ width: `${barWidth(part, whole)}%` }} />
    </div>
  );
}

/** Row of dashboard Figures as a <dl> band. */
export const FigureBand = ({ children }) => (
  <dl className="m-0 grid grid-cols-4 gap-x-6 gap-y-4 px-5 py-4 nav-drawer:grid-cols-2 mobile:grid-cols-1 mobile:px-3.5">{children}</dl>
);

/** Rows of [name, value, caption] as labelled bars against `max`. */
export function Bars({ rows, max }) {
  const top = max || Math.max(0, ...rows.map((r) => r[1]));
  return (
    <div className="grid gap-3">
      {rows.map(([name, value, caption], i) => (
        <div key={`${i}-${name}`} className="grid gap-1">
          <div className="flex justify-between gap-3 text-sm"><b>{name}</b><span className="tabular-nums text-text-muted">{caption}</span></div>
          <ProgressBar part={value} whole={top} label={`${name}: ${caption}`} />
        </div>
      ))}
    </div>
  );
}

/** Two bars per bucket (share of count vs share of value). */
export function Pairs({ rows, a, b }) {
  return (
    <div className="grid gap-3">
      {rows.map((x) => (
        <div key={x.b} className="grid gap-1 text-sm">
          <b>{x.b}</b>
          <div className="flex items-center gap-2"><div className="flex-1"><ProgressBar part={x.cnt} whole={100} tone="bg-primary/40" label={`${x.b}: ${a} ${x.cnt}%`} /></div><em className="w-10 text-right text-xs not-italic tabular-nums">{x.cnt}%</em></div>
          <div className="flex items-center gap-2"><div className="flex-1"><ProgressBar part={x.val} whole={100} label={`${x.b}: ${b} ${x.val}%`} /></div><em className="w-10 text-right text-xs not-italic tabular-nums">{x.val}%</em></div>
        </div>
      ))}
      <div className="flex gap-4 text-xs text-text-muted"><span>▮ {a}</span><span className="text-primary">▮ {b}</span></div>
    </div>
  );
}

/** Semantic table in the dashboards' style; `cols` = [label, rightAligned?]. */
export function InsightTable({ cols, children, testId }) {
  return (
    <div className="overflow-x-auto">
      <table data-testid={testId} className="w-full border-collapse text-sm">
        <thead>
          <tr>{cols.map(([label, right]) => <th key={label} className={cn('border-b border-border px-5 py-2.5 text-xs font-bold text-text-muted mobile:px-3.5', right ? 'text-right' : 'text-left')}>{label}</th>)}</tr>
        </thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  );
}
export const Td = ({ right, className, children, ...p }) => (
  <td className={cn('border-b border-border-subtle px-5 py-2.5 mobile:px-3.5', right && 'text-right tabular-nums', className)} {...p}>{children}</td>
);
export const Small = ({ children }) => <span className="ml-1.5 text-xs text-text-muted">{children}</span>;
