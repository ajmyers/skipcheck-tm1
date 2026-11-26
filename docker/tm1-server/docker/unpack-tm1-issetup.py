# SPDX-License-Identifier: Apache-2.0

"""Detect or install IBM's older full TM1 installer (tm1_linuxi38664h_<version>_ml.tar.gz).

Usage:
  unpack-tm1-issetup.py detect <bundle.tar.gz>             exit 0 if it is one, 1 if not
  unpack-tm1-issetup.py install <bundle.tar.gz> <target>   install it into <target>

Planning Analytics 2.1.2 to 2.1.7 come only in this format: an issetup installer with
no manifest for unpack-tm1.py to follow. Its components are zipfiles/linuxi38664h/
<name>-linuxi38664h-<app|gate>-<version>-inst.tar.gz, each rooted at the install folder.
What IBM's installer does for a server install is, in effect, to unpack them all into one
folder; the language packs only add translated messages, so they're left out. Components
are unpacked in name order, so the result doesn't depend on the archive's order.
"""
import os
import re
import shutil
import sys
import tarfile
import tempfile

COMPONENTS = "zipfiles/linuxi38664h/"
SERVER = re.compile(r"tm1server-linuxi38664h-app-([0-9.]+)-inst\.tar\.gz$")
# The product version, from the version component's name (2.1.7.1 is Planning Analytics 2.1.7).
# Not from the bin64/version.xml it carries: 2.1.7's still says 2.1.2.
PRODUCT = re.compile(r"verxml-linuxi38664h-app-(\d+\.\d+\.\d+)(?:\.\d+)*-inst\.tar\.gz$")


def components(name):
    """The component tarball a member is, or None: not one, or a language pack."""
    if not name.startswith(COMPONENTS) or not name.endswith("-inst.tar.gz"):
        return None
    base = name[len(COMPONENTS):]
    if "/" in base or base.startswith("langpack_"):
        return None
    return base


INSTALLER_DIRS = ("zipfiles", "linuxi38664h")


def detect(bundle):
    """Whether it's an issetup installer, from its first entries: its components are under
    zipfiles/ and its installer under linuxi38664h/. A tm1server bundle's first entry is a file
    (its zip, or IBM's installer binary), so this reads one header for it, not the archive."""
    try:
        with tarfile.open(bundle, "r|gz") as tar:
            for member in tar:
                name = member.name.removeprefix("./").rstrip("/")
                if name.split("/")[0] in INSTALLER_DIRS:
                    return True
                if member.isfile():
                    return False
    except (tarfile.TarError, EOFError, OSError):
        return False  # not a tar at all: install-tm1.sh says so when it tries the other format
    return False


def install(bundle, target):
    work = tempfile.mkdtemp(dir=os.environ.get("TMPDIR"))
    try:
        found = []
        with tarfile.open(bundle, "r|gz") as tar:
            for member in tar:
                base = components(member.name.removeprefix("./"))
                if base is None or not member.isfile():
                    continue
                with tar.extractfile(member) as src, open(os.path.join(work, base), "wb") as dst:
                    shutil.copyfileobj(src, dst)
                found.append(base)
        if not any(SERVER.match(base) for base in found):
            raise SystemExit("no tm1server-linuxi38664h-app component in " + bundle)
        print(f"Installer with {len(found)} components (language packs left out)")
        os.makedirs(target, exist_ok=True)
        for base in sorted(found):
            print("  - " + base.removesuffix("-inst.tar.gz"))
            with tarfile.open(os.path.join(work, base)) as tar:
                tar.extractall(target, filter="tar")
        write_versions(target, found)
    finally:
        shutil.rmtree(work, ignore_errors=True)


def write_versions(target, found):
    """tm1server_version.txt and versions.txt, as the newer bundles have them: 2.1.2's components
    don't write the first, and none writes the second."""
    version_file = os.path.join(target, "tm1server_version.txt")
    if not os.path.exists(version_file):
        server = next(SERVER.match(b).group(1) for b in found if SERVER.match(b))
        with open(version_file, "w") as out:
            out.write(f"tm1server = {server}\n")
    pa = next((PRODUCT.match(b).group(1) for b in found if PRODUCT.match(b)), None)
    with open(os.path.join(target, "versions.txt"), "w") as out:
        out.write((f"Planning Analytics {pa}\n" if pa else "") + open(version_file).read())


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "detect":
        sys.exit(0 if detect(sys.argv[2]) else 1)
    if len(sys.argv) == 4 and sys.argv[1] == "install":
        install(sys.argv[2], sys.argv[3])
        print("Done.")
        sys.exit(0)
    raise SystemExit(__doc__)
