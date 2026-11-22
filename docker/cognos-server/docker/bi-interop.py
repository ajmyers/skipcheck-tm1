"""Install IBM's Planning Analytics interoperability files into Cognos.

Usage: bi-interop.py <folder with IBM downloads> <Cognos install> <URL> [URL ...]

PAW and TM1 Web in Cognos (CAM) security send browsers to Cognos to log on, through
Cognos's portal "bridge", which hands the logon back to them with pmhub.html. IBM
ships those files in bi_interop.zip:
  webcontent/pmhub.html, planning.html, tm1web/web/tm1web.html, images
  templates/ps/portal/variables_plan.xml, variables_TM1.xml
and pmhub.html only hands logons back to the addresses in its pmhubURLs list, which
IBM leaves as a placeholder. This installs the files and sets that list to the URLs
given: what browsers use for PAW and TM1 Web, e.g. http://localhost:8800.

bi_interop.zip is IBM software, so this image doesn't carry it. It's looked for in the
downloads folder: on its own, or inside the TM1 Server bundle
(tm1server-*_bundle.tar.gz or its tm1server-*-linuxi38664h.zip), where it's the
biinterop part of the PAA_Agent installable.
"""
import glob
import io
import json
import os
import re
import shutil
import sys
import tarfile
import tempfile
import zipfile

downloads, cognos, *urls = sys.argv[1:]
URL = re.compile(r"^https?://[A-Za-z0-9.-]+(:[0-9]+)?$")


def log(message):
    print(f"[cognos-bi-interop] {message}", flush=True)


def from_repository(repo):
    """bi_interop.zip's bytes from a TM1 repository zip, or None."""
    manifest = next((n for n in repo.namelist() if n.startswith("manifest/") and n.endswith(".json")), None)
    if not manifest:
        return None
    parts = [p for i in json.load(repo.open(manifest))["installables"] for p in i.get("parts", [])
             if p["name"] == "biinterop"]
    if not parts:
        return None
    with repo.open(parts[0]["repositoryPath"]) as raw, tarfile.open(fileobj=raw, mode="r|*") as tar:
        for member in tar:
            if member.name.endswith("bi_interop.zip"):
                return tar.extractfile(member).read()
    return None


def find():
    for path in sorted(glob.glob(os.path.join(downloads, "bi_interop*.zip")), reverse=True):
        return open(path, "rb").read(), path
    for path in sorted(glob.glob(os.path.join(downloads, "tm1server-*-linuxi38664h.zip")), reverse=True):
        with zipfile.ZipFile(path) as repo:
            data = from_repository(repo)
            if data:
                return data, path
    for path in sorted(glob.glob(os.path.join(downloads, "tm1server-*_bundle.tar.gz")), reverse=True):
        with tarfile.open(path) as bundle:
            for member in bundle:
                if member.name.endswith("-linuxi38664h.zip"):
                    with tempfile.TemporaryFile() as tmp:
                        shutil.copyfileobj(bundle.extractfile(member), tmp)
                        tmp.seek(0)
                        with zipfile.ZipFile(tmp) as repo:
                            data = from_repository(repo)
                    if data:
                        return data, path
    return None, None


for url in urls:
    if not URL.match(url):
        sys.exit(f"COGNOS_PMHUB_URLS: '{url}' isn't a URL like http://localhost:8800 (no path)")
data, source = find()
if not data:
    log(f"WARNING: no bi_interop.zip in {downloads} (on its own, or in a tm1server bundle). PAW and TM1 Web "
        "logons through Cognos won't come back to them.")
    sys.exit(0)
installed = 0
with zipfile.ZipFile(io.BytesIO(data)) as z:
    for name in z.namelist():
        if name.endswith("/") or not name.startswith(("webcontent/", "templates/")) or ".." in name.split("/"):
            continue
        target = os.path.join(cognos, name)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        content = z.read(name)
        if name == "webcontent/pmhub.html":
            text, n = re.subn(r"var pmhubURLs = \[[^\]]*\];", lambda m: "var pmhubURLs = " + json.dumps(urls) + ";",
                              content.decode("utf-8"))
            if n != 1:
                sys.exit("pmhub.html: couldn't find its pmhubURLs list")
            content = text.encode("utf-8")
        with open(target, "wb") as out:
            out.write(content)
        installed += 1
log(f"Installed {installed} interoperability files from {os.path.basename(source)}; logons go back to "
    + ", ".join(urls))
