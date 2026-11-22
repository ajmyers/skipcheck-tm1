"""Turn a list of test users into LDIF for the embedded LDAP directory.

Usage: users.py <base DN>    (writes LDIF to stdout, a summary to stderr)

Users come from COGNOS_USERS and/or the file named by COGNOS_USERS_FILE, one
user per entry:

    user:password[:group,group,...]

Entries are separated by whitespace or newlines. In a file, # at the start of a
line or after whitespace starts a comment (so passwords may contain #).
User and group names are letters, digits, '.', '_' and '-'. A password can't
contain ':' or whitespace.

Users go under ou=people (uid=<user>), groups under ou=groups as
groupOfUniqueNames, the object classes Cognos's LDAP namespace expects by default.
"""
import base64
import hashlib
import os
import re
import sys

NAME = re.compile(r"[A-Za-z0-9._-]+")


def entries():
    yield from os.environ.get("COGNOS_USERS", "").split()
    path = os.environ.get("COGNOS_USERS_FILE")
    if path:
        try:
            text = open(path, encoding="utf-8").read()
        except OSError as e:
            sys.exit(f"can't read COGNOS_USERS_FILE {path}: {e.strerror}")
        for line in text.splitlines():
            yield from re.sub(r"(^|\s)#.*", "", line).split()


def ssha(password):
    salt = os.urandom(8)
    return "{SSHA}" + base64.b64encode(hashlib.sha1(password.encode() + salt).digest() + salt).decode()


base = sys.argv[1]
users, groups = {}, {}
for entry in entries():
    parts = entry.split(":")
    if len(parts) not in (2, 3) or not parts[0] or not parts[1]:
        sys.exit(f"bad user entry '{parts[0]}:...': expected user:password[:group,group]")
    user, password = parts[0], parts[1]
    if not NAME.fullmatch(user):
        sys.exit(f"bad user name '{user}': use letters, digits, '.', '_' and '-'")
    if user in users:
        sys.exit(f"user '{user}' is listed twice")
    member_of = [g for g in (parts[2].split(",") if len(parts) == 3 else []) if g]
    for g in member_of:
        if not NAME.fullmatch(g):
            sys.exit(f"bad group name '{g}' for user '{user}': use letters, digits, '.', '_' and '-'")
        groups.setdefault(g, []).append(user)
    users[user] = password

if not users:
    sys.exit("no users given (COGNOS_USERS or COGNOS_USERS_FILE)")

dc = base.split(",")[0].split("=", 1)[1]
out = [f"dn: {base}", "objectClass: top", "objectClass: dcObject", "objectClass: organization",
       f"dc: {dc}", f"o: {dc}", "",
       f"dn: ou=people,{base}", "objectClass: organizationalUnit", "ou: people", "",
       f"dn: ou=groups,{base}", "objectClass: organizationalUnit", "ou: groups", ""]
for user, password in users.items():
    out += [f"dn: uid={user},ou=people,{base}", "objectClass: inetOrgPerson", f"uid: {user}",
            f"cn: {user}", f"sn: {user}", f"mail: {user}@example.com", f"userPassword: {ssha(password)}", ""]
for group, members in groups.items():
    out += [f"dn: cn={group},ou=groups,{base}", "objectClass: groupOfUniqueNames", f"cn: {group}"]
    out += [f"uniqueMember: uid={m},ou=people,{base}" for m in members] + [""]
print("\n".join(out))
summary = ", ".join(f"{u}{' (' + ', '.join(g for g, ms in groups.items() if u in ms) + ')' if any(u in ms for ms in groups.values()) else ''}"
                    for u in users)
print(f"{len(users)} user(s): {summary}", file=sys.stderr)
