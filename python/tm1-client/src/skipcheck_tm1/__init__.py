"""A Python client for the IBM Planning Analytics (TM1) REST API.

Generated from the same language-neutral API spec as the Java client (``spec/tm1-api.json``,
from TM1's ``$metadata``), so it covers every endpoint the spec lists::

    from skipcheck_tm1 import Tm1Client

    with Tm1Client.builder().base_url("https://tm1:8010/api/v1").basic_auth("admin", "apple").build() as tm1:
        print(tm1.server.product_version())

Not affiliated with or endorsed by IBM.
"""

from . import auth, model
from .base import Tm1Enum, Tm1Model
from .client import Tm1Batch, Tm1Client, Tm1ClientBuilder
from .errors import (
    Tm1ApiError,
    Tm1AuthenticationError,
    Tm1BadRequestError,
    Tm1ConflictError,
    Tm1Error,
    Tm1ErrorClassifier,
    Tm1ErrorKind,
    Tm1ForbiddenError,
    Tm1NotFoundError,
    Tm1NotImplementedError,
    Tm1PreconditionFailedError,
    Tm1ProtocolError,
    Tm1ServerError,
    Tm1SessionClosedError,
    Tm1TransportError,
    Tm1UnsupportedOperationError,
)
from .identity import Tm1Identity
from .odata import Bind, Filter, ODataPath, ODataProperty, ODataQuery, Raw
from .operation import Tm1Operation
from .request import Tm1Request
from .resources import UNSET
from .session import Tm1PoolStats, Tm1Session, Tm1SessionBatch, Tm1SessionBuilder, Tm1SessionPool, Tm1SessionPoolBuilder
from .timeouts import Tm1Timeouts
from .version import Tm1Capabilities, Tm1Capability, Tm1Dialect, Tm1Version

__all__ = [
    "UNSET",
    "Bind",
    "Filter",
    "ODataProperty",
    "Tm1Batch",
    "Tm1Capabilities",
    "Tm1Capability",
    "Tm1ClientBuilder",
    "Tm1Dialect",
    "Tm1ErrorClassifier",
    "Tm1Operation",
    "Tm1Request",
    "Tm1Identity",
    "Tm1PoolStats",
    "Tm1SessionBatch",
    "Tm1SessionBuilder",
    "Tm1SessionClosedError",
    "Tm1SessionPool",
    "Tm1SessionPoolBuilder",
    "Tm1Timeouts",
    "Tm1Version",
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
