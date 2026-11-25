// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.codegen;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;

import org.jspecify.annotations.Nullable;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;
import io.skipcheck.tm1.csdl.CsdlAnnotation;
import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlEntityType;
import io.skipcheck.tm1.csdl.CsdlEnumType;
import io.skipcheck.tm1.csdl.CsdlNavigationProperty;
import io.skipcheck.tm1.csdl.CsdlOperation;
import io.skipcheck.tm1.csdl.CsdlParameter;
import io.skipcheck.tm1.csdl.CsdlProperty;
import io.skipcheck.tm1.csdl.CsdlRevision;
import io.skipcheck.tm1.csdl.CsdlSchema;
import io.skipcheck.tm1.csdl.CsdlStructuredType;
import io.skipcheck.tm1.csdl.CsdlTypeRef;

/**
 * Generates the model from a {@code $metadata} document, into one package:
 *
 * <ul>
 * <li>a Java enum per CSDL enum type, with a fallback constant, so a newer server's new
 * member decodes instead of failing;</li>
 * <li>a record per complex and entity type: every component {@code @Nullable}, since
 * {@code $select} makes any property optional; navigation properties as components,
 * filled when expanded; an entity's {@code @odata.etag} as {@code etag}; unknown
 * properties ignored, and null components not sent.</li>
 * <li>a sealed interface per inheritance hierarchy ({@code View}, {@code Entry}, …) over
 * the records of its subtypes, read by {@code @odata.type}, which Jackson also writes
 * when one is sent; a nested {@code Plain} or {@code Unknown} record takes a value no
 * known subtype stands for.</li>
 * </ul>
 *
 * <p>
 * Properties of flags enum types stay {@code String} (TM1 sends them comma-separated).
 */
public final class ModelGenerator {

	/**
	 * What every generated file starts with, as the hand-written sources do: the license
	 * it's under.
	 */
	static final String LICENSE = "SPDX-License-Identifier: Apache-2.0";

	/** The package the model is generated into. */
	public static final String PACKAGE = "io.skipcheck.tm1.client.model";

	private static final AnnotationSpec GENERATED = AnnotationSpec
		.builder(ClassName.get("javax.annotation.processing", "Generated"))
		.addMember("value", "$S", "io.skipcheck.tm1.codegen")
		.build();

	private static final ClassName NULLABLE = ClassName.get("org.jspecify.annotations", "Nullable");

	private static final ClassName JSON_PROPERTY = ClassName.get("com.fasterxml.jackson.annotation", "JsonProperty");

	private static final ClassName JSON_IGNORE_PROPERTIES = ClassName.get("com.fasterxml.jackson.annotation",
			"JsonIgnoreProperties");

	private static final ClassName JSON_CREATOR = ClassName.get("com.fasterxml.jackson.annotation", "JsonCreator");

	private static final ClassName JSON_VALUE = ClassName.get("com.fasterxml.jackson.annotation", "JsonValue");

	private static final ClassName JSON_INCLUDE = ClassName.get("com.fasterxml.jackson.annotation", "JsonInclude");

	private static final ClassName JSON_TYPE_INFO = ClassName.get("com.fasterxml.jackson.annotation", "JsonTypeInfo");

	private static final ClassName JSON_TYPE_NAME = ClassName.get("com.fasterxml.jackson.annotation", "JsonTypeName");

	private static final ClassName JSON_SUB_TYPES = ClassName.get("com.fasterxml.jackson.annotation", "JsonSubTypes");

	private static final ClassName ODATA_PROPERTY = ClassName.get("io.skipcheck.tm1.client.odata", "ODataProperty");

	private static final ClassName ODATA_LITERAL = ClassName.get("io.skipcheck.tm1.client.odata", "ODataLiteral");

	private static final ClassName ODATA_LITERALS = ClassName.get("io.skipcheck.tm1.client.odata", "ODataLiterals");

	private static final ClassName ODATA_PATH = ClassName.get("io.skipcheck.tm1.client.odata", "ODataPath");

	private static final ClassName SINCE = ClassName.get("io.skipcheck.tm1.client.version", "Since");

	private static final ClassName TM1_DEPARTURE = ClassName.get("io.skipcheck.tm1.client.version", "Tm1Departure");

	private static final AnnotationSpec SUPPRESS_DEPRECATION = AnnotationSpec.builder(SuppressWarnings.class)
		.addMember("value", "$S", "deprecation")
		.build();

	private static final ClassName TM1_OPERATION = ClassName.get("io.skipcheck.tm1.client", "Tm1Operation");

	private static final ClassName JSON_ANY_SETTER = ClassName.get("com.fasterxml.jackson.annotation", "JsonAnySetter");

	private static final ClassName JSON_ANY_GETTER = ClassName.get("com.fasterxml.jackson.annotation", "JsonAnyGetter");

	/**
	 * The json name marking the component that holds an open type's dynamic properties.
	 */
	private static final String DYNAMIC = "*";

	/**
	 * Properties TM1 sends in another type than {@code $metadata} declares, by
	 * {@code Type/Property}, with what it does instead (from the overlay). Their
	 * components are {@code JsonNode}, so either form reads; each was found against a
	 * server.
	 */
	private final Map<String, String> departures;

	/**
	 * Date-time properties TM1 sometimes sends something else in, by
	 * {@code Type/Property}, with what (from the overlay): they're read leniently, as
	 * {@code null} when the text isn't a date-time.
	 */
	private final Map<String, String> lenientDates;

	private static final ClassName JSON_DESERIALIZE = ClassName.get("tools.jackson.databind.annotation",
			"JsonDeserialize");

	private static final ClassName LENIENT_DATE_TIME = ClassName.get("io.skipcheck.tm1.client.odata",
			"LenientOffsetDateTimeDeserializer");

	private static final ClassName JSON_NODE = ClassName.get("tools.jackson.databind", "JsonNode");

	private static final Set<String> KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case",
			"catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends",
			"false", "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
			"interface", "long", "native", "new", "null", "package", "private", "protected", "public", "return",
			"short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient",
			"true", "try", "void", "volatile", "while", "var", "record", "yield", "sealed", "permits");

	private final CsdlDocument metadata;

	/** What a POST and a PATCH do with each property, by type, from the probe. */
	private final Writes writes;

	/**
	 * {@code @Tm1Departure} for each member that departs from {@code $metadata}, by
	 * {@code Type/Property}.
	 */
	private final Map<String, Departure> tags = new LinkedHashMap<>();

	/** Roots of inheritance hierarchies, by qualified name, with their subtypes. */
	private final Map<String, List<CsdlStructuredType>> hierarchies = new LinkedHashMap<>();

	/** Each subtype's root, by the subtype's qualified name. */
	private final Map<String, CsdlStructuredType> roots = new HashMap<>();

	private final Offers offers;

	private final Overlay overlay;

	/** The write plan, once worked out: see {@link #plan()}. */
	private @Nullable Builders plan;

	/**
	 * A generator for {@code metadata}, with the bundled overlay.
	 * @param metadata the {@code $metadata} document
	 * @throws IllegalArgumentException if a type derives from a derived type, which TM1
	 * doesn't do and the generator doesn't support yet
	 */
	public ModelGenerator(CsdlDocument metadata) {
		this(metadata, Overlay.bundled());
	}

	/**
	 * A generator for {@code metadata}.
	 * @param metadata the {@code $metadata} document
	 * @param overlay what TM1 does differently from {@code metadata}
	 * @throws IllegalArgumentException if a type derives from a derived type, which TM1
	 * doesn't do and the generator doesn't support yet
	 */
	public ModelGenerator(CsdlDocument metadata, Overlay overlay) {
		this(metadata, overlay, Undeclared.NONE);
	}

	/**
	 * A generator for {@code metadata}, with properties a probe found that it doesn't
	 * declare.
	 * @param metadata the {@code $metadata} document, with the undeclared properties
	 * {@linkplain Undeclared#applyTo(CsdlDocument) added}
	 * @param overlay what TM1 does differently from {@code metadata}, with the undeclared
	 * properties' {@linkplain Undeclared#applyTo(Overlay) departures}
	 * @param undeclared the undeclared properties, to tag
	 * @throws IllegalArgumentException if a type derives from a derived type, which TM1
	 * doesn't do and the generator doesn't support yet
	 */
	public ModelGenerator(CsdlDocument metadata, Overlay overlay, Undeclared undeclared) {
		this(metadata, overlay, undeclared, Writes.NONE);
	}

	/**
	 * A generator for {@code metadata}, with what a probe found: the undeclared
	 * properties, and what a POST and a PATCH do with each property.
	 * @param metadata the {@code $metadata} document, with the undeclared properties
	 * added
	 * @param overlay what TM1 does differently from {@code metadata}, with the undeclared
	 * properties' departures
	 * @param undeclared the undeclared properties, to tag
	 * @param writes what writes do, for the create types and {@code from}
	 * @throws IllegalArgumentException if a type derives from a derived type
	 */
	public ModelGenerator(CsdlDocument metadata, Overlay overlay, Undeclared undeclared, Writes writes) {
		this(metadata, overlay, undeclared, writes, Offers.NONE);
	}

	/**
	 * A generator for {@code metadata}, with what a probe found, and the write types to
	 * generate: only those {@code offers} lists, each with only its setters.
	 * @param metadata the {@code $metadata} document, with the undeclared properties
	 * added
	 * @param overlay what TM1 does differently from {@code metadata}, with the undeclared
	 * properties' departures
	 * @param undeclared the undeclared properties, to tag
	 * @param writes what writes do, for what a create needs and {@code from}
	 * @param offers the write types and their setters; with none, every type gets a
	 * patch, offering what the writes don't refuse
	 * @throws IllegalArgumentException if a type derives from a derived type
	 */
	public ModelGenerator(CsdlDocument metadata, Overlay overlay, Undeclared undeclared, Writes writes, Offers offers) {
		this.metadata = metadata;
		this.writes = writes;
		this.offers = offers;
		this.overlay = overlay;
		overlay.departures().forEach((key, note) -> this.tags.put(key, new Departure("TYPE", List.of(), plain(note))));
		overlay.lenientDates()
			.forEach((key, note) -> this.tags.put(key, new Departure("LENIENT_DATE", List.of(), plain(note))));
		undeclared.properties()
			.forEach((key, p) -> this.tags.put(key,
					new Departure("UNDECLARED", List.of(undeclared.schemaVersion()), plain(p.note()))));
		this.departures = javadocs(overlay.departures());
		this.lenientDates = javadocs(overlay.lenientDates());
		metadata.structuredTypes().sorted(Comparator.comparing(CsdlStructuredType::name)).forEach(type -> {
			List<CsdlStructuredType> chain = metadata.typeHierarchy(qualified(type));
			if (chain.size() > 2) {
				throw new IllegalArgumentException(type.name() + " derives from " + chain.get(1).name()
						+ ", itself derived: only one level of inheritance is supported");
			}
			if (chain.size() == 2) {
				CsdlStructuredType root = chain.get(1);
				hierarchies.computeIfAbsent(qualified(root), k -> new ArrayList<>()).add(type);
				roots.put(qualified(type), root);
			}
		});
	}

	private static Map<String, String> javadocs(Map<String, String> notes) {
		Map<String, String> result = new LinkedHashMap<>();
		notes.forEach((key, note) -> result.put(key, escape(Overlay.javadoc(note)).replace("&#64;code", "@code")));
		return result;
	}

	/**
	 * The generated sources.
	 * @return one file per type, enums first, then records and hierarchies, by name
	 */
	public List<JavaFile> generate() {
		List<JavaFile> files = new ArrayList<>();
		metadata.schemas()
			.stream()
			.flatMap(s -> s.enumTypes().stream())
			.sorted(Comparator.comparing(CsdlEnumType::name))
			.forEach(e -> files.add(file(revised(enumType(e), e.annotations()))));
		metadata.structuredTypes().sorted(Comparator.comparing(CsdlStructuredType::name)).forEach(t -> {
			List<CsdlStructuredType> subtypes = hierarchies.get(qualified(t));
			files.add(file(revised(subtypes != null ? hierarchy(t, subtypes) : record(t, roots.get(qualified(t))),
					t.annotations())));
		});
		metadata.structuredTypes()
			.filter(this::patchable)
			.sorted(Comparator.comparing(CsdlStructuredType::name))
			.forEach(t -> files.add(file(revised(patch(t), t.annotations()))));
		// Creates, for the entity types the probe has writes for, and the roots their
		// creates extend: a complex value is only ever part of another write
		metadata.structuredTypes()
			.filter(this::creatable)
			.sorted(Comparator.comparing(CsdlStructuredType::name))
			.forEach(t -> files.add(file(revised(create(t), t.annotations()))));
		files.add(file(catalog()));
		files.add(file(readBindings()));
		return files;
	}

	/**
	 * Writes the generated sources, and the package's {@code package-info.java}, under
	 * {@code directory}, replacing whatever the package held before.
	 * @param directory the source root to write into
	 * @throws IOException if writing fails
	 */
	public void writeTo(Path directory) throws IOException {
		Path packageDir = directory.resolve(PACKAGE.replace('.', '/'));
		if (Files.isDirectory(packageDir)) {
			try (Stream<Path> old = Files.list(packageDir)) {
				old.forEach(ModelGenerator::delete);
			}
		}
		for (JavaFile file : generate()) {
			file.writeTo(directory);
		}
		Files.createDirectories(packageDir);
		Files.writeString(packageDir.resolve("package-info.java"), packageInfo(), StandardCharsets.UTF_8);
	}

	private TypeSpec.Builder enumType(CsdlEnumType type) {
		ClassName name = ClassName.get(PACKAGE, javaTypeName(type.name()));
		Set<String> constants = new LinkedHashSet<>();
		TypeSpec.Builder spec = TypeSpec.enumBuilder(name)
			.addModifiers(Modifier.PUBLIC)
			.addAnnotation(GENERATED)
			.addJavadoc("$L", javadoc(description(type.annotations())
				.orElse("The {@code " + type.name() + "} enum type of the TM1 REST API.")
					+ "\n\n<p>\nGenerated from {@code $metadata}; the fallback constant stands for a value this client doesn't know, such as one a newer server added. It's never sent.\n"));
		for (CsdlEnumType.Member member : type.members()) {
			String constant = unique(constantName(member.name()), constants);
			Revisions revisions = revisions(member.annotations());
			spec.addEnumConstant(constant, TypeSpec.anonymousClassBuilder("$S", member.name())
				.addJavadoc("$L",
						javadoc(description(member.annotations()).orElse("{@code " + member.name() + "}.") + "\n")
								+ revisions.tag())
				.addAnnotations(revisions.annotations())
				.build());
		}
		String fallback = constants.contains("UNKNOWN") ? "UNRECOGNIZED" : "UNKNOWN";
		spec.addEnumConstant(fallback,
				TypeSpec.anonymousClassBuilder("$L", "(String) null")
					.addJavadoc("A value this client doesn't know.\n")
					.build());
		TypeName nullableString = ClassName.get(String.class).annotated(AnnotationSpec.builder(NULLABLE).build());
		spec.addField(FieldSpec.builder(nullableString, "json", Modifier.PRIVATE, Modifier.FINAL).build())
			.addMethod(MethodSpec.constructorBuilder()
				.addParameter(nullableString, "json")
				.addStatement("this.json = json")
				.build())
			.addMethod(MethodSpec.methodBuilder("json")
				.addModifiers(Modifier.PUBLIC)
				.addAnnotation(JSON_VALUE)
				.returns(nullableString)
				.addJavadoc(
						"The value as TM1 writes it.\n@return the value, or {@code null} for the fallback constant\n")
				.addStatement("return json")
				.build())
			.addSuperinterface(ODATA_LITERAL)
			.addMethod(MethodSpec.methodBuilder("odataLiteral")
				.addModifiers(Modifier.PUBLIC)
				.addAnnotation(Override.class)
				.returns(String.class)
				.addJavadoc(
						"The value as an OData literal, for filters: {@code $L'$L'}.\n@return the literal\n@throws IllegalStateException for {@code $L}, which has none\n",
						enumQualified(type), type.members().isEmpty() ? "Value" : type.members().get(0).name(),
						fallback)
				.beginControlFlow("if (json == null)")
				.addStatement("throw new IllegalStateException($S)",
						fallback + " stands for a value this client doesn't know, and has no literal")
				.endControlFlow()
				.addStatement("return $T.enumMember($S, json)", ODATA_LITERALS, enumQualified(type))
				.build())
			.addMethod(MethodSpec.methodBuilder("fromJson")
				.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
				.addAnnotation(AnnotationSpec.builder(JSON_CREATOR)
					.addMember("mode", "$T.Mode.DELEGATING", JSON_CREATOR)
					.build())
				.addParameter(nullableString, "value")
				.returns(name)
				.addJavadoc(
						"The constant for a value TM1 sent.\n@param value the value\n@return its constant, or {@code $L} if this client doesn't know it\n",
						fallback)
				.beginControlFlow("for ($T constant : values())", name)
				.beginControlFlow("if (constant.json != null && constant.json.equals(value))")
				.addStatement("return constant")
				.endControlFlow()
				.endControlFlow()
				.addStatement("return $L", fallback)
				.build());
		return spec;
	}

	/** A record component, or a hierarchy interface's accessor. */
	private record Component(String name, TypeName type, String json, String doc, Revisions revisions,
			boolean lenient) {

		Component(String name, TypeName type, String json, String doc, Revisions revisions) {
			this(name, type, json, doc, revisions, false);
		}

	}

	/**
	 * A type's components: an entity's ETag, then the properties and navigation
	 * properties of its root, then its own, so a subtype's components start with its
	 * root's.
	 */
	private List<Component> components(CsdlStructuredType type) {
		List<CsdlStructuredType> rootFirst = new ArrayList<>(metadata.typeHierarchy(qualified(type)));
		Collections.reverse(rootFirst);
		Set<String> names = new HashSet<>();
		List<Component> components = new ArrayList<>();
		if (type instanceof CsdlEntityType) {
			names.add("etag");
			components.add(new Component("etag", ClassName.get(String.class), "@odata.etag",
					"the entity's ETag ({@code @odata.etag}), for {@code If-Match}", Revisions.NONE));
		}
		for (CsdlStructuredType level : rootFirst) {
			for (CsdlProperty property : level.properties()) {
				String key = level.name() + "/" + property.name();
				String departure = departures.get(key);
				String lenient = lenientDates.get(key);
				javaType(property.type()).map(t -> departure == null ? t : (TypeName) JSON_NODE)
					.ifPresent(t -> components
						.add(new Component(unique(componentName(property.name()), names), t, property.name(),
								paramText(property.annotations(), property.name())
										+ (departure == null ? "" : ". " + departure)
										+ (lenient == null ? "" : ". " + lenient),
								revisions(level, property), lenient != null)));
			}
			for (CsdlNavigationProperty navigation : level.navigationProperties()) {
				javaType(navigation.type()).ifPresent(t -> components
					.add(new Component(unique(componentName(navigation.name()), names), t, navigation.name(),
							paramText(navigation.annotations(), navigation.name()) + " (when expanded)",
							revisions(navigation.annotations()))));
			}
		}
		if (open(type)) {
			components.add(new Component(unique("dynamicProperties", names),
					ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(String.class), JSON_NODE),
					DYNAMIC,
					"the properties {@code $metadata} doesn't declare, which an open type can have, such as attribute values",
					Revisions.NONE));
		}
		return components;
	}

	/**
	 * Whether a type, or a type it derives from, is open: it can have undeclared
	 * properties.
	 */
	private boolean open(CsdlStructuredType type) {
		return metadata.typeHierarchy(qualified(type)).stream().anyMatch(CsdlStructuredType::openType);
	}

	private TypeSpec.Builder record(CsdlStructuredType type, @Nullable CsdlStructuredType root) {
		String head = description(type.annotations()).orElse("The {@code " + type.name() + "} "
				+ (type instanceof CsdlEntityType ? "entity" : "complex") + " type of the TM1 REST API.");
		TypeSpec.Builder spec = recordSpec(ClassName.get(PACKAGE, javaTypeName(type.name())),
				head + kindNote(type, root), type instanceof CsdlEntityType, components(type));
		spec.addFields(constants(type));
		if (root != null) {
			// Asked for by name, it reads without @odata.type too, as a cast path returns
			// it
			ClassName self = ClassName.get(PACKAGE, javaTypeName(type.name()));
			spec.addSuperinterface(ClassName.get(PACKAGE, javaTypeName(root.name())))
				.addAnnotation(AnnotationSpec.builder(JSON_TYPE_INFO)
					.addMember("use", "$T.Id.NAME", JSON_TYPE_INFO)
					.addMember("include", "$T.As.PROPERTY", JSON_TYPE_INFO)
					.addMember("property", "$S", "@odata.type")
					.addMember("defaultImpl", "$T.class", self)
					.build())
				.addAnnotation(AnnotationSpec.builder(JSON_TYPE_NAME).addMember("value", "$S", typeId(type)).build());
		}
		return spec;
	}

	private String kindNote(CsdlStructuredType type, @Nullable CsdlStructuredType root) {
		return root == null ? "" : "\n\n<p>\nA kind of {@link " + javaTypeName(root.name())
				+ "}, read and sent with {@code @odata.type} {@code " + typeId(type) + "}.";
	}

	private TypeSpec.Builder recordSpec(ClassName name, String head, boolean entity, List<Component> components) {
		StringBuilder doc = new StringBuilder(head).append(
				"\n\n<p>\nGenerated from {@code $metadata}. Every component is nullable: {@code $select} can leave any property out")
			.append(entity ? ", and navigation properties are only there when expanded." : ".")
			.append(" Null components aren't sent.\n\n");
		MethodSpec.Builder constructor = MethodSpec.constructorBuilder();
		for (Component component : components) {
			ParameterSpec.Builder parameter = component(component.type(), component.name(), component.json())
				.toBuilder()
				.addAnnotations(component.revisions().annotations());
			if (component.lenient()) {
				parameter.addAnnotation(AnnotationSpec.builder(JSON_DESERIALIZE)
					.addMember("using", "$T.class", LENIENT_DATE_TIME)
					.build());
			}
			constructor.addParameter(parameter.build());
			doc.append("@param ")
				.append(component.name())
				.append(' ')
				.append(component.doc())
				.append(component.revisions().note())
				.append('\n');
		}
		return TypeSpec.recordBuilder(name)
			.addModifiers(Modifier.PUBLIC)
			.addAnnotation(GENERATED)
			.addAnnotation(AnnotationSpec.builder(JSON_IGNORE_PROPERTIES).addMember("ignoreUnknown", "true").build())
			.addAnnotation(AnnotationSpec.builder(JSON_INCLUDE)
				.addMember("value", "$T.Include.NON_NULL", JSON_INCLUDE)
				.build())
			.addJavadoc("$L", javadoc(doc.toString()))
			.recordConstructor(constructor.build());
	}

	/**
	 * A hierarchy's root: a sealed interface over its subtypes' records, read by
	 * {@code @odata.type}, with a nested record for a value no subtype stands for. That's
	 * {@code Plain} when the root isn't abstract (a {@code Member} that's just a member),
	 * and {@code Unknown} when it is (a subtype a newer server added).
	 */
	private TypeSpec.Builder hierarchy(CsdlStructuredType root, List<CsdlStructuredType> subtypes) {
		ClassName name = ClassName.get(PACKAGE, javaTypeName(root.name()));
		boolean entity = root instanceof CsdlEntityType;
		ClassName fallback = name.nestedClass(root.isAbstract() ? "Unknown" : "Plain");
		List<Component> components = components(root);
		String head = description(root.annotations()).orElse(
				"The {@code " + root.name() + "} " + (entity ? "entity" : "complex") + " type of the TM1 REST API.");

		TypeSpec.Builder spec = TypeSpec.interfaceBuilder(name)
			.addModifiers(Modifier.PUBLIC, Modifier.SEALED)
			.addAnnotation(GENERATED)
			.addAnnotation(AnnotationSpec.builder(JSON_TYPE_INFO)
				.addMember("use", "$T.Id.NAME", JSON_TYPE_INFO)
				.addMember("include", "$T.As.PROPERTY", JSON_TYPE_INFO)
				.addMember("property", "$S", "@odata.type")
				.addMember("defaultImpl", "$T.class", fallback)
				.build());
		AnnotationSpec.Builder known = AnnotationSpec.builder(JSON_SUB_TYPES);
		List<CsdlStructuredType> named = new ArrayList<>(subtypes);
		if (!root.isAbstract()) {
			named.add(root);
		}
		for (CsdlStructuredType type : named) {
			ClassName impl = type.equals(root) ? fallback : ClassName.get(PACKAGE, javaTypeName(type.name()));
			AnnotationSpec.Builder entry = AnnotationSpec.builder(JSON_SUB_TYPES.nestedClass("Type"))
				.addMember("value", "$T.class", impl)
				.addMember("name", "$S", typeId(type));
			aliasTypeId(type).ifPresent(alias -> entry.addMember("names", "$S", alias));
			known.addMember("value", "$L", entry.build());
		}
		spec.addAnnotation(known.build());
		spec.addFields(constants(root));
		subtypes.forEach(t -> spec.addPermittedSubclass(ClassName.get(PACKAGE, javaTypeName(t.name()))));
		spec.addPermittedSubclass(fallback);

		StringBuilder doc = new StringBuilder(head).append("\n\n<p>\nOne of ");
		for (int i = 0; i < subtypes.size(); i++) {
			doc.append(i == 0 ? "" : i == subtypes.size() - 1 ? " or " : ", ")
				.append("{@link ")
				.append(javaTypeName(subtypes.get(i).name()))
				.append('}');
		}
		doc.append(root.isAbstract()
				? ", chosen by {@code @odata.type}; {@link Unknown} when this client doesn't know the type."
				: ", chosen by {@code @odata.type}, or {@link Plain} for a value of this type itself (or of a type this client doesn't know).")
			.append(" Generated from {@code $metadata}.\n");
		spec.addJavadoc("$L", doc.toString());

		for (Component component : components) {
			spec.addMethod(MethodSpec.methodBuilder(component.name())
				.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
				.returns(component.type().annotated(AnnotationSpec.builder(NULLABLE).build()))
				.addJavadoc("$L",
						capitalize(component.doc()) + (component.doc().endsWith(".") ? "" : ".") + "\n@return "
								+ component.doc() + "\n" + component.revisions().tag())
				.addAnnotations(component.revisions().annotations())
				.build());
		}

		String fallbackHead = root.isAbstract() ? "A {@code " + root.name()
				+ "} of a type this client doesn't know, such as one a newer server added: only the properties all {@code "
				+ root.name() + "}s have are read. It's never sent."
				: "A {@code " + root.name() + "} that's no subtype ({@code @odata.type} {@code " + typeId(root)
						+ "}, or none), or of a subtype this client doesn't know: then only the {@code " + root.name()
						+ "} properties are read.";
		TypeSpec.Builder nested = recordSpec(fallback, fallbackHead, entity, components).addModifiers(Modifier.STATIC)
			.addSuperinterface(name);
		if (root.isAbstract()) {
			nested.addAnnotation(
					AnnotationSpec.builder(JSON_TYPE_INFO).addMember("use", "$T.Id.NONE", JSON_TYPE_INFO).build());
		}
		spec.addType(nested.build());
		return spec;
	}

	/**
	 * Field constants for a type's own properties, for queries: {@code Cube.NAME}. A
	 * subtype's inherited ones are its root interface's.
	 */
	private List<FieldSpec> constants(CsdlStructuredType type) {
		Set<String> names = new HashSet<>();
		List<FieldSpec> fields = new ArrayList<>();
		for (CsdlProperty property : type.properties()) {
			if (javaType(property.type()).isPresent()) {
				fields.add(constant(unique(constantName(property.name()), names), property.name(),
						paramText(property.annotations(), property.name()), revisions(type, property)));
			}
		}
		for (CsdlNavigationProperty navigation : type.navigationProperties()) {
			if (javaType(navigation.type()).isPresent()) {
				fields.add(constant(unique(constantName(navigation.name()), names), navigation.name(),
						paramText(navigation.annotations(), navigation.name())
								+ " (a navigation property, for {@code $expand})",
						revisions(navigation.annotations())));
			}
		}
		return fields;
	}

	private static FieldSpec constant(String name, String property, String doc, Revisions revisions) {
		return FieldSpec.builder(ODATA_PROPERTY, name, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
			.initializer("$T.of($S)", ODATA_PROPERTY, property)
			.addJavadoc("{@code $L}, for queries: $L\n$L", property, doc, revisions.tag())
			.addAnnotations(revisions.annotations())
			.build();
	}

	private String enumQualified(CsdlEnumType type) {
		return metadata.schemas()
			.stream()
			.filter(s -> s.enumTypes().contains(type))
			.findFirst()
			.map(s -> s.namespace() + "." + type.name())
			.orElse(type.name());
	}

	/**
	 * A write type's property, for its setter and bind methods: {@code set} whether it
	 * has its setter (for a navigation, the nested one), {@code bind} whether a
	 * navigation has its {@code @odata.bind} one.
	 */
	private record PatchProperty(String method, TypeName type, String json, String doc, boolean navigation,
			boolean collection, Revisions revisions, boolean set, boolean bind) {

		PatchProperty(String method, TypeName type, String json, String doc, boolean navigation, boolean collection,
				Revisions revisions) {
			this(method, type, json, doc, navigation, collection, revisions, true, navigation);
		}

		PatchProperty offering(boolean set, boolean bind, TypeName type) {
			return new PatchProperty(method, type, json, doc, navigation, collection, revisions, set, bind);
		}

	}

	private List<PatchProperty> patchProperties(CsdlStructuredType type) {
		Set<String> names = new HashSet<>(Set.of("build"));
		List<PatchProperty> properties = new ArrayList<>();
		for (CsdlProperty property : type.properties()) {
			boolean departs = departures.containsKey(type.name() + "/" + property.name());
			patchType(property.type()).map(t -> departs ? (TypeName) ClassName.get(Object.class) : t)
				.ifPresent(t -> properties.add(new PatchProperty(unique(componentName(property.name()), names), t,
						property.name(), paramText(property.annotations(), property.name()), false,
						property.type().collection(), revisions(type, property))));
		}
		for (CsdlNavigationProperty navigation : type.navigationProperties()) {
			patchType(navigation.type())
				.ifPresent(t -> properties.add(new PatchProperty(unique(componentName(navigation.name()), names), t,
						navigation.name(), paramText(navigation.annotations(), navigation.name()), true,
						navigation.type().collection(), revisions(navigation.annotations()))));
		}
		return properties;
	}

	/**
	 * What a write takes for a property: the property's own type, with JSON as any value;
	 * for a structured value, a placeholder the write plan's type replaces.
	 */
	private Optional<TypeName> patchType(CsdlTypeRef ref) {
		String qualified = metadata.qualify(ref.name());
		Optional<CsdlStructuredType> structured = ref.name().startsWith("Edm.") ? Optional.empty()
				: metadata.structuredType(qualified);
		if (structured.isEmpty()) {
			return javaType(ref).map(t -> {
				if (t.equals(JSON_NODE)) {
					return ClassName.get(Object.class);
				}
				if (t instanceof ParameterizedTypeName list && list.typeArguments().get(0).equals(JSON_NODE)) {
					return ParameterizedTypeName.get(ClassName.get(List.class), ClassName.get(Object.class));
				}
				return t;
			});
		}
		// A structured value's setter takes the write type the plan gives it (valueType):
		// this stands in until a setter is offered
		return Optional.of(ClassName.get(Object.class));
	}

	private static ClassName patchName(CsdlStructuredType type) {
		return ClassName.get(PACKAGE, javaTypeName(type.name()) + "Patch");
	}

	/**
	 * A type's patch: only the properties set are written, explicit nulls included, plus
	 * {@code @odata.bind} references, in the order they were set. A record can't do that,
	 * since it can't tell "not set" from "set to null". A subtype's patch extends its
	 * root's and writes {@code @odata.type} first.
	 */
	private TypeSpec.Builder patch(CsdlStructuredType type) {
		return write(type, false);
	}

	/**
	 * A type's create ({@code XCreate}): as its patch, but with only the properties a
	 * POST takes, by the probe's writes, and {@code build()} checks those a POST needs.
	 */
	private TypeSpec.Builder create(CsdlStructuredType type) {
		return write(type, true);
	}

	private TypeSpec.Builder write(CsdlStructuredType type, boolean create) {
		ClassName name = create ? createName(type) : patchName(type);
		if (metadata.structuredTypes().anyMatch(t -> javaTypeName(t.name()).equals(name.simpleName()))) {
			throw new IllegalArgumentException(
					name.simpleName() + " is a CSDL type, so its write type needs another name");
		}
		CsdlStructuredType root = roots.get(qualified(type));
		Builders.Builder plan = plan(name);
		boolean extensible = hierarchies.containsKey(qualified(type));
		ClassName builder = name.nestedClass("Builder");
		TypeName valuesType = ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(String.class),
				ClassName.get(Object.class).annotated(AnnotationSpec.builder(NULLABLE).build()));
		String record = javaTypeName(type.name());

		TypeSpec.Builder spec = TypeSpec.classBuilder(name)
			.addModifiers(extensible ? new Modifier[] { Modifier.PUBLIC }
					: new Modifier[] { Modifier.PUBLIC, Modifier.FINAL })
			.addAnnotation(GENERATED)
			.addJavadoc("$L", (create ? "A create of {@link " + record
					+ "}, for a {@code POST}: only the properties set are sent, and only those TM1 applies in a create are offered"
					+ (writes.get(type.name()) == null ? " (this type has no write probe yet, so all are)"
							: ", as the probe found (" + writes.schemaVersion() + ")")
					+ ". {@code build()} checks what a create needs. {@code builder(read)} starts one from a read entity, to recreate it.\n\n<p>\n"
					: "") + "A write of {@link " + record
					+ "}: for a {@code PATCH}, only the properties set are sent, an explicit {@code null} included; for a {@code POST}, the new entity's properties, nested patches for deep inserts and {@code @odata.bind} references to existing entities.\n\n<p>\n"
					+ (root != null ? "Extends {@link " + (create ? createName(root) : patchName(root)).simpleName()
							+ "} and sends {@code @odata.type} {@code " + plan.odataType() + "}. " : "")
					+ (extensible ? "Each subtype's patch extends this one. " : "")
					+ "Generated from {@code $metadata}.\n");
		if (root != null) {
			spec.superclass(create ? createName(root) : patchName(root));
			spec.addMethod(MethodSpec.constructorBuilder()
				.addParameter(valuesType, "values")
				.addStatement("super(values)")
				.build());
		}
		else {
			spec.addField(FieldSpec.builder(valuesType, "values", Modifier.PRIVATE, Modifier.FINAL).build())
				.addMethod(MethodSpec.constructorBuilder()
					.addParameter(valuesType, "values")
					.addStatement("this.values = $T.unmodifiableMap(new $T<>(values))", Collections.class,
							LinkedHashMap.class)
					.build())
				.addMethod(MethodSpec.methodBuilder("json")
					.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
					.addAnnotation(JSON_VALUE)
					.returns(valuesType)
					.addJavadoc(
							"The properties set, by their names in TM1's JSON, in the order they were set.\n@return the properties, unmodifiable\n")
					.addStatement("return values")
					.build())
				.addMethod(MethodSpec.methodBuilder("equals")
					.addAnnotation(Override.class)
					.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
					.addParameter(ClassName.get(Object.class).annotated(AnnotationSpec.builder(NULLABLE).build()),
							"other")
					.returns(boolean.class)
					.addStatement(
							"return other != null && other.getClass() == getClass() && values.equals((($T) other).values)",
							name)
					.build())
				.addMethod(MethodSpec.methodBuilder("hashCode")
					.addAnnotation(Override.class)
					.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
					.returns(int.class)
					.addStatement("return values.hashCode()")
					.build())
				.addMethod(MethodSpec.methodBuilder("toString")
					.addAnnotation(Override.class)
					.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
					.returns(String.class)
					.addStatement("return getClass().getSimpleName() + values")
					.build());
		}
		spec.addMethod(MethodSpec.methodBuilder("builder")
			.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
			.returns(builder)
			.addJavadoc("A builder with nothing set" + (root != null ? " but {@code @odata.type}" : "")
					+ ".\n@return a new builder\n")
			.addStatement("return new $T()", builder)
			.build());

		TypeSpec.Builder b = TypeSpec.classBuilder(builder)
			.addModifiers(extensible ? new Modifier[] { Modifier.PUBLIC, Modifier.STATIC }
					: new Modifier[] { Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL })
			.addJavadoc("Builds a {@link $T}; each setter replaces what an earlier call set.\n", name);
		MethodSpec.Builder ctor = MethodSpec.constructorBuilder();
		if (root != null) {
			b.superclass((create ? createName(root) : patchName(root)).nestedClass("Builder"));
			ctor.addStatement("this.values.put($S, $S)", "@odata.type", plan.odataType());
			for (PatchProperty inherited : writable(root, type, create)) {
				addPatchMethods(b, builder, inherited, true);
			}
		}
		else {
			b.addField(
					FieldSpec
						.builder(
								ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(String.class),
										ClassName.get(Object.class)
											.annotated(AnnotationSpec.builder(NULLABLE).build())),
								"values", Modifier.FINAL)
						.initializer("new $T<>()", LinkedHashMap.class)
						.build());
		}
		b.addMethod(ctor.build());
		for (PatchProperty own : writable(type, type, create)) {
			addPatchMethods(b, builder, own, false);
		}
		if (plan.open()) {
			MethodSpec.Builder dynamic = MethodSpec.methodBuilder("property")
				.addModifiers(Modifier.PUBLIC)
				.addParameter(String.class, "name")
				.addParameter(ClassName.get(Object.class).annotated(AnnotationSpec.builder(NULLABLE).build()), "value")
				.returns(builder)
				.addJavadoc(
						"Sets a property {@code $$metadata} doesn't declare, which this open type can have, such as an attribute's value.\n@param name the property's name, as TM1 writes it\n@param value the value; {@code null} sends an explicit {@code null}\n@return this builder\n");
			if (plan.base() != null && plan(ClassName.get(PACKAGE, plan.base())).open()) {
				dynamic.addAnnotation(Override.class).addStatement("super.property(name, value)");
			}
			else {
				dynamic.addStatement("this.values.put($T.requireNonNull(name, $S), value)", Objects.class, "name");
			}
			b.addMethod(dynamic.addStatement("return this").build());
		}
		MethodSpec.Builder build = MethodSpec.methodBuilder("build")
			.addModifiers(Modifier.PUBLIC)
			.returns(name)
			.addJavadoc("The $L, with what's been set so far.\n@return the $L\n$L", create ? "create" : "patch",
					create ? "create" : "patch", create && !required(type).isEmpty()
							? "@throws IllegalStateException if it lacks what a create needs\n" : "");
		if (create) {
			for (String needed : required(type)) {
				String bare = needed.replace("@odata.bind", "");
				build
					.beginControlFlow("if (!values.containsKey($S) && !values.containsKey($S))", bare,
							bare + "@odata.bind")
					.addStatement("throw new $T($S)", IllegalStateException.class,
							"A " + type.name() + " create needs " + bare + ": TM1 refuses one without it")
					.endControlFlow();
			}
		}
		build.addStatement("return new $T(values)", name);
		if (root != null) {
			build.addAnnotation(Override.class);
		}
		b.addMethod(build.build());
		spec.addType(b.build());
		List<CsdlStructuredType> subtypes = hierarchies.get(qualified(type));
		if (subtypes == null) {
			spec.addMethod(from(type, root, builder, create));
		}
		else if (create) {
			// A hierarchy's root: its create starts from a read of any of its types, with
			// that type's builder, which extends this one's
			ClassName rootRecord = ClassName.get(PACKAGE, javaTypeName(type.name()));
			CodeBlock.Builder cases = CodeBlock.builder();
			for (Builders.Case c : Objects.requireNonNull(plan(name).dispatch())) {
				cases.beginControlFlow("if (read instanceof $T v)", ClassName.get(PACKAGE, simple(c.type())))
					.addStatement("return $T.builder(v)", ClassName.get(PACKAGE, c.builder()))
					.endControlFlow();
			}
			cases.addStatement("throw new $T($S + read.getClass().getSimpleName() + $S)",
					IllegalArgumentException.class, "A ", " has no create: TM1 makes none of its type");
			spec.addMethod(MethodSpec.methodBuilder("builder")
				.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
				.addParameter(rootRecord, "read")
				.returns(builder)
				.addJavadoc(
						"A create of what {@code read} holds that a {@code POST} applies, to recreate it, by its type: that type's {@code builder(read)}.\n@param read an entity read from TM1\n@return a builder\n@throws IllegalArgumentException if TM1 makes none of its type\n")
				.addCode(cases.build())
				.build());
		}
		else {
			// A hierarchy's root: its patch reads a value of any of its types, by its
			// type
			ClassName rootRecord = ClassName.get(PACKAGE, javaTypeName(type.name()));
			ClassName fallback = rootRecord.nestedClass(type.isAbstract() ? "Unknown" : "Plain");
			spec.addMethod(from(type, null, builder, false, fallback));
			// instanceof, not a switch on the type: the client is built for Java 17. The
			// plan refuses a subtype without a patch, which would reach the cast to the
			// fallback below and fail at run time
			CodeBlock.Builder cases = CodeBlock.builder();
			for (Builders.Case c : Objects.requireNonNull(plan(name).dispatch())) {
				cases.beginControlFlow("if (read instanceof $T v)", ClassName.get(PACKAGE, simple(c.type())))
					.addStatement("return $T.builder(v).build()", ClassName.get(PACKAGE, c.builder()))
					.endControlFlow();
			}
			cases.addStatement("return builder(($T) read).build()", fallback);
			spec.addMethod(MethodSpec.methodBuilder("of")
				.addModifiers(Modifier.STATIC)
				.addParameter(rootRecord, "read")
				.returns(name)
				.addJavadoc(
						"A patch of what {@code read} holds that a write takes, by its type: for nested values.\n@param read a value read from TM1\n@return the patch\n")
				.addCode(cases.build())
				.build());
		}
		return spec;
	}

	/** Whether a type gets a create: the write plan has one. */
	private boolean creatable(CsdlStructuredType type) {
		return plan().get(createName(type).simpleName()) != null;
	}

	/** Whether a type gets a patch: the write plan has one. */
	private boolean patchable(CsdlStructuredType type) {
		return plan().get(patchName(type).simpleName()) != null;
	}

	/**
	 * The write plan, worked out on first use: what the spec exports as {@code builders},
	 * from the same inputs.
	 */
	private Builders plan() {
		if (plan == null) {
			plan = Builders.of(metadata, overlay, writes, offers);
		}
		return plan;
	}

	private Builders.Builder plan(ClassName write) {
		return Objects.requireNonNull(plan().get(write.simpleName()), write.simpleName());
	}

	private static ClassName createName(CsdlStructuredType type) {
		return ClassName.get(PACKAGE, javaTypeName(type.name()) + "Create");
	}

	/**
	 * The properties a write of {@code type} offers that {@code level} ({@code type} or
	 * its root) declares, with the setters the write plan gives each.
	 */
	private List<PatchProperty> writable(CsdlStructuredType level, CsdlStructuredType type, boolean create) {
		Builders.Builder write = plan(create ? createName(type) : patchName(type));
		String declaredOn = qualified(level);
		List<PatchProperty> out = new ArrayList<>();
		for (PatchProperty p : patchProperties(level)) {
			write.setters()
				.stream()
				.filter(s -> s.json().equals(p.json()) && s.declaredOn().equals(declaredOn))
				.findFirst()
				.ifPresent(s -> out.add(p.offering(s.set(), s.bind(), valueType(s, p))));
		}
		return out;
	}

	/**
	 * The type a setter takes: for a structured value, the write type the plan gives it
	 * (any subtype's welcome in a collection of a root's), else the property's own.
	 */
	private TypeName valueType(Builders.Setter s, PatchProperty p) {
		if (s.value() == null) {
			return p.type();
		}
		ClassName value = ClassName.get(PACKAGE, s.value());
		if (!s.collection()) {
			return value;
		}
		boolean root = Objects.requireNonNull(plan().get(s.value())).dispatch() != null;
		return ParameterizedTypeName.get(ClassName.get(List.class), root ? WildcardTypeName.subtypeOf(value) : value);
	}

	/** What a create of {@code type} needs, by the write plan. */
	private List<String> required(CsdlStructuredType type) {
		return plan(createName(type)).required();
	}

	/**
	 * {@code builder(read)}: a builder holding what a read entity has that this write
	 * sets: for a create, what a POST applies; for a patch, what a PATCH applies, leaving
	 * out the collections a PATCH appends to. Navigation properties aren't copied, nor
	 * nulls. Structured values are converted with their own patch's {@code from}.
	 */
	private MethodSpec from(CsdlStructuredType type, @Nullable CsdlStructuredType root, ClassName builder,
			boolean create) {
		return from(type, root, builder, create, ClassName.get(PACKAGE, javaTypeName(type.name())));
	}

	private MethodSpec from(CsdlStructuredType type, @Nullable CsdlStructuredType root, ClassName builder,
			boolean create, ClassName record) {
		Builders.FromRead plan = Objects.requireNonNull(plan(create ? createName(type) : patchName(type)).fromRead());
		Map<String, String> accessors = new HashMap<>();
		components(type).forEach(c -> accessors.put(c.json(), c.name()));
		Map<String, PatchProperty> properties = new HashMap<>();
		if (root != null) {
			writable(root, type, create).forEach(p -> properties.putIfAbsent(p.json(), p));
		}
		writable(type, type, create).forEach(p -> properties.putIfAbsent(p.json(), p));
		MethodSpec.Builder from = MethodSpec.methodBuilder("builder")
			.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
			.addParameter(record, "read")
			.returns(builder)
			.addJavadoc(create
					? "A create of what {@code read} holds that a {@code POST} applies, to recreate it: change it with the builder's setters.\n"
					: "A patch of what {@code read} holds that a {@code PATCH} applies, leaving out collections a {@code PATCH} appends to: change it with the builder's setters.\n")
			.addJavadoc(
					"A navigation the read holds expanded is bound, where its targets' paths follow from it: an entity set's by key, an element's or member's by its {@code UniqueName}; one it doesn't hold, or holds empty, or a subset or a view (whose path the read can't tell), isn't, nor are nested ones or nulls"
							+ (create ? "" : ", nor a collection, which a {@code PATCH} would replace or append to")
							+ ".\n@param read an entity read from TM1\n@return a builder\n")
			.addStatement("$T b = new $T()", builder, builder);
		for (Builders.Copy c : plan.copy()) {
			PatchProperty p = Objects.requireNonNull(properties.get(c.json()), c.json());
			String accessor = Objects.requireNonNull(accessors.get(c.json()), c.json());
			from.beginControlFlow("if (read.$L() != null)", accessor)
				.addStatement("b.$L($L)", p.method(), convert(p, c, "read." + accessor + "()"))
				.endControlFlow();
		}
		for (Builders.Binding binding : plan.bindings()) {
			PatchProperty p = Objects.requireNonNull(properties.get(binding.json()), binding.json());
			from.addCode(
					readBinding(binding, p, Objects.requireNonNull(accessors.get(binding.json()), binding.json())));
		}
		if (plan.undeclared() != null && accessors.containsKey(DYNAMIC)) {
			// Not nulls, as for declared properties, nor annotations (@odata.etag)
			from.beginControlFlow("if (read.$L() != null)", accessors.get(DYNAMIC))
				.beginControlFlow("for (var e : read.$L().entrySet())", accessors.get(DYNAMIC))
				.beginControlFlow("if (!e.getKey().startsWith($S) && e.getValue() != null && !e.getValue().isNull())",
						"@")
				.addStatement("b.property(e.getKey(), e.getValue())")
				.endControlFlow()
				.endControlFlow()
				.endControlFlow();
		}
		return from.addStatement("return b").build();
	}

	/**
	 * The code binding a navigation a read holds, as the write plan says: by key, or by
	 * {@code UniqueName}. A collection is bound only when every member's path can be
	 * made.
	 */
	private CodeBlock readBinding(Builders.Binding binding, PatchProperty p, String accessor) {
		boolean byKey = switch (binding.by()) {
			case "key" -> true;
			case "uniqueName" -> false;
			default -> throw new IllegalStateException("A binding by " + binding.by() + ": by key or uniqueName");
		};
		String helper = byKey ? (binding.collection() ? "byKeys" : "byKey")
				: (binding.collection() ? "allInHierarchy" : "inHierarchy");
		String argument = componentName(binding.key());
		String bind = "bind" + capitalize(p.method());
		CodeBlock.Builder code = CodeBlock.builder().beginControlFlow("if (read.$L() != null)", accessor);
		if (binding.collection()) {
			code.addStatement("$T[] targets = ReadBindings.$L($S, read.$L().stream().map(v -> v.$L()).toList())",
					ODATA_PATH, helper, binding.set(), accessor, argument);
		}
		else {
			code.addStatement("$T targets = ReadBindings.$L($S, read.$L().$L())", ODATA_PATH, helper, binding.set(),
					accessor, argument);
		}
		code.beginControlFlow("if (targets != null)").addStatement("b.$L(targets)", bind).endControlFlow();
		return code.endControlFlow().build();
	}

	/**
	 * {@code ReadBindings}: the paths {@code builder(read)} binds a read's navigations
	 * to, package-private to the generated model.
	 */
	private TypeSpec.Builder readBindings() {
		TypeName nullablePath = ODATA_PATH.annotated(AnnotationSpec.builder(NULLABLE).build());
		TypeName paths = ArrayTypeName.of(ODATA_PATH).annotated(AnnotationSpec.builder(NULLABLE).build());
		TypeName nullableObject = ClassName.get(Object.class).annotated(AnnotationSpec.builder(NULLABLE).build());
		TypeName keys = ParameterizedTypeName.get(ClassName.get(List.class),
				WildcardTypeName.subtypeOf(nullableObject));
		TypeName nullableString = ClassName.get(String.class).annotated(AnnotationSpec.builder(NULLABLE).build());
		return TypeSpec.classBuilder("ReadBindings")
			.addModifiers(Modifier.FINAL)
			.addAnnotation(GENERATED)
			.addJavadoc(
					"The paths a write's {@code builder(read)} binds a read's navigations to: null where one can't be made.\n")
			.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
			.addMethod(MethodSpec.methodBuilder("byKey")
				.addModifiers(Modifier.STATIC)
				.returns(nullablePath)
				.addParameter(String.class, "set")
				.addParameter(nullableObject, "key")
				.addJavadoc("An entity in a set, by its key: {@code Dimensions('Region')}.\n")
				.addStatement("return key == null ? null : $T.of(set).key(key)", ODATA_PATH)
				.build())
			.addMethod(MethodSpec.methodBuilder("byKeys")
				.addModifiers(Modifier.STATIC)
				.returns(paths)
				.addParameter(String.class, "set")
				.addParameter(keys, "keys")
				.addJavadoc("Entities in a set, by their keys: null if any key is missing, or there are none.\n")
				.beginControlFlow("if (keys.isEmpty())")
				.addStatement("return null")
				.endControlFlow()
				.addStatement("$T[] out = new $T[keys.size()]", ODATA_PATH, ODATA_PATH)
				.beginControlFlow("for (int i = 0; i < out.length; i++)")
				.addStatement("$T path = byKey(set, keys.get(i))", nullablePath)
				.beginControlFlow("if (path == null)")
				.addStatement("return null")
				.endControlFlow()
				.addStatement("out[i] = path")
				.endControlFlow()
				.addStatement("return out")
				.build())
			.addMethod(MethodSpec.methodBuilder("inHierarchy")
				.addModifiers(Modifier.STATIC)
				.returns(nullablePath)
				.addParameter(String.class, "collection")
				.addParameter(nullableString, "uniqueName")
				.addJavadoc(
						"An element or member, by its {@code UniqueName}: {@code [d].[h].[e]}, or {@code [d].[e]} in the dimension's own hierarchy, with {@code ]]} for {@code ]}. Null for any other shape.\n")
				.beginControlFlow("if (uniqueName == null)")
				.addStatement("return null")
				.endControlFlow()
				.addStatement("$T<String> parts = new $T<>()", List.class, ArrayList.class)
				.addStatement("int i = 0")
				.beginControlFlow("while (i < uniqueName.length())")
				.beginControlFlow("if (uniqueName.charAt(i) != '[')")
				.addStatement("return null")
				.endControlFlow()
				.addStatement("$T part = new $T()", StringBuilder.class, StringBuilder.class)
				.addStatement("int j = i + 1")
				.beginControlFlow("while (true)")
				.beginControlFlow("if (j >= uniqueName.length())")
				.addStatement("return null")
				.endControlFlow()
				.addStatement("char c = uniqueName.charAt(j)")
				.beginControlFlow("if (c == ']')")
				.beginControlFlow("if (j + 1 < uniqueName.length() && uniqueName.charAt(j + 1) == ']')")
				.addStatement("part.append(']')")
				.addStatement("j += 2")
				.addStatement("continue")
				.endControlFlow()
				.addStatement("break")
				.endControlFlow()
				.addStatement("part.append(c)")
				.addStatement("j++")
				.endControlFlow()
				.addStatement("parts.add(part.toString())")
				.addStatement("i = j + 1")
				.beginControlFlow("if (i < uniqueName.length())")
				.beginControlFlow("if (uniqueName.charAt(i) != '.')")
				.addStatement("return null")
				.endControlFlow()
				.addStatement("i++")
				.beginControlFlow("if (i == uniqueName.length())")
				.addStatement("return null")
				.endControlFlow()
				.endControlFlow()
				.endControlFlow()
				.beginControlFlow("if (parts.size() == 2)")
				.addStatement("parts.add(1, parts.get(0))")
				.endControlFlow()
				.beginControlFlow("if (parts.size() != 3)")
				.addStatement("return null")
				.endControlFlow()
				.addStatement(
						"return $T.of($S).key(parts.get(0)).nav($S).key(parts.get(1)).nav(collection).key(parts.get(2))",
						ODATA_PATH, "Dimensions", "Hierarchies")
				.build())
			.addMethod(MethodSpec.methodBuilder("allInHierarchy")
				.addModifiers(Modifier.STATIC)
				.returns(paths)
				.addParameter(String.class, "collection")
				.addParameter(keys, "uniqueNames")
				.addJavadoc(
						"Elements or members, by their {@code UniqueName}s: null if any can't be made, or there are none.\n")
				.beginControlFlow("if (uniqueNames.isEmpty())")
				.addStatement("return null")
				.endControlFlow()
				.addStatement("$T[] out = new $T[uniqueNames.size()]", ODATA_PATH, ODATA_PATH)
				.beginControlFlow("for (int i = 0; i < out.length; i++)")
				.addStatement("Object name = uniqueNames.get(i)")
				.addStatement("$T path = name instanceof String s ? inHierarchy(collection, s) : null", nullablePath)
				.beginControlFlow("if (path == null)")
				.addStatement("return null")
				.endControlFlow()
				.addStatement("out[i] = path")
				.endControlFlow()
				.addStatement("return out")
				.build());
	}

	/**
	 * A read value as a write setter takes it, as the write plan says: as read, or
	 * through the value's write type's {@code builder(read)}, or a root's
	 * {@code of(read)}.
	 */
	private static CodeBlock convert(PatchProperty p, Builders.Copy copy, String value) {
		if (copy.via() == null) {
			if (p.type() instanceof ParameterizedTypeName list
					&& list.typeArguments().get(0).equals(ClassName.get(Object.class))) {
				return CodeBlock.of("$T.<Object>copyOf($L)", List.class, value);
			}
			return CodeBlock.of("$L", value);
		}
		ClassName c = ClassName.get(PACKAGE, Objects.requireNonNull(copy.value()));
		boolean of = copy.via().equals("of");
		if (p.collection()) {
			return of ? CodeBlock.of("$L.stream().map(v -> $T.of(v)).toList()", value, c)
					: CodeBlock.of("$L.stream().map(v -> $T.builder(v).build()).toList()", value, c);
		}
		return of ? CodeBlock.of("$T.of($L)", c, value) : CodeBlock.of("$T.builder($L).build()", c, value);
	}

	private static void addPatchMethods(TypeSpec.Builder builder, ClassName builderName, PatchProperty property,
			boolean inherited) {
		if (property.set()) {
			addSetter(builder, builderName, property, inherited);
		}
		if (property.bind()) {
			addBinder(builder, builderName, property, inherited);
		}
	}

	private static void addSetter(TypeSpec.Builder builder, ClassName builderName, PatchProperty property,
			boolean inherited) {
		TypeName nullable = property.type().annotated(AnnotationSpec.builder(NULLABLE).build());
		MethodSpec.Builder set = MethodSpec.methodBuilder(property.method())
			.addModifiers(Modifier.PUBLIC)
			.addParameter(nullable, property.method())
			.returns(builderName)
			.addJavadoc(
					"Sets {@code $L}: $L$L\n@param $L the value; {@code null} sends an explicit {@code null}\n@return this builder\n$L",
					property.json(), property.doc(), property.navigation() ? " (nested, for a deep insert)" : "",
					property.method(), property.revisions().tag())
			.addAnnotations(property.revisions().annotations());
		if (inherited) {
			set.addAnnotation(Override.class).addStatement("super.$L($L)", property.method(), property.method());
		}
		else if (property.collection()) {
			set.addStatement("this.values.put($S, $L == null ? null : $T.copyOf($L))", property.json(),
					property.method(), List.class, property.method());
		}
		else {
			set.addStatement("this.values.put($S, $L)", property.json(), property.method());
		}
		builder.addMethod(set.addStatement("return this").build());
	}

	private static void addBinder(TypeSpec.Builder builder, ClassName builderName, PatchProperty property,
			boolean inherited) {
		String bind = "bind" + capitalize(property.method());
		MethodSpec.Builder bindMethod = MethodSpec.methodBuilder(bind)
			.addModifiers(Modifier.PUBLIC)
			.returns(builderName)
			.addAnnotations(property.revisions().annotations());
		if (property.collection()) {
			bindMethod.addParameter(ArrayTypeName.of(ODATA_PATH), "targets")
				.varargs()
				.addJavadoc(
						"Binds {@code $L} to existing entities ({@code $L@odata.bind}).\n@param targets the entities' paths, such as {@code Dimensions('Region')}\n@return this builder\n",
						property.json(), property.json());
			if (inherited) {
				bindMethod.addAnnotation(Override.class).addStatement("super.$L(targets)", bind);
			}
			else {
				bindMethod.addStatement("this.values.put($S, $T.stream(targets).map($T::toString).toList())",
						property.json() + "@odata.bind", Arrays.class, ODATA_PATH);
			}
		}
		else {
			bindMethod.addParameter(ODATA_PATH, "target")
				.addJavadoc(
						"Binds {@code $L} to an existing entity ({@code $L@odata.bind}).\n@param target the entity's path\n@return this builder\n",
						property.json(), property.json());
			if (inherited) {
				bindMethod.addAnnotation(Override.class).addStatement("super.$L(target)", bind);
			}
			else {
				bindMethod.addStatement("this.values.put($S, target.toString())", property.json() + "@odata.bind");
			}
		}
		builder.addMethod(bindMethod.addJavadoc("$L", property.revisions().tag()).addStatement("return this").build());
	}

	/** How a member departs from {@code $metadata}, for {@code @Tm1Departure}. */
	private record Departure(String kind, List<String> observedIn, String note) {

		AnnotationSpec annotation() {
			AnnotationSpec.Builder a = AnnotationSpec.builder(TM1_DEPARTURE)
				.addMember("value", "$T.$L", TM1_DEPARTURE.nestedClass("Kind"), kind);
			if (!observedIn.isEmpty()) {
				a.addMember("observedIn", "{$L}",
						String.join(", ", observedIn.stream().map(v -> '"' + v + '"').toList()));
			}
			return a.addMember("note", "$S", note).build();
		}

	}

	/** A Markdown note as plain text, for an annotation. */
	private static String plain(String markdown) {
		return markdown.replace("`", "");
	}

	/**
	 * What {@code Core.Revisions} says about an element: the version that added it, and
	 * the one that deprecated it with IBM's reason; and how it departs from
	 * {@code $metadata}, if it does.
	 */
	private record Revisions(@Nullable String since, @Nullable String deprecatedIn, @Nullable String reason,
			@Nullable Departure departure) {

		static final Revisions NONE = new Revisions(null, null, null, null);

		Revisions with(@Nullable Departure d) {
			return new Revisions(since, deprecatedIn, reason, d);
		}

		/**
		 * {@code @Since}, {@code @Deprecated(since = …)} and {@code @Tm1Departure}, as
		 * they apply.
		 */
		List<AnnotationSpec> annotations() {
			List<AnnotationSpec> annotations = new ArrayList<>();
			if (departure != null) {
				annotations.add(departure.annotation());
			}
			if (since != null) {
				annotations.add(AnnotationSpec.builder(SINCE).addMember("value", "$S", since).build());
			}
			if (deprecatedIn != null) {
				annotations
					.add(AnnotationSpec.builder(Deprecated.class).addMember("since", "$S", deprecatedIn).build());
			}
			return annotations;
		}

		/** The Javadoc {@code @deprecated} tag, or nothing. */
		String tag() {
			return deprecatedIn == null ? "" : "@deprecated " + deprecation() + "\n";
		}

		/** A note for an {@code @param}, which can't carry tags of its own. */
		String note() {
			return (since == null ? "" : " (since " + since + ")") + (deprecatedIn == null ? "" : ". " + deprecation());
		}

		private String deprecation() {
			return "Deprecated in TM1 schema " + deprecatedIn + (reason == null ? "." : ": " + reason);
		}

	}

	private static Revisions revisions(List<CsdlAnnotation> annotations) {
		String reason = CsdlRevision.of(annotations)
			.stream()
			.filter(r -> r.kind() == CsdlRevision.Kind.DEPRECATED && r.description() != null)
			.map(r -> escape(Objects.requireNonNull(r.description()).strip()))
			.findFirst()
			.orElse(null);
		return new Revisions(CsdlRevision.addedIn(annotations).orElse(null),
				CsdlRevision.deprecatedIn(annotations).orElse(null), reason, null);
	}

	/** A property's revisions, with how it departs from {@code $metadata}, if it does. */
	private Revisions revisions(CsdlStructuredType owner, CsdlProperty property) {
		return revisions(property.annotations()).with(tags.get(owner.name() + "/" + property.name()));
	}

	/**
	 * A type with its revisions: {@code @Since}, {@code @Deprecated} and the Javadoc tag.
	 */
	private static TypeSpec.Builder revised(TypeSpec.Builder type, List<CsdlAnnotation> annotations) {
		Revisions revisions = revisions(annotations);
		return type.addAnnotations(revisions.annotations()).addJavadoc("$L", revisions.tag());
	}

	/**
	 * The operation catalog: a {@code Tm1Operation} per action and function, bound ones
	 * named after their binding ({@code CHORE_ACTIVATE}) and unbound ones after their
	 * import ({@code EXECUTE_MDX}), plus {@code ALL}.
	 */
	private TypeSpec.Builder catalog() {
		TypeSpec.Builder spec = TypeSpec.classBuilder(ClassName.get(PACKAGE, "Tm1Operations"))
			.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
			.addAnnotation(GENERATED)
			.addJavadoc("$L",
					"Every action and function of the TM1 REST API, as {@code $metadata} declares them. Services refer to these, so a request checks the server has the operation ({@code requires(op.capability())}) and the coverage gate knows what's implemented.\n\n<p>\nGenerated from {@code $metadata}.\n")
			.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
		Set<String> names = new HashSet<>(Set.of("ALL"));
		List<String> all = new ArrayList<>();
		for (CsdlSchema schema : metadata.schemas()) {
			String prefix = (schema.alias() != null ? schema.alias() : schema.namespace()) + ".";
			schema.operations()
				.stream()
				.filter(CsdlOperation::bound)
				.sorted(Comparator.comparing((CsdlOperation op) -> op.bindingType().orElseThrow().name())
					.thenComparing(CsdlOperation::name))
				.forEach(op -> {
					String binding = op.bindingType().orElseThrow().name();
					String constant = unique(constantName(binding.substring(binding.lastIndexOf('.') + 1)) + "_"
							+ constantName(op.name()), names);
					spec.addField(operation(constant, op, prefix + op.name(), binding, null));
					all.add(constant);
				});
		}
		metadata.entityContainer().ifPresent(container -> {
			List<String[]> imports = new ArrayList<>();
			container.actionImports().forEach(i -> imports.add(new String[] { i.name(), i.action() }));
			container.functionImports().forEach(i -> imports.add(new String[] { i.name(), i.function() }));
			imports.sort(Comparator.comparing((String[] i) -> i[0]));
			for (String[] i : imports) {
				metadata.operations(i[1]).stream().filter(op -> !op.bound()).findFirst().ifPresent(op -> {
					String constant = unique(constantName(i[0]), names);
					spec.addField(operation(constant, op, i[1], null, i[0]));
					all.add(constant);
				});
			}
		});
		spec.addField(FieldSpec
			.builder(ParameterizedTypeName.get(ClassName.get(List.class), TM1_OPERATION), "ALL", Modifier.PUBLIC,
					Modifier.STATIC, Modifier.FINAL)
			.initializer("$T.of($L)", List.class, String.join(", ", all))
			.addJavadoc("Every operation above, bound ones first.\n")
			.build());
		return spec;
	}

	private static FieldSpec operation(String constant, CsdlOperation op, String name, @Nullable String binding,
			@Nullable String importName) {
		Revisions revisions = revisions(op.annotations());
		String kind = op.kind() == CsdlOperation.Kind.ACTION ? "ACTION" : "FUNCTION";
		CodeBlock.Builder parameters = CodeBlock.builder();
		StringBuilder doc = new StringBuilder(
				description(op.annotations()).orElse("The {@code " + name + "} " + kind.toLowerCase(Locale.ROOT) + "."))
			.append("\n\n<p>\n")
			.append(op.kind() == CsdlOperation.Kind.ACTION ? "An action" : "A function")
			.append(binding != null ? ", bound to {@code " + binding + "}" : ", imported as {@code " + importName + "}")
			.append(op.returnType() != null
					? ", returning {@code " + typeText(Objects.requireNonNull(op.returnType())) + "}" : "")
			.append(".\n");
		List<CsdlParameter> callerParameters = op.callerParameters();
		if (!callerParameters.isEmpty()) {
			doc.append("\n<ul>\n");
		}
		for (int i = 0; i < callerParameters.size(); i++) {
			CsdlParameter p = callerParameters.get(i);
			parameters.add(i == 0 ? "" : ", ")
				.add("new $T.Parameter($S, $S, $L)", TM1_OPERATION, p.name(), typeText(p.type()), p.nullable());
			doc.append("<li>{@code ")
				.append(p.name())
				.append("} ({@code ")
				.append(typeText(p.type()))
				.append("}")
				.append(p.nullable() ? ", optional" : "")
				.append(')')
				.append(description(p.annotations()).map(d -> ": " + d).orElse(""))
				.append("</li>\n");
		}
		if (!callerParameters.isEmpty()) {
			doc.append("</ul>\n");
		}
		CodeBlock initializer = CodeBlock.of("new $T($T.Kind.$L, $S, $S, $S, $T.of($L), $S, $S, $S)", TM1_OPERATION,
				TM1_OPERATION, kind, name, binding, importName, List.class, parameters.build(),
				op.returnType() == null ? null : typeText(Objects.requireNonNull(op.returnType())), revisions.since(),
				revisions.deprecatedIn());
		return FieldSpec.builder(TM1_OPERATION, constant, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
			.initializer(initializer)
			.addJavadoc("$L", doc + revisions.tag())
			.addAnnotations(revisions.annotations())
			.build();
	}

	/**
	 * A type as {@code $metadata} writes it: {@code tm1.Cube},
	 * {@code Collection(Edm.String)}.
	 */
	private static String typeText(CsdlTypeRef type) {
		return type.collection() ? "Collection(" + type.name() + ")" : type.name();
	}

	private String typeId(CsdlStructuredType type) {
		return "#" + qualified(type);
	}

	/**
	 * The type's {@code @odata.type} by schema alias ({@code #tm1.NativeView}), if the
	 * schema has one.
	 */
	private Optional<String> aliasTypeId(CsdlStructuredType type) {
		String qualified = qualified(type);
		return metadata.schemas()
			.stream()
			.filter(s -> s.alias() != null && qualified.equals(s.namespace() + "." + type.name()))
			.findFirst()
			.map(s -> "#" + s.alias() + "." + type.name());
	}

	/** {@code ibm.tm1.api.v1.Cube} → {@code Cube}. */
	private static String simple(String qualified) {
		return qualified.substring(qualified.lastIndexOf('.') + 1);
	}

	private static String capitalize(String text) {
		return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}

	private static ParameterSpec component(TypeName type, String name, String json) {
		ParameterSpec.Builder component = ParameterSpec
			.builder(type.annotated(AnnotationSpec.builder(NULLABLE).build()), name);
		if (DYNAMIC.equals(json)) {
			// Read through the constructor parameter, written through the accessor
			return component.addAnnotation(JSON_ANY_SETTER).addAnnotation(JSON_ANY_GETTER).build();
		}
		return component.addAnnotation(AnnotationSpec.builder(JSON_PROPERTY).addMember("value", "$S", json).build())
			.build();
	}

	/** The Java type for a CSDL type, or empty for one that isn't in JSON (streams). */
	private Optional<TypeName> javaType(CsdlTypeRef ref) {
		Optional<TypeName> element = elementType(ref.name());
		if (ref.collection()) {
			return element.map(e -> ParameterizedTypeName.get(ClassName.get(List.class), e));
		}
		return element;
	}

	private Optional<TypeName> elementType(String name) {
		if ("Edm.Stream".equals(name)) {
			// Media content, read through $value, never part of the JSON
			return Optional.empty();
		}
		TypeName edm = switch (name) {
			case "Edm.String" -> ClassName.get(String.class);
			case "Edm.Boolean" -> ClassName.get(Boolean.class);
			case "Edm.Byte", "Edm.SByte", "Edm.Int16", "Edm.Int32" -> ClassName.get(Integer.class);
			case "Edm.Int64" -> ClassName.get(Long.class);
			case "Edm.Single", "Edm.Double" -> ClassName.get(Double.class);
			case "Edm.Decimal" -> ClassName.get("java.math", "BigDecimal");
			case "Edm.Date" -> ClassName.get("java.time", "LocalDate");
			case "Edm.TimeOfDay" -> ClassName.get("java.time", "LocalTime");
			case "Edm.DateTimeOffset" -> ClassName.get("java.time", "OffsetDateTime");
			case "Edm.Duration" -> ClassName.get("java.time", "Duration");
			case "Edm.Guid" -> ClassName.get("java.util", "UUID");
			default -> null;
		};
		if (edm != null) {
			return Optional.of(edm);
		}
		String qualified = metadata.qualify(name);
		Optional<CsdlEnumType> enumType = metadata.enumType(qualified);
		if (enumType.isPresent()) {
			// Flags enums arrive comma-separated; a typed set comes later
			return Optional.of(enumType.get().flags() ? ClassName.get(String.class)
					: ClassName.get(PACKAGE, javaTypeName(enumType.get().name())));
		}
		Optional<CsdlStructuredType> structured = metadata.structuredType(qualified);
		if (structured.isPresent()) {
			return Optional.of(ClassName.get(PACKAGE, javaTypeName(structured.get().name())));
		}
		// Edm.PrimitiveType (a value of any type), anything
		// unknown
		return Optional.of(JSON_NODE);
	}

	private String qualified(CsdlStructuredType type) {
		return metadata.qualifiedName(type).orElse(type.name());
	}

	private static Optional<String> description(List<CsdlAnnotation> annotations) {
		return CsdlAnnotation.find(annotations, "Core.Description")
			.flatMap(a -> a.value().asText())
			.map(String::strip)
			.filter(s -> !s.isEmpty())
			.map(ModelGenerator::escape);
	}

	private static String paramText(List<CsdlAnnotation> annotations, String property) {
		return description(annotations).map(ModelGenerator::firstSentence)
			.orElse("the {@code " + property + "} property");
	}

	private static String firstSentence(String text) {
		String oneLine = text.replaceAll("\\s+", " ");
		int end = oneLine.indexOf(". ");
		return end < 0 ? oneLine : oneLine.substring(0, end + 1);
	}

	/**
	 * IBM's text as Javadoc: HTML special characters escaped, and no tag-like {@code @}.
	 */
	private static String escape(String text) {
		return text.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("@", "&#64;")
			.replace("*/", "*&#47;");
	}

	private static String javadoc(String text) {
		return text.endsWith("\n") ? text : text + "\n";
	}

	/** A type's Java name: TM1's own, which are valid identifiers. */
	static String javaTypeName(String csdlName) {
		return csdlName;
	}

	/**
	 * {@code Name} → {@code name}, {@code UIData} → {@code uiData}, {@code ID} →
	 * {@code id}; keywords get {@code Value}.
	 */
	static String componentName(String property) {
		int upper = 0;
		while (upper < property.length() && Character.isUpperCase(property.charAt(upper))) {
			upper++;
		}
		String name;
		if (upper == property.length()) {
			name = property.toLowerCase(Locale.ROOT);
		}
		else if (upper <= 1) {
			name = Character.toLowerCase(property.charAt(0)) + property.substring(1);
		}
		else {
			name = property.substring(0, upper - 1).toLowerCase(Locale.ROOT) + property.substring(upper - 1);
		}
		return KEYWORDS.contains(name) ? name + "Value" : name;
	}

	/**
	 * {@code NativeView} → {@code NATIVE_VIEW}, {@code MDX} → {@code MDX}; a leading
	 * digit gets {@code _}.
	 */
	static String constantName(String member) {
		String snake = member.replaceAll("([a-z])([A-Z])", "$1_$2")
			.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
			.replaceAll("[^A-Za-z0-9_]", "_")
			.toUpperCase(Locale.ROOT);
		return Character.isDigit(snake.charAt(0)) ? "_" + snake : snake;
	}

	private static String unique(String name, Set<String> taken) {
		String candidate = name;
		for (int n = 2; !taken.add(candidate); n++) {
			candidate = name + n;
		}
		return candidate;
	}

	/**
	 * The file for a type, built here because JavaPoet's toBuilder() drops a record's
	 * components.
	 */
	private static JavaFile file(TypeSpec.Builder type) {
		return JavaFile.builder(PACKAGE, type.addAnnotation(SUPPRESS_DEPRECATION).build())
			.addFileComment(LICENSE)
			.skipJavaLangImports(true)
			.indent("\t")
			.build();
	}

	private static String packageInfo() {
		return "// " + LICENSE
				+ "\n\n/**\n * The TM1 REST API's model, generated from the {@code $metadata} snapshot by\n"
				+ " * {@code tm1-codegen}: don't edit these files.\n *\n * <p>\n"
				+ " * Everything here is non-null unless marked {@link org.jspecify.annotations.Nullable}.\n */\n"
				+ "@NullMarked\npackage " + PACKAGE + ";\n\nimport org.jspecify.annotations.NullMarked;\n";
	}

	private static void delete(Path path) {
		try {
			Files.delete(path);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
