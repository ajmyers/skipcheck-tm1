# SPDX-License-Identifier: Apache-2.0

"""Errors, as the Java client's ``io.skipcheck.tm1.client.error``.

Every failure is a :class:`Tm1Error`, which says whether a retry could help
(:meth:`~Tm1Error.is_retryable`) and whether the request may have taken effect
(:meth:`~Tm1Error.may_have_applied`):

* :class:`Tm1ApiError`: TM1 refused the request. Its class follows the *kind* of failure, which
  a :class:`Tm1ErrorClassifier` recognizes from TM1's status, code and message (TM1 reports
  a missing object, or an existing one, with a 400 in places);
* :class:`Tm1TransportError`: no complete answer (connection refused, timeout, TLS);
* :class:`Tm1ProtocolError`: an answer this client can't read;
* :class:`Tm1UnsupportedOperationError`: the server doesn't define what was asked for.

The names end in ``Error`` (Python's convention) where Java's end in ``Exception``.
"""

from __future__ import annotations

import enum
import json
import re
from dataclasses import dataclass, field
from typing import Any

__all__ = [
    "ODataError",
    "Rule",
    "Tm1ApiError",
    "Tm1AuthenticationError",
    "Tm1BadRequestError",
    "Tm1ConflictError",
    "Tm1Error",
    "Tm1ErrorClassifier",
    "Tm1ErrorKind",
    "Tm1ForbiddenError",
    "Tm1NotFoundError",
    "Tm1NotImplementedError",
    "Tm1PreconditionFailedError",
    "Tm1ProtocolError",
    "Tm1ServerError",
    "Tm1SessionClosedError",
    "Tm1TransportError",
    "Tm1UnsupportedOperationError",
    "classify",
]


class Tm1ErrorKind(enum.Enum):
    """What went wrong, independent of the status TM1 chose to report it with."""

    NOT_FOUND = "not-found"
    ALREADY_EXISTS = "already-exists"
    LOCKED = "locked"
    IN_USE = "in-use"
    CONFLICT = "conflict"
    PRECONDITION_FAILED = "precondition-failed"
    UNAUTHENTICATED = "unauthenticated"
    """No (or no longer a) valid session: a 401 on a request, after logging in again."""
    CREDENTIAL_REJECTED = "credential-rejected"
    """TM1 refused the login's credentials (or an impersonated user is disabled)."""
    FORBIDDEN = "forbidden"
    NO_READ_RIGHTS = "no-read-rights"
    IMPERSONATION_REFUSED = "impersonation-refused"
    UNKNOWN_USER = "unknown-user"
    DATA_SPREAD_FAILED = "data-spread-failed"
    INVALID_UNIQUE_NAME = "invalid-unique-name"
    CANCELLED = "cancelled"
    NOT_IMPLEMENTED = "not-implemented"
    OTHER = "other"


_MAX_TEXT = 1000


@dataclass(frozen=True)
class ODataError:
    """The ``error`` object of an OData error response, or TM1's plain-text message."""

    code: str = ""
    message: str = ""
    target: str | None = None
    details: tuple[ODataError, ...] = field(default_factory=tuple)
    inner_error: Any = None

    @staticmethod
    def from_json(body: Any) -> ODataError | None:
        """The error in a parsed JSON body (``{"error": {...}}``, the object itself, or a TM1 12
        auth/gateway error ``{"error": "invalid_token", "error_details": "..."}``), or None if it
        isn't one (Java's ``ODataError.from``)."""
        if not isinstance(body, dict):
            return None
        error = body.get("error", body) if "error" in body else body
        if isinstance(error, str):
            code = error
            raw_msg = body.get("error_details")
            if raw_msg is None or raw_msg == "":
                raw_msg = body.get("error_description")
            if raw_msg is None:
                message = ""
            elif isinstance(raw_msg, str):
                message = raw_msg
            else:
                message = json.dumps(raw_msg, separators=(",", ":"))
            if not code and not message:
                return None
            return ODataError(code, message)
        if not isinstance(error, dict) or not ("code" in error or "message" in error):
            return None
        details = []
        for item in error.get("details") or []:
            detail = ODataError.from_json(item)
            if detail is not None:
                details.append(detail)

        def text(name: str) -> str | None:
            value = error.get(name)
            return None if value is None else str(value)

        return ODataError(text("code") or "", text("message") or "", text("target"), tuple(details), error.get("innererror"))

    @staticmethod
    def from_text(body: str) -> ODataError | None:
        """TM1's plain-text error: its lines but the ``Error: …`` status line, joined, at most
        1000 characters (and an ellipsis)."""
        lines = [line.strip() for line in re.split(r"\r\n|\r|\n", body)]
        message = " ".join(line for line in lines if line and not line.startswith("Error:"))
        if not message:
            return None
        return ODataError("", message[:_MAX_TEXT] + "…" if len(message) > _MAX_TEXT else message)

    @staticmethod
    def parse(body: bytes | str | None) -> ODataError | None:
        """An error response's body: OData JSON, else plain text."""
        if not body:
            return None
        text = body.decode("utf-8", "replace") if isinstance(body, bytes) else body
        try:
            return ODataError.from_json(json.loads(text)) or ODataError.from_text(text)
        except ValueError:
            return ODataError.from_text(text)


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
        """Whether the rule matches an error response: each condition that's set must match, the
        status, the code exactly, and the message pattern (ignoring case, anywhere in the message)."""
        if self.status is not None and self.status != status:
            return False
        if self.code is not None and (error is None or error.code != self.code):
            return False
        return self.message is None or (error is not None and self.message.search(error.message) is not None)


class Tm1ErrorClassifier:
    """What a refusal means, from TM1's status, code and message, as the Java client's
    ``Tm1ErrorClassifier``: the first rule that matches, else by status. TM1 reports some
    failures only by message (impersonation, a missing object answered with 400)."""

    Rule = Rule

    def __init__(self, rules: list[Rule]):
        self._rules = tuple(rules)

    @staticmethod
    def defaults() -> Tm1ErrorClassifier:
        """The rules this client knows about."""
        return _DEFAULTS

    def with_rule(self, rule: Rule) -> Tm1ErrorClassifier:
        """This classifier with ``rule`` tried first."""
        if rule is None:
            raise TypeError("rule")
        return Tm1ErrorClassifier([rule, *self._rules])

    @property
    def rules(self) -> tuple[Rule, ...]:
        """The rules, in the order they are tried."""
        return self._rules

    def classify(self, status: int, error: ODataError | None) -> Tm1ErrorKind:
        """Classifies an error response: the first matching rule's kind, or else the kind for the
        status. ``error`` may be None if the body wasn't an OData error."""
        for rule in self._rules:
            if rule.matches(status, error):
                return rule.kind
        return _BY_STATUS.get(status, Tm1ErrorKind.OTHER)

    def __repr__(self) -> str:
        return f"Tm1ErrorClassifier[{len(self._rules)} rules]"


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


def _describe(method: str | None, url: str | None) -> str:
    return "" if method is None else f" ({method} {url})"


class Tm1Error(Exception):
    """Any failure talking to TM1: the request it was (``method``, ``url``), if any."""

    def __init__(self, message: str, method: str | None = None, url: str | None = None):
        super().__init__(message)
        self.method = method
        self.url = url

    def is_retryable(self) -> bool:
        """Whether sending the same request again could succeed."""
        return False

    def may_have_applied(self) -> bool:
        """Whether the request may have taken effect on TM1 despite the failure."""
        return False


class Tm1ApiError(Tm1Error):
    """TM1 refused a request: its status, the :class:`Tm1ErrorKind`, and TM1's error."""

    def __init__(self, status: int, kind: Tm1ErrorKind, error: ODataError | None, method: str, url: str):
        detail = "" if error is None or not error.message else f": {error.message}"
        code = "" if error is None or not error.code else f" [code {error.code}]"
        super().__init__(f"TM1 returned {status}{detail}{code}{_describe(method, url)}", method, url)
        self.status = status
        self.kind = kind
        self.error = error

    @staticmethod
    def of(status: int, error: ODataError | None, method: str, url: str, classifier: Tm1ErrorClassifier | None = None) -> Tm1ApiError:
        """The exception for a refusal, of the class its kind (by ``classifier``) calls for."""
        kind = (classifier or _DEFAULTS).classify(status, error)
        return Tm1ApiError.of_kind(status, kind, error, method, url)

    @staticmethod
    def of_kind(status: int, kind: Tm1ErrorKind, error: ODataError | None, method: str, url: str) -> Tm1ApiError:
        """The exception for a refusal of a known kind (Java's other ``of``)."""
        k = Tm1ErrorKind
        cls: type[Tm1ApiError]
        if kind is k.NOT_FOUND:
            cls = Tm1NotFoundError
        elif kind in (k.ALREADY_EXISTS, k.LOCKED, k.IN_USE, k.CONFLICT):
            cls = Tm1ConflictError
        elif kind is k.PRECONDITION_FAILED:
            cls = Tm1PreconditionFailedError
        elif kind in (k.UNAUTHENTICATED, k.CREDENTIAL_REJECTED):
            cls = Tm1AuthenticationError
        elif kind in (k.FORBIDDEN, k.NO_READ_RIGHTS, k.IMPERSONATION_REFUSED):
            cls = Tm1ForbiddenError
        elif kind is k.NOT_IMPLEMENTED:
            cls = Tm1NotImplementedError
        elif status >= 500:
            cls = Tm1ServerError
        elif status >= 400:
            cls = Tm1BadRequestError
        else:
            cls = Tm1ApiError
        return cls(status, kind, error, method, url)

    def is_retryable(self) -> bool:
        """True for statuses where trying again later may work: 429, 502, 503 and 504."""
        return self.status in (429, 502, 503, 504)

    def may_have_applied(self) -> bool:
        """False for client errors (4xx), which TM1 rejected; true for server errors, except 501."""
        return self.status >= 500 and self.status != 501


class Tm1BadRequestError(Tm1ApiError):
    """A 4xx no other class stands for."""


class Tm1AuthenticationError(Tm1ApiError):
    """The credentials were refused, or the session expired and logging in again failed."""


class Tm1ForbiddenError(Tm1ApiError):
    """Not allowed: rights, or impersonation refused."""


class Tm1NotFoundError(Tm1ApiError):
    """The object doesn't exist, whatever status TM1 reported that with."""


class Tm1ConflictError(Tm1ApiError):
    """It exists already, is locked or in use, or another conflict."""


class Tm1PreconditionFailedError(Tm1ApiError):
    """An ``If-Match`` ETag was stale (412), or missing (428)."""


class Tm1NotImplementedError(Tm1ApiError):
    """501."""


class Tm1ServerError(Tm1ApiError):
    """5xx."""


class Tm1TransportError(Tm1Error):
    """No complete answer: couldn't connect or send (``CONNECT``), or the answer didn't arrive
    whole (``RESPONSE``: a timeout, a dropped connection)."""

    class Phase(enum.Enum):
        """How far the request got."""

        CONNECT = "connect"
        RESPONSE = "response"

    def __init__(
        self,
        phase: Tm1TransportError.Phase,
        idempotent: bool,
        method: str,
        url: str,
        cause: BaseException | None = None,
    ):
        head = "Couldn't send the request to TM1" if phase is Tm1TransportError.Phase.CONNECT else "No complete response from TM1"
        reason = "" if cause is None or not str(cause) else f": {cause}"
        super().__init__(head + _describe(method, url) + reason, method, url)
        self.phase = phase
        self.idempotent = idempotent
        self.__cause__ = cause

    def is_retryable(self) -> bool:
        """Connect failures are always retryable; response failures only for idempotent requests."""
        return self.phase is Tm1TransportError.Phase.CONNECT or self.idempotent

    def may_have_applied(self) -> bool:
        """False only if the request was never sent."""
        return self.phase is Tm1TransportError.Phase.RESPONSE


class Tm1ProtocolError(Tm1Error):
    """TM1 answered with something this client can't read."""

    def __init__(self, message: str, error: ODataError | None = None, method: str | None = None, url: str | None = None):
        super().__init__(message + _describe(method, url), method, url)
        self.error = error

    def may_have_applied(self) -> bool:
        """True: the server got the request and started answering."""
        return True


class Tm1UnsupportedOperationError(Tm1Error):
    """What was asked for isn't there: ``missing`` names it (``entity set Bananas``,
    ``Cube.Reserve``). Also a request this client refuses to send, because of what TM1 would
    do with it."""

    def __init__(self, message: str, missing: str = ""):
        super().__init__(message)
        self.missing = missing


class Tm1SessionClosedError(RuntimeError):
    """The session was closed: it sends nothing more (Java's ``Tm1SessionClosedException``,
    an ``IllegalStateException``)."""

    def __init__(self, service_root: str):
        super().__init__(f"The TM1 session at {service_root} is closed")
        self.service_root = service_root


def api_error(method: str, url: str, status: int, body: bytes | None, classifier: Tm1ErrorClassifier | None = None) -> Tm1ApiError:
    """The exception for a refused request, from its body (OData JSON or plain text)."""
    return Tm1ApiError.of(status, ODataError.parse(body), method, url, classifier)
