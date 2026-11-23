/** Who sees a subset, and so which of a hierarchy's collections holds it: the Java client's
 * `SubsetScope`; {@link SubsetScope.navigation} names the collection. */
export type SubsetScope = "Public" | "Private" | "Session";

const NAVIGATION: Readonly<Record<SubsetScope, string>> = {
  Public: "Subsets",
  Private: "PrivateSubsets",
  Session: "SessionSubsets",
};

/** Who sees a subset, as values: `SubsetScope.Public`. {@link SubsetScope.navigation} names the
 * hierarchy's collection that holds it. */
export const SubsetScope = {
  /** Everyone: `Hierarchies('h')/Subsets`. */
  Public: "Public",
  /** Only the user who made it: `Hierarchies('h')/PrivateSubsets`. */
  Private: "Private",
  /** Only this session, gone when it ends: `Hierarchies('h')/SessionSubsets`. */
  Session: "Session",
  /** The hierarchy's navigation property holding subsets of this scope, such as `PrivateSubsets`
   * (Java's `scope.navigation()`). */
  navigation(scope: SubsetScope): string {
    const navigation = Object.prototype.hasOwnProperty.call(NAVIGATION, scope) ? NAVIGATION[scope] : undefined;
    if (navigation === undefined) throw new RangeError("Not a subset scope: " + String(scope));
    return navigation;
  },
} as const;
