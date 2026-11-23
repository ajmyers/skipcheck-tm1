// SPDX-License-Identifier: Apache-2.0

import { LocalizedAttributes, type LocalizedAttributesPatch } from "../../generated/index.js";
import type { ODataPath, ODataQuery } from "../../odata/index.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import type { Tm1Stream } from "../../stream.js";
import { required } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";
import type { AttributeOwner } from "./AttributeOwner.js";

const OWNERS = [
  "Navigation Dimension/LocalizedAttributes",
  "Navigation Hierarchy/LocalizedAttributes",
  "Navigation Element/LocalizedAttributes",
  "Navigation Member/LocalizedAttributes",
  "Navigation Subset/LocalizedAttributes",
  "Navigation Cube/LocalizedAttributes",
  "Navigation View/LocalizedAttributes",
  "Navigation Process/LocalizedAttributes",
  "Navigation Chore/LocalizedAttributes",
] as const;

/** Translated attribute values: an {@link AttributeOwner}'s `LocalizedAttributes`, one per locale,
 * keyed by locale ID (`fr`, `de-CH`). The values are in `Attributes`: `Caption`, and every other
 * attribute as a property of its own (set with `AttributesPatch.builder().property(…)`).
 *
 * Only attributes the owner's kind has can be translated (see `AttributeService`): `Caption` too,
 * once it's defined. A member's translations are its element's, and read only: write them through
 * `AttributeOwner.element`.
 *
 * TM1 (11.8) creates a locale's translations once and reads them, and that's all: it supports no
 * PATCH or DELETE on them and refuses to create a locale twice. Changing or removing translations
 * goes through the `}Localized…Attributes` control cubes, as cell writes.
 *
 * Translate an element's attribute into French:
 * ```ts
 * await tm1.localizedAttributes.create(AttributeOwner.element("Region", "Region", "North"),
 *   LocalizedAttributesPatch.builder().localeID("fr").attributes(AttributesPatch.builder().property("Label", "Nord").build()).build());
 * ```
 * Read the translation back:
 * ```ts
 * const fr = await tm1.localizedAttributes.get(AttributeOwner.element("Region", "Region", "North"), "fr");
 * console.log(fr.Attributes?.["Label"]);
 * ```
 */
export class LocalizedAttributeService extends Tm1Service {
  /** The endpoints each method implements, by method name, as coverage-gate IDs: the Java client's
   * `@Tm1Endpoint`. */
  static readonly endpoints = {
    list: OWNERS,
    get: OWNERS,
    create: OWNERS,
  } as const satisfies Tm1Endpoints<LocalizedAttributeService>;

  /** `GET …/LocalizedAttributes`, streamed: the owner's translations, one per locale. */
  list(owner: AttributeOwner, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<LocalizedAttributes> {
    return this.session.stream(Tm1Request.list(localized(owner), query, LocalizedAttributes), options);
  }

  /** `GET …/LocalizedAttributes('locale')`: the translations for the locale (such as `fr`), or a
   * `Tm1NotFoundException` if there are none. */
  get(owner: AttributeOwner, locale: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<LocalizedAttributes> {
    return this.session.execute(Tm1Request.get(localized(owner).key(required(locale, "locale")), query, LocalizedAttributes), options);
  }

  /** `POST …/LocalizedAttributes`: adds translations for a locale, its `LocaleID` and the translated
   * `Attributes`; returns them as created. */
  create(owner: AttributeOwner, translations: LocalizedAttributesPatch, options?: Tm1CallOptions): Promise<LocalizedAttributes> {
    return this.session.execute(Tm1Request.post(localized(owner), required(translations, "translations"), LocalizedAttributes), options);
  }
}

function localized(owner: AttributeOwner): ODataPath {
  return required(owner, "owner").path().nav("LocalizedAttributes");
}
