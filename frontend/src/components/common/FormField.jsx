import { Children, cloneElement, isValidElement } from 'react';

/** Inline-validation field wrapper: label + input + optional hint/error. Existing callers that already wire
 * this by hand (e.g. `EmployeeFormModal.jsx`, `ChangeRequestModal.jsx`, `ChangePasswordModal.jsx`) continue to
 * work unchanged — manual wiring is no longer required, only still supported. */
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
    // If the child can't be safely cloned (e.g. an element type that rejects extra props), leave it untouched
    // rather than breaking the form.
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
    /* `content-start` is what stops the control stretching. Two fields side by side therefore rendered at
     * different heights and different vertical offsets, which was the app-wide field stagger. Packing to the start
     * keeps the control at its natural height and leaves the slack below it. */
    <div className="grid content-start gap-[7px]">
      {label ? (
        <label htmlFor={fieldId}>
          {/* The global `label { display: grid }` rule (styles.css) is relied on elsewhere to stack a label's own text
           * above its control when both are children of the same <label> (see e.g. AttendancePage, CommissionPage). */}
          <span className="form-field__label-text">
            {label}
            {required ? <span className="field-required text-danger ml-0.5" aria-hidden="true"> *</span> : null}
          </span>
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
