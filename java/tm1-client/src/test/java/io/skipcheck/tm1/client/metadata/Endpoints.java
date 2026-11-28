// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.metadata;

import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlEntityContainer;
import io.skipcheck.tm1.csdl.CsdlOperation;
import io.skipcheck.tm1.csdl.CsdlSchema;
import io.skipcheck.tm1.csdl.CsdlStructuredType;
import io.skipcheck.tm1.csdl.CsdlTypeRef;

/**
 * Every endpoint a document defines, as stable, readable IDs: the unit the coverage gate
 * counts. An endpoint is anything a client calls or navigates to:
 *
 * <ul>
 * <li>{@code EntitySet Cubes}, {@code Singleton Server}: top-level resources
 * <li>{@code ActionImport ExecuteMDX}, {@code FunctionImport TailMessageLog}: unbound
 * operations
 * <li>{@code Action Cube/Reserve}, {@code Function Cube/DimensionsStorageOrder}: bound
 * operations, by binding type ({@code Collection(Cube)/...} when bound to a collection)
 * <li>{@code Navigation Cube/Views}: navigation properties, including those of complex
 * types
 * </ul>
 *
 * <p>
 * Type names are written without a namespace, since TM1's API has one.
 */
public final class Endpoints {

	private Endpoints() {
	}

	/**
	 * A document's endpoints, as coverage-gate IDs.
	 * @param doc the {@code $metadata} document
	 * @return its endpoints, sorted
	 */
	public static NavigableSet<String> of(CsdlDocument doc) {
		NavigableSet<String> out = new TreeSet<>();
		doc.entityContainer().ifPresent(c -> container(out, c));
		List<CsdlSchema> primary = doc.schemas().stream().filter(s -> s.entityContainer() != null).toList();
		if (primary.isEmpty()) {
			primary = doc.schemas();
		}
		for (CsdlSchema schema : primary) {
			for (CsdlOperation op : schema.operations()) {
				if (op.bound()) {
					CsdlTypeRef binding = op.bindingType().orElseThrow();
					String type = simple(binding.name());
					out.add((op.kind() == CsdlOperation.Kind.ACTION ? "Action " : "Function ")
							+ (binding.collection() ? "Collection(" + type + ")" : type) + "/" + op.name());
				}
			}
			schema.entityTypes().forEach(t -> navigation(out, t));
			schema.complexTypes().forEach(t -> navigation(out, t));
		}
		return out;
	}

	private static void container(NavigableSet<String> out, CsdlEntityContainer c) {
		c.entitySets().forEach(s -> out.add("EntitySet " + s.name()));
		c.singletons().forEach(s -> out.add("Singleton " + s.name()));
		c.actionImports().forEach(a -> out.add("ActionImport " + a.name()));
		c.functionImports().forEach(f -> out.add("FunctionImport " + f.name()));
	}

	private static void navigation(NavigableSet<String> out, CsdlStructuredType t) {
		t.navigationProperties().forEach(n -> out.add("Navigation " + t.name() + "/" + n.name()));
	}

	private static String simple(String qualifiedName) {
		return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
	}

}
