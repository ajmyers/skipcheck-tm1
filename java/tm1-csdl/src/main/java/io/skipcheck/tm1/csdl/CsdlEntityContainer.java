// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.csdl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * The entity container: the service's top-level resources, addressed from the service
 * root.
 *
 * @param name the container's name
 * @param entitySets its entity sets, such as {@code Cubes}
 * @param singletons its singletons, such as {@code Server}
 * @param actionImports its action imports, such as {@code ExecuteMDX}
 * @param functionImports its function imports, such as {@code TailMessageLog}
 * @param annotations its annotations, including the service's {@code Capabilities}
 */
public record CsdlEntityContainer(String name, List<EntitySet> entitySets, List<Singleton> singletons,
		List<ActionImport> actionImports, List<FunctionImport> functionImports, List<CsdlAnnotation> annotations) {

	/** Validates and copies the components. */
	public CsdlEntityContainer {
		Objects.requireNonNull(name, "name");
		entitySets = List.copyOf(entitySets);
		singletons = List.copyOf(singletons);
		actionImports = List.copyOf(actionImports);
		functionImports = List.copyOf(functionImports);
		annotations = List.copyOf(annotations);
	}

	/**
	 * An entity set.
	 *
	 * @param name its name
	 * @param entityType the qualified name of its entity type
	 * @param navigationBindings navigation property paths and the sets they lead to
	 * @param annotations its annotations
	 */
	public record EntitySet(String name, String entityType, Map<String, String> navigationBindings,
			List<CsdlAnnotation> annotations) {
		/** Validates and copies the components. */
		public EntitySet {
			Objects.requireNonNull(name, "name");
			Objects.requireNonNull(entityType, "entityType");
			navigationBindings = Collections.unmodifiableMap(new LinkedHashMap<>(navigationBindings));
			annotations = List.copyOf(annotations);
		}
	}

	/**
	 * A singleton: a single entity addressed by name.
	 *
	 * @param name its name
	 * @param type the qualified name of its entity type
	 * @param navigationBindings navigation property paths and the sets they lead to
	 * @param annotations its annotations
	 */
	public record Singleton(String name, String type, Map<String, String> navigationBindings,
			List<CsdlAnnotation> annotations) {
		/** Validates and copies the components. */
		public Singleton {
			Objects.requireNonNull(name, "name");
			Objects.requireNonNull(type, "type");
			navigationBindings = Collections.unmodifiableMap(new LinkedHashMap<>(navigationBindings));
			annotations = List.copyOf(annotations);
		}
	}

	/**
	 * An unbound action, addressable from the service root.
	 *
	 * @param name its name in the container
	 * @param action the qualified name of the action
	 * @param entitySet the entity set its result belongs to, or {@code null}
	 * @param annotations its annotations
	 */
	public record ActionImport(String name, String action, @Nullable String entitySet,
			List<CsdlAnnotation> annotations) {
		/** Validates and copies the components. */
		public ActionImport {
			Objects.requireNonNull(name, "name");
			Objects.requireNonNull(action, "action");
			annotations = List.copyOf(annotations);
		}
	}

	/**
	 * An unbound function, addressable from the service root.
	 *
	 * @param name its name in the container
	 * @param function the qualified name of the function
	 * @param entitySet the entity set its result belongs to, or {@code null}
	 * @param annotations its annotations
	 */
	public record FunctionImport(String name, String function, @Nullable String entitySet,
			List<CsdlAnnotation> annotations) {
		/** Validates and copies the components. */
		public FunctionImport {
			Objects.requireNonNull(name, "name");
			Objects.requireNonNull(function, "function");
			annotations = List.copyOf(annotations);
		}
	}
}
