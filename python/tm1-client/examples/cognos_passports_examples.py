# SPDX-License-Identifier: Apache-2.0

"""Examples for CognosPassports, quoted in the guide."""

from __future__ import annotations

from collections.abc import Callable

from skipcheck_tm1 import Tm1Client, auth


def logon(password: str) -> Tm1Client:
    # @start region = logon
    # The client logs on to Cognos for a passport at every TM1 login, so TM1 never
    # sees the password and an expired passport is never reused
    cognos = auth.cognos_logon("https://cognos.example.com:9300", "LDAP", "alice", password)
    return Tm1Client.builder().address("https://tm1.example.com:8010").authenticator(cognos).build()
    # @end


def id_token(current_id_token: Callable[[], str]) -> Tm1Client:
    # @start region = id-token
    # An application that signed its user in with OpenID Connect hands over their ID
    # token; Cognos's OIDC namespace turns it into a passport
    cognos = auth.cognos_id_token("https://cognos.example.com:9300", "OIDC", current_id_token)
    return Tm1Client.builder().address("https://tm1.example.com:8010").authenticator(cognos).build()
    # @end
