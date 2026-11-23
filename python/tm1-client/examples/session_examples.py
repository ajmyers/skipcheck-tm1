"""Examples for Tm1Session, quoted in the guide."""

from __future__ import annotations

from collections.abc import Callable, Iterator

from skipcheck_tm1 import ODataPath, ODataQuery, Tm1Request, Tm1Session, auth
from skipcheck_tm1.model import MessageLogEntry, SandboxPatch
from skipcheck_tm1.services import CellService, SandboxService


def build(password: str) -> Tm1Session:
    # @start region = build
    # CAM security, with a label that shows in Sessions and Threads
    return (
        Tm1Session.builder()
        .address("https://tm1.example.com:8010")
        .authenticator(auth.cam_namespace("alice", password, "LDAP"))
        .session_context("Sales planning")
        .build()
    )
    # @end


def passport(cognos: Callable[[], str]) -> Tm1Session:
    # @start region = passport
    # CAM passports expire, so the session calls the function for a current one on every login
    return Tm1Session.builder().address("https://tm1.example.com:8010").authenticator(auth.cam_passport(cognos)).build()
    # @end


def request(session: Tm1Session) -> Iterator[MessageLogEntry]:
    # @start region = request
    # GET MessageLogEntries through the session itself: the latest 20 entries
    newest = ODataQuery.none().order_by_descending(MessageLogEntry.TIME_STAMP).top(20)
    return session.stream(Tm1Request.list(ODataPath.of("MessageLogEntries"), newest, MessageLogEntry))
    # @end


def sandbox(session: Tm1Session) -> None:
    # @start region = sandbox
    # The cell: every dimension, in the cube's order
    cell = {"Region": "North", "Month": "Jan"}
    SandboxService(session).create(SandboxPatch.builder().name("What-if").build())
    CellService(session.with_sandbox("What-if")).update("Sales", cell, 1200)
    # @end
