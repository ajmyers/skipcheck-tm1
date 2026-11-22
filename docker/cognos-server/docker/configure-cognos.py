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
  COGNOS_LDAP_HOST, COGNOS_LDAP_PORT (389), COGNOS_LDAP_BASE,
  COGNOS_LDAP_BIND_DN, COGNOS_LDAP_BIND_PASSWORD, COGNOS_NAMESPACE (LDAP)
                                            an LDAP namespace (when COGNOS_LDAP_HOST is set)
  COGNOS_OIDC_DISCOVERY_URL, COGNOS_OIDC_CLIENT_ID, COGNOS_OIDC_CLIENT_SECRET,
  COGNOS_OIDC_NAMESPACE (OIDC), COGNOS_OIDC_SCOPE (openid profile email),
  COGNOS_OIDC_CAMID_CLAIM (preferred_username), COGNOS_OIDC_GROUPS_CLAIM (groups),
  COGNOS_PUBLIC_URL (http://localhost:9300)
                                            an OIDC namespace (when COGNOS_OIDC_DISCOVERY_URL is set)
  COGNOS_ANONYMOUS (T, or F with a namespace)   allow anonymous access

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


def ldap_namespace():
    ns = env("COGNOS_NAMESPACE") or "LDAP"
    if not re.fullmatch(r"[A-Za-z0-9_]+", ns):
        raise SystemExit(f"COGNOS_NAMESPACE must be letters, digits and _, not '{ns}'")
    base = env("COGNOS_LDAP_BASE") or "dc=cognos,dc=local"
    lookup = env("COGNOS_LDAP_USER_LOOKUP") or f"uid=${{userID}},ou=people,{base}"
    params = [
        ("id", "xsd:string", ns),
        ("connection", "cfg:hostPort", f"{env('COGNOS_LDAP_HOST')}:{env('COGNOS_LDAP_PORT') or '389'}"),
        ("baseDN", "xsd:string", base),
        ("userLookup", "xsd:string", lookup),
        ("useBindCredentialsForSearch", "xsd:boolean", "true" if env("COGNOS_LDAP_BIND_DN") else "false"),
    ]
    xml = "".join(f"""
                    <crn:parameter name="{n}">
                        <crn:value xsi:type="{t}">{escape(v)}</crn:value>
                    </crn:parameter>""" for n, t, v in params)
    if env("COGNOS_LDAP_BIND_DN"):
        xml += f"""
                    <crn:parameter name="bindCredentials">
                        <crn:value xsi:type="cfg:credential">
                            <credential>
                                <username>{escape(env('COGNOS_LDAP_BIND_DN'))}</username>
                                <password>{escape(env('COGNOS_LDAP_BIND_PASSWORD') or '')}</password>
                            </credential>
                        </crn:value>
                    </crn:parameter>"""
    return f"""
                <crn:instance class="LDAP" name="{ns}">{xml}
                </crn:instance>"""


def oidc_namespace():
    ns = env("COGNOS_OIDC_NAMESPACE") or "OIDC"
    if not re.fullmatch(r"[A-Za-z0-9_]+", ns):
        raise SystemExit(f"COGNOS_OIDC_NAMESPACE must be letters, digits and _, not '{ns}'")
    for name in ("COGNOS_OIDC_CLIENT_ID", "COGNOS_OIDC_CLIENT_SECRET"):
        if not env(name):
            raise SystemExit(f"{name} is required with COGNOS_OIDC_DISCOVERY_URL")
    public = (env("COGNOS_PUBLIC_URL") or "http://localhost:9300").rstrip("/")
    # The claim users are known by (their CAMID). accountCamidProperty names a Cognos
    # account property, not a claim: "username", which acUsername maps to the claim.
    camid = env("COGNOS_OIDC_CAMID_CLAIM") or "preferred_username"
    params = [
        ("id", "xsd:string", ns),
        ("identityProviderType", "xsd:string", "Generic"),
        ("useDiscoveryEndpoint", "xsd:boolean", "true"),
        ("oidcDiscEndpoint", "cfg:anyPathURI", env("COGNOS_OIDC_DISCOVERY_URL")),
        ("clientId", "xsd:string", env("COGNOS_OIDC_CLIENT_ID")),
        ("clientSecret", "xsd:string", env("COGNOS_OIDC_CLIENT_SECRET")),
        ("tokenEndpointAuth", "xsd:string", "client_secret_post"),
        ("returnUrl", "cfg:anyPathURI", public + "/bi/completeAuth.jsp"),
        ("authScope", "xsd:string", env("COGNOS_OIDC_SCOPE") or "openid profile email"),
        ("accountClaims", "xsd:string", "token"),
        ("keyLocation", "xsd:string", "jwks_uri"),
        # CAMUsername/CAMPassword logons: a password grant at the provider
        ("pgStrategy", "xsd:string", "idToken"),
        ("tcStrategy", "xsd:string", "refreshToken"),
        ("accountCamidProperty", "xsd:string", "username"),
        ("acUsername", "xsd:string", camid),
        ("acEmail", "xsd:string", "email"),
        ("acName", "xsd:string", "name"),
        ("acGivenName", "xsd:string", "given_name"),
        ("acSurname", "xsd:string", "family_name"),
        ("acMemberOf", "xsd:string", env("COGNOS_OIDC_GROUPS_CLAIM") or "groups"),
    ]
    body = "".join(f"""
                    <crn:parameter name="{n}">
                        <crn:value xsi:type="{t}">{escape(v)}</crn:value>
                    </crn:parameter>""" for n, t, v in params)
    return f"""
                <crn:instance class="OIDC_Generic" name="{ns}">{body}
                </crn:instance>"""


def flag(name, default):
    value = (env(name) or default).upper()
    if value not in ("T", "F", "TRUE", "FALSE"):
        raise SystemExit(f"{name} must be T or F, not '{env(name)}'")
    return "true" if value.startswith("T") else "false"


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
    namespace = bool(env("COGNOS_LDAP_HOST") or env("COGNOS_OIDC_DISCOVERY_URL"))
    xml = sub_once(
        r'(<crn:instance class="Cognos" name="Cognos">(?:\s|<!--.*?-->)*<crn:parameter name="allowAnon">\s*<crn:value [^>]*>)\w+(</crn:value>)',
        lambda m: m.group(1) + flag("COGNOS_ANONYMOUS", "F" if namespace else "T") + m.group(2), xml, "allowAnon")
    providers = (ldap_namespace() if env("COGNOS_LDAP_HOST") else "") + \
        (oidc_namespace() if env("COGNOS_OIDC_DISCOVERY_URL") else "")
    if providers:
        xml = sub_once(r'(<crn:instances name="authProvider">.*?</crn:instance>)',
                       lambda m: m.group(1) + providers, xml, "the authentication providers")
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
