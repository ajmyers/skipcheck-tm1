// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { basename, dirname, resolve } from "node:path";
import { parseDocument, type Tags } from "yaml";
import { CognosContainer } from "./CognosContainer.js";
import type { PlanningAnalyticsEnvironment } from "./PlanningAnalyticsEnvironment.js";
import { IllegalArgumentException } from "./settings.js";
import { Tm1Seed } from "./Tm1Seed.js";

const NAME = /^[A-Za-z0-9_-]+$/;
const USER = /^[A-Za-z0-9._-]+$/;
const SEEDS = ["empty", "sdata", "24retail"];
const BOOL = "tag:yaml.org,2002:bool";

/** The file's settings each part's source and image come from.
 * @internal */
export interface FileSources {
  /** The folder of IBM downloads, absolute. */
  ibm?: string;
  /** PAW's zip in {@link ibm}, or `undefined` for the newest there. */
  pawZip?: string;
  /** TM1's image, for the TM1 servers and the Admin Server. */
  tm1Image?: string;
  /** TM1 Web's image. */
  tm1WebImage?: string;
  /** Cognos's image. */
  cognosImage?: string;
  /** Keycloak's image. */
  keycloakImage?: string;
}

interface Server {
  port: number;
  seed: string;
  cfg: Map<string, string>;
}

interface Cognos {
  port: number;
  users: CognosContainer.User[];
  keycloakPort: number | undefined;
  namespace: CognosContainer.Namespace;
  admins: string[];
  memory: number;
}

/** Reads and checks the file, and configures `builder` from it.
 * @internal
 * @throws IllegalArgumentException if the file can't be read, isn't valid, or asks for what the
 * environment can't run */
export function readPaEnvironment(path: string, builder: PlanningAnalyticsEnvironment.Builder): PlanningAnalyticsEnvironment.Builder {
  const file = new PaEnvironmentFile(basename(path));
  let text: string;
  try {
    text = readFileSync(path, "utf8");
  } catch (e) {
    if ((e as NodeJS.ErrnoException).code === "ENOENT") throw file.error("not found: " + resolve(path));
    throw file.error("can't be read: " + (e instanceof Error ? e.message : String(e)));
  }
  let cfg: unknown;
  try {
    cfg = load(text);
  } catch (e) {
    throw file.error("isn't valid YAML: " + (e instanceof Error ? e.message : String(e)));
  }
  return file.apply(cfg ?? new Map(), dirname(resolve(path)), builder);
}

/** YAML as PyYAML's `safe_load` reads it: YAML 1.1, so `yes` and `off` are booleans; ints apart
 * from floats (as bigints); mappings as Maps, keeping their keys' types; a repeated key's last
 * value. */
function load(text: string): unknown {
  const doc = parseDocument(text, { version: "1.1", intAsBigInt: true, uniqueKeys: false, customTags: pyyamlBooleans });
  if (doc.errors.length > 0) throw doc.errors[0]!;
  return doc.toJS({ mapAsMap: true }) as unknown;
}

/** PyYAML's booleans: yaml's YAML 1.1 also takes `y` and `n`, which PyYAML reads as strings. */
function pyyamlBooleans(tags: Tags): Tags {
  return tags.map((t) => {
    if (typeof t !== "object" || t.tag !== BOOL || !("test" in t) || !t.test) return t;
    return { ...t, test: t.test.test("yes") ? /^(?:[Yy]es|YES|[Tt]rue|TRUE|[Oo]n|ON)$/ : /^(?:[Nn]o|NO|[Ff]alse|FALSE|[Oo]ff|OFF)$/ };
  });
}

/** Reads pa-setup's `pa-environment.yaml` onto a {@link PlanningAnalyticsEnvironment.Builder}, as
 * the Java `PaEnvironmentFile`. The checks and defaults are `docker/pa-setup/pa_setup.py`'s
 * (`normalize`), in its order and with its messages, so a file one accepts the other does too,
 * except for what the environment can't run, which fails here with a message naming the setting. */
class PaEnvironmentFile {
  constructor(readonly file: string) {}

  apply(root: unknown, dir: string, b: PlanningAnalyticsEnvironment.Builder): PlanningAnalyticsEnvironment.Builder {
    // pa_setup.normalize: the same checks, in the same order
    const cfg = this.keys(root, ["version", "name", "ibm", "files", "images", "tm1", "tm1Web", "paw", "cognos"], "the file");
    if (cfg.get("version") !== 1n) throw this.error("version: must be 1");
    const ibm = cfg.get("ibm") != null && truthy(cfg.get("ibm")) ? this.hostPath(cfg.get("ibm"), "ibm", dir) : undefined;
    this.hostPath(or(cfg.get("files"), "pa-files"), "files", dir);
    const images = this.keys(or(cfg.get("images"), new Map()), ["tm1Server", "tm1Web", "cognosServer", "keycloak"], "images");

    const tm1 = this.section(cfg, "tm1");
    const servers = new Map<string, Server>();
    let admin: number | undefined;
    if (tm1 !== undefined) {
      this.keys(tm1, ["servers", "adminServer", "adminPort"], "tm1");
      const named = tm1.get("servers");
      if (!(named instanceof Map) || named.size === 0) throw this.error("tm1.servers: name at least one server, e.g. tm1: {servers: {tm1: {}}}");
      const usedPorts = new Set<number>();
      const lower = new Set<string>();
      let i = 0;
      for (const [key, value] of named as Map<unknown, unknown>) {
        const where = "tm1.servers." + str(key);
        if (typeof key !== "string" || !NAME.test(key)) throw this.error(where + ": server names are letters, digits, _ and -");
        if (lower.has(key.toLowerCase())) throw this.error(where + ": two servers differ only in case");
        lower.add(key.toLowerCase());
        const s = this.keys(or(value, new Map()), ["port", "seed", "cfg"], where);
        const p = this.port(s.get("port"), where, 8010 + i);
        if (usedPorts.has(p)) throw this.error(where + ": port " + p + " is used twice");
        usedPorts.add(p);
        const seed = s.get("seed") != null && truthy(s.get("seed")) ? str(s.get("seed")) : "empty";
        if (!SEEDS.includes(seed)) throw this.error(where + ".seed: one of " + SEEDS.join(", "));
        const extra = this.mapping(or(s.get("cfg"), new Map()), where + ".cfg");
        const settings = new Map<string, string>();
        for (const [k, v] of extra) {
          const name = str(k);
          if (!NAME.test(name)) throw this.error(where + ".cfg: '" + name + "' isn't a tm1s.cfg parameter name");
          settings.set(name, this.cfgValue(v, where + ".cfg." + name));
        }
        servers.set(key, { port: p, seed, cfg: settings });
        i++;
      }
      const wantsAdmin = tm1.get("adminServer");
      if (wantsAdmin === true || (wantsAdmin == null && servers.size > 1)) admin = this.port(tm1.get("adminPort"), "tm1.adminPort", 5895);
      else if (wantsAdmin === false && servers.size > 1) throw this.error("tm1.adminServer: several servers need a shared Admin Server");
    }

    const web = this.section(cfg, "tm1Web");
    let webPort: number | undefined;
    let heap: string | undefined;
    if (web !== undefined) {
      this.keys(web, ["port", "heap"], "tm1Web");
      if (servers.size === 0) throw this.error("tm1Web: needs a TM1 server (tm1.servers)");
      webPort = this.port(web.get("port"), "tm1Web", 9510);
      heap = web.get("heap") != null && truthy(web.get("heap")) ? str(web.get("heap")) : "1g";
    }

    const cognos = this.section(cfg, "cognos");
    let cam: Cognos | undefined;
    if (cognos !== undefined) {
      this.keys(cognos, ["port", "users", "oidc", "cam", "memory"], "cognos");
      const listed = or(cognos.get("users"), []);
      if (!Array.isArray(listed)) throw this.error("cognos.users: a list of users, each user:password[:group,group]");
      const users = listed.map((u: unknown, n) => this.user(u, "cognos.users[" + n + "]"));
      const names = users.map((u) => u.name);
      if (new Set(names).size !== names.length) throw this.error("cognos.users: a user is listed twice");
      let oidc = cognos.get("oidc");
      if (oidc === true) oidc = new Map();
      let keycloakPort: number | undefined;
      if (oidc != null && truthy(oidc)) {
        const o = this.keys(oidc, ["keycloakPort"], "cognos.oidc");
        if (users.length === 0) throw this.error("cognos.oidc: needs cognos.users");
        keycloakPort = this.port(o.get("keycloakPort"), "cognos.oidc", 8180);
      }
      const camSection = this.keys(or(cognos.get("cam"), new Map()), ["namespace", "admins"], "cognos.cam");
      const namespace = (camSection.get("namespace") != null && truthy(camSection.get("namespace")) ? str(camSection.get("namespace")) : "ldap").toLowerCase();
      if (namespace !== "ldap" && namespace !== "oidc") throw this.error("cognos.cam.namespace: ldap or oidc");
      if (namespace === "oidc" && keycloakPort === undefined) throw this.error("cognos.cam.namespace: oidc needs cognos.oidc");
      const admins: string[] = [];
      const listedAdmins = camSection.get("admins");
      if (truthy(listedAdmins)) {
        if (!Array.isArray(listedAdmins)) throw this.error("cognos.cam.admins: a list of cognos.users");
        for (const a of listedAdmins as unknown[]) admins.push(str(a));
      } else if (names.length > 0) admins.push(names[0]!);
      if (servers.size > 0 && users.length === 0) throw this.error("cognos.users: TM1 in CAM security needs test users");
      for (const a of admins) if (!names.includes(a)) throw this.error("cognos.cam.admins: " + a + " isn't one of cognos.users");
      const memory = or(cognos.get("memory"), 6144n);
      if (!isInt(memory) || memory < 2048n) throw this.error("cognos.memory: the dispatcher heap in MB, at least 2048 (6144 recommended)");
      const cognosPort = this.port(cognos.get("port"), "cognos", 9300);
      cam = {
        port: cognosPort,
        users,
        keycloakPort,
        namespace: namespace === "oidc" ? CognosContainer.Namespace.OIDC : CognosContainer.Namespace.LDAP,
        admins,
        memory: Number(memory),
      };
    }

    const paw = this.section(cfg, "paw");
    let pawPort: number | undefined;
    let pawZip: string | undefined;
    if (paw !== undefined) {
      this.keys(paw, ["port", "acceptLicense", "zip"], "paw");
      if (servers.size === 0) throw this.error("paw: needs a TM1 server (tm1.servers)");
      if (paw.get("acceptLicense") !== true) {
        throw this.error(
          "paw.acceptLicense: PAW starts only once you accept IBM's license (true); read it with: unzip -p ipa_workspace_local_*.zip licenses/LA_en",
        );
      }
      if (ibm === undefined) throw this.error("paw: needs ibm, the folder with the PAW Local zip");
      pawPort = this.port(paw.get("port"), "paw", 8800);
      pawZip = paw.get("zip") != null && truthy(paw.get("zip")) ? str(paw.get("zip")) : undefined;
    }

    if (servers.size === 0 && cam === undefined) throw this.error("nothing to run: add tm1.servers and/or cognos");
    const ports: number[] = [...servers.values()].map((s) => s.port);
    if (admin !== undefined) ports.push(admin);
    if (webPort !== undefined) ports.push(webPort);
    if (cam !== undefined) {
      ports.push(cam.port);
      if (cam.keycloakPort !== undefined) ports.push(cam.keycloakPort);
    }
    if (pawPort !== undefined) ports.push(pawPort);
    const dupes = [...new Set(ports.filter((p) => ports.indexOf(p) !== ports.lastIndexOf(p)))].sort((x, y) => x - y);
    if (dupes.length > 0) throw this.error("port(s) " + dupes.join(", ") + " used by more than one service");

    // What pa-setup runs and this environment can't
    if (cam !== undefined && servers.size === 0) {
      throw this.error("cognos: PlanningAnalyticsEnvironment needs a TM1 server too (tm1.servers); for Cognos alone, use CognosContainer");
    }
    for (const [name, s] of servers) {
      if (s.seed === "24retail") {
        throw this.error("tm1.servers." + name + ".seed: 24retail isn't available in tm1-testcontainers (Tm1Seed has empty and sdata)");
      }
    }

    return configure(b, servers, admin !== undefined, heap, cam, pawPort !== undefined, {
      ibm,
      pawZip,
      tm1Image: image(images, "tm1Server"),
      tm1WebImage: image(images, "tm1Web"),
      cognosImage: image(images, "cognosServer"),
      keycloakImage: image(images, "keycloak"),
    });
  }

  /** pa_setup.check_keys: a mapping with only the allowed settings. */
  keys(section: unknown, allowed: string[], where: string): Map<unknown, unknown> {
    const map = this.mapping(section, where);
    const unknown = [...new Set([...map.keys()].map(str).filter((k) => !allowed.includes(k)))].sort();
    if (unknown.length > 0) throw this.error(where + ": unknown setting(s) " + unknown.join(", ") + " (allowed: " + allowed.join(", ") + ")");
    return map;
  }

  mapping(section: unknown, where: string): Map<unknown, unknown> {
    if (!(section instanceof Map)) throw this.error(where + " must be a mapping");
    return section as Map<unknown, unknown>;
  }

  /** pa_setup.section: absent or false is off; true is on with defaults. */
  section(cfg: Map<unknown, unknown>, key: string): Map<unknown, unknown> | undefined {
    const value = cfg.get(key);
    if (value == null || value === false) return undefined;
    if (value === true) return new Map();
    return this.mapping(value, key);
  }

  port(value: unknown, where: string, fallback: number): number {
    const p = value == null ? BigInt(fallback) : value;
    if (!isInt(p) || p < 1n || p > 65535n) throw this.error(where + ": port must be a number from 1 to 65535, not " + str(p));
    return Number(p);
  }

  hostPath(value: unknown, what: string, dir: string): string {
    if (typeof value !== "string" || value.trim() === "") throw this.error(what + " must be a path");
    const path = value.trim();
    if (path === "~" || path.startsWith("~/") || path.startsWith("~\\")) return resolve(homedir(), path.slice(2));
    return resolve(dir, path);
  }

  user(entry: unknown, where: string): CognosContainer.User {
    let name: unknown;
    let password: unknown;
    const groups: unknown[] = [];
    if (typeof entry === "string") {
      const parts = entry.split(":");
      if (parts.length !== 2 && parts.length !== 3) throw this.error(where + ": expected user:password[:group,group], not '" + entry + "'");
      name = parts[0]!;
      password = parts[1];
      if (parts.length === 3) groups.push(...parts[2]!.split(",").filter((g) => g !== ""));
    } else if (entry instanceof Map) {
      const map = this.keys(entry, ["name", "password", "groups"], where);
      name = map.get("name");
      password = map.get("password");
      const listed = or(map.get("groups"), []);
      if (!Array.isArray(listed)) throw this.error(where + ": groups is a list");
      groups.push(...(listed as unknown[]));
    } else {
      throw this.error(where + ": a user is user:password[:groups] or a mapping");
    }
    // Strings only, as in pa_setup: a missing name or a number isn't a user name
    if (typeof name !== "string" || !USER.test(name)) throw this.error(where + ": user name '" + str(name) + "' must be letters, digits, '.', '_' and '-'");
    if (typeof password !== "string" || password === "" || /[:\s]/.test(password)) {
      throw this.error(where + ": " + name + "'s password must be non-empty, without ':' or spaces");
    }
    const groupNames: string[] = [];
    for (const g of groups) {
      if (typeof g !== "string" || !USER.test(g)) throw this.error(where + ": group '" + str(g) + "' must be letters, digits, '.', '_' and '-'");
      groupNames.push(g);
    }
    return new CognosContainer.User(name, password, groupNames);
  }

  /** A tm1s.cfg value as pa_setup writes it, with Python's str(): true is True. */
  cfgValue(value: unknown, where: string): string {
    if (typeof value === "boolean" || typeof value === "string" || typeof value === "bigint" || typeof value === "number") return str(value);
    throw this.error(where + ": a string, number or boolean");
  }

  error(message: string): IllegalArgumentException {
    return new IllegalArgumentException(this.file + ": " + message);
  }
}

function configure(
  b: PlanningAnalyticsEnvironment.Builder,
  servers: Map<string, Server>,
  admin: boolean,
  heap: string | undefined,
  cam: Cognos | undefined,
  paw: boolean,
  sources: FileSources,
): PlanningAnalyticsEnvironment.Builder {
  for (const [name, s] of servers) {
    b.tm1(name, (server) => {
      server.withSeed(s.seed === "sdata" ? Tm1Seed.SDATA : Tm1Seed.EMPTY);
      for (const [key, value] of s.cfg) server.withEnv("TM1_CFG_" + key, value);
    });
  }
  if (admin) b.adminServer();
  if (heap !== undefined) b.tm1Web((web) => web.withEnv("TM1_WEB_HEAP", heap));
  if (cam !== undefined) {
    b.cognos((cognos) => {
      for (const u of cam.users) cognos.withUser(u.name, u.password, ...u.groups);
      cognos.withEnv("COGNOS_MAX_MEMORY", String(cam.memory));
    });
    if (cam.keycloakPort !== undefined) b.keycloak();
    b.camSecurity(cam.namespace, ...cam.admins);
  }
  if (paw) b.paw((p) => p.acceptLicense());
  return b.withFileSources(sources);
}

function image(images: Map<unknown, unknown>, key: string): string | undefined {
  const value = images.get(key);
  return value != null && truthy(value) ? str(value).trim() : undefined;
}

function isInt(value: unknown): value is bigint {
  return typeof value === "bigint";
}

/** pa_setup's `value or fallback`. */
function or(value: unknown, fallback: unknown): unknown {
  return value != null && truthy(value) ? value : fallback;
}

/** Python's truth: None, False, 0, "" and empty collections are false. */
function truthy(value: unknown): boolean {
  if (value == null || value === false || value === 0n || value === 0 || value === "") return false;
  if (value instanceof Map || value instanceof Set) return value.size > 0;
  if (Array.isArray(value)) return value.length > 0;
  return true;
}

/** Python's str() for what YAML gives: True, None, 4.0. */
function str(value: unknown): string {
  if (value === true) return "True";
  if (value === false) return "False";
  if (value == null) return "None";
  if (typeof value === "number") {
    if (Number.isNaN(value)) return "nan";
    if (!Number.isFinite(value)) return value > 0 ? "inf" : "-inf";
    return Number.isInteger(value) && Math.abs(value) < 1e16 ? value.toFixed(1) : String(value);
  }
  return String(value);
}
