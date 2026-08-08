import js from '@eslint/js';
import react from 'eslint-plugin-react';
import reactHooks from 'eslint-plugin-react-hooks';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import globals from 'globals';

export default [
  { ignores: ['dist/**', 'node_modules/**'] },
  js.configs.recommended,
  {
    files: ['src/**/*.{js,jsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
      globals: { ...globals.browser },
      parserOptions: { ecmaFeatures: { jsx: true } },
    },
    settings: { react: { version: 'detect' } },
    plugins: {
      react,
      'react-hooks': reactHooks,
      'jsx-a11y': jsxA11y,
    },
    rules: {
      ...react.configs.flat.recommended.rules,
      ...reactHooks.configs.recommended.rules,
      ...jsxA11y.flatConfigs.recommended.rules,
      // New JSX transform: React import not required, prop-types not used in this codebase.
      'react/react-in-jsx-scope': 'off',
      'react/prop-types': 'off',
      // Allow the "strip a field via rest" idiom, e.g. const { password, ...safe } = user.
      'no-unused-vars': ['error', { ignoreRestSiblings: true }],
      // react-hooks v7 added Compiler-oriented rules; this one flags the standard
      // fetch-on-mount pattern (calling a setState-bearing function from useEffect)
      // used throughout this codebase, which is not a bug here.
      'react-hooks/set-state-in-effect': 'off',
      // #safe-form-primitive: a raw <form> is how HTML implicit submission -- Enter in a lone
      // text field, when the form has no submit button anywhere in the DOM, submits the form
      // directly with no button ever clicked -- shipped to production TWICE: a real tax
      // declaration (#tax-allowance-ia-hub-review) and a real CRM deal
      // (fix/form-enter-submits-real-records). Both were silent and both were invisible to
      // jsdom, which does not implement the browser's implicit-submission algorithm at all --
      // only e2e/implicit-submission.spec.js can catch this class of bug, and it only runs once
      // per PR, not on every edit. `<FormGrid as="form">` used to be a second, unguarded way to
      // put a `<form>` on the page that this selector could never see (it matches the JSX tag
      // name literally, not what a component renders at runtime) -- FormGrid dropped that `as`
      // prop for exactly this reason (see Layout.jsx), so this rule now catches every literal
      // `<form>` JSX element in `src/` -- which is the shape both historical incidents actually
      // were, and the shape a developer reaches for by default. It is not unconditional: an
      // `eslint-disable` comment, `React.createElement('form', ...)`, or a variable used as a
      // dynamic JSX tag name (`const Tag = 'form'; <Tag ... />`) all still compile, all still ship
      // a real `<form>`, and none of them trip this selector. Each is visible in a diff and each
      // is a deliberate step out of the normal path, unlike the two incidents this rule exists
      // to prevent a repeat of -- but "every `<form>`" would overstate what a lint rule can
      // actually promise. SafeForm.jsx itself is exempted below; it is the one place a raw
      // `<form>` is correct, because it IS the primitive.
      'no-restricted-syntax': [
        'error',
        {
          selector: 'JSXOpeningElement[name.name="form"]',
          message:
            'Raw <form> re-opens HTML implicit submission: Enter in a lone text field, with no ' +
            'submit button anywhere in the DOM, submits the form directly -- no button ever ' +
            'clicked, no error, nothing in a vitest suite to catch it (jsdom does not implement ' +
            'this at all). This has shipped to production twice as a real tax declaration and a ' +
            'real CRM deal filed by a stray Enter keypress. Use <SafeForm> from ' +
            'components/common/SafeForm.jsx instead -- it forwards every prop a <form> takes ' +
            '(id, noValidate, className, children, ref) and blocks a submitter-less submit by ' +
            'default.',
        },
      ],
    },
  },
  {
    // The one exemption: SafeForm.jsx's own <form> IS the primitive the rule above tells every
    // other file to use instead.
    files: ['src/components/common/SafeForm.jsx'],
    rules: {
      'no-restricted-syntax': 'off',
    },
  },
];
