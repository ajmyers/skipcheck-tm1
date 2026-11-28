// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.csdl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * Unions multiple CSDL documents (for example, TM1 11.8.35 and TM1 12.6.0) into one
 * combined model, and resolves local {@code edmx:Reference} schemas.
 *
 * <p>
 * Documents are merged oldest-first: where multiple versions define the same type,
 * property, enum member, operation, or container element, the newest version's
 * descriptions, annotations, and revisions win, while elements present only in older
 * versions (such as v11's {@code Reserve}/{@code Release} or encryption at rest) are
 * kept. An operation parameter that exists in only a subset of the versions defining the
 * operation, or that carries {@code Core.OptionalParameter}, becomes nullable (optional).
 */
public final class CsdlMerger {

	private static final String OPTIONAL_PARAMETER_TERM = "Core.OptionalParameter";

	private CsdlMerger() {
	}

	/**
	 * Merges CSDL documents ordered oldest-first, newest-last.
	 * @param documents the documents to merge (at least one)
	 * @return the merged document, carrying the newest document's OData version and
	 * {@code Core.SchemaVersion}
	 * @throws IllegalArgumentException if {@code documents} is empty
	 */
	public static CsdlDocument merge(List<CsdlDocument> documents) {
		Objects.requireNonNull(documents, "documents");
		if (documents.isEmpty()) {
			throw new IllegalArgumentException("At least one CSDL document is required");
		}
		CsdlDocument newest = documents.get(documents.size() - 1);
		// References: newest wins per URI, preserving newest's order with older-only
		// appended
		Map<String, CsdlReference> refs = new LinkedHashMap<>();
		for (int i = documents.size() - 1; i >= 0; i--) {
			for (CsdlReference ref : documents.get(i).references()) {
				refs.putIfAbsent(ref.uri(), ref);
			}
		}
		// Group schemas by namespace, recording the document they came from (for qualify)
		record SchemaInDoc(CsdlDocument doc, CsdlSchema schema) {
		}
		Map<String, List<SchemaInDoc>> byNamespace = new LinkedHashMap<>();
		for (CsdlDocument doc : documents) {
			for (CsdlSchema schema : doc.schemas()) {
				byNamespace.computeIfAbsent(schema.namespace(), k -> new ArrayList<>())
					.add(new SchemaInDoc(doc, schema));
			}
		}
		List<CsdlSchema> mergedSchemas = new ArrayList<>();
		for (List<SchemaInDoc> versions : byNamespace.values()) {
			CsdlSchema latest = versions.get(versions.size() - 1).schema();
			List<CsdlEntityType> entities = mergeEntities(
					versions.stream().map(v -> v.schema().entityTypes()).toList());
			List<CsdlComplexType> complexes = mergeComplexes(
					versions.stream().map(v -> v.schema().complexTypes()).toList());
			List<CsdlEnumType> enums = mergeEnums(versions.stream().map(v -> v.schema().enumTypes()).toList());
			List<CsdlOperation> operations = mergeOperations(
					versions.stream().map(v -> new OpsInDoc(v.doc(), v.schema().operations())).toList());
			CsdlEntityContainer container = mergeContainers(
					versions.stream().map(v -> v.schema().entityContainer()).filter(Objects::nonNull).toList());
			Map<String, List<CsdlAnnotation>> external = new LinkedHashMap<>();
			for (int i = versions.size() - 1; i >= 0; i--) {
				versions.get(i).schema().externalAnnotations().forEach(external::putIfAbsent);
			}
			mergedSchemas.add(new CsdlSchema(latest.namespace(), latest.alias(), entities, complexes, enums, operations,
					container, latest.annotations(), external));
		}
		return new CsdlDocument(newest.version(), mergedSchemas, List.copyOf(refs.values()));
	}

	/**
	 * Resolves local (non-HTTP) {@code edmx:Reference} elements in {@code document} using
	 * {@code resolver}, appending type-only schemas for the referenced namespaces not
	 * already present in {@code document}.
	 *
	 * <p>
	 * When a referenced schema defines its own {@code EntityContainer} (a separate OData
	 * service such as {@code ibm.tm1s.api.v1}), only the transitive closure of entity,
	 * complex, and enum types referenced by {@code document} is included — avoiding name
	 * collisions (such as {@code tm1s.Session} vs {@code tm1.Session}) and keeping
	 * another service's operations and container out of this service's model. When a
	 * referenced schema has no {@code EntityContainer} (an extension schema such as
	 * {@code ibm.odata.mpu.v1}), all of its types and enums are included, without its
	 * operations.
	 * @param document the document whose references to resolve
	 * @param resolver resolves a reference URI (e.g. {@code ibm.tm1s.api.v1.xml}) to a
	 * parsed document, or empty if unavailable
	 * @return a document with the resolved type-only schemas appended
	 */
	public static CsdlDocument resolveReferences(CsdlDocument document,
			Function<String, Optional<CsdlDocument>> resolver) {
		Objects.requireNonNull(document, "document");
		Objects.requireNonNull(resolver, "resolver");
		if (document.references().isEmpty()) {
			return document;
		}
		Set<String> existingNamespaces = new LinkedHashSet<>();
		document.schemas().forEach(s -> existingNamespaces.add(s.namespace()));
		Set<String> referencedTypes = directReferences(document);
		List<CsdlSchema> schemas = new ArrayList<>(document.schemas());
		for (CsdlReference ref : document.references()) {
			String uri = ref.uri();
			if (uri.startsWith("http://") || uri.startsWith("https://")) {
				continue;
			}
			Optional<CsdlDocument> resolved = resolver.apply(uri);
			if (resolved.isEmpty()) {
				continue;
			}
			CsdlDocument targetDoc = resolved.get();
			for (CsdlReference.Include include : ref.includes()) {
				String ns = include.namespace();
				if (existingNamespaces.contains(ns)) {
					continue;
				}
				Optional<CsdlSchema> found = targetDoc.schemas()
					.stream()
					.filter(s -> s.namespace().equals(ns))
					.findFirst();
				if (found.isEmpty()) {
					continue;
				}
				CsdlSchema refSchema = found.get();
				String alias = refSchema.alias() != null ? refSchema.alias() : include.alias();
				CsdlSchema imported = refSchema.entityContainer() != null
						? subsetReferenced(targetDoc, refSchema, alias, referencedTypes)
						: new CsdlSchema(refSchema.namespace(), alias, refSchema.entityTypes(),
								refSchema.complexTypes(), refSchema.enumTypes(), List.of(), null,
								refSchema.annotations(), Map.of());
				if (!imported.entityTypes().isEmpty() || !imported.complexTypes().isEmpty()
						|| !imported.enumTypes().isEmpty()) {
					checkNoSimpleNameCollisions(schemas, imported);
					schemas.add(imported);
					existingNamespaces.add(ns);
				}
			}
		}
		return new CsdlDocument(document.version(), schemas, document.references());
	}

	private static void checkNoSimpleNameCollisions(List<CsdlSchema> existing, CsdlSchema imported) {
		Set<String> names = new LinkedHashSet<>();
		for (CsdlSchema s : existing) {
			s.entityTypes().forEach(t -> names.add(t.name()));
			s.complexTypes().forEach(t -> names.add(t.name()));
			s.enumTypes().forEach(e -> names.add(e.name()));
		}
		for (CsdlEntityType t : imported.entityTypes()) {
			if (names.contains(t.name())) {
				throw new IllegalStateException(
						"Referenced schema " + imported.namespace() + " collides on simple name " + t.name());
			}
		}
		for (CsdlComplexType t : imported.complexTypes()) {
			if (names.contains(t.name())) {
				throw new IllegalStateException(
						"Referenced schema " + imported.namespace() + " collides on simple name " + t.name());
			}
		}
		for (CsdlEnumType e : imported.enumTypes()) {
			if (names.contains(e.name())) {
				throw new IllegalStateException(
						"Referenced schema " + imported.namespace() + " collides on simple name " + e.name());
			}
		}
	}

	/**
	 * Every qualified type name referenced by properties, navigations, base types,
	 * operations, and entity container entries in {@code doc}.
	 */
	private static Set<String> directReferences(CsdlDocument doc) {
		Set<String> refs = new LinkedHashSet<>();
		doc.structuredTypes().forEach(t -> {
			if (t.baseType() != null) {
				refs.add(doc.qualify(t.baseType()));
			}
			t.properties().forEach(p -> refs.add(doc.qualify(p.type().name())));
			t.navigationProperties().forEach(n -> refs.add(doc.qualify(n.type().name())));
		});
		for (CsdlSchema s : doc.schemas()) {
			for (CsdlOperation op : s.operations()) {
				op.parameters().forEach(p -> refs.add(doc.qualify(p.type().name())));
				if (op.returnType() != null) {
					refs.add(doc.qualify(op.returnType().name()));
				}
			}
			CsdlEntityContainer c = s.entityContainer();
			if (c != null) {
				c.entitySets().forEach(es -> refs.add(doc.qualify(es.entityType())));
				c.singletons().forEach(sg -> refs.add(doc.qualify(sg.type())));
			}
		}
		return refs;
	}

	/**
	 * Transitive closure of types and enums in {@code refSchema} reachable from
	 * {@code seeds} (including derived subtypes of referenced types).
	 */
	private static CsdlSchema subsetReferenced(CsdlDocument refDoc, CsdlSchema refSchema, @Nullable String alias,
			Set<String> seeds) {
		String prefix = refSchema.namespace() + ".";
		Set<String> needed = new LinkedHashSet<>();
		Deque<String> queue = new ArrayDeque<>();
		for (String seed : seeds) {
			if (seed.startsWith(prefix) && needed.add(seed)) {
				queue.add(seed);
			}
		}
		while (!queue.isEmpty()) {
			String qualified = queue.poll();
			refDoc.structuredType(qualified).ifPresent(t -> {
				List<String> next = new ArrayList<>();
				if (t.baseType() != null) {
					next.add(refDoc.qualify(t.baseType()));
				}
				t.properties().forEach(p -> next.add(refDoc.qualify(p.type().name())));
				t.navigationProperties().forEach(n -> next.add(refDoc.qualify(n.type().name())));
				for (CsdlEntityType sub : refSchema.entityTypes()) {
					if (sub.baseType() != null && refDoc.qualify(sub.baseType()).equals(qualified)) {
						next.add(prefix + sub.name());
					}
				}
				for (CsdlComplexType sub : refSchema.complexTypes()) {
					if (sub.baseType() != null && refDoc.qualify(sub.baseType()).equals(qualified)) {
						next.add(prefix + sub.name());
					}
				}
				for (String n : next) {
					if (n.startsWith(prefix) && needed.add(n)) {
						queue.add(n);
					}
				}
			});
		}
		List<CsdlEntityType> entities = refSchema.entityTypes()
			.stream()
			.filter(t -> needed.contains(prefix + t.name()))
			.toList();
		List<CsdlComplexType> complexes = refSchema.complexTypes()
			.stream()
			.filter(t -> needed.contains(prefix + t.name()))
			.toList();
		List<CsdlEnumType> enums = refSchema.enumTypes()
			.stream()
			.filter(e -> needed.contains(prefix + e.name()))
			.toList();
		return new CsdlSchema(refSchema.namespace(), alias, entities, complexes, enums, List.of(), null,
				refSchema.annotations(), Map.of());
	}

	private static List<CsdlEntityType> mergeEntities(List<List<CsdlEntityType>> versions) {
		Map<String, List<CsdlEntityType>> byName = new LinkedHashMap<>();
		for (int i = versions.size() - 1; i >= 0; i--) {
			for (CsdlEntityType t : versions.get(i)) {
				byName.computeIfAbsent(t.name(), k -> new ArrayList<>()).add(t);
			}
		}
		List<CsdlEntityType> out = new ArrayList<>();
		for (List<CsdlEntityType> list : byName.values()) {
			// list is newest-first
			CsdlEntityType latest = list.get(0);
			if (list.size() == 1) {
				out.add(latest);
				continue;
			}
			Map<String, CsdlProperty> props = new LinkedHashMap<>();
			Map<String, CsdlNavigationProperty> navs = new LinkedHashMap<>();
			for (CsdlEntityType t : list) {
				t.properties().forEach(p -> props.putIfAbsent(p.name(), p));
				t.navigationProperties().forEach(n -> navs.putIfAbsent(n.name(), n));
			}
			out.add(new CsdlEntityType(latest.name(), latest.baseType(), latest.isAbstract(), latest.openType(),
					latest.hasStream(), latest.key(), List.copyOf(props.values()), List.copyOf(navs.values()),
					latest.annotations()));
		}
		return out;
	}

	private static List<CsdlComplexType> mergeComplexes(List<List<CsdlComplexType>> versions) {
		Map<String, List<CsdlComplexType>> byName = new LinkedHashMap<>();
		for (int i = versions.size() - 1; i >= 0; i--) {
			for (CsdlComplexType t : versions.get(i)) {
				byName.computeIfAbsent(t.name(), k -> new ArrayList<>()).add(t);
			}
		}
		List<CsdlComplexType> out = new ArrayList<>();
		for (List<CsdlComplexType> list : byName.values()) {
			CsdlComplexType latest = list.get(0);
			if (list.size() == 1) {
				out.add(latest);
				continue;
			}
			Map<String, CsdlProperty> props = new LinkedHashMap<>();
			Map<String, CsdlNavigationProperty> navs = new LinkedHashMap<>();
			for (CsdlComplexType t : list) {
				t.properties().forEach(p -> props.putIfAbsent(p.name(), p));
				t.navigationProperties().forEach(n -> navs.putIfAbsent(n.name(), n));
			}
			out.add(new CsdlComplexType(latest.name(), latest.baseType(), latest.isAbstract(), latest.openType(),
					List.copyOf(props.values()), List.copyOf(navs.values()), latest.annotations()));
		}
		return out;
	}

	private static List<CsdlEnumType> mergeEnums(List<List<CsdlEnumType>> versions) {
		Map<String, List<CsdlEnumType>> byName = new LinkedHashMap<>();
		for (int i = versions.size() - 1; i >= 0; i--) {
			for (CsdlEnumType e : versions.get(i)) {
				byName.computeIfAbsent(e.name(), k -> new ArrayList<>()).add(e);
			}
		}
		List<CsdlEnumType> out = new ArrayList<>();
		for (List<CsdlEnumType> list : byName.values()) {
			CsdlEnumType latest = list.get(0);
			if (list.size() == 1) {
				out.add(latest);
				continue;
			}
			Map<String, CsdlEnumType.Member> members = new LinkedHashMap<>();
			for (CsdlEnumType e : list) {
				e.members().forEach(m -> members.putIfAbsent(m.name(), m));
			}
			out.add(new CsdlEnumType(latest.name(), latest.underlyingType(), latest.flags(),
					List.copyOf(members.values()), latest.annotations()));
		}
		return out;
	}

	private record OpsInDoc(CsdlDocument doc, List<CsdlOperation> operations) {
	}

	private record OpKey(CsdlOperation.Kind kind, String name, boolean bound, @Nullable String bindingType,
			boolean bindingCollection) {
	}

	private static List<CsdlOperation> mergeOperations(List<OpsInDoc> versions) {
		Map<OpKey, List<CsdlOperation>> byKey = new LinkedHashMap<>();
		for (int i = versions.size() - 1; i >= 0; i--) {
			OpsInDoc entry = versions.get(i);
			Set<OpKey> seenInDoc = new LinkedHashSet<>();
			for (CsdlOperation op : entry.operations()) {
				CsdlTypeRef binding = op.bindingType().orElse(null);
				OpKey key = new OpKey(op.kind(), op.name(), op.bound(),
						binding == null ? null : entry.doc().qualify(binding.name()),
						binding != null && binding.collection());
				if (!seenInDoc.add(key)) {
					throw new IllegalArgumentException(
							"Duplicate operation overload in a single schema is not supported: " + key);
				}
				byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(op);
			}
		}
		List<CsdlOperation> out = new ArrayList<>();
		for (List<CsdlOperation> list : byKey.values()) {
			CsdlOperation latest = list.get(0);
			List<CsdlParameter> params = mergeParameters(list);
			out.add(new CsdlOperation(latest.kind(), latest.name(), latest.bound(), latest.composable(),
					latest.entitySetPath(), params, latest.returnType(), latest.returnNullable(),
					latest.annotations()));
		}
		return out;
	}

	private static List<CsdlParameter> mergeParameters(List<CsdlOperation> newestFirst) {
		CsdlOperation latest = newestFirst.get(0);
		List<CsdlParameter> out = new ArrayList<>();
		if (latest.bound()) {
			out.add(latest.parameters().get(0));
		}
		Map<String, CsdlParameter> chosen = new LinkedHashMap<>();
		Map<String, Integer> occurrences = new LinkedHashMap<>();
		Map<String, Boolean> nullable = new LinkedHashMap<>();
		for (CsdlOperation op : newestFirst) {
			for (CsdlParameter p : op.callerParameters()) {
				chosen.putIfAbsent(p.name(), p);
				occurrences.merge(p.name(), 1, Integer::sum);
				boolean opt = p.nullable() || CsdlAnnotation.find(p.annotations(), OPTIONAL_PARAMETER_TERM).isPresent();
				nullable.merge(p.name(), opt, (a, b) -> a || b);
			}
		}
		int total = newestFirst.size();
		for (Map.Entry<String, CsdlParameter> entry : chosen.entrySet()) {
			CsdlParameter p = entry.getValue();
			boolean isOptional = Boolean.TRUE.equals(nullable.get(p.name()))
					|| occurrences.getOrDefault(p.name(), 0) < total;
			out.add(isOptional == p.nullable() ? p
					: new CsdlParameter(p.name(), p.type(), isOptional, p.annotations()));
		}
		return out;
	}

	private static @Nullable CsdlEntityContainer mergeContainers(List<CsdlEntityContainer> containers) {
		if (containers.isEmpty()) {
			return null;
		}
		CsdlEntityContainer latest = containers.get(containers.size() - 1);
		if (containers.size() == 1) {
			return latest;
		}
		Map<String, CsdlEntityContainer.EntitySet> entitySets = new LinkedHashMap<>();
		Map<String, CsdlEntityContainer.Singleton> singletons = new LinkedHashMap<>();
		Map<String, CsdlEntityContainer.ActionImport> actionImports = new LinkedHashMap<>();
		Map<String, CsdlEntityContainer.FunctionImport> functionImports = new LinkedHashMap<>();
		for (int i = containers.size() - 1; i >= 0; i--) {
			CsdlEntityContainer c = containers.get(i);
			c.entitySets().forEach(s -> entitySets.putIfAbsent(s.name(), s));
			c.singletons().forEach(s -> singletons.putIfAbsent(s.name(), s));
			c.actionImports().forEach(a -> actionImports.putIfAbsent(a.name(), a));
			c.functionImports().forEach(f -> functionImports.putIfAbsent(f.name(), f));
		}
		return new CsdlEntityContainer(latest.name(), List.copyOf(entitySets.values()),
				List.copyOf(singletons.values()), List.copyOf(actionImports.values()),
				List.copyOf(functionImports.values()), latest.annotations());
	}

}
