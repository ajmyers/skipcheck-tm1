# SPDX-License-Identifier: Apache-2.0

"""Apply settings to a tm1web_config.xml, keeping the rest of the file as it is.

Usage: configure-tm1-web.py <tm1web_config.xml>

Settings come from the environment:
  TM1_ADMIN_HOST       AdminHostName  (the Admin Server TM1 Web lists servers from)
  TM1_ADMIN_PORT       AdminHostPort
  TM1_WEB_SERVER_NAME  TM1ServerName  (log on to this server without asking)
  TM1_WEB_CFG_<Key>    any appSettings key, e.g. TM1_WEB_CFG_HttpSessionTimeout=30

Only settings that are set are applied. Each change is printed. The file is edited
as text, not re-serialized, so IBM's comments and layout survive.
"""
import os
import re
import sys
from xml.sax.saxutils import quoteattr

path = sys.argv[1]
text = open(path, encoding="utf-8").read()

named = {
    "AdminHostName": "TM1_ADMIN_HOST",
    "AdminHostPort": "TM1_ADMIN_PORT",
    "TM1ServerName": "TM1_WEB_SERVER_NAME",
}
settings = []
for key, var in named.items():
    if os.environ.get(var):
        settings.append((key, os.environ[var], var))
for var, value in sorted(os.environ.items()):
    if var.startswith("TM1_WEB_CFG_") and len(var) > len("TM1_WEB_CFG_"):
        settings.append((var[len("TM1_WEB_CFG_"):], value, var))


def setting(key):
    # <add key="Key" value="..." />, with either quote style (IBM uses both)
    return re.compile(r'(<add\s+key\s*=\s*"' + re.escape(key) + r'"\s+value\s*=\s*)("[^"]*"|\'[^\']*\')', re.I)


for key, value, var in settings:
    pattern = setting(key)
    match = pattern.search(text)
    if match:
        old = match.group(2)[1:-1]
        if old == value:
            continue
        text = text[:match.start(2)] + quoteattr(value) + text[match.end(2):]
        print(f"  {key}={value}  (was: {old or 'blank'})  [{var}]", flush=True)
    else:
        if "</appSettings>" not in text:
            raise SystemExit(f"{path} has no <appSettings> to add {key} to")
        text = text.replace("</appSettings>", f"    <add key={quoteattr(key)} value={quoteattr(value)} />\n    </appSettings>", 1)
        print(f"  {key}={value}  (added)  [{var}]", flush=True)

open(path, "w", encoding="utf-8").write(text)
