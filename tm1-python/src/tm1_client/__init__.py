"""A Python client for the IBM Planning Analytics (TM1) REST API.

Generated from the same language-neutral API spec as the Java client (``spec/tm1-api.json``,
from TM1's ``$metadata``), so it covers every endpoint the spec lists::

    from tm1_client import Tm1Client

    with Tm1Client.connect("https://tm1:8010/api/v1", user="admin", password="apple") as tm1:
        print(tm1.product_version())

Not affiliated with or endorsed by IBM.
"""

from . import auth
from . import model
from .base import Tm1Enum, Tm1Model
from .client import Tm1Client
from .errors import (
    Tm1ApiError,
    Tm1AuthenticationError,
    Tm1BadRequestError,
    Tm1ConflictError,
    Tm1Error,
    Tm1ErrorKind,
    Tm1ForbiddenError,
    Tm1NotFoundError,
    Tm1NotImplementedError,
    Tm1PreconditionFailedError,
    Tm1ProtocolError,
    Tm1ServerError,
    Tm1TransportError,
    Tm1UnsupportedOperationError,
)
from .odata import Bind, ODataPath, ODataQuery, Raw
from .resources import UNSET
from .session import Tm1Session

__all__ = [
    "UNSET",
    "Bind",
    "ODataPath",
    "ODataQuery",
    "Raw",
    "Tm1ApiError",
    "Tm1AuthenticationError",
    "Tm1BadRequestError",
    "Tm1Client",
    "Tm1ConflictError",
    "Tm1Enum",
    "Tm1Error",
    "Tm1ErrorKind",
    "Tm1ForbiddenError",
    "Tm1Model",
    "Tm1NotFoundError",
    "Tm1NotImplementedError",
    "Tm1PreconditionFailedError",
    "Tm1ProtocolError",
    "Tm1ServerError",
    "Tm1Session",
    "Tm1TransportError",
    "Tm1UnsupportedOperationError",
    "auth",
    "model",
]
