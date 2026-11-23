import { Primitive } from "../../model.js";
import { Tm1Operations } from "../../generated/index.js";
import { ODataPath, ODataQuery } from "../../odata/index.js";
import type { Tm1Operation } from "../../operation.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import type { Tm1Stream } from "../../stream.js";
import { required } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";

/** The OpenID Connect signing keys TM1 has cached, for servers that check OIDC tokens themselves.
 * Removing a key makes TM1 fetch it from the provider again, as after the provider rotates its
 * keys. On a server that checks no tokens, the cache is empty.
 *
 * Refresh the cache after the provider rotates its keys:
 * ```ts
 * await tm1.oidcKeys.remove(await tm1.oidcKeys.keys().toArray());
 * ```
 */
export class OidcKeyService extends Tm1Service {
  static readonly endpoints = {
    keys: ["FunctionImport GetOIDCKeys"],
    remove: ["ActionImport RemoveOIDCKeyFromCache"],
  } as const satisfies Tm1Endpoints<OidcKeyService>;

  /** `GetOIDCKeys()`, streamed: the IDs of the cached keys. */
  keys(options?: Tm1CallOptions): Tm1Stream<string> {
    const operation = Tm1Operations.GetOidcKeys;
    return this.session.stream(
      Tm1Request.list(ODataPath.functionImport(importName(operation), {}), ODataQuery.none(), Primitive.string).requires(operation.capability()),
      options,
    );
  }

  /** `RemoveOIDCKeyFromCache`: drops keys from the cache. A key TM1 hasn't cached fails the call
   * with a `Tm1NotFoundException`: TM1 answers 400 with `ObjectNotFound` (code 55), which the client
   * reads as not found. An empty list does nothing. */
  remove(keyIds: readonly string[], options?: Tm1CallOptions): Promise<void> {
    const operation = Tm1Operations.RemoveOidcKeyFromCache;
    const body = { KeyId: [...required(keyIds, "keyIds")].map((k) => required(k, "keyId")) };
    return this.session.execute(Tm1Request.post(ODataPath.of(importName(operation)), body).requires(operation.capability()), options);
  }
}

function importName(operation: Tm1Operation): string {
  return required(operation.importName, operation.name);
}
