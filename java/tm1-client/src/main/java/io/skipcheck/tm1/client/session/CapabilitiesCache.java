package io.skipcheck.tm1.client.session;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import io.skipcheck.tm1.client.version.Tm1Capabilities;
import io.skipcheck.tm1.client.version.Tm1Version;
import reactor.core.publisher.Mono;

/**
 * Capabilities by server and version, shared by every session built from the same
 * settings (a pool's sessions included), so {@code $metadata} is fetched once per server
 * version rather than once per session. A server that's upgraded reports a new version,
 * and gets a new entry. A failed fetch isn't kept.
 */
final class CapabilitiesCache {

	private final ConcurrentHashMap<String, Mono<Tm1Capabilities>> byServer = new ConcurrentHashMap<>();

	Mono<Tm1Capabilities> get(String serviceRoot, Tm1Version version, Supplier<Mono<Tm1Capabilities>> fetch) {
		String key = serviceRoot + " " + version;
		return byServer.computeIfAbsent(key, k -> {
			AtomicReference<Mono<Tm1Capabilities>> self = new AtomicReference<>();
			Mono<Tm1Capabilities> cached = Mono.defer(fetch).doOnError(e -> byServer.remove(k, self.get())).cache();
			self.set(cached);
			return cached;
		});
	}

}
