// Lint for the TypeScript client and tm1-testcontainers: the counterpart of the Java build's Error
// Prone. Layout is Prettier's (.prettierrc.json), so no rule here is about layout. `npm test` runs
// both checks first, as `./gradlew build` runs checkFormat.
import js from "@eslint/js";
import tseslint from "typescript-eslint";

export default tseslint.config(
  { ignores: ["**/dist/", "tm1-client/src/generated/", "**/node_modules/"] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
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
    // Tests read TM1's JSON and fakes' requests as they come
    files: ["tm1-client/test/**", "tm1-testcontainers/test/**"],
    rules: { "@typescript-eslint/no-explicit-any": "off" },
  },
);
