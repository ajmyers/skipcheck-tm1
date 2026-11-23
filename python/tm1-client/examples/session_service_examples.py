# SPDX-License-Identifier: Apache-2.0

"""Examples for SessionService, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import ODataQuery, Tm1Client
from skipcheck_tm1.model import Session, User


def list_sessions(tm1: Tm1Client) -> list[str]:
    # @start region = list
    # Every session on the server, with its user's name and its label
    with_users = ODataQuery.none().select(Session.ID, Session.CONTEXT).expand(Session.USER, lambda user: user.select(User.NAME))
    return [f"{session.id} {session.user.name if session.user else None} {session.context}" for session in tm1.sessions.list(with_users)]
    # @end


def close_labelled(tm1: Tm1Client) -> None:
    # @start region = close
    # Close the sessions labelled "Nightly load", except this client's own
    own = tm1.sessions.active_session(ODataQuery.none().select(Session.ID)).id
    labelled = tm1.sessions.list(ODataQuery.none().select(Session.ID).filter(Session.CONTEXT.eq("Nightly load")))
    for session in labelled:
        if session.id is not None and session.id != own:
            tm1.sessions.close(session.id)
    # @end
