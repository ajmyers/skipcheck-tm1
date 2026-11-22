/** Who sees an entry, and so which of a folder's collections holds it: `Public` (everyone,
 * `…/Contents('id')`) or `Private` (only the user who made it, `…/PrivateContents('id')`). */
export type ContentScope = "Public" | "Private";

const NAVIGATION: Readonly<Record<ContentScope, string>> = { Public: "Contents", Private: "PrivateContents" };

export const ContentScope = {
  /** Everyone: `…/Contents('id')`. */
  Public: "Public",
  /** Only the user who made it: `…/PrivateContents('id')`. */
  Private: "Private",

  /** The folder's navigation property holding entries of `scope`, such as `PrivateContents`. */
  navigation(scope: ContentScope): string {
    const navigation = Object.prototype.hasOwnProperty.call(NAVIGATION, scope) ? NAVIGATION[scope] : undefined;
    if (navigation === undefined) throw new RangeError("Not a content scope: " + String(scope));
    return navigation;
  },
} as const;
