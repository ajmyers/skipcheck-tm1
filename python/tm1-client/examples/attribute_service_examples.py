"""Examples for AttributeService, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import ODataQuery, Tm1Client
from skipcheck_tm1.model import AttributeDefinition, AttributeDefinitionPatch, AttributeType
from skipcheck_tm1.services import AttributeSet


def create(tm1: Tm1Client) -> AttributeDefinition:
    # @start region = create
    # A text attribute on every element of the Region hierarchy
    manager = AttributeDefinitionPatch.builder().name("Manager").type(AttributeType.STRING).build()
    return tm1.attributes.create(AttributeSet.elements("Region", "Region"), manager)
    # @end


def list_names(tm1: Tm1Client) -> list[str]:
    # @start region = list
    # The attributes that every cube has
    attributes = tm1.attributes.list(AttributeSet.cubes(), ODataQuery.none().select(AttributeDefinition.NAME))
    return [attribute.name for attribute in attributes if attribute.name is not None]
    # @end
