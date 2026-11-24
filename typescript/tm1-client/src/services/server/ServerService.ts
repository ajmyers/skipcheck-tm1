// SPDX-License-Identifier: Apache-2.0

import { Configuration, Logger, Server, ServerSettings, type LoggerPatch, type LogLevel, type ServerSettingsPatch } from "../../generated/index.js";
import { ODataPath, type ODataQuery } from "../../odata/index.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import type { Tm1Stream } from "../../stream.js";
import { required } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";

const LOGGERS = ODataPath.of("Loggers");

/** The server: what it is (`Server`), how it's configured, and its loggers.
 *
 * The static configuration is `tm1s.cfg`'s, and the one to change; the active one is what the
 * server runs with now, read only.
 *
 * Read the server's name and version:
 * ```ts
 * const { Name, ProductVersion } = await tm1.server.server(ODataQuery.none().select(Server.Fields.Name, Server.Fields.ProductVersion));
 * ```
 * Read a setting the server runs with now:
 * ```ts
 * const settings = await tm1.server.activeConfiguration(ODataQuery.none().select("Administration/DataBaseDirectory"));
 * ```
 * Change a logger's level:
 * ```ts
 * await tm1.server.setLoggerLevel("TM1.Server", LogLevel.Debug);
 * ```
 */
export class ServerService extends Tm1Service {
  /** The endpoints each method implements, by method name, as coverage-gate IDs: the Java client's
   * `@Tm1Endpoint`. */
  static readonly endpoints = {
    server: ["Singleton Server"],
    staticConfiguration: ["Singleton StaticConfiguration"],
    updateStaticConfiguration: ["Singleton StaticConfiguration"],
    activeConfiguration: ["Singleton ActiveConfiguration"],
    configuration: ["Singleton Configuration"],
    loggers: ["EntitySet Loggers"],
    logger: ["EntitySet Loggers"],
    setLoggerLevel: ["EntitySet Loggers"],
    updateLogger: ["EntitySet Loggers"],
  } as const satisfies Tm1Endpoints<ServerService>;

  /** `GET Server`: the server's name, version and ports. TM1 (11.8) sends no ETag for it. */
  server(query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Server> {
    return this.session.execute(Tm1Request.get(ODataPath.of("Server"), query, Server), options);
  }

  /** `GET StaticConfiguration`: the settings the server started with, from `tm1s.cfg`. */
  staticConfiguration(query?: ODataQuery | null, options?: Tm1CallOptions): Promise<ServerSettings> {
    return this.session.execute(Tm1Request.get(ODataPath.of("StaticConfiguration"), query, ServerSettings), options);
  }

  /** `PATCH StaticConfiguration`: changes `tm1s.cfg`; most settings take effect when the server next starts. */
  updateStaticConfiguration(patch: ServerSettingsPatch, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.patch(ODataPath.of("StaticConfiguration"), required(patch, "patch")), options);
  }

  /** `GET ActiveConfiguration`: the settings the server runs with now. Read only: TM1 (11.8) refuses
   * a PATCH here ("PATCH not allowed on this resource"); settings change through
   * {@link updateStaticConfiguration}. */
  activeConfiguration(query?: ODataQuery | null, options?: Tm1CallOptions): Promise<ServerSettings> {
    return this.session.execute(Tm1Request.get(ODataPath.of("ActiveConfiguration"), query, ServerSettings), options);
  }

  /** `GET Configuration`: the server's configuration in the older, flat form.
   * @deprecated Deprecated in TM1 schema 11.0.0: use {@link staticConfiguration} and {@link activeConfiguration}. */
  configuration(query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Configuration> {
    return this.session.execute(Tm1Request.get(ODataPath.of("Configuration"), query, Configuration), options);
  }

  /** `GET Loggers`, streamed: the server's loggers and their levels. */
  loggers(query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Logger> {
    return this.session.stream(Tm1Request.list(LOGGERS, query, Logger), options);
  }

  /** `GET Loggers('name')`, such as `TM1.Server`: the logger, or a `Tm1NotFoundException` if there's none. */
  logger(name: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Logger> {
    return this.session.execute(Tm1Request.get(LOGGERS.key(required(name, "name")), query, Logger), options);
  }

  /** `PATCH Loggers('name')`: changes a logger's level, at once. `Unrecognized`, a level this client
   * doesn't know, isn't sent. */
  setLoggerLevel(name: string, level: LogLevel, options?: Tm1CallOptions): Promise<void> {
    const path = LOGGERS.key(required(name, "name"));
    if (required(level, "level") === "Unrecognized") throw new RangeError("LogLevel.Unrecognized stands for a level this client doesn't know, and isn't sent");
    return this.session.execute(Tm1Request.patch(path, { Level: level }), options);
  }

  /** `PATCH Loggers('name')`: changes a logger, at once. TM1 (11.8) changes only its `Level`, which
   * {@link setLoggerLevel} sets as well. */
  updateLogger(name: string, patch: LoggerPatch, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.patch(LOGGERS.key(required(name, "name")), required(patch, "patch")), options);
  }
}
