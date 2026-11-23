// SPDX-License-Identifier: Apache-2.0

import { SQLDataSource } from "../../generated/index.js";
import { ODataPath, type ODataQuery } from "../../odata/index.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import type { Tm1Stream } from "../../stream.js";
import { required } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";

const SOURCES = ODataPath.of("SQLDataSources");

/** The ODBC data sources on TM1's host, which processes can read from (`DataSource.Type` `ODBC`).
 * They're the host's ODBC configuration, not TM1's: read only here.
 *
 * List the data sources:
 * ```ts
 * const names = (await tm1.sqlDataSources.list(ODataQuery.none().select(SQLDataSource.Fields.Name)).toArray()).map((s) => s.Name);
 * ```
 */
export class SqlDataSourceService extends Tm1Service {
  /** The endpoints each method implements, by method name, as coverage-gate IDs: the Java client's
   * `@Tm1Endpoint`. */
  static readonly endpoints = {
    list: ["EntitySet SQLDataSources"],
    get: ["EntitySet SQLDataSources"],
  } as const satisfies Tm1Endpoints<SqlDataSourceService>;

  /** `GET SQLDataSources`, streamed: the data sources' names and descriptions. */
  list(query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<SQLDataSource> {
    return this.session.stream(Tm1Request.list(SOURCES, query, SQLDataSource), options);
  }

  /** `GET SQLDataSources('name')`: the data source, or a `Tm1NotFoundException` if the host has none
   * by that name. */
  get(name: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<SQLDataSource> {
    return this.session.execute(Tm1Request.get(SOURCES.key(required(name, "name")), query, SQLDataSource), options);
  }
}
