/** Distinct {value,label} options from employee rows, sorted by Thai label. */
export function uniqueOptions(rows, valueKey, labelKey) {
  const options = new Map();
  rows.forEach((row) => {
    const value = row[valueKey];
    const label = row[labelKey] || value;
    if (value && label && !options.has(value)) {
      options.set(value, { value, label });
    }
  });
  return [...options.values()].sort((a, b) => a.label.localeCompare(b.label, 'th'));
}
