"""Make and fill in the cogstartup.xml template the container runs Cognos from.

Usage:
  configure-cognos.py template <installed cogstartup.xml> <template>
  configure-cognos.py render <template> <cogstartup.xml>

template: IBM's post-install (discoverPorts) writes the installing machine's
  hostname into the dispatcher URIs and the configuration group, and leaves
  hostName empty. That hostname is replaced with HOST_MARKER, and hostName set to
  it, so the install works in any container.

render: writes the cogstartup.xml Cognos runs from, using environment variables:
  COGNOS_HOST (this container's hostname)   host in the dispatcher URIs, hostName
  COGNOS_CS_HOST, COGNOS_CS_PORT (5432), COGNOS_CS_NAME (cm), COGNOS_CS_SCHEMA (public),
  COGNOS_CS_USER (cognos), COGNOS_CS_PASSWORD (cognos)     PostgreSQL content store
  COGNOS_MAX_MEMORY (6144)                  dispatcher JVM heap, MB

Edits are textual, so IBM's comments and layout survive. cogconfig encrypts the
credentials when it saves the file.
"""
import os
import re
import socket
import sys
from xml.sax.saxutils import escape

HOST_MARKER = "cognos-install-host"
env = os.environ.get


def sub_once(pattern, repl, text, what):
    new, n = re.subn(pattern, repl, text, count=1, flags=re.S)
    if n != 1:
        raise SystemExit(f"cogstartup.xml: couldn't find {what}")
    return new


def make_template(xml):
    # The install host is whatever IBM's post-install put in the external dispatcher
    # URI (Java's idea of the local host name, which isn't always `hostname`).
    m = re.search(r'<crn:parameter name="externalDispatcher">\s*<crn:value [^>]*>https?://([^:/<]+):(\d+)/', xml)
    if not m:
        raise SystemExit("no external dispatcher URI in cogstartup.xml; did IBM's post-install run?")
    host, port = m.group(1), m.group(2)
    h = re.escape(host)
    # Only where the install host appears: dispatcher URIs (on the dispatcher's port,
    # so other localhost services keep theirs), the host-typed values (the member
    # coordination host), and the configuration group name.
    xml = re.sub(rf"(://){h}(:{port}[/<])", rf"\g<1>{HOST_MARKER}\g<2>", xml)
    xml = re.sub(rf'(xsi:type="cfg:(?:hostOnly|localHostOnly)">){h}<', rf"\g<1>{HOST_MARKER}<", xml)
    xml = re.sub(rf">GRP_{h}<", f">GRP_{HOST_MARKER}<", xml)
    xml = sub_once(r'(<crn:parameter name="hostName">\s*)<crn:value xsi:type="xsd:string"\s*(?:/>|>[^<]*</crn:value>)',
                   lambda mm: mm.group(1) + f'<crn:value xsi:type="xsd:string">{HOST_MARKER}</crn:value>',
                   xml, "hostName")
    print(f"cogstartup.xml template: replaced install hostname '{host}' with {HOST_MARKER}")
    return xml


def content_store():
    host = env("COGNOS_CS_HOST")
    if not host:
        raise SystemExit("COGNOS_CS_HOST is not set: Cognos needs a PostgreSQL content store")
    server = f"{host}:{env('COGNOS_CS_PORT') or '5432'}"
    return f"""<crn:instance class="PostgreSQL" name="Content Store">
                    <crn:parameter name="server">
                        <crn:value xsi:type="cfg:hostPort">{escape(server)}</crn:value>
                    </crn:parameter>
                    <crn:parameter name="user">
                        <crn:value xsi:type="cfg:credential">
                            <credential>
                                <username>{escape(env('COGNOS_CS_USER') or 'cognos')}</username>
                                <password>{escape(env('COGNOS_CS_PASSWORD') or 'cognos')}</password>
                            </credential>
                        </crn:value>
                    </crn:parameter>
                    <crn:parameter name="name">
                        <crn:value xsi:type="xsd:string">{escape(env('COGNOS_CS_NAME') or 'cm')}</crn:value>
                    </crn:parameter>
                    <crn:parameter name="schemaName">
                        <crn:value xsi:type="xsd:string">{escape(env('COGNOS_CS_SCHEMA') or 'public')}</crn:value>
                    </crn:parameter>
                </crn:instance>"""


def set_value(xml, name, value):
    return sub_once(
        rf'(<crn:parameter name="{name}">\s*<crn:value xsi:type="xsd:string")\s*(?:/>|>[^<]*</crn:value>)',
        lambda m: m.group(1) + ">" + escape(value) + "</crn:value>", xml, name)


def render(xml):
    if HOST_MARKER not in xml:
        raise SystemExit(f"template has no {HOST_MARKER}: was it made by 'configure-cognos.py template'?")
    host = env("COGNOS_HOST") or socket.gethostname()
    xml = xml.replace(HOST_MARKER, escape(host))
    # The server certificate Cognos generates must name the coordination host
    # (CAM-CRP-1655 otherwise). IBM's defaults name only localhost.
    xml = set_value(xml, "serverCommonName", host)
    xml = set_value(xml, "sanDNSName", " ".join(dict.fromkeys([host, "localhost"])))
    xml = sub_once(
        r'(<crn:instances name="database">.*?)<crn:instance [^>]*name="Content Store"[^>]*>.*?</crn:instance>',
        lambda m: m.group(1) + content_store(), xml, "the content store")
    memory = env("COGNOS_MAX_MEMORY") or "6144"
    if not memory.isdigit():
        raise SystemExit(f"COGNOS_MAX_MEMORY must be a number of MB, not '{memory}'")
    xml = sub_once(
        r'(<crn:parameter name="dispatcherMaxMemory">\s*<crn:value [^>]*>)\d+(</crn:value>)',
        lambda m: m.group(1) + memory + m.group(2), xml, "dispatcherMaxMemory")
    return xml


mode, src, dest = sys.argv[1:4]
text = open(src, encoding="utf-8").read()
if mode == "template":
    text = make_template(text)
elif mode == "render":
    text = render(text)
else:
    sys.exit(f"unknown mode {mode}")
open(dest, "w", encoding="utf-8").write(text)
