import js from '@eslint/js';
import importPlugin from 'eslint-plugin-import';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';

const featureDirs = [
  'auth', 'plan', 'stays', 'reservations', 'movements', 'business-day',
  'guest-list', 'admin', 'audit', 'offline', 'updates',
];

// Každá funkcia nesmie importovať žiadnu inú funkciu (DLD kap. 1).
const featureZones = featureDirs.map((dir) => ({
  target: `./src/features/${dir}`,
  from: './src/features',
  except: [`./${dir}`],
  message: 'Funkcia (features/*) nesmie importovať inú funkciu; spoločný kód patrí do shared/.',
}));

export default tseslint.config(
  { ignores: ['dist', 'node_modules', 'src/shared/api/schema.d.ts'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: { globals: { ...globals.browser } },
    plugins: { 'react-hooks': reactHooks, import: importPlugin },
    settings: {
      'import/resolver': { typescript: { project: './tsconfig.app.json' } },
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      '@typescript-eslint/no-explicit-any': 'error',
      'import/no-restricted-paths': ['error', { zones: featureZones }],
    },
  },
  {
    files: ['*.config.{js,ts}'],
    languageOptions: { globals: { ...globals.node } },
  },
);
