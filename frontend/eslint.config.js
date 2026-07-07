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
    },
  },
];
