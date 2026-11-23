# SPDX-License-Identifier: Apache-2.0

"""Attribute definitions, and the attribute values translated per locale
(``LocalizedAttributes``), for every kind of TM1 object that has them (the Java client's
``io.skipcheck.tm1.client.service.attribute``). Attribute values in the default locale are cells
of the ``}…Attributes`` control cubes."""

from __future__ import annotations

from typing import Any

from ..._calls import endpoint
from ..._generated.model import AttributeDefinition, LocalizedAttributes
from ...odata import ODataPath, ODataQuery
from ...request import Tm1Request
from ...values import AttributeOwner, AttributeSet
from ._base import Service, _require

__all__ = ["AttributeOwner", "AttributeService", "AttributeSet", "LocalizedAttributeService"]


def _navigation(scope: Any) -> str:
    """A scope's navigation property: a ``SubsetScope`` or ``ViewScope``, or its name."""
    navigation = getattr(scope, "navigation", None)
    return str(navigation() if callable(navigation) else _require(scope, "scope"))


def _hierarchy(dimension: str, hierarchy: str) -> ODataPath:
    return ODataPath.of("Dimensions").key(_require(dimension, "dimension")).nav("Hierarchies").key(_require(hierarchy, "hierarchy"))


_ATTRIBUTE_SETS = (
    "EntitySet DimensionAttributes",
    "Navigation Dimension/HierarchyAttributes",
    "Navigation Hierarchy/ElementAttributes",
    "Navigation Hierarchy/SubsetAttributes",
    "EntitySet CubeAttributes",
    "Navigation Cube/ViewAttributes",
    "EntitySet ProcessAttributes",
    "EntitySet ChoreAttributes",
)


class AttributeService(Service):
    """Attribute definitions: which attributes a kind of object has, and their types, for each
    :class:`AttributeSet`. Creating one adds it to every object of that kind; the values are
    cells of the matching ``}…Attributes`` control cube.

    Add a text attribute to every element of the Region hierarchy::

        await tm1.attributes.create(AttributeSet.elements("Region", "Region"),
            AttributeDefinitionPatch.builder().name("Manager").type(AttributeType.STRING).build())

    List the attributes that every cube has::

        attributes = await tm1.attributes.list(AttributeSet.cubes(), ODataQuery.none().select(AttributeDefinition.NAME))
    """

    @endpoint(*_ATTRIBUTE_SETS)
    async def list(self, set: AttributeSet, query: ODataQuery | None = None, **options: Any) -> list[AttributeDefinition]:
        """``GET`` the set's attributes."""
        path = _require(set, "set").path()
        return await self._session.collect(Tm1Request.list(path, ODataQuery.of(query, **options), AttributeDefinition))

    @endpoint(*_ATTRIBUTE_SETS)
    async def get(self, set: AttributeSet, name: str, query: ODataQuery | None = None, **options: Any) -> AttributeDefinition:
        """``GET`` one attribute of the set."""
        path = _require(set, "set").path().key(_require(name, "name"))
        return await self._session.execute(Tm1Request.get(path, ODataQuery.of(query, **options), AttributeDefinition))

    @endpoint(*_ATTRIBUTE_SETS)
    async def create(self, set: AttributeSet, attribute: Any) -> AttributeDefinition:
        """``POST``: adds an attribute to every object of the set's kind."""
        path = _require(set, "set").path()
        return await self._session.execute(Tm1Request.post(path, _require(attribute, "attribute"), AttributeDefinition))

    @endpoint(*_ATTRIBUTE_SETS)
    async def delete(self, set: AttributeSet, name: str) -> None:
        """``DELETE``: removes an attribute, with its values, from every object of the set's kind."""
        await self._session.execute(Tm1Request.delete(_require(set, "set").path().key(_require(name, "name"))))


_LOCALIZED = (
    "Navigation Dimension/LocalizedAttributes",
    "Navigation Hierarchy/LocalizedAttributes",
    "Navigation Element/LocalizedAttributes",
    "Navigation Member/LocalizedAttributes",
    "Navigation Subset/LocalizedAttributes",
    "Navigation Cube/LocalizedAttributes",
    "Navigation View/LocalizedAttributes",
    "Navigation Process/LocalizedAttributes",
    "Navigation Chore/LocalizedAttributes",
)


def _localized(owner: AttributeOwner) -> ODataPath:
    return _require(owner, "owner").path().nav("LocalizedAttributes")


class LocalizedAttributeService(Service):
    """Translated attribute values: an :class:`AttributeOwner`'s ``LocalizedAttributes``, one per
    locale, keyed by locale ID (``fr``, ``de-CH``). The values are in ``Attributes``:
    ``Caption``, and every other attribute in its ``model_extra`` (set with
    ``AttributesPatch.Builder.property``).

    Only attributes the owner's kind has can be translated (see :class:`AttributeService`):
    ``Caption`` too, once it's defined. A member's translations are its element's, and read
    only: write them through :meth:`AttributeOwner.element`.

    TM1 (11.8) creates a locale's translations once and reads them, and that's all: it supports
    no PATCH or DELETE on them and refuses to create a locale twice. Changing or removing
    translations goes through the ``}Localized…Attributes`` control cubes, as cell writes.

    Translate an element's Long Name, an attribute its hierarchy has, into French (a locale's
    translations are created once: TM1 can't change them here)::

        await tm1.localized_attributes.create(AttributeOwner.element("Region", "Region", "North"),
            LocalizedAttributesPatch.builder()
            .locale_id("fr")
            .attributes(AttributesPatch.builder().property("Long Name", "Région Nord").build())
            .build())

    Read the translation back; attributes other than Caption come back in ``model_extra``::

        french = await tm1.localized_attributes.get(AttributeOwner.element("Region", "Region", "North"), "fr")
        french.attributes.model_extra["Long Name"]
    """

    @endpoint(*_LOCALIZED)
    async def list(self, owner: AttributeOwner, query: ODataQuery | None = None, **options: Any) -> list[LocalizedAttributes]:
        """``GET …/LocalizedAttributes``: the owner's translations, one per locale."""
        return await self._session.collect(Tm1Request.list(_localized(owner), ODataQuery.of(query, **options), LocalizedAttributes))

    @endpoint(*_LOCALIZED)
    async def get(self, owner: AttributeOwner, locale: str, query: ODataQuery | None = None, **options: Any) -> LocalizedAttributes:
        """``GET …/LocalizedAttributes('locale')``: the translations for a locale, or a
        ``Tm1NotFoundError`` if there are none."""
        path = _localized(owner).key(_require(locale, "locale"))
        return await self._session.execute(Tm1Request.get(path, ODataQuery.of(query, **options), LocalizedAttributes))

    @endpoint(*_LOCALIZED)
    async def create(self, owner: AttributeOwner, translations: Any) -> LocalizedAttributes:
        """``POST …/LocalizedAttributes``: adds translations for a locale."""
        return await self._session.execute(Tm1Request.post(_localized(owner), _require(translations, "translations"), LocalizedAttributes))
