"""Attribute definitions, and the attribute values translated per locale
(``LocalizedAttributes``), for every kind of TM1 object that has them (the Java client's
``net.ajmyers.tm1.client.service.attribute``). Attribute values in the default locale are cells
of the ``}…Attributes`` control cubes."""

from __future__ import annotations

from typing import Any

from ..._calls import endpoint
from ..._generated.model import AttributeDefinition, LocalizedAttributes
from ...odata import ODataPath, ODataQuery
from ...request import Tm1Request
from ._base import Service, _require
from .dimension import SubsetScope

__all__ = ["AttributeOwner", "AttributeService", "AttributeSet", "LocalizedAttributeService"]


def _navigation(scope: Any) -> str:
    """A scope's navigation property: a ``SubsetScope`` or ``ViewScope``, or its name."""
    navigation = getattr(scope, "navigation", None)
    return navigation() if callable(navigation) else str(_require(scope, "scope"))


def _hierarchy(dimension: str, hierarchy: str) -> ODataPath:
    return (
        ODataPath.of("Dimensions")
        .key(_require(dimension, "dimension"))
        .nav("Hierarchies")
        .key(_require(hierarchy, "hierarchy"))
    )


class AttributeOwner:
    """An object whose attribute values can be translated: it has ``LocalizedAttributes``."""

    __slots__ = ("_path",)

    def __init__(self, path: ODataPath):
        self._path = path

    @staticmethod
    def dimension(dimension: str) -> AttributeOwner:
        """A dimension."""
        return AttributeOwner(ODataPath.of("Dimensions").key(_require(dimension, "dimension")))

    @staticmethod
    def hierarchy(dimension: str, hierarchy: str) -> AttributeOwner:
        """A hierarchy."""
        return AttributeOwner(_hierarchy(dimension, hierarchy))

    @staticmethod
    def element(dimension: str, hierarchy: str, element: str) -> AttributeOwner:
        """An element."""
        return AttributeOwner(_hierarchy(dimension, hierarchy).nav("Elements").key(_require(element, "element")))

    @staticmethod
    def member(dimension: str, hierarchy: str, member: str) -> AttributeOwner:
        """A member. Its translations are its element's, and read only here."""
        return AttributeOwner(_hierarchy(dimension, hierarchy).nav("Members").key(_require(member, "member")))

    @staticmethod
    def subset(dimension: str, hierarchy: str, scope: SubsetScope, subset: str) -> AttributeOwner:
        """A subset."""
        return AttributeOwner(_hierarchy(dimension, hierarchy).nav(_navigation(scope)).key(_require(subset, "subset")))

    @staticmethod
    def cube(cube: str) -> AttributeOwner:
        """A cube."""
        return AttributeOwner(ODataPath.of("Cubes").key(_require(cube, "cube")))

    @staticmethod
    def view(cube: str, scope: Any, view: str) -> AttributeOwner:
        """A view; ``scope`` is a ``ViewScope``."""
        path = ODataPath.of("Cubes").key(_require(cube, "cube")).nav(_navigation(scope)).key(_require(view, "view"))
        return AttributeOwner(path)

    @staticmethod
    def process(process: str) -> AttributeOwner:
        """A process."""
        return AttributeOwner(ODataPath.of("Processes").key(_require(process, "process")))

    @staticmethod
    def chore(chore: str) -> AttributeOwner:
        """A chore."""
        return AttributeOwner(ODataPath.of("Chores").key(_require(chore, "chore")))

    def path(self) -> ODataPath:
        return self._path

    def __eq__(self, other: object) -> bool:
        return isinstance(other, AttributeOwner) and other._path == self._path

    def __hash__(self) -> int:
        return hash(self._path)

    def __str__(self) -> str:
        return str(self._path)

    def __repr__(self) -> str:
        return f"AttributeOwner({self._path})"


class AttributeSet:
    """A set of attribute definitions: the attributes that one kind of object has, such as
    every element of a hierarchy."""

    __slots__ = ("_path",)

    def __init__(self, path: ODataPath):
        self._path = path

    @staticmethod
    def dimensions() -> AttributeSet:
        """Dimensions' attributes: ``/DimensionAttributes``."""
        return AttributeSet(ODataPath.of("DimensionAttributes"))

    @staticmethod
    def hierarchies(dimension: str) -> AttributeSet:
        """The attributes of a dimension's hierarchies: ``Dimensions('d')/HierarchyAttributes``."""
        return AttributeSet(ODataPath.of("Dimensions").key(_require(dimension, "dimension")).nav("HierarchyAttributes"))

    @staticmethod
    def elements(dimension: str, hierarchy: str) -> AttributeSet:
        """The attributes of a hierarchy's elements: ``…/Hierarchies('h')/ElementAttributes``."""
        return AttributeSet(_hierarchy(dimension, hierarchy).nav("ElementAttributes"))

    @staticmethod
    def subsets(dimension: str, hierarchy: str) -> AttributeSet:
        """The attributes of a hierarchy's subsets: ``…/Hierarchies('h')/SubsetAttributes``."""
        return AttributeSet(_hierarchy(dimension, hierarchy).nav("SubsetAttributes"))

    @staticmethod
    def cubes() -> AttributeSet:
        """Cubes' attributes: ``/CubeAttributes``."""
        return AttributeSet(ODataPath.of("CubeAttributes"))

    @staticmethod
    def views(cube: str) -> AttributeSet:
        """The attributes of a cube's views: ``Cubes('c')/ViewAttributes``. TM1 (11.8) refuses to
        create one (400, "no public views") until the cube has a public *native* view; an MDX
        view doesn't count."""
        return AttributeSet(ODataPath.of("Cubes").key(_require(cube, "cube")).nav("ViewAttributes"))

    @staticmethod
    def processes() -> AttributeSet:
        """Processes' attributes: ``/ProcessAttributes``."""
        return AttributeSet(ODataPath.of("ProcessAttributes"))

    @staticmethod
    def chores() -> AttributeSet:
        """Chores' attributes: ``/ChoreAttributes``."""
        return AttributeSet(ODataPath.of("ChoreAttributes"))

    def path(self) -> ODataPath:
        return self._path

    def __eq__(self, other: object) -> bool:
        return isinstance(other, AttributeSet) and other._path == self._path

    def __hash__(self) -> int:
        return hash(self._path)

    def __str__(self) -> str:
        return str(self._path)

    def __repr__(self) -> str:
        return f"AttributeSet({self._path})"


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
        return await self._session.collect(
            Tm1Request.list(_localized(owner), ODataQuery.of(query, **options), LocalizedAttributes)
        )

    @endpoint(*_LOCALIZED)
    async def get(
        self, owner: AttributeOwner, locale: str, query: ODataQuery | None = None, **options: Any
    ) -> LocalizedAttributes:
        """``GET …/LocalizedAttributes('locale')``: the translations for a locale, or a
        ``Tm1NotFoundError`` if there are none."""
        path = _localized(owner).key(_require(locale, "locale"))
        return await self._session.execute(Tm1Request.get(path, ODataQuery.of(query, **options), LocalizedAttributes))

    @endpoint(*_LOCALIZED)
    async def create(self, owner: AttributeOwner, translations: Any) -> LocalizedAttributes:
        """``POST …/LocalizedAttributes``: adds translations for a locale."""
        return await self._session.execute(
            Tm1Request.post(_localized(owner), _require(translations, "translations"), LocalizedAttributes)
        )
