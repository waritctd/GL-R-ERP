import { Button } from '../../components/common/Button.jsx';
import { expandedRowRegionId } from '../../components/common/DataTable.jsx';
import { Icon } from '../../components/common/Icon.jsx';

export const EMPLOYEE_COLUMN = {
  key: 'employee',
  header: 'พนักงาน',
  sortable: true,
  sortAccessor: (row) => row.employeeCode ?? '',
  searchAccessor: (row) => `${row.employeeCode ?? ''} ${row.employeeName ?? ''}`,
  render: (row) => (
    <span className="grid">
      <strong className="truncate" title={row.employeeName}>{row.employeeName}</strong>
      <code className="text-text-muted text-2xs">{row.employeeCode}</code>
    </span>
  ),
};

/** Chevron toggle for a row's expanded detail; `labelFor(row)` completes the "ซ่อน/ดู…" aria-label. */
export function expandColumn({ expandedId, setExpandedId, hideTitle, showTitle, labelFor }) {
  return {
    key: 'expand',
    header: '',
    render: (row) => {
      const expanded = expandedId === row.id;
      return (
        <Button
          type="button"
          variant="icon"
          aria-expanded={expanded}
          aria-controls={expandedRowRegionId(row.id)}
          title={expanded ? hideTitle : showTitle}
          aria-label={`${expanded ? 'ซ่อน' : 'ดู'}${labelFor(row)}`}
          onClick={() => setExpandedId((current) => (current === row.id ? null : row.id))}
        >
          <Icon name={expanded ? 'chevronUp' : 'chevronDown'} size={14} />
        </Button>
      );
    },
  };
}
