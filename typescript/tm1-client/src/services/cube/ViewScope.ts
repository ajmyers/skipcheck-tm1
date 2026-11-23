/** Who sees a view, and so which of a cube's collections holds it: the Java client's `ViewScope`.
 * {@link ViewScope.navigation} names the collection. */
export type ViewScope = "Public" | "Private";
export const ViewScope = {
  /** Everyone: `Cubes('c')/Views`. */
  Public: "Public",
  /** Only the user who made it: `Cubes('c')/PrivateViews`. */
  Private: "Private",
  /** The cube's navigation property holding views of this scope, such as `PrivateViews`. */
  navigation(scope: ViewScope): string {
    switch (scope) {
      case "Public":
        return "Views";
      case "Private":
        return "PrivateViews";
      default:
        throw new RangeError(`not a ViewScope: ${String(scope)}`);
    }
  },
} as const;
