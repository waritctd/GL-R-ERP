// Client-side sums over the GET /api/people-insights response. Time-exempt people are left out of every attendance figure
// here as well as on the server (which already sends them no attendance rows), so a stray row can never skew a late rate.
export const MONTH_LABELS = ['ม.ค.', 'ก.พ.', 'มี.ค.', 'เม.ย.', 'พ.ค.', 'มิ.ย.', 'ก.ค.', 'ส.ค.', 'ก.ย.', 'ต.ค.', 'พ.ย.', 'ธ.ค.'];

export const monthKey = (year, index) => `${year}-${String(index + 1).padStart(2, '0')}`;

const sum = (rows, field) => rows.reduce((total, row) => total + (row[field] || 0), 0);

/** Leave and attendance sums for `ids` (null = everyone in the response) in one month key, or the whole year when `key` is null. */
export function summarise(data, ids, key) {
  const wanted = ids ? new Set(ids) : null;
  const exempt = new Set(data.people.filter((p) => p.timeExempt).map((p) => p.employeeId));
  const pick = (row) => (!wanted || wanted.has(row.employeeId)) && (!key || row.month === key);
  const leave = data.leave.filter(pick);
  const att = data.attendance.filter((row) => pick(row) && !exempt.has(row.employeeId));
  const byType = {};
  leave.forEach((row) => { byType[row.leaveTypeCode] = (byType[row.leaveTypeCode] || 0) + row.days; });
  // Days charged to the quota year, which is not the start-date year `leave` is bucketed by; a year figure, so `key` does not apply.
  const quotaUsed = {};
  (data.quotaUsage || []).filter((row) => !wanted || wanted.has(row.employeeId))
    .forEach((row) => { quotaUsed[row.leaveTypeCode] = (quotaUsed[row.leaveTypeCode] || 0) + row.days; });
  return {
    leaveDays: sum(leave, 'days'),
    byType,
    quotaUsed,
    sickEdge: sum(leave.filter((row) => row.leaveTypeCode === 'SICK'), 'sickEdgeDays'),
    lateFiled: sum(leave, 'lateFiled'),
    unpaid: sum(leave, 'unpaidDays'),
    scanDays: sum(att, 'scanDays'),
    late: sum(att, 'lateDays'),
    lateMin: sum(att, 'lateMinutes'),
    noOut: sum(att, 'noCheckOut'),
    early: sum(att, 'earlyLeave'),
  };
}

/** Index (0-11) of the newest month that has any row; else the as-of month; else January. */
export function latestMonthIndex(data) {
  const months = [...data.leave, ...data.attendance].map((row) => Number(row.month.slice(5, 7)));
  if (months.length) return Math.max(...months) - 1;
  return data.asOf ? Number(data.asOf.slice(5, 7)) - 1 : 0;
}

/** Leave days for each of the first `count` months. */
export const monthTotals = (data, ids, count) => (
  Array.from({ length: count }, (_, i) => summarise(data, ids, monthKey(data.year, i)).leaveDays)
);

/** One row per person: their sums plus the check-in average weighted by morning scan days (null for a time-exempt person). */
export function personRows(data, ids, key) {
  const wanted = ids ? new Set(ids) : null;
  return data.people.filter((p) => !wanted || wanted.has(p.employeeId)).map((p) => {
    const rows = p.timeExempt ? [] : data.attendance.filter((r) => r.employeeId === p.employeeId
      && (!key || r.month === key) && r.avgCheckInMinutes != null);
    // The server averages only check-ins before 12:00, so each month weighs in by how many it averaged.
    const scans = sum(rows, 'morningScanDays');
    const avgIn = scans ? rows.reduce((total, r) => total + r.avgCheckInMinutes * r.morningScanDays, 0) / scans : null;
    return { employeeId: p.employeeId, code: p.employeeCode, nick: p.nickname, timeExempt: p.timeExempt, ...summarise(data, [p.employeeId], key), avgIn };
  });
}

/** Each team of the response with its yearly sums. */
export const teamRows = (data) => data.teams.map((t) => ({
  id: t.id, label: t.label, heads: t.headNicknames, n: t.memberIds.length, ...summarise(data, t.memberIds, null),
}));
