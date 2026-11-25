# SPDX-License-Identifier: Apache-2.0

"""Unpack IBM's TM1 v12 installer (tm1-v<ver>-standalone-installer-linux.run) without running it.

Usage: unpack-tm1-engine.py <installer.run> <dir>

The installer is a Makeself archive: a shell script, then a gzipped tar from line skip+1
on, with the tar's SHA-256 in the script. This checks the SHA-256 and extracts the tar.

In Python rather than with tar or by running the installer: Makeself's own unpacking
uses dd in ways newer Ubuntus' dd (uutils) doesn't take, and GNU tar 1.35 opens files
with openat2, which emulators such as Rosetta don't have.
"""
import hashlib
import re
import sys
import tarfile

run, target = sys.argv[1:]
with open(run, "rb") as file:
    header = file.read(64 * 1024)
    skip = re.search(rb'^skip="(\d+)"$', header, re.M)
    if not header.startswith(b"#!") or not skip:
        raise SystemExit("not a TM1 v12 installer (no Makeself header)")
    offset = 0
    for _ in range(int(skip.group(1))):
        end = header.find(b"\n", offset)
        if end < 0:
            raise SystemExit("not a TM1 v12 installer (its header is cut short)")
        offset = end + 1
    expected = re.search(rb'^SHA="([0-9a-f]{64})"$', header, re.M)
    if expected:
        file.seek(offset)
        digest = hashlib.sha256()
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
        if digest.hexdigest() != expected.group(1).decode():
            raise SystemExit("the installer is damaged (its checksum doesn't match)")
    file.seek(offset)
    with tarfile.open(fileobj=file, mode="r|gz") as tar:
        tar.extractall(target, filter="tar")
