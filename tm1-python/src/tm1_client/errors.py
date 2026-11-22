"""Errors, as the Java client classifies them (``net.ajmyers.tm1.client.error``).

Every failure is a :class:`Tm1Error`. A response TM1 refused is a :class:`Tm1ApiError`,
of a subclass chosen by status, carrying the OData error and a :class:`Tm1ErrorKind`
recognized from TM1's code and message (TM1 reports a missing object as a 400 in places).
"""

from __future__ import annotations

import enum
import json
import re
from dataclasses import dataclass
from typing import Any

__all__ = [
    "ODataError",
    "Tm1ApiError",
    "Tm1AuthenticationError",
    "Tm1BadRequestError",
    "Tm1ConflictError",
    "Tm1Error",
    "Tm1ErrorKind",
    "Tm1ForbiddenError",
    "Tm1NotFoundError",
    "Tm1NotImplementedError",
    "Tm1PreconditionFailedError",
    "Tm1ProtocolError",
    "Tm1ServerError",
    "Tm1TransportError",
    "Tm1UnsupportedOperationError",
    "classify",
]


class Tm1ErrorKind(enum.Enum):
    """What went wrong, independent of the status TM1 chose to report it with."""

    UNAUTHENTICATED = "unauthenticated"
    FORBIDDEN = "forbidden"
    NOT_FOUND = "not-found"
    ALREADY_EXISTS = "already-exists"
    CONFLICT = "conflict"
    PRECONDITION_FAILED = "precondition-failed"
    NOT_IMPLEMENTED = "not-implemented"
    LOCKED = "locked"
    IN_USE = "in-use"
    NO_READ_RIGHTS = "no-read-rights"
    IMPERSONATION_REFUSED = "impersonation-refused"
    UNKNOWN_USER = "unknown-user"
    CANCELLED = "cancelled"
    DATA_SPREAD_FAILED = "data-spread-failed"
    INVALID_UNIQUE_NAME = "invalid-unique-name"
    OTHER = "other"


@dataclass(frozen=True)
class ODataError:
    """The ``error`` object of an OData error response, or TM1's plain-text message."""

    code: str | None
    message: str
    details: Any = None

    @staticmethod
    def parse(body: bytes | str | None) -> ODataError | None:
        if not body:
            return None
        text = body.decode("utf-8", "replace") if isinstance(body, bytes) else body
        try:
            doc = json.loads(text)
        except ValueError:
            return ODataError(None, text.strip())
        error = doc.get("error") if isinstance(doc, dict) else None
        if not isinstance(error, dict):
            return ODataError(None, text.strip())
        code = error.get("code")
        return ODataError(None if code is None else str(code), str(error.get("message", "")), error.get("details"))


# Same order and patterns as Tm1ErrorClassifier.defaults() in the Java client
_RULES: list[tuple[str | None, re.Pattern[str] | None, Tm1ErrorKind]] = [
    (None, re.compile(r"insufficient rights to impersonate", re.I), Tm1ErrorKind.IMPERSONATION_REFUSED),
    (None, re.compile(r"administrator to be able to impersonate", re.I), Tm1ErrorKind.IMPERSONATION_REFUSED),
    (None, re.compile(r"user specified in the impersonation request does not exist", re.I), Tm1ErrorKind.UNKNOWN_USER),
    (None, re.compile(r"tm1userexception: cancel", re.I), Tm1ErrorKind.CANCELLED),
    (None, re.compile(r"DataSpreadFailed", re.I), Tm1ErrorKind.DATA_SPREAD_FAILED),
    ("248", None, Tm1ErrorKind.INVALID_UNIQUE_NAME),
    ("65", None, Tm1ErrorKind.NO_READ_RIGHTS),
    (None, re.compile(r"ObjectSecurityNoReadRights", re.I), Tm1ErrorKind.NO_READ_RIGHTS),
    (None, re.compile(r"can ?not be found|not found|does not exist|doesn't exist|ObjectNotFound", re.I), Tm1ErrorKind.NOT_FOUND),
    (None, re.compile(r"already exists", re.I), Tm1ErrorKind.ALREADY_EXISTS),
    (None, re.compile(r"\blocked\b", re.I), Tm1ErrorKind.LOCKED),
    (None, re.compile(r"\bin use\b|is used by|isbeingusedby|cannot be deleted", re.I), Tm1ErrorKind.IN_USE),
]

_BY_STATUS = {
    401: Tm1ErrorKind.UNAUTHENTICATED,
    403: Tm1ErrorKind.FORBIDDEN,
    404: Tm1ErrorKind.NOT_FOUND,
    409: Tm1ErrorKind.CONFLICT,
    412: Tm1ErrorKind.PRECONDITION_FAILED,
    428: Tm1ErrorKind.PRECONDITION_FAILED,
    501: Tm1ErrorKind.NOT_IMPLEMENTED,
}


def classify(status: int, error: ODataError | None) -> Tm1ErrorKind:
    """The kind of a refusal: the first rule matching TM1's code or message, else by status."""
    for code, message, kind in _RULES:
        if code is not None and (error is None or error.code != code):
            continue
        if message is not None and (error is None or not message.search(error.message)):
            continue
        return kind
    return _BY_STATUS.get(status, Tm1ErrorKind.OTHER)


class Tm1Error(Exception):
    """Any failure talking to TM1."""


class Tm1TransportError(Tm1Error):
    """The request didn't get a response: connection refused, timeout, TLS."""


class Tm1ProtocolError(Tm1Error):
    """TM1 answered with something this client can't read."""


class Tm1UnsupportedOperationError(Tm1Error):
    """A request this client refuses to send, because of what TM1 would do with it."""


class Tm1ApiError(Tm1Error):
    """TM1 refused a request."""

    def __init__(self, method: str, url: str, status: int, error: ODataError | None, kind: Tm1ErrorKind):
        self.method = method
        self.url = url
        self.status = status
        self.error = error
        self.kind = kind
        detail = "" if error is None else f": {error.message}" + (f" (code {error.code})" if error.code else "")
        super().__init__(f"{method} {url} → {status}{detail}")


class Tm1BadRequestError(Tm1ApiError):
    """400."""


class Tm1AuthenticationError(Tm1ApiError):
    """401: the credentials were refused, or the session expired and logging in again failed."""


class Tm1ForbiddenError(Tm1ApiError):
    """403."""


class Tm1NotFoundError(Tm1ApiError):
    """404, or a refusal TM1 sent another status for that means the object doesn't exist."""


class Tm1ConflictError(Tm1ApiError):
    """409."""


class Tm1PreconditionFailedError(Tm1ApiError):
    """412 or 428: an ``If-Match`` ETag was stale, or missing."""


class Tm1NotImplementedError(Tm1ApiError):
    """501."""


class Tm1ServerError(Tm1ApiError):
    """5xx."""


def api_error(method: str, url: str, status: int, body: bytes | None) -> Tm1ApiError:
    """The exception for a refused request."""
    error = ODataError.parse(body)
    kind = classify(status, error)
    cls: type[Tm1ApiError]
    if kind is Tm1ErrorKind.NOT_FOUND:
        cls = Tm1NotFoundError
    elif status == 400:
        cls = Tm1BadRequestError
    elif status == 401:
        cls = Tm1AuthenticationError
    elif status == 403:
        cls = Tm1ForbiddenError
    elif status == 409:
        cls = Tm1ConflictError
    elif status in (412, 428):
        cls = Tm1PreconditionFailedError
    elif status == 501:
        cls = Tm1NotImplementedError
    elif status >= 500:
        cls = Tm1ServerError
    else:
        cls = Tm1ApiError
    return cls(method, url, status, error, kind)
