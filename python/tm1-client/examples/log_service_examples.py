# SPDX-License-Identifier: Apache-2.0

"""Examples for LogService, quoted in the guide."""

from __future__ import annotations

from collections.abc import Iterator

from skipcheck_tm1 import ODataQuery, Tm1Client
from skipcheck_tm1.model import LogLevel, MessageLogEntry


def newest(tm1: Tm1Client) -> list[MessageLogEntry]:
    # @start region = newest
    # The 50 newest entries, newest first
    return tm1.logs.message_log(True, ODataQuery.none().top(50))
    # @end


def follow(tm1: Tm1Client) -> Iterator[str]:
    # @start region = follow
    # Errors as they're written, until the caller stops iterating; a poll every 5 s
    for entry in tm1.logs.tail_message_log(5):
        if entry.level is LogLevel.ERROR:
            yield f"{entry.logger}: {entry.message}"
    # @end


def process_errors(tm1: Tm1Client) -> list[str]:
    # @start region = process-errors
    # What each error log of one process says
    files = [file.filename for file in tm1.logs.error_log_files() if file.filename and file.filename.endswith("_Load Sales.log")]
    return [tm1.logs.error_log_content(filename) for filename in files]
    # @end


def changes(tm1: Tm1Client) -> int:
    # @start region = changes
    # How many cell changes the transaction log holds, leaving out its status lines
    return len(tm1.logs.transaction_log(False, True))
    # @end
