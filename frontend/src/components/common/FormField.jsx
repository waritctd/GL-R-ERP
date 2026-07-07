/**
 * Inline-validation field wrapper: label + input + optional hint/error.
 *
 * Accessibility pattern: when `error` is set, this wrapper renders the error
 * text with `id={`${id}-error`}` and `role="alert"`. Callers must attach
 * `aria-invalid={Boolean(error)}` and `aria-describedby={error ? `${id}-error` : undefined}`
 * to the input they pass as `children` (FormField does not clone/inject props
 * into `children`, since inputs here are plain elements with their own
 * value/onChange wiring). Use the exported `fieldErrorId(id)` helper to avoid
 * repeating the `${id}-error` string.
 *
 * Example:
 *   <FormField label="อีเมล" htmlFor="email" error={errors.email} required>
 *     <input
 *       id="email"
 *       className={errors.email ? 'is-invalid' : ''}
 *       aria-invalid={Boolean(errors.email)}
 *       aria-describedby={errors.email ? fieldErrorId('email') : undefined}
 *       value={email}
 *       onChange={(e) => setEmail(e.target.value)}
 *     />
 *   </FormField>
 */
export function fieldErrorId(id) {
  return `${id}-error`;
}

export function FormField({ label, htmlFor, id, error, hint, required = false, children }) {
  const fieldId = htmlFor ?? id;
  const errorId = fieldId ? fieldErrorId(fieldId) : undefined;
  const hintId = fieldId ? `${fieldId}-hint` : undefined;

  return (
    <div className="form-field">
      {label ? (
        <label htmlFor={fieldId}>
          {label}
          {required ? <span className="field-required" aria-hidden="true"> *</span> : null}
        </label>
      ) : null}
      {children}
      {hint ? (
        <p className="field-hint" id={hintId}>
          {hint}
        </p>
      ) : null}
      {error ? (
        <p className="field-error-text" id={errorId} role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}
