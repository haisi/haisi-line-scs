// @ts-check
const eslint = require('@eslint/js');
const tseslint = require('typescript-eslint');
const angular = require('angular-eslint');
const unicorn = require('eslint-plugin-unicorn').default;
const prettier = require('eslint-config-prettier');

module.exports = tseslint.config(
  {
    ignores: ['dist/**', 'coverage/**', '.angular/**'],
  },
  {
    files: ['**/*.ts'],
    extends: [
      eslint.configs.recommended,
      ...tseslint.configs.strictTypeChecked,
      ...tseslint.configs.stylisticTypeChecked,
      ...angular.configs.tsRecommended,
      unicorn.configs.recommended,
      prettier,
    ],
    processor: angular.processInlineTemplates,
    languageOptions: {
      parserOptions: {
        projectService: true,
        tsconfigRootDir: __dirname,
      },
    },
    rules: {
      '@angular-eslint/directive-selector': [
        'error',
        {
          type: 'attribute',
          prefix: 'app',
          style: 'camelCase',
        },
      ],
      '@angular-eslint/component-selector': [
        'error',
        {
          type: 'element',
          prefix: 'app',
          style: 'kebab-case',
        },
      ],
      // Explicit even though strictTypeChecked already errors on this -- "any" defeats every other
      // rule here, so it's called out on its own rather than left implicit in an extended config.
      '@typescript-eslint/no-explicit-any': 'error',
      // A @Component/@Injectable-decorated class with no members of its own (e.g. a purely
      // template-driven presentational component) is completely normal Angular, not the
      // "namespace used as a static bag" anti-pattern this rule otherwise catches.
      '@typescript-eslint/no-extraneous-class': ['error', { allowWithDecorator: true }],
      // Angular/RxJS/the DOM use null idiomatically (FormControl values, @Input defaults, Router
      // navigation state); forbidding it fights the framework rather than catching real bugs.
      'unicorn/no-null': 'off',
      // Renames well-established, unambiguous short names (e.g. "props", "params", the "$" RxJS
      // convention) that read fine in this codebase and elsewhere in the Angular ecosystem.
      'unicorn/prevent-abbreviations': 'off',
      // This project's (default, Angular CLI-provided) browser support matrix predates top-level
      // await -- see the "Top-level await is not available in the configured target environment"
      // build error this rule's autofix produces in main.ts otherwise.
      'unicorn/prefer-top-level-await': 'off',
    },
  },
  {
    files: ['**/*.html'],
    extends: [...angular.configs.templateRecommended, ...angular.configs.templateAccessibility],
    rules: {},
  },
  {
    // Test doubles routinely stub a void-returning method with an intentional no-op (e.g. a fake
    // notifications service); that's a deliberate stand-in, not the accidentally-forgotten-body
    // this rule is meant to catch in real application code.
    files: ['**/*.spec.ts'],
    rules: {
      '@typescript-eslint/no-empty-function': ['error', { allow: ['arrowFunctions', 'methods'] }],
    },
  },
);
