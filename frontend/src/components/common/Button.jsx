import { forwardRef } from 'react';
import { cva } from 'class-variance-authority';
import { cn } from '../../utils/cn.js';

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
    'py-0',
  ],
  {
    variants: {
      variant: {
        primary: 'bg-primary text-surface px-4',
        secondary: 'bg-surface text-icon-muted border-border-input px-[13px]',
        success: 'bg-success text-surface px-[13px]',
        danger: 'bg-surface text-danger border-danger-border px-[13px]',
        text: 'border-0 bg-transparent text-primary p-0',
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
