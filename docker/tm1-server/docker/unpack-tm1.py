# SPDX-License-Identifier: Apache-2.0

"""Unpack the parts of selected installables from an IBM TM1 repository zip.

Usage: unpack-tm1.py <extracted-repo-dir> <install-dir> <installable> [...]

Mirrors what IBM's installer does: read manifest/*/*.json, follow each
installable's parts (and installablesRef), and extract each part's archive into
<install-dir>/<part.installpath>.
"""
import glob
import json
import os
import sys
import tarfile
import zipfile

repo, target, *wanted = sys.argv[1:]
manifest_path = glob.glob(os.path.join(repo, "manifest", "*", "*", "*.json"))[0]
manifest = json.load(open(manifest_path))
print(f"Manifest: {manifest['productName']} {manifest['productVersion']} ({manifest['platform']})")

installables = {i["name"]: i for i in manifest["installables"]}
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
    install(name)

# Same bookkeeping IBM's unixPostInstall.sh does
pa = os.path.join(target, "configuration", "pa_2.1.txt")
ver = os.path.join(target, "tm1server_version.txt")
if os.path.exists(pa) and os.path.exists(ver):
    with open(os.path.join(target, "versions.txt"), "w") as out:
        out.write(open(pa).read() + open(ver).read())
print("Done.")
