// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.probe;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlEntityType;
import io.skipcheck.tm1.csdl.CsdlNavigationProperty;
import io.skipcheck.tm1.csdl.CsdlProperty;
import io.skipcheck.tm1.csdl.CsdlStructuredType;
import tools.jackson.databind.JsonNode;

/**
 * The probe: what TM1 does that its {@code $metadata} doesn't say.
 *
 * <ol>
 * <li>Fixtures on every server ({@link ProbeFixtures}).</li>
 * <li>Candidate names: those in the server's binary ({@link BinaryNames}), the names an
 * earlier probe found, and every name {@code $metadata} declares.</li>
 * <li>A path per entity type ({@link ProbePaths}), on each server.</li>
 * <li>Every candidate in a {@code $select} at each path ({@link PropertyNameOracle}), the
 * servers sharing the types. What TM1 accepts that the type doesn't declare is
 * undeclared, and is read from an entity for the kind of value it holds.</li>
 * <li>The writes ({@link WriteProbe}), on the first server, with the undeclared
 * properties selected too.</li>
 * </ol>
 */
public final class Tm1Probe {

	private final List<ProbeClient> servers;

	private final CsdlDocument metadata;

	private final int parallelism;

	private final Consumer<String> log;

	/**
	 * A probe of one server, or of several identical ones, which share the types between
	 * them. One TM1 (11.8) answers about 35,000 names a second however many requests it's
	 * sent at once, so more servers are the way to go faster. Identical servers give the
	 * same answers, so the report doesn't depend on how many there were.
	 * @param servers a client for each server: the same TM1 version, from the same source
	 * @param metadata the servers' {@code $metadata}
	 * @param parallelism how many batches to send each server at once
	 * @param log where progress goes
	 */
	public Tm1Probe(List<ProbeClient> servers, CsdlDocument metadata, int parallelism, Consumer<String> log) {
		if (servers.isEmpty()) {
			throw new IllegalArgumentException("No server to probe");
		}
		this.servers = List.copyOf(servers);
		this.metadata = metadata;
		this.parallelism = parallelism;
		this.log = log;
	}

	/**
	 * Runs the probe: creates the fixtures on every server, probes, and deletes them.
	 * @param binary the servers' {@code bin64/tm1s.exe}
	 * @param earlier names earlier probes found, to try whatever the binary holds
	 * @return what it found
	 */
	public ProbeReport run(byte[] binary, Collection<String> earlier) {
		JsonNode product = servers.get(0).get("Configuration/ProductVersion").body();
		String productVersion = product == null ? "" : product.path("value").asString("");
		String schemaVersion = metadata.schemaVersion().orElse("");

		NavigableSet<String> candidates = new TreeSet<>(BinaryNames.extract(binary));
		candidates.addAll(earlier);
		metadata.structuredTypes().forEach(t -> declared(t).forEach(candidates::add));
		log.accept(candidates.size() + " candidate names, " + servers.size() + " server(s)");

		NavigableMap<String, NavigableMap<String, String>> undeclared = new ConcurrentSkipListMap<>();
		NavigableMap<String, NavigableSet<String>> refused = new ConcurrentSkipListMap<>();
		NavigableSet<String> unverifiable = new ConcurrentSkipListSet<>();
		NavigableSet<String> unreached = new ConcurrentSkipListSet<>();
		WriteProbe.Result writes;
		List<Map<String, String>> seeds = onEachServer(ProbeFixtures::create);
		try {
			onEachServer(client -> {
				int share = servers.indexOf(client);
				probe(share, seeds.get(share), candidates, undeclared, refused, unverifiable, unreached);
				return Boolean.TRUE;
			});
			// After the names: the writes select the undeclared properties too
			writes = new WriteProbe(servers.get(0), metadata, undeclared, WriteProbe.bundledCases(),
					ProbeFixtures.PREFIX, log)
				.run();
		}
		finally {
			onEachServer(client -> {
				ProbeFixtures.delete(client);
				return Boolean.TRUE;
			});
		}
		return new ProbeReport(productVersion, schemaVersion, sha256(binary), candidates.size(), undeclared, refused,
				unverifiable, unreached, writes.writes(), writes.collections());
	}

	/**
	 * Runs something on every server at once; each one's result, in the servers' order.
	 */
	private <T> List<T> onEachServer(Function<ProbeClient, T> task) {
		List<T> results = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(servers.size())) {
			List<Future<T>> futures = new ArrayList<>();
			for (ProbeClient server : servers) {
				futures.add(pool.submit(() -> task.apply(server)));
			}
			for (Future<T> future : futures) {
				results.add(future.get());
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted", e);
		}
		catch (ExecutionException e) {
			throw new IllegalStateException("The probe failed", e.getCause());
		}
		return results;
	}

	/**
	 * One server's share of the names: the types whose index, in name order, is
	 * {@code share} modulo the servers.
	 */
	private void probe(int share, Map<String, String> seeds, Set<String> candidates,
			Map<String, NavigableMap<String, String>> undeclared, Map<String, NavigableSet<String>> refused,
			Set<String> unverifiable, Set<String> unreached) {
		ProbeClient client = servers.get(share);
		PropertyNameOracle oracle = new PropertyNameOracle(client, parallelism);
		NavigableMap<String, ProbePaths.Reach> reach = new ProbePaths(metadata, client, oracle, ProbeFixtures.PREFIX)
			.find(seeds);
		List<Map.Entry<String, ProbePaths.Reach>> types = List.copyOf(reach.entrySet());
		for (int t = share; t < types.size(); t += servers.size()) {
			String type = types.get(t).getKey();
			ProbePaths.Reach where = types.get(t).getValue();
			String path = where.path();
			if (path == null) {
				(where.entity() == null ? unreached : unverifiable).add(type);
				log.accept(type + ": " + (where.entity() == null ? "no path found" : "TM1 doesn't check names"));
				continue;
			}
			Set<String> declared = declared(type);
			NavigableSet<String> accepted = oracle.accepted(path, candidates);
			NavigableSet<String> extra = new TreeSet<>(accepted);
			extra.removeAll(declared);
			NavigableSet<String> missing = new TreeSet<>(declared);
			missing.removeAll(accepted);
			if (!extra.isEmpty()) {
				undeclared.put(type, kinds(client, where.entity(), extra));
			}
			if (!missing.isEmpty()) {
				refused.put(type, missing);
			}
			log.accept(type + " at " + path + ": undeclared " + extra
					+ (missing.isEmpty() ? "" : ", declared but refused " + missing));
		}
	}

	/** The kind of JSON value each property holds in an entity. */
	private static NavigableMap<String, String> kinds(ProbeClient client, @Nullable String entity, Set<String> names) {
		NavigableMap<String, String> kinds = new TreeMap<>();
		names.forEach(n -> kinds.put(n, "unread"));
		if (entity == null) {
			return kinds;
		}
		ProbeClient.Answer answer = client.get(PropertyNameOracle.select(entity, String.join(",", names)));
		JsonNode body = answer.body();
		if (!answer.ok() || body == null) {
			return kinds;
		}
		for (String name : names) {
			JsonNode value = body.get(name);
			if (value != null) {
				kinds.put(name, kind(value));
			}
			else if (body.has(name + "@odata.navigationLink")) {
				kinds.put(name, "navigation");
			}
		}
		return kinds;
	}

	static String kind(JsonNode value) {
		if (value.isNull()) {
			return "null";
		}
		if (value.isString()) {
			return "string";
		}
		if (value.isBoolean()) {
			return "boolean";
		}
		if (value.isNumber()) {
			return "number";
		}
		return value.isArray() ? "array" : "object";
	}

	private Set<String> declared(String simpleName) {
		return metadata.structuredTypes()
			.filter(t -> t instanceof CsdlEntityType && t.name().equals(simpleName))
			.findFirst()
			.map(this::declared)
			.orElse(Set.of());
	}

	/** The properties a type and its base types declare, navigation ones included. */
	private Set<String> declared(CsdlStructuredType type) {
		Set<String> names = new TreeSet<>();
		String qualified = metadata.qualifiedName(type).orElse(type.name());
		for (CsdlStructuredType t : metadata.typeHierarchy(qualified)) {
			t.properties().stream().map(CsdlProperty::name).forEach(names::add);
			t.navigationProperties().stream().map(CsdlNavigationProperty::name).forEach(names::add);
		}
		return names;
	}

	static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is part of every JDK", e);
		}
	}

	/**
	 * The names earlier probes found, from their report files.
	 * @param reports each report file's content
	 * @return the names
	 */
	public static NavigableSet<String> earlierNames(List<String> reports) {
		NavigableSet<String> names = new TreeSet<>();
		reports.forEach(r -> names.addAll(ProbeReport.undeclaredNames(r)));
		return names;
	}

}
