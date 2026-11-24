// SPDX-License-Identifier: Apache-2.0

import { AttributeDefinition, AttributeDefinitionCreate, AttributeSet, AttributeType, ODataQuery, type Tm1Client } from "@skipcheck/tm1-client";

// Examples for AttributeService, quoted in its documentation.

export function create(tm1: Tm1Client): Promise<AttributeDefinition> {
  // @start region = create
  // A text attribute on every element of the Region hierarchy
  const manager = AttributeDefinitionCreate.builder().name("Manager").type(AttributeType.String).build();
  return tm1.attributes.create(AttributeSet.elements("Region", "Region"), manager);
  // @end
}

export async function list(tm1: Tm1Client): Promise<string[]> {
  // @start region = list
  // The attributes that every cube has
  const attributes = await tm1.attributes.list(AttributeSet.cubes(), ODataQuery.none().select(AttributeDefinition.Fields.Name)).toArray();
  return attributes.map((attribute) => attribute.Name ?? "");
  // @end
}
