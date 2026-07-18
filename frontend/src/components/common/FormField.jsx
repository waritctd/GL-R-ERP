import { Children, cloneElement, isValidElement } from 'react';

/**
 * Inline-validation field wrapper: label + input + optional hint/error.
 *
 * Accessibility pattern: when `error` is set, this wrapper renders the error
 * text with `id={`${id}-error`}` and `role="alert"`, and when `hint` is set it
 * renders `id={`${id}-hint`}`. FormField automatically wires `aria-invalid`,
 * `aria-describedby` (pointing at the hint id and/or error id, as
 * applicable), and — when `required` is set — `aria-required="true"` onto
 * the form control among `children` whose own `id` prop equals the field's
 * `htmlFor`/`id`. That id match is what makes this safe: it is, by
 * construction, the exact element the `<label htmlFor>` points at (a custom
 * component must already forward that id to its real control for label
 * association to work at all), so sibling helper markup — a hint `<small>`,
 * a `<datalist>`, a read-only display input with a different id — is never
 * touched.
 *
 * Manual values still win: if the child already sets `aria-invalid` or
 * `aria-required`, FormField leaves it as-is. If the child already sets
 * `aria-describedby`, FormField appends the hint/error id(s) to it rather
 * than replacing it (a field may legitimately point at more than one
 * description). Existing callers that already wire this by hand (e.g.
 * `EmployeeFormModal.jsx`, `ChangeRequestModal.jsx`, `ChangePasswordModal.jsx`)
 * continue to work unchanged — manual wiring is no longer required, only
 * still supported. Use the exported `fieldErrorId(id)` helper to avoid
 * repeating the `${id}-error` string.
 *
 * Example (manual aria wiring is optional now, shown here omitted):
 *   <FormField label="อีเมล" htmlFor="email" error={errors.email} required>
 *     <input id="email" value={email} onChange={(e) => setEmail(e.target.value)} />
 *   </FormField>
 */
export function fieldErrorId(id) {
  return `${id}-error`;
}

function fieldHintId(id) {
  return `${id}-hint`;
}

function mergeDescribedBy(existing, autoIds) {
  const tokens = typeof existing === 'string' ? existing.split(' ').filter(Boolean) : [];
  autoIds.forEach((token) => {
    if (token && !tokens.includes(token)) tokens.push(token);
  });
  return tokens.length ? tokens.join(' ') : undefined;
}

function augmentControl(child, { fieldId, errorId, hintId, hasError, hasHint, required }) {
  if (!fieldId || !isValidElement(child) || child.props?.id !== fieldId) return child;

  try {
    const nextProps = {};

    if (hasError && child.props['aria-invalid'] === undefined) {
      nextProps['aria-invalid'] = true;
    }

    const autoDescribedByIds = [];
    if (hasHint) autoDescribedByIds.push(hintId);
    if (hasError) autoDescribedByIds.push(errorId);
    if (autoDescribedByIds.length) {
      const merged = mergeDescribedBy(child.props['aria-describedby'], autoDescribedByIds);
      if (merged !== child.props['aria-describedby']) {
        nextProps['aria-describedby'] = merged;
      }
    }

    if (required && child.props['aria-required'] === undefined) {
      nextProps['aria-required'] = true;
    }

    if (Object.keys(nextProps).length === 0) return child;
    return cloneElement(child, nextProps);
  } catch {
    // If the child can't be safely cloned (e.g. an element type that rejects
    // extra props), leave it untouched rather than breaking the form.
    return child;
  }
}

export function FormField({ label, htmlFor, id, error, hint, required = false, children }) {
  const fieldId = htmlFor ?? id;
  const errorId = fieldId ? fieldErrorId(fieldId) : undefined;
  const hintId = fieldId ? fieldHintId(fieldId) : undefined;
  const hasError = Boolean(error);
  const hasHint = Boolean(hint);

  const augmentedChildren = Children.map(children, (child) => augmentControl(child, {
    fieldId,
    errorId,
    hintId,
    hasError,
    hasHint,
    required,
  }));

  return (
    <div className="form-field">
      {label ? (
        <label htmlFor={fieldId}>
          {label}
          {required ? <span className="field-required" aria-hidden="true"> *</span> : null}
        </label>
      ) : null}
      {augmentedChildren}
      {hint ? (
        <p className="m-0 text-text-muted text-xs font-medium" id={hintId}>
          {hint}
        </p>
      ) : null}
      {error ? (
        <p className="m-0 text-danger text-xs font-bold" id={errorId} role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}
