// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.probe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlEntityContainer;
import io.skipcheck.tm1.csdl.CsdlEntityType;
import io.skipcheck.tm1.csdl.CsdlNavigationProperty;
import io.skipcheck.tm1.csdl.CsdlProperty;
import io.skipcheck.tm1.csdl.CsdlStructuredType;
import tools.jackson.databind.JsonNode;

/**
 * Finds, for each entity type, a path where TM1 checks {@code $select} names, and an
 * entity of it to read values from.
 *
 * <p>
 * It walks from the entity sets and singletons through the navigation properties
 * {@code $metadata} declares, breadth first and in name order, so the same server gives
 * the same paths. Through a collection it picks an entity named with the fixtures' prefix
 * first, then any but a control object ({@code }Cubes}), then a control object.
 */
public final class ProbePaths {

	/** The deepest path walked, in segments. */
	static final int MAX_DEPTH = 8;

	private final CsdlDocument metadata;

	private final ProbeClient client;

	private final PropertyNameOracle oracle;

	private final String prefix;

	/**
	 * Where to probe a type.
	 *
	 * @param path a path where TM1 checks {@code $select} names, or null if none was
	 * found
	 * @param entity a path to one entity of the type, or null if none was found
	 */
	public record Reach(@Nullable String path, @Nullable String entity) {
	}

	/**
	 * A path finder.
	 * @param metadata the server's {@code $metadata}
	 * @param client the server's client
	 * @param oracle checks whether TM1 checks names at a path
	 * @param prefix the fixtures' names start with this
	 */
	public ProbePaths(CsdlDocument metadata, ProbeClient client, PropertyNameOracle oracle, String prefix) {
		this.metadata = metadata;
		this.client = client;
		this.oracle = oracle;
		this.prefix = prefix;
	}

	private record Step(String path, String type, boolean collection) {
	}

	/**
	 * Walks the server.
	 * @param seeds entities to start from, by qualified type name, such as a cellset the
	 * fixtures made (TM1 lists none)
	 * @return what was found, by the type's simple name, for every entity type
	 * {@code $metadata} defines
	 */
	public NavigableMap<String, Reach> find(Map<String, String> seeds) {
		Deque<Step> queue = new ArrayDeque<>();
		new TreeMap<>(seeds).forEach((type, path) -> queue.add(new Step(path, metadata.qualify(type), false)));
		CsdlEntityContainer container = metadata.entityContainer()
			.orElseThrow(() -> new IllegalArgumentException("$metadata has no entity container"));
		container.entitySets()
			.stream()
			.sorted(Comparator.comparing(CsdlEntityContainer.EntitySet::name))
			.forEach(s -> queue.add(new Step(s.name(), metadata.qualify(s.entityType()), true)));
		container.singletons()
			.stream()
			.sorted(Comparator.comparing(CsdlEntityContainer.Singleton::name))
			.forEach(s -> queue.add(new Step(s.name(), metadata.qualify(s.type()), false)));

		Map<String, String> checked = new TreeMap<>();
		Map<String, String> entities = new TreeMap<>();
		Set<String> tried = new HashSet<>();
		while (!queue.isEmpty()) {
			Step step = queue.poll();
			String type = step.type();
			String probe = step.collection() ? step.path() + "?$top=1" : step.path();
			if (!checked.containsKey(type) && tried.add(probe)) {
				known(type).filter(k -> oracle.checks(probe, k)).ifPresent(k -> checked.put(type, probe));
			}
			if (entities.containsKey(type)) {
				continue;
			}
			String entity = step.collection() ? pick(step.path(), type) : exists(step.path()) ? step.path() : null;
			if (entity == null) {
				continue;
			}
			entities.put(type, entity);
			if (entity.split("/").length >= MAX_DEPTH) {
				continue;
			}
			for (CsdlNavigationProperty navigation : navigations(type)) {
				String target = metadata.qualify(navigation.type().name());
				if (!entities.containsKey(target) || !checked.containsKey(target)) {
					queue.add(new Step(entity + "/" + navigation.name(), target, navigation.type().collection()));
				}
			}
		}

		NavigableMap<String, Reach> reach = new TreeMap<>();
		metadata.schemas().forEach(schema -> schema.entityTypes().forEach(t -> {
			String qualified = schema.namespace() + "." + t.name();
			reach.put(t.name(), new Reach(checked.get(qualified), entities.get(qualified)));
		}));
		return Collections.unmodifiableNavigableMap(reach);
	}

	/** A property the type declares, to check TM1 accepts a real name at a path. */
	private Optional<String> known(String type) {
		for (CsdlStructuredType t : metadata.typeHierarchy(type)) {
			if (t instanceof CsdlEntityType entity && !entity.key().isEmpty()) {
				return Optional.of(entity.key().get(0));
			}
		}
		return metadata.typeHierarchy(type)
			.stream()
			.flatMap(t -> t.properties().stream())
			.map(CsdlProperty::name)
			.findFirst();
	}

	private List<CsdlNavigationProperty> navigations(String type) {
		List<CsdlNavigationProperty> all = new ArrayList<>();
		metadata.typeHierarchy(type).forEach(t -> all.addAll(t.navigationProperties()));
		all.sort(Comparator.comparing(CsdlNavigationProperty::name));
		return all;
	}

	private boolean exists(String path) {
		ProbeClient.Answer answer = client.get(path);
		return answer.status() == 200;
	}

	/** One entity of a collection, as a path, or null if it has none. */
	private @Nullable String pick(String collection, String type) {
		ProbeClient.Answer answer = client.get(collection + "?$top=100");
		JsonNode body = answer.body();
		if (!answer.ok() || body == null || !body.path("value").isArray()) {
			return null;
		}
		List<String> keys = keys(type);
		String best = null;
		int bestRank = Integer.MAX_VALUE;
		for (JsonNode item : body.path("value")) {
			String path = pathOf(collection, item, keys);
			if (path == null) {
				continue;
			}
			String name = item.path(keys.isEmpty() ? "Name" : keys.get(0)).asString("");
			int rank = name.startsWith(prefix) ? 0 : name.startsWith("}") ? 2 : 1;
			if (rank < bestRank) {
				best = path;
				bestRank = rank;
			}
		}
		return best;
	}

	private @Nullable String pathOf(String collection, JsonNode item, List<String> keys) {
		JsonNode id = item.get("@odata.id");
		if (id != null && id.isString() && !id.asString().contains("://")) {
			return id.asString();
		}
		if (keys.isEmpty()) {
			return null;
		}
		List<String> parts = new ArrayList<>();
		for (String key : keys) {
			JsonNode value = item.get(key);
			if (value == null || value.isNull()) {
				return null;
			}
			String literal = value.isString() ? "'" + value.asString().replace("'", "''") + "'" : value.asString();
			parts.add(keys.size() == 1 ? literal : key + "=" + literal);
		}
		return collection + "(" + String.join(",", parts) + ")";
	}

	private List<String> keys(String type) {
		for (CsdlStructuredType t : metadata.typeHierarchy(type)) {
			if (t instanceof CsdlEntityType entity && !entity.key().isEmpty()) {
				return entity.key();
			}
		}
		return List.of();
	}

}
