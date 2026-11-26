# SPDX-License-Identifier: Apache-2.0

"""Unpack the parts of selected installables from an IBM TM1 repository zip.

Usage: unpack-tm1.py <extracted-repo-dir> <install-dir> <installable> [...]
       unpack-tm1.py --check <extracted-repo-dir> <installable> [...]

Mirrors what IBM's installer does: read manifest/*/*.json, follow each
installable's parts (and installablesRef), and extract each part's archive into
<install-dir>/<part.installpath>. --check only checks the manifest has what's
asked for, so a bad list can be refused before an earlier install is removed.

An installable named with a trailing ? (TM1_NON-PROD_SWTAG?) is optional: a
bundle whose manifest doesn't have it installs without it. Planning Analytics
2.1.8 to 2.1.10 have no software tags (TM1_PROD_SWTAG, TM1_NON-PROD_SWTAG come
in 2.1.11). Any other name the manifest lacks, asked for or referred to by an
installable asked for, stops it before anything is unpacked.
"""
import glob
import json
import os
import sys
import tarfile
import zipfile

args = sys.argv[1:]
check_only = args[:1] == ["--check"]
if check_only:
    repo, *wanted = args[1:]
    target = None
else:
    repo, target, *wanted = args
manifest_path = glob.glob(os.path.join(repo, "manifest", "*", "*", "*.json"))[0]
manifest = json.load(open(manifest_path))
print(f"Manifest: {manifest['productName']} {manifest['productVersion']} ({manifest['platform']})")

installables = {i["name"]: i for i in manifest["installables"]}
optional = {name.rstrip("?") for name in wanted if name.endswith("?")}
wanted = [name.rstrip("?") for name in wanted]


def missing_from(names, missing, seen):
    """The names the manifest lacks, among these and the installables they refer to."""
    for name in names:
        if name in seen:
            continue
        seen.add(name)
        if name not in installables:
            missing.append(name)
        else:
            missing_from(installables[name].get("installablesRef") or [], missing, seen)
    return missing


missing = missing_from([n for n in wanted if n in installables or n not in optional], [], set())
if missing:
    raise SystemExit(
        f"This bundle has no {', '.join(missing)} (it has {', '.join(installables)})"
    )
if check_only:
    sys.exit(0)

seen_parts = set()


def extract(part):
    key = part["repositoryPath"]
    if key in seen_parts:
        return
    seen_parts.add(key)
    src = os.path.join(repo, key)
    dest = os.path.normpath(os.path.join(target, part.get("installpath") or "./"))
    os.makedirs(dest, exist_ok=True)
    print(f"  - {part['name']} {part['version']} {part.get('classifier', '')}")
    if src.endswith((".tar.gz", ".tgz", ".tar")):
        with tarfile.open(src) as tar:
            tar.extractall(dest, filter="tar")
    elif src.endswith(".zip"):
        with zipfile.ZipFile(src) as zf:
            zf.extractall(dest)
    else:
        raise SystemExit(f"Unknown archive type: {src}")


def install(name, depth=0):
    inst = installables[name]
    print("  " * depth + f"Installable {name}")
    for part in inst.get("parts", []):
        extract(part)
    for ref in inst.get("installablesRef") or []:
        install(ref, depth + 1)


os.makedirs(target, exist_ok=True)
extract(manifest["license"])
for name in wanted:
    if name in installables:
        install(name)
    else:
        print(f"Installable {name}: not in this bundle, skipped (optional)")

# Same bookkeeping IBM's unixPostInstall.sh does
pa = os.path.join(target, "configuration", "pa_2.1.txt")
ver = os.path.join(target, "tm1server_version.txt")
if os.path.exists(pa) and os.path.exists(ver):
    with open(os.path.join(target, "versions.txt"), "w") as out:
        out.write(open(pa).read() + open(ver).read())
print("Done.")
