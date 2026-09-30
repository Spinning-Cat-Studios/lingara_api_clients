// The size and complexity budgets for this package.
//
// The repository's code-health scanner does not read TypeScript, so the
// budgets are carried by the linter: 300 lines per file (a warning) and 600
// (an error), 50 lines per function, complexity 10 and 5 parameters.
// BUDGETS is exported so a test can pin the numbers.
import js from "@eslint/js";
import { builtinRules } from "eslint/use-at-your-own-risk";
import tseslint from "typescript-eslint";

export const BUDGETS = {
  warnLines: 300,
  maxLines: 600,
  maxLinesPerFunction: 50,
  complexity: 10,
  maxParams: 5,
};

const lines = { skipBlankLines: false, skipComments: false };

// One rule id carries one severity, so the 300-line warning is the core
// `max-lines` rule registered a second time under its own name.
const budget = { rules: { "warn-lines": builtinRules.get("max-lines") } };

export default tseslint.config(
  { ignores: ["dist", "conformance/dist", "node_modules", "src/generated"] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ["**/*.{ts,mjs}"],
    languageOptions: {
      ecmaVersion: 2022,
      globals: { process: "readonly", console: "readonly" },
    },
    plugins: { budget },
    rules: {
      "max-lines": ["error", { max: BUDGETS.maxLines, ...lines }],
      "budget/warn-lines": ["warn", { max: BUDGETS.warnLines, ...lines }],
      "max-lines-per-function": ["error", { max: BUDGETS.maxLinesPerFunction, ...lines }],
      complexity: ["error", BUDGETS.complexity],
      "max-params": ["error", BUDGETS.maxParams],
    },
  },
  {
    // A `describe` block is a function to ESLint but a folder to a reader,
    // and a test file's line budget is the 600-line one alone.
    files: ["src/__tests__/**/*.ts"],
    rules: { "max-lines-per-function": "off", "budget/warn-lines": "off" },
  },
);
