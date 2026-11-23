// SPDX-License-Identifier: Apache-2.0

// Lint for the TypeScript client and tm1-testcontainers: the counterpart of the Java build's Error
// Prone. Layout is Prettier's (.prettierrc.json), so no rule here is about layout. `npm test` runs
// both checks first, as `./gradlew build` runs checkFormat.
import js from "@eslint/js";
import jsdoc from "eslint-plugin-jsdoc";
import tseslint from "typescript-eslint";

// Every source file starts with its license, as scripts/license-headers.py writes it for every
// language: `npm test` fails on a file without the line, and `eslint --fix .` adds it.
const LICENSE = "// SPDX-License-Identifier: Apache-2.0";
const license = {
  rules: {
    header: {
      meta: { type: "problem", fixable: "code", schema: [], messages: { missing: `The file doesn't start with "${LICENSE}"` } },
      create(context) {
        return {
          Program() {
            if (context.sourceCode.text.startsWith(LICENSE + "\n")) return;
            context.report({ loc: { line: 1, column: 0 }, messageId: "missing", fix: (fixer) => fixer.insertTextBeforeRange([0, 0], LICENSE + "\n\n") });
          },
        };
      },
    },
  },
};

export default tseslint.config(
  { ignores: ["**/dist/", "tm1-client/src/generated/", "**/node_modules/"] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  { plugins: { license }, rules: { "license/header": "error" } },
  {
    rules: {
      // A type and a namespace of the same name stand for a Java type with nested types
      // (Tm1Source.InstallDir), so the ports read like the Java API
      "@typescript-eslint/no-namespace": "off",
      // A generator inside a method can't see the method's "this"
      "@typescript-eslint/no-this-alias": ["error", { allowedNames: ["self"] }],
      // An interface that only extends another names the same shape for a different role
      "@typescript-eslint/no-empty-object-type": ["error", { allowInterfaces: "with-single-extends" }],
      // "_" names a value that has to be bound but isn't used
      "@typescript-eslint/no-unused-vars": ["error", { argsIgnorePattern: "^_", varsIgnorePattern: "^_", caughtErrorsIgnorePattern: "^_" }],
    },
  },
  {
    // The public API is documented, as the Java build fails on a public class, method or
    // constructor without Javadoc. Presence only: how a comment is written is the writer's.
    files: ["tm1-client/src/**", "tm1-testcontainers/src/**"],
    plugins: { jsdoc },
    rules: {
      "jsdoc/require-jsdoc": [
        "error",
        {
          publicOnly: true,
          enableFixer: false,
          checkConstructors: true,
          checkGetters: true,
          checkSetters: true,
          require: { ClassDeclaration: true, FunctionDeclaration: true, MethodDefinition: false },
          contexts: [
            // Every member of an exported class but the private ones. An overloaded method is
            // documented on its signatures, which is what a caller sees, not on the implementation
            // that follows them
            'MethodDefinition:not([accessibility="private"]):not([key.type="PrivateIdentifier"]):not(MethodDefinition[value.type="TSEmptyBodyFunctionExpression"] + MethodDefinition) > FunctionExpression',
            'MethodDefinition:not([accessibility="private"]) > TSEmptyBodyFunctionExpression',
            'PropertyDefinition:not([accessibility="private"]):not([key.type="PrivateIdentifier"])',
            'TSAbstractMethodDefinition:not([accessibility="private"]) > TSEmptyBodyFunctionExpression',
            "TSInterfaceDeclaration",
            "TSTypeAliasDeclaration",
            "TSEnumDeclaration",
            "TSModuleDeclaration",
            "TSMethodSignature",
            "TSPropertySignature",
            "ExportNamedDeclaration > VariableDeclaration",
          ],
        },
      ],
    },
  },
  {
    // Tests read TM1's JSON and fakes' requests as they come
    files: ["tm1-client/test/**", "tm1-testcontainers/test/**"],
    rules: { "@typescript-eslint/no-explicit-any": "off" },
  },
);
