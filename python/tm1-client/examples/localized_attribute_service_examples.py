"""Examples for LocalizedAttributeService, quoted in the guide."""

from __future__ import annotations

from typing import Any

from skipcheck_tm1 import Tm1Client
from skipcheck_tm1.model import AttributesPatch, LocalizedAttributes, LocalizedAttributesPatch
from skipcheck_tm1.services import AttributeOwner


def translate(tm1: Tm1Client) -> LocalizedAttributes:
    # @start region = translate
    # The French for an element's Long Name, an attribute its hierarchy has. A
    # locale's translations are created once: TM1 can't change them here.
    french = LocalizedAttributesPatch.builder().locale_id("fr").attributes(AttributesPatch.builder().property("Long Name", "Région Nord").build()).build()
    return tm1.localized_attributes.create(AttributeOwner.element("Region", "Region", "North"), french)
    # @end


def read(tm1: Tm1Client) -> Any:
    # @start region = read
    # Attributes other than Caption come back in model_extra
    french = tm1.localized_attributes.get(AttributeOwner.element("Region", "Region", "North"), "fr")
    values = (french.attributes.model_extra or {}) if french.attributes is not None else {}
    return values.get("Long Name")
    # @end
