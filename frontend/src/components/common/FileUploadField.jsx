import { forwardRef, useId, useState } from 'react';
import { Icon } from './Icon.jsx';
import { cn } from '../../utils/cn.js';

/**
 * FileUploadField — a real `<input type="file">` visually hidden behind a
 * styled label/button, so file selection still works exactly like a native
 * file input (drag/drop, keyboard, screen readers, mobile file pickers) but
 * never renders the raw "Choose File" control.
 *
 * This component is presentation-only: it does not alter what gets
 * submitted. It forwards every native file-input prop (`accept`, `multiple`,
 * `required`, `name`, `disabled`, ...) straight onto the real input, forwards
 * `ref` to that same input (so callers that reset it via
 * `fileRef.current.value = ''` keep working), and calls the caller's
 * `onChange` with the untouched native change event — the same
 * `event.target.files` shape as before. It only adds local state to display
 * the selected filename(s); that state never reaches `onChange`.
 *
 * Usage:
 *   <FileUploadField
 *     id="leave-attachment-file"
 *     accept="application/pdf,image/jpeg,image/png,.pdf,.jpg,.jpeg,.png"
 *     onChange={(event) => setAttachmentFile(event.target.files?.[0] || null)}
 *     helperText="PDF, JPG หรือ PNG"
 *   />
 */
export const FileUploadField = forwardRef(function FileUploadField(
  {
    id,
    className,
    onChange,
    helperText,
    buttonLabel = 'เลือกไฟล์',
    emptyLabel = 'ยังไม่ได้เลือกไฟล์',
    disabled = false,
    ...inputProps
  },
  ref,
) {
  const autoId = useId();
  const inputId = id || autoId;
  const [fileNames, setFileNames] = useState([]);

  function handleChange(event) {
    const files = event.target.files;
    setFileNames(files && files.length ? Array.from(files).map((file) => file.name) : []);
    onChange?.(event);
  }

  const hasFiles = fileNames.length > 0;

  return (
    <div className={cn('w-full', className)}>
      <input
        {...inputProps}
        ref={ref}
        id={inputId}
        type="file"
        disabled={disabled}
        onChange={handleChange}
        // styles.css now loads into @layer legacy, ordered before Tailwind's
        // utilities layer (see src/index.css), so these utilities win over the
        // legacy global `input { width/height/padding/border }` rules without
        // needing `!` overrides. Keep sr-only itself so the input stays
        // keyboard-focusable.
        className="sr-only h-px min-h-0 w-px border-0 p-0"
      />
      <label
        htmlFor={inputId}
        className={cn(
          // styles.css loads into @layer legacy (before Tailwind's utilities
          // layer, see src/index.css), so these utilities correctly win over
          // the legacy global `label { display: grid; gap: 7px }` rule without
          // needing `!` overrides.
          'flex min-h-11 w-full items-center gap-3 rounded-md border-[1.5px] border-dashed border-border-input bg-surface px-3 py-2.5 cursor-pointer transition-colors',
          'hover:border-primary-hover focus-within:border-primary-hover',
          disabled && 'pointer-events-none opacity-55',
        )}
      >
        <span className="inline-flex min-h-9 shrink-0 items-center gap-1.5 rounded-md border-[1.5px] border-border-input bg-surface-muted px-3 text-sm font-bold text-text">
          <Icon name="upload" size={14} />
          {buttonLabel}
        </span>
        <span
          className={cn(
            // Muted, not faint: this is read as text, and faint (#94a3b8) is
            // ~2.8:1 on white. Muted (#64748b) clears 4.5:1.
            'min-w-0 flex-1 truncate text-sm',
            hasFiles ? 'font-semibold text-text' : 'text-text-muted',
          )}
        >
          {hasFiles ? fileNames.join(', ') : emptyLabel}
        </span>
      </label>
      {helperText ? <p className="mt-1.5 text-xs text-text-muted">{helperText}</p> : null}
    </div>
  );
});
