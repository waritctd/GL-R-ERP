import js from '@eslint/js';
import react from 'eslint-plugin-react';
import reactHooks from 'eslint-plugin-react-hooks';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import globals from 'globals';

// Extracted so the exemption block at the bottom can drop ONE of these rules without
// silently losing the other. (`no-restricted-syntax: 'off'` would drop both, which is how
// a file ends up quietly exempt from a guard nobody meant to exempt it from.)
const RAW_FORM_RULE = {
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
};

// #exclusive-breakpoint-variant: Tailwind's arbitrary `max-[Npx]:` variant is EXCLUSIVE of
// its own boundary -- it compiles to `(width < Npx)`, which the production build ships as
// `not all and (width>=Npx)`. Both are FALSE at exactly Npx. This app reads the same bands
// INCLUSIVELY everywhere else: useIsMobile.js's MOBILE_QUERY is `(max-width: 720px)` and
// styles.css uses `@media (max-width: 720px)`. So a `max-[720px]:` utility disagreed with
// the app's own JS at exactly 720px, and `grid-cols-2 max-[720px]:grid-cols-1` fell through
// to the 2-column base there. Measured on the real bundle before the 2026-08-08 migration:
// AccountOverview's stat grid went 2 cols at 719px -> 3 at 720px, 3 at 1039px -> 5 at 1040px,
// and every Button lost its 44px mobile touch target at exactly 720px. This is invisible to
// jsdom (no layout engine, no media-query evaluation) and invisible to normal browser
// testing, which never lands on the exact boundary pixel -- so a lint rule is the only thing
// that reliably stops it coming back. Use the inclusive named variants declared in
// src/index.css: nav-drawer: (<=1040) attendance-scroll: (<=900) tablet: (721-1040)
// mobile: (<=720) mobile-sm: (<=560) mobile-xs: (<=520).
const INCLUSIVE_BREAKPOINT_MESSAGE =
  "Tailwind's `max-[Npx]:` is EXCLUSIVE of its own boundary -- it compiles to `(width < Npx)` " +
  'and is false at exactly Npx, while this app reads the same bands inclusively in JS ' +
  "(useIsMobile.js's `(max-width: 720px)`) and in styles.css. Mixing the two makes the layout " +
  'disagree with the JS at exactly the boundary pixel. Use the inclusive named variants from ' +
  'src/index.css instead: max-[1040px]: -> nav-drawer:, max-[900px]: -> attendance-scroll:, ' +
  'max-[720px]: -> mobile:, max-[560px]: -> mobile-sm:, max-[520px]: -> mobile-xs:. ' +
  'A band with no variant yet should get one declared in index.css, in widest-first order.';

const INCLUSIVE_BREAKPOINT_RULES = [
  { selector: 'Literal[value=/max-\\[\\d+px\\]:/]', message: INCLUSIVE_BREAKPOINT_MESSAGE },
  { selector: 'TemplateElement[value.raw=/max-\\[\\d+px\\]:/]', message: INCLUSIVE_BREAKPOINT_MESSAGE },
];

export default [
  { ignores: ['dist/**', 'node_modules/**'] },
  js.configs.recommended,
  {
    files: ['src/**/*.{js,jsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
      // Node globals alongside the browser ones: a handful of *.test.js files run under vitest's
      // node environment and legitimately reach the filesystem — stageCatalog.test.js reads
      // DealStage.java out of the backend source tree to prove the frontend's stage catalog and
      // Thai labels still match the Java (see its own header). Without `process` here that guard
      // cannot be written at all.
      globals: { ...globals.browser, ...globals.node },
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
      // #safe-form-primitive (RAW_FORM_RULE, declared above): a raw <form> is how implicit submission -- Enter in a lone
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
      'no-restricted-syntax': ['error', RAW_FORM_RULE, ...INCLUSIVE_BREAKPOINT_RULES],
    },
  },
  {
    // The one exemption: SafeForm.jsx's own <form> IS the primitive the rule above tells every
    // other file to use instead. It drops RAW_FORM_RULE only -- turning the whole rule off here
    // would quietly exempt this file from the breakpoint guard too, for no reason.
    files: ['src/components/common/SafeForm.jsx'],
    rules: {
      'no-restricted-syntax': ['error', ...INCLUSIVE_BREAKPOINT_RULES],
    },
  },
];
