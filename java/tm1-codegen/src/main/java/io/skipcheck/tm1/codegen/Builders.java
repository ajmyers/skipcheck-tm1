// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.codegen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlEntityContainer;
import io.skipcheck.tm1.csdl.CsdlEntityType;
import io.skipcheck.tm1.csdl.CsdlNavigationProperty;
import io.skipcheck.tm1.csdl.CsdlProperty;
import io.skipcheck.tm1.csdl.CsdlStructuredType;
import io.skipcheck.tm1.csdl.CsdlTypeRef;

/**
 * The write plan: everything about each write type ({@code CubeCreate},
 * {@code CubePatch}) that a client's generator needs, worked out once (#353). Which
 * setters it has and what each takes, what its {@code build()} requires, what
 * {@code builder(read)} copies and binds, and how a hierarchy's root picks its subtype's.
 * The spec exports it as {@code builders}; the Java generator generates from it, and the
 * Python, .NET and TypeScript generators print it, so none of them re-derives a rule.
 *
 * <p>
 * What a write type offers comes from {@link Offers}: only what the probe verified, or
 * the overlay keeps. Without offers (no probe file), every type gets a patch offering
 * what the writes don't refuse, and the types the writes cover a create: the Java
 * generator's tests generate that way.
 *
 * <p>
 * The rules:
 * <ul>
 * <li>A setter of a structured value takes that type's write type: a complex type's
 * {@code Patch}; an entity's {@code Create} where it has one (a nested entity is new),
 * else its {@code Patch}. A property TM1 sends otherwise than declared (a departure)
 * takes its value as read.</li>
 * <li>{@code builder(read)} copies the properties set (not navigations), as read, or
 * through the value's own write type: its {@code builder(read)}, or a root's
 * {@code of(read)}. A value whose type is a root and is written as a create isn't copied:
 * which subtype to make the read doesn't say. A patch leaves out what a PATCH doesn't
 * apply, and a collection it appends to (copying one would add its members again),
 * ignores or rejects.</li>
 * <li>It binds a navigation it holds expanded where its targets' paths follow from the
 * read: an entity set's by its single key, an element's or member's by its
 * {@code UniqueName}. A patch binds no collection: sent back, it would replace or append
 * to what's there, whichever TM1 does for it, on an unrelated change.</li>
 * <li>An open type's {@code builder(read)} copies the properties {@code $metadata}
 * doesn't declare too: {@code undeclared} lists those it does, to skip.</li>
 * <li>A hierarchy's root dispatches on the read's type: its create to the subtype's
 * create (none for a type with no create), its patch's {@code of(read)} to the subtype's
 * patch, falling back to its own {@code fromRead} for a type the client doesn't
 * know.</li>
 * </ul>
 *
 * @param builders per write type, by name, sorted
 */
public record Builders(Map<String, Builder> builders) {

	private static final String BIND = "@odata.bind";

	/**
	 * A write type.
	 *
	 * @param name its name, such as {@code CubeCreate}
	 * @param type the type it writes, qualified
	 * @param write {@code create} or {@code patch}
	 * @param base a subtype's: its root's write type of the same kind
	 * @param odataType a subtype's: the {@code @odata.type} it sends first
	 * @param open whether it takes properties {@code $metadata} doesn't declare
	 * @param required what its {@code build()} checks is set, by JSON name without
	 * {@code @odata.bind} (either form will do)
	 * @param setters its setters, in the order they're declared: the root's level first,
	 * then the type's, each level's properties before its navigations
	 * @param fromRead what {@code builder(read)} does; for a root's patch, what its
	 * {@code of(read)} does with a read of a type the client doesn't know; null for a
	 * root's create
	 * @param dispatch a root's: its subtypes' write types, by the read's type
	 */
	public record Builder(String name, String type, String write, @Nullable String base, @Nullable String odataType,
			boolean open, List<String> required, List<Setter> setters, @Nullable FromRead fromRead,
			@Nullable List<Case> dispatch) {

		/** Copies the lists. */
		public Builder {
			required = List.copyOf(required);
			setters = List.copyOf(setters);
			dispatch = dispatch == null ? null : List.copyOf(dispatch);
		}

		/**
		 * Whether it creates.
		 * @return whether it's a {@code Create}
		 */
		public boolean create() {
			return write.equals("create");
		}

		/**
		 * A setter, by the JSON name of its property or navigation.
		 * @param json the name, such as {@code Dimensions}
		 * @return the setter, if it has one
		 */
		public Optional<Setter> setter(String json) {
			return setters.stream().filter(s -> s.json().equals(json)).findFirst();
		}

	}

	/**
	 * A property's or navigation's setters.
	 *
	 * @param json its name in TM1's JSON
	 * @param declaredOn the type that declares it, qualified: the write type's own, or
	 * its root's
	 * @param navigation whether it's a navigation
	 * @param collection whether it's a collection
	 * @param set whether it has a setter of its value (for a navigation, the nested one,
	 * for a deep insert)
	 * @param bind whether it has an {@code @odata.bind} setter (a navigation to existing
	 * entities)
	 * @param value the write type its value setter takes, or null for anything else (a
	 * primitive, an enum, a departure)
	 */
	public record Setter(String json, String declaredOn, boolean navigation, boolean collection, boolean set,
			boolean bind, @Nullable String value) {
	}

	/**
	 * What {@code builder(read)} does.
	 *
	 * @param copy the properties it copies from the read, in order
	 * @param bindings the navigations it binds, in order
	 * @param undeclared an open type's: every name {@code $metadata} declares for it,
	 * which the copy of undeclared properties skips; null for a type that isn't open
	 */
	public record FromRead(List<Copy> copy, List<Binding> bindings, @Nullable List<String> undeclared) {

		/** Copies the lists. */
		public FromRead {
			copy = List.copyOf(copy);
			bindings = List.copyOf(bindings);
			undeclared = undeclared == null ? null : List.copyOf(undeclared);
		}

	}

	/**
	 * A property {@code builder(read)} copies.
	 *
	 * @param json its name
	 * @param via how the read value becomes the setter's: null as read, {@code builder}
	 * through {@code value}'s {@code builder(read)}, {@code of} through the root
	 * {@code value}'s {@code of(read)}
	 * @param value the write type it goes through, or null
	 */
	public record Copy(String json, @Nullable String via, @Nullable String value) {
	}

	/**
	 * A navigation {@code builder(read)} binds: only when every target's path can be
	 * made, and a collection only when it has members.
	 *
	 * @param json the navigation's name
	 * @param collection whether it's a collection
	 * @param by {@code key}: {@code set('key')}; {@code uniqueName}:
	 * {@code Dimensions('d')/Hierarchies('h')/set('e')} from the target's
	 * {@code UniqueName}
	 * @param set for {@code key}, the entity set; for {@code uniqueName}, the hierarchy's
	 * collection ({@code Elements}, {@code Members})
	 * @param key the target's property its path is made from
	 */
	public record Binding(String json, boolean collection, String by, String set, String key) {
	}

	/**
	 * A root's subtype, and the write type a read of it goes to.
	 *
	 * @param type the subtype, qualified
	 * @param builder its write type of the same kind
	 */
	public record Case(String type, String builder) {
	}

	/** Copies the map, sorted. */
	public Builders {
		builders = Collections.unmodifiableMap(new TreeMap<>(builders));
	}

	/**
	 * A write type.
	 * @param name its name, such as {@code CubeCreate}
	 * @return it, or null if it isn't generated
	 */
	public @Nullable Builder get(String name) {
		return builders.get(name);
	}

	/**
	 * Works out the write plan.
	 * @param metadata the {@code $metadata} document, with the undeclared properties
	 * added
	 * @param overlay the overlay, for its departures
	 * @param writes what the probe found writes do
	 * @param offers the write types and their setters; with none, every type gets a patch
	 * @return the plan
	 * @throws IllegalArgumentException if a type derives from a derived type
	 * @throws IllegalStateException if a root has a patch and a subtype that isn't
	 * abstract has none (its {@code of(read)} couldn't write a read of it), or a
	 * subtype's create needs less than its root's
	 */
	public static Builders of(CsdlDocument metadata, Overlay overlay, Writes writes, Offers offers) {
		return new Planner(metadata, overlay, writes, offers).plan();
	}

	private static final class Planner {

		private final CsdlDocument metadata;

		private final Overlay overlay;

		private final Writes writes;

		private final Offers offers;

		/** Roots of hierarchies, by qualified name, with their subtypes, by name. */
		private final Map<String, List<CsdlStructuredType>> hierarchies = new LinkedHashMap<>();

		/** Each subtype's root, by the subtype's qualified name. */
		private final Map<String, CsdlStructuredType> roots = new LinkedHashMap<>();

		Planner(CsdlDocument metadata, Overlay overlay, Writes writes, Offers offers) {
			this.metadata = metadata;
			this.overlay = overlay;
			this.writes = writes;
			this.offers = offers;
			metadata.structuredTypes().sorted(Comparator.comparing(CsdlStructuredType::name)).forEach(type -> {
				List<CsdlStructuredType> chain = metadata.typeHierarchy(qualified(type));
				if (chain.size() > 2) {
					throw new IllegalArgumentException(type.name() + " derives from " + chain.get(1).name()
							+ ", itself derived: only one level of inheritance is supported");
				}
				if (chain.size() == 2) {
					hierarchies.computeIfAbsent(qualified(chain.get(1)), k -> new ArrayList<>()).add(type);
					roots.put(qualified(type), chain.get(1));
				}
			});
		}

		Builders plan() {
			Map<String, Builder> out = new TreeMap<>();
			metadata.structuredTypes().sorted(Comparator.comparing(CsdlStructuredType::name)).forEach(t -> {
				if (patchable(t)) {
					out.put(t.name() + "Patch", builder(t, false));
				}
				if (creatable(t)) {
					out.put(t.name() + "Create", builder(t, true));
				}
			});
			return new Builders(out);
		}

		private Builder builder(CsdlStructuredType type, boolean create) {
			String suffix = create ? "Create" : "Patch";
			CsdlStructuredType root = roots.get(qualified(type));
			List<CsdlStructuredType> subtypes = hierarchies.get(qualified(type));
			List<Setter> setters = new ArrayList<>();
			if (root != null) {
				setters.addAll(setters(root, type, create));
			}
			setters.addAll(setters(type, type, create));
			List<String> required = create ? required(type) : List.of();
			if (root != null && create) {
				List<String> more = new ArrayList<>(required(root));
				more.removeAll(required);
				if (!more.isEmpty()) {
					throw new IllegalStateException(root.name() + "Create needs " + String.join(", ", more) + ", which "
							+ type.name() + "Create doesn't");
				}
			}
			FromRead fromRead = null;
			List<Case> dispatch = null;
			if (subtypes == null) {
				fromRead = fromRead(type, create, setters);
			}
			else if (create) {
				dispatch = subtypes.stream()
					.filter(this::creatable)
					.map(s -> new Case(qualified(s), s.name() + suffix))
					.toList();
			}
			else {
				// A subtype without a patch would reach the fallback, and be written as
				// its
				// root: refuse it instead
				for (CsdlStructuredType sub : subtypes) {
					if (!sub.isAbstract() && !patchable(sub)) {
						throw new IllegalStateException(type.name() + suffix + " has a subtype without a patch, "
								+ sub.name() + ", which its of() can't write: offer " + sub.name()
								+ "Patch, or none of the hierarchy's");
					}
				}
				dispatch = subtypes.stream()
					.filter(this::patchable)
					.map(s -> new Case(qualified(s), s.name() + suffix))
					.toList();
				fromRead = fromRead(type, false, setters);
			}
			return new Builder(type.name() + suffix, qualified(type), create ? "create" : "patch",
					root == null ? null : root.name() + suffix, root == null ? null : "#" + qualified(type), open(type),
					required, setters, fromRead, dispatch);
		}

		/**
		 * The setters a write of {@code type} has for the properties {@code level}
		 * declares ({@code type}'s own, or its root's).
		 */
		private List<Setter> setters(CsdlStructuredType level, CsdlStructuredType type, boolean create) {
			Map<String, Offers.Offer> offered = offers.builders().isEmpty() ? null : Objects.requireNonNullElse(
					offers.get(type.name() + (create ? "Create" : "Patch")), Map.<String, Offers.Offer>of());
			List<Setter> out = new ArrayList<>();
			for (CsdlProperty p : level.properties()) {
				if (p.type().name().equals("Edm.Stream")) {
					// Media content, read through $value, never part of the JSON
					continue;
				}
				boolean set = offered != null ? offered.containsKey(p.name())
						: create ? creates(type, p.name()) : offersInPatch(type, p.name());
				if (set) {
					boolean departs = overlay.departures().containsKey(level.name() + "/" + p.name());
					out.add(new Setter(p.name(), qualified(level), false, p.type().collection(), true, false,
							departs ? null : valueType(p.type()).orElse(null)));
				}
			}
			for (CsdlNavigationProperty n : level.navigationProperties()) {
				boolean entity = metadata.entityType(metadata.qualify(n.type().name())).isPresent();
				boolean set;
				boolean bind;
				if (offered != null) {
					set = offered.containsKey(n.name());
					bind = entity && offered.containsKey(n.name() + BIND);
				}
				else {
					set = create || offersInPatch(type, n.name());
					bind = entity && set;
				}
				if (set || bind) {
					out.add(new Setter(n.name(), qualified(level), true, n.type().collection(), set, bind,
							valueType(n.type()).orElse(null)));
				}
			}
			return out;
		}

		/**
		 * The write type a setter of a structured value takes: a complex type's patch; an
		 * entity's create, where it has one (a nested entity is new), else its patch.
		 */
		private Optional<String> valueType(CsdlTypeRef ref) {
			if (ref.name().startsWith("Edm.")) {
				return Optional.empty();
			}
			return metadata.structuredType(metadata.qualify(ref.name()))
				.map(t -> t.name() + (creatable(t) ? "Create" : "Patch"));
		}

		private FromRead fromRead(CsdlStructuredType type, boolean create, List<Setter> setters) {
			Writes.TypeWrites w = writes.get(type.name());
			List<Copy> copy = new ArrayList<>();
			for (Setter s : setters) {
				if (s.navigation() || !s.set() || (!create && w != null && !w.patches(s.json()))) {
					continue;
				}
				if (s.value() == null) {
					copy.add(new Copy(s.json(), null, null));
					continue;
				}
				boolean rootValue = root(s.value());
				if (rootValue && s.value().endsWith("Create")) {
					// A root's create starts from a read only by its type, which a nested
					// value's read doesn't settle
					continue;
				}
				copy.add(new Copy(s.json(), rootValue ? "of" : "builder", s.value()));
			}
			List<Binding> bindings = new ArrayList<>();
			for (Setter s : setters) {
				if (s.navigation() && s.bind() && (create || !s.collection())) {
					binding(type, s).ifPresent(bindings::add);
				}
			}
			if (!open(type)) {
				return new FromRead(copy, bindings, null);
			}
			List<String> declared = new ArrayList<>();
			List<CsdlStructuredType> rootFirst = new ArrayList<>(metadata.typeHierarchy(qualified(type)));
			Collections.reverse(rootFirst);
			for (CsdlStructuredType level : rootFirst) {
				level.properties().forEach(p -> declared.add(p.name()));
				level.navigationProperties().forEach(n -> declared.add(n.name()));
			}
			return new FromRead(copy, bindings, declared);
		}

		/**
		 * How a navigation a read holds is bound: by key for a target in an entity set,
		 * by {@code UniqueName} for an element or a member; empty for any other (a
		 * subset, public or private, or a view, whose cube the read doesn't say).
		 */
		private Optional<Binding> binding(CsdlStructuredType type, Setter s) {
			CsdlNavigationProperty nav = null;
			for (CsdlStructuredType level : metadata.typeHierarchy(qualified(type))) {
				for (CsdlNavigationProperty n : level.navigationProperties()) {
					if (n.name().equals(s.json())) {
						nav = n;
					}
				}
			}
			if (nav == null) {
				return Optional.empty();
			}
			Optional<CsdlStructuredType> target = metadata.structuredType(metadata.qualify(nav.type().name()));
			if (target.isEmpty() || !(target.get() instanceof CsdlEntityType)) {
				return Optional.empty();
			}
			// The target's type, then the types it derives from
			String set = null;
			List<String> key = List.of();
			for (CsdlStructuredType level : metadata.typeHierarchy(qualified(target.get()))) {
				if (level instanceof CsdlEntityType e && !e.key().isEmpty() && key.isEmpty()) {
					key = e.key();
				}
				if (set == null) {
					String q = qualified(level);
					set = metadata.entityContainer()
						.flatMap(c -> c.entitySets()
							.stream()
							.filter(e -> metadata.qualify(e.entityType()).equals(q))
							.map(CsdlEntityContainer.EntitySet::name)
							.findFirst())
						.orElse(null);
				}
			}
			if (set != null && key.size() == 1) {
				return Optional.of(new Binding(s.json(), s.collection(), "key", set, key.get(0)));
			}
			String name = target.get().name();
			if (name.equals("Element") || name.equals("Member")) {
				return Optional.of(new Binding(s.json(), s.collection(), "uniqueName", name + "s", "UniqueName"));
			}
			return Optional.empty();
		}

		/** Whether a write type writes a hierarchy's root. */
		private boolean root(String builder) {
			String type = builder.replaceAll("(Create|Patch)$", "");
			return metadata.structuredTypes()
				.filter(t -> t.name().equals(type))
				.findFirst()
				.map(t -> hierarchies.containsKey(qualified(t)))
				.orElse(false);
		}

		/**
		 * Whether a type gets a create: an entity type the offers list one for; without
		 * offers, one the probe has writes for, or the root of a hierarchy whose subtype
		 * does.
		 */
		private boolean creatable(CsdlStructuredType type) {
			if (!(type instanceof CsdlEntityType)) {
				return false;
			}
			if (!offers.builders().isEmpty()) {
				return offers.get(type.name() + "Create") != null;
			}
			if (writes.get(type.name()) != null) {
				return true;
			}
			List<CsdlStructuredType> subtypes = hierarchies.get(qualified(type));
			return subtypes != null && subtypes.stream().anyMatch(s -> writes.get(s.name()) != null);
		}

		/** Whether a type gets a patch: every type does, unless the offers say which. */
		private boolean patchable(CsdlStructuredType type) {
			return offers.builders().isEmpty() || offers.get(type.name() + "Patch") != null;
		}

		/**
		 * Without offers, whether a patch of {@code type} offers a property: not if a
		 * PATCH rejects or ignores it; for the root of a hierarchy without writes of its
		 * own, if every probed subtype's patch offers it.
		 */
		private boolean offersInPatch(CsdlStructuredType type, String property) {
			Writes.TypeWrites w = writes.get(type.name());
			if (w != null) {
				return w.offersInPatch(property);
			}
			List<Writes.TypeWrites> probed = hierarchies.getOrDefault(qualified(type), List.of())
				.stream()
				.map(s -> writes.get(s.name()))
				.filter(Objects::nonNull)
				.toList();
			return probed.isEmpty() || probed.stream().allMatch(s -> s.offersInPatch(property));
		}

		/**
		 * Without offers, whether a POST of {@code type} takes a property: for the root
		 * of a hierarchy without writes of its own, if every subtype that has writes
		 * takes it.
		 */
		private boolean creates(CsdlStructuredType type, String property) {
			Writes.TypeWrites w = writes.get(type.name());
			if (w != null) {
				return w.creates(property);
			}
			return hierarchies.getOrDefault(qualified(type), List.of())
				.stream()
				.map(s -> writes.get(s.name()))
				.filter(Objects::nonNull)
				.allMatch(s -> s.creates(property));
		}

		/** What a create of {@code type} needs, by name without {@code @odata.bind}. */
		private List<String> required(CsdlStructuredType type) {
			Writes.TypeWrites w = writes.get(type.name());
			return w == null ? List.of() : w.required().stream().map(r -> r.replace(BIND, "")).toList();
		}

		/** Whether a type, or a type it derives from, is open. */
		private boolean open(CsdlStructuredType type) {
			return metadata.typeHierarchy(qualified(type)).stream().anyMatch(CsdlStructuredType::openType);
		}

		private String qualified(CsdlStructuredType type) {
			return metadata.qualifiedName(type).orElse(type.name());
		}

	}

}
