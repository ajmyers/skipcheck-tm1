// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.security;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.skipcheck.tm1.client.Tm1Operation;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.model.Tm1Operations;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.Tm1Endpoint;
import io.skipcheck.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The OpenID Connect signing keys TM1 has cached, for servers that check OIDC tokens
 * themselves. Removing a key makes TM1 fetch it from the provider again, as after the
 * provider rotates its keys. On a server that checks no tokens, the cache is empty.
 *
 * <p>
 * Refresh the cache after the provider rotates its keys:
 * {@snippet class = examples.OidcKeyServiceExamples region = refresh}
 */
public final class OidcKeyService {

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public OidcKeyService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code GetOIDCKeys()}, streamed: the IDs of the cached keys.
	 * @return the key IDs
	 */
	@SuppressWarnings("deprecation") // Deprecated in TM1 schema 12.0.0; still active on
										// TM1 11.8
	@Tm1Endpoint("FunctionImport GetOIDCKeys")
	public Flux<String> keys() {
		Tm1Operation operation = Tm1Operations.GET_OIDC_KEYS;
		return session.stream(Tm1Request
			.list(ODataPath.functionImport(importName(operation), Map.of()), ODataQuery.none(), String.class)
			.requires(operation.capability()));
	}

	/**
	 * {@code RemoveOIDCKeyFromCache}: drops keys from the cache. A key TM1 hasn't cached
	 * fails the call with a {@code Tm1NotFoundException}: TM1 answers 400 with
	 * {@code ObjectNotFound} (code 55), which the client reads as not found. An empty
	 * list does nothing.
	 * @param keyIds the keys' IDs
	 * @return completes when they're dropped
	 */
	@SuppressWarnings("deprecation") // Deprecated in TM1 schema 12.0.0; still active on
										// TM1 11.8
	@Tm1Endpoint("ActionImport RemoveOIDCKeyFromCache")
	public Mono<Void> remove(List<String> keyIds) {
		Tm1Operation operation = Tm1Operations.REMOVE_OIDC_KEY_FROM_CACHE;
		return session.execute(
				Tm1Request.post(ODataPath.of(importName(operation)), Map.of("KeyId", List.copyOf(keyIds)), Void.class)
					.requires(operation.capability()));
	}

	private static String importName(Tm1Operation operation) {
		return Objects.requireNonNull(operation.importName(), operation.name());
	}

}
