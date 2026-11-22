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
    "Rule",
    "Tm1ErrorClassifier",
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


@dataclass(frozen=True)
class Rule:
    """A classification rule: TM1's status, code and message to match (any left None matches
    anything; at least one is needed), and the kind it means."""

    status: int | None
    code: str | None
    message: re.Pattern[str] | None
    kind: Tm1ErrorKind

    def __post_init__(self) -> None:
        if self.status is None and self.code is None and self.message is None:
            raise ValueError("A rule needs at least one condition")

    @staticmethod
    def of_message(regex: str, kind: Tm1ErrorKind) -> Rule:
        """Matches TM1's message, case-insensitively (Java's ``Rule.message``)."""
        return Rule(None, None, re.compile(regex, re.I), kind)

    @staticmethod
    def of_code(code: str, kind: Tm1ErrorKind) -> Rule:
        """Matches TM1's error code (Java's ``Rule.code``)."""
        return Rule(None, code, None, kind)

    def matches(self, status: int, error: ODataError | None) -> bool:
        if self.status is not None and self.status != status:
            return False
        if self.code is not None and (error is None or error.code != self.code):
            return False
        return self.message is None or (error is not None and self.message.search(error.message) is not None)


class Tm1ErrorClassifier:
    """What a refusal means, from TM1's status, code and message, as the Java client's
    ``Tm1ErrorClassifier``: the first rule that matches, else by status. TM1 reports some
    failures only by message (impersonation, a missing object answered with 400)."""

    def __init__(self, rules: list[Rule]):
        self._rules = tuple(rules)

    @staticmethod
    def defaults() -> Tm1ErrorClassifier:
        return _DEFAULTS

    def with_rule(self, rule: Rule) -> Tm1ErrorClassifier:
        """This classifier with ``rule`` tried first."""
        return Tm1ErrorClassifier([rule, *self._rules])

    @property
    def rules(self) -> tuple[Rule, ...]:
        return self._rules

    def classify(self, status: int, error: ODataError | None) -> Tm1ErrorKind:
        for rule in self._rules:
            if rule.matches(status, error):
                return rule.kind
        return _BY_STATUS.get(status, Tm1ErrorKind.OTHER)


# Same order and patterns as Tm1ErrorClassifier.defaults() in the Java client
_DEFAULTS = Tm1ErrorClassifier(
    [
        Rule.of_message(r"insufficient rights to impersonate", Tm1ErrorKind.IMPERSONATION_REFUSED),
        Rule.of_message(r"administrator to be able to impersonate", Tm1ErrorKind.IMPERSONATION_REFUSED),
        Rule.of_message(r"user specified in the impersonation request does not exist", Tm1ErrorKind.UNKNOWN_USER),
        Rule.of_message(r"tm1userexception: cancel", Tm1ErrorKind.CANCELLED),
        Rule.of_message(r"DataSpreadFailed", Tm1ErrorKind.DATA_SPREAD_FAILED),
        Rule.of_code("248", Tm1ErrorKind.INVALID_UNIQUE_NAME),
        Rule.of_code("65", Tm1ErrorKind.NO_READ_RIGHTS),
        Rule.of_message(r"ObjectSecurityNoReadRights", Tm1ErrorKind.NO_READ_RIGHTS),
        Rule.of_message(r"can ?not be found|not found|does not exist|doesn't exist|ObjectNotFound", Tm1ErrorKind.NOT_FOUND),
        Rule.of_message(r"already exists", Tm1ErrorKind.ALREADY_EXISTS),
        Rule.of_message(r"\blocked\b", Tm1ErrorKind.LOCKED),
        Rule.of_message(r"\bin use\b|is used by|isbeingusedby|cannot be deleted", Tm1ErrorKind.IN_USE),
    ]
)

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
    """The kind of a refusal by the default rules."""
    return _DEFAULTS.classify(status, error)


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


def api_error(
    method: str, url: str, status: int, body: bytes | None, classifier: Tm1ErrorClassifier | None = None
) -> Tm1ApiError:
    """The exception for a refused request."""
    error = ODataError.parse(body)
    kind = (classifier or _DEFAULTS).classify(status, error)
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
