import React, { forwardRef } from 'react';
import { cva } from 'class-variance-authority';
import { cn } from '../../utils/cn.js';

// Touch targets: DESIGN.md documents 38-44px as the button height range, and
// Fitts's-law/touch ergonomics only matter where input is actually touch — so
// the base/default size is 38px (desktop mouse) and grows to 44px only at
// <=720px (the app's mobile breakpoint, see useIsMobile.js). Desktop stays
// visually unchanged from before. The `text` variant (borderless inline link,
// padding:0 per DESIGN.md) is intentionally excluded: forcing it to 44px would
// pad prose links into ugly tap boxes that break inline text flow.
const buttonVariants = cva(
  [
    'inline-flex',
    'items-center',
    'justify-center',
    'gap-[7px]',
    'rounded-md',
    'border-[1.5px]',
    'border-solid',
    'border-transparent',
    '!font-bold',
    'min-h-[38px]',
    'max-[720px]:min-h-[44px]',
    'py-0',
  ],
  {
    variants: {
      variant: {
        primary: 'bg-primary text-surface px-4',
        secondary: 'bg-surface text-icon-muted border-border-input px-[13px]',
        success: 'bg-success text-surface px-[13px]',
        danger: 'bg-surface text-danger border-danger-border px-[13px]',
        // Opts out of the 44px mobile floor only: forcing an inline prose link
        // into a tap box breaks text flow. Keeps the 38px desktop floor it
        // already had, so desktop is unchanged.
        text: 'border-0 bg-transparent text-primary p-0 max-[720px]:min-h-0',
        // Stays 44x44 at every width: DESIGN.md specs the icon button as a
        // 44x44 square, and it already cleared the touch floor — shrinking it
        // to 38px on desktop would regress the spec and the topbar.
        icon: 'w-11 min-h-[44px] p-0 bg-surface text-icon-muted border-border-input',
      },
      size: {
        md: '',
        sm: 'min-h-[32px] px-3 text-sm',
      },
    },
    compoundVariants: [
      {
        variant: 'icon',
        size: 'sm',
        className: 'w-9 min-h-[36px] p-0',
      },
      {
        variant: 'text',
        size: 'sm',
        className: 'p-0',
      },
    ],
    defaultVariants: {
      variant: 'primary',
      size: 'md',
    },
  },
);

export const Button = forwardRef(function Button({
  variant = 'primary',
  size = 'md',
  type = 'button',
  className,
  children,
  ...props
}, ref) {
  return (
    <button
      ref={ref}
      type={type}
      className={cn(buttonVariants({ variant, size }), className)}
      {...props}
    >
      {children}
    </button>
  );
});
