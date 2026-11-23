"""Examples for OidcKeyService, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import Tm1Client


def refresh(tm1: Tm1Client) -> None:
    # @start region = refresh
    # After the provider rotates its signing keys: drop every cached key, so TM1
    # fetches them again
    tm1.oidc_keys.remove(tm1.oidc_keys.keys())
    # @end
