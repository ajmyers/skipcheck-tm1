"""Unpack the parts of selected installables from an IBM TM1 Web repository zip.

Usage: unpack-tm1-web.py <tm1web-*.zip> <install-dir> <installable> [...]

Mirrors what IBM's installer does: read the manifest inside the zip, follow each
installable's parts (and installablesRef), and extract each part's archive into
<install-dir>/<part.installpath>. Parts are read straight out of the repository zip,
so it never has to be extracted as a whole.
"""
import json
import os
import shutil
import stat
import sys
import tarfile
import tempfile
import zipfile

repo_path, target, *wanted = sys.argv[1:]
repo = zipfile.ZipFile(repo_path)
manifest_name = next(n for n in repo.namelist() if n.startswith("manifest/") and n.endswith(".json"))
manifest = json.load(repo.open(manifest_name))
print(f"Manifest: {manifest['productName']} {manifest['productVersion']} ({manifest['platform']})", flush=True)

installables = {i["name"]: i for i in manifest["installables"]}
seen_parts = set()


def extract_zip(zf, dest):
    """zipfile.extractall drops Unix permissions and symlinks; restore both."""
    for info in zf.infolist():
        mode = info.external_attr >> 16
        out = os.path.join(dest, info.filename)
        if stat.S_ISLNK(mode):
            os.makedirs(os.path.dirname(out), exist_ok=True)
            if os.path.lexists(out):
                os.remove(out)
            os.symlink(zf.read(info).decode(), out)
            continue
        zf.extract(info, dest)
        if mode and not info.is_dir():
            os.chmod(out, stat.S_IMODE(mode))


def extract(part):
    key = part["repositoryPath"]
    if key in seen_parts:
        return
    seen_parts.add(key)
    dest = os.path.normpath(os.path.join(target, part.get("installpath") or "./"))
    os.makedirs(dest, exist_ok=True)
    print(f"  - {part['name']} {part['version']} {part.get('classifier') or ''}", flush=True)
    if key.endswith((".tar.gz", ".tgz", ".tar")):
        with repo.open(key) as raw, tarfile.open(fileobj=raw, mode="r|*") as tar:
            tar.extractall(dest, filter="tar")
    elif key.endswith(".zip"):
        # A zip needs random access: copy the member out first.
        with tempfile.TemporaryFile() as tmp:
            with repo.open(key) as raw:
                shutil.copyfileobj(raw, tmp, 1 << 20)
            tmp.seek(0)
            with zipfile.ZipFile(tmp) as zf:
                extract_zip(zf, dest)
    else:
        raise SystemExit(f"Unknown archive type: {key}")


def install(name, depth=0):
    if name not in installables:
        raise SystemExit(f"No installable {name} in {manifest_name}; it has: {', '.join(installables)}")
    inst = installables[name]
    print("  " * depth + f"Installable {name}", flush=True)
    for part in inst.get("parts", []):
        extract(part)
    for ref in inst.get("installablesRef") or []:
        install(ref, depth + 1)


os.makedirs(target, exist_ok=True)
extract(manifest["license"])
for name in wanted:
    install(name)
print("Done.", flush=True)
