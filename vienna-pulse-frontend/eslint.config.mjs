// @ts-check
import eslint from '@eslint/js';
import { defineConfig, globalIgnores } from 'eslint/config';
import tseslint from 'typescript-eslint';
import angular from 'angular-eslint';

export default defineConfig([
  // openapi-typescript writes it, and openapi:check compares it with exactly what it writes.
  globalIgnores(['src/app/timetable/openapi.ts']),
  {
    files: ['**/*.ts'],
    // Type information catches unsafe values and unhandled promises that syntax-only lint misses.
    languageOptions: {
      parserOptions: {
        projectService: true,
      },
    },
    extends: [
      eslint.configs.recommended,
      tseslint.configs.strictTypeChecked,
      tseslint.configs.stylisticTypeChecked,
      angular.configs.tsRecommended,
    ],
    processor: angular.processInlineTemplates,
    rules: {
      // A component can host a template without needing any class state.
      '@typescript-eslint/no-extraneous-class': ['error', { allowWithDecorator: true }],
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
    },
  },
  {
    // The API data stays free of the map, so other views such as a departure board can use it.
    files: ['src/app/timetable/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            { group: ['maplibre-gl', '@maplibre/*'], message: 'Keep MapLibre code in the map view.' },
            { group: ['**/map-view/**'], message: 'The map view uses the timetable, not the other way round.' },
          ],
        },
      ],
    },
  },
  {
    files: ['**/*.html'],
    extends: [angular.configs.templateRecommended, angular.configs.templateAccessibility],
    rules: {},
  },
]);
