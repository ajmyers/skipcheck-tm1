# SPDX-License-Identifier: Apache-2.0

"""Examples for Tm1Authenticator (the auth module), quoted in the guide."""

from __future__ import annotations

from collections.abc import Callable

from skipcheck_tm1 import auth
from skipcheck_tm1.auth import Authenticator


def kinds(passports: Callable[[], str]) -> list[Authenticator]:
    # @start region = kinds
    # TM1's own security
    basic = auth.basic("admin", "apple")
    # CAM, with a user and password
    cam = auth.cam_namespace("alice", "secret", "LDAP")
    # CAM, with a passport
    passport = auth.cam_passport(passports)
    # A passport from Cognos
    cognos = auth.cognos_logon("http://cognos:9300", "LDAP", "alice", "secret")
    # @end
    return [basic, cam, passport, cognos]
