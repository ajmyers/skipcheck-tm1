// SPDX-License-Identifier: Apache-2.0

import { AttributeOwner, AttributesPatch, LocalizedAttributesCreate, type LocalizedAttributes, type Tm1Client } from "@skipcheck/tm1-client";

// Examples for LocalizedAttributeService, quoted in its documentation.

export function translate(tm1: Tm1Client): Promise<LocalizedAttributes> {
  // @start region = translate
  // The French for an element's Long Name, an attribute its hierarchy has. A locale's
  // translations are created once: TM1 can't change them here.
  const french = LocalizedAttributesCreate.builder().localeID("fr").attributes(AttributesPatch.builder().property("Long Name", "Région Nord").build()).build();
  return tm1.localizedAttributes.create(AttributeOwner.element("Region", "Region", "North"), french);
  // @end
}

export async function read(tm1: Tm1Client): Promise<string | undefined> {
  // @start region = read
  // Attributes are read by their own names, beside Caption
  const french = await tm1.localizedAttributes.get(AttributeOwner.element("Region", "Region", "North"), "fr");
  const longName = french.Attributes?.["Long Name"];
  return typeof longName === "string" ? longName : undefined;
  // @end
}
