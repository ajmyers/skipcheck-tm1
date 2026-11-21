"""Install selected installables from an IBM Cognos Analytics repository zip.

Usage: unpack-cognos.py <casrv-*.zip> <install-dir> <installable> [...]

Mirrors what IBM's installer does: read the manifest inside the zip, follow each
installable's parts (and installablesRef), and extract each part's archive into
<install-dir>/<part.installpath>. Parts are read straight out of the repository
zip, so it never has to be extracted as a whole (it is several GB).

Like the installer, it leaves a copy of the manifest in <install-dir>/uninstall/logs/,
where IBM's post-install steps look for it. The manifest's file name is written to
<install-dir>/.cognos-server/manifest-name.
"""
import json
import os
import shutil
import stat
import sys
import tarfile
import tempfile
import time
import zipfile

repo_path, target, *wanted = sys.argv[1:]
repo = zipfile.ZipFile(repo_path)
manifest_name = next(n for n in repo.namelist() if n.startswith("manifest/") and n.endswith(".json"))
manifest = json.load(repo.open(manifest_name))
print(f"Manifest: {manifest['productName']} {manifest['productVersion']} ({manifest['platform']})", flush=True)

missing = [n for n in wanted if n not in {i["name"] for i in manifest["installables"]}]
if missing:
    raise SystemExit(f"Not in this manifest: {', '.join(missing)}")
installables = {i["name"]: i for i in manifest["installables"]}
seen_parts = set()


def inside(dest, path):
    """True if path, with any symlinks in its directories resolved, is under dest."""
    root = os.path.realpath(dest)
    return os.path.commonpath([root, os.path.realpath(os.path.dirname(path))]) == root


def extract_zip(zf, dest):
    """zipfile.extractall drops Unix permissions and symlinks; restore both.

    Member names must be relative and stay inside dest, also through symlinks
    unpacked earlier (the same rule as tarfile's "tar" filter)."""
    for info in zf.infolist():
        name = info.filename
        if os.path.isabs(name) or ".." in name.replace("\\", "/").split("/"):
            raise SystemExit(f"unsafe path in a part: {name}")
        mode = info.external_attr >> 16
        out = os.path.join(dest, name)
        if not inside(dest, out):
            raise SystemExit(f"path escapes the install directory: {name}")
        if stat.S_ISLNK(mode):
            os.makedirs(os.path.dirname(out), exist_ok=True)
            if os.path.lexists(out):
                os.remove(out)
            os.symlink(zf.read(info).decode(), out)
            continue
        written = zf.extract(info, dest)
        if mode and not info.is_dir():
            os.chmod(written, stat.S_IMODE(mode))


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
        # A plain file (a jar, say): copy it into place under its own name.
        with repo.open(key) as raw, open(os.path.join(dest, os.path.basename(key)), "wb") as out:
            shutil.copyfileobj(raw, out, 1 << 20)


def install(name, depth=0):
    inst = installables[name]
    print("  " * depth + f"Installable {name}", flush=True)
    for part in inst.get("parts", []):
        extract(part)
    for ref in inst.get("installablesRef") or []:
        install(ref, depth + 1)


start = time.time()
os.makedirs(target, exist_ok=True)
extract(manifest["license"])
extract(manifest["installResource"])
for name in wanted:
    install(name)
logs = os.path.join(target, "uninstall", "logs")
os.makedirs(logs, exist_ok=True)
manifest_file = os.path.basename(manifest_name)
with repo.open(manifest_name) as raw, open(os.path.join(logs, manifest_file), "wb") as out:
    shutil.copyfileobj(raw, out)
os.makedirs(os.path.join(target, ".cognos-server"), exist_ok=True)
with open(os.path.join(target, ".cognos-server", "manifest-name"), "w") as out:
    out.write(manifest_file + "\n")
print(f"Done: {len(seen_parts)} parts in {time.time() - start:.0f}s.", flush=True)
