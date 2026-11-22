package net.ajmyers.tm1.codegen;

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
import net.ajmyers.tm1.csdl.CsdlAnnotation;
import net.ajmyers.tm1.csdl.CsdlDocument;
import net.ajmyers.tm1.csdl.CsdlEntityType;
import net.ajmyers.tm1.csdl.CsdlEnumType;
import net.ajmyers.tm1.csdl.CsdlNavigationProperty;
import net.ajmyers.tm1.csdl.CsdlOperation;
import net.ajmyers.tm1.csdl.CsdlParameter;
import net.ajmyers.tm1.csdl.CsdlProperty;
import net.ajmyers.tm1.csdl.CsdlRevision;
import net.ajmyers.tm1.csdl.CsdlSchema;
import net.ajmyers.tm1.csdl.CsdlStructuredType;
import net.ajmyers.tm1.csdl.CsdlTypeRef;

/**
 * Generates the model from a {@code $metadata} document (plan §8.1), into one package:
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

	/** The package the model is generated into. */
	public static final String PACKAGE = "net.ajmyers.tm1.client.model";

	private static final AnnotationSpec GENERATED = AnnotationSpec
		.builder(ClassName.get("javax.annotation.processing", "Generated"))
		.addMember("value", "$S", "net.ajmyers.tm1.codegen")
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

	private static final ClassName ODATA_PROPERTY = ClassName.get("net.ajmyers.tm1.client.odata", "ODataProperty");

	private static final ClassName ODATA_LITERAL = ClassName.get("net.ajmyers.tm1.client.odata", "ODataLiteral");

	private static final ClassName ODATA_LITERALS = ClassName.get("net.ajmyers.tm1.client.odata", "ODataLiterals");

	private static final ClassName ODATA_PATH = ClassName.get("net.ajmyers.tm1.client.odata", "ODataPath");

	private static final ClassName SINCE = ClassName.get("net.ajmyers.tm1.client.version", "Since");

	private static final AnnotationSpec SUPPRESS_DEPRECATION = AnnotationSpec.builder(SuppressWarnings.class)
		.addMember("value", "$S", "deprecation")
		.build();

	private static final ClassName TM1_OPERATION = ClassName.get("net.ajmyers.tm1.client", "Tm1Operation");

	private static final ClassName JSON_NODE = ClassName.get("tools.jackson.databind", "JsonNode");

	private static final Set<String> KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case",
			"catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends",
			"false", "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
			"interface", "long", "native", "new", "null", "package", "private", "protected", "public", "return",
			"short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient",
			"true", "try", "void", "volatile", "while", "var", "record", "yield", "sealed", "permits");

	private final CsdlDocument metadata;

	/** Roots of inheritance hierarchies, by qualified name, with their subtypes. */
	private final Map<String, List<CsdlStructuredType>> hierarchies = new LinkedHashMap<>();

	/** Each subtype's root, by the subtype's qualified name. */
	private final Map<String, CsdlStructuredType> roots = new HashMap<>();

	/**
	 * A generator for {@code metadata}.
	 * @param metadata the {@code $metadata} document
	 * @throws IllegalArgumentException if a type derives from a derived type, which TM1
	 * doesn't do and the generator doesn't support yet
	 */
	public ModelGenerator(CsdlDocument metadata) {
		this.metadata = metadata;
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
			.sorted(Comparator.comparing(CsdlStructuredType::name))
			.forEach(t -> files.add(file(revised(patch(t), t.annotations()))));
		files.add(file(catalog()));
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
	private record Component(String name, TypeName type, String json, String doc, Revisions revisions) {
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
				javaType(property.type()).ifPresent(t -> components
					.add(new Component(unique(componentName(property.name()), names), t, property.name(),
							paramText(property.annotations(), property.name()), revisions(property.annotations()))));
			}
			for (CsdlNavigationProperty navigation : level.navigationProperties()) {
				javaType(navigation.type()).ifPresent(t -> components
					.add(new Component(unique(componentName(navigation.name()), names), t, navigation.name(),
							paramText(navigation.annotations(), navigation.name()) + " (when expanded)",
							revisions(navigation.annotations()))));
			}
		}
		return components;
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
			constructor.addParameter(component(component.type(), component.name(), component.json()).toBuilder()
				.addAnnotations(component.revisions().annotations())
				.build());
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
						paramText(property.annotations(), property.name()), revisions(property.annotations())));
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

	/** A setter or bind method of a patch builder, for one property. */
	private record PatchProperty(String method, TypeName type, String json, String doc, boolean navigation,
			boolean collection, Revisions revisions) {
	}

	private List<PatchProperty> patchProperties(CsdlStructuredType type) {
		Set<String> names = new HashSet<>(Set.of("build"));
		List<PatchProperty> properties = new ArrayList<>();
		for (CsdlProperty property : type.properties()) {
			patchType(property.type())
				.ifPresent(t -> properties.add(new PatchProperty(unique(componentName(property.name()), names), t,
						property.name(), paramText(property.annotations(), property.name()), false,
						property.type().collection(), revisions(property.annotations()))));
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
	 * What a patch takes for a property: patches for structured values, so nested ones
	 * are partial too.
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
		ClassName patch = patchName(structured.get());
		if (!ref.collection()) {
			return Optional.of(patch);
		}
		TypeName element = hierarchies.containsKey(qualified) ? WildcardTypeName.subtypeOf(patch) : patch;
		return Optional.of(ParameterizedTypeName.get(ClassName.get(List.class), element));
	}

	private static ClassName patchName(CsdlStructuredType type) {
		return ClassName.get(PACKAGE, javaTypeName(type.name()) + "Patch");
	}

	/**
	 * A type's patch (plan §8.3): only the properties set are written, explicit nulls
	 * included, plus {@code @odata.bind} references, in the order they were set. A record
	 * can't do that, since it can't tell "not set" from "set to null". A subtype's patch
	 * extends its root's and writes {@code @odata.type} first.
	 */
	private TypeSpec.Builder patch(CsdlStructuredType type) {
		ClassName name = patchName(type);
		if (metadata.structuredTypes().anyMatch(t -> javaTypeName(t.name()).equals(name.simpleName()))) {
			throw new IllegalArgumentException(name.simpleName() + " is a CSDL type, so its patch needs another name");
		}
		CsdlStructuredType root = roots.get(qualified(type));
		boolean extensible = hierarchies.containsKey(qualified(type));
		ClassName builder = name.nestedClass("Builder");
		TypeName valuesType = ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(String.class),
				ClassName.get(Object.class).annotated(AnnotationSpec.builder(NULLABLE).build()));
		String record = javaTypeName(type.name());

		TypeSpec.Builder spec = TypeSpec.classBuilder(name)
			.addModifiers(extensible ? new Modifier[] { Modifier.PUBLIC }
					: new Modifier[] { Modifier.PUBLIC, Modifier.FINAL })
			.addAnnotation(GENERATED)
			.addJavadoc("$L", "A write of {@link " + record
					+ "}: for a {@code PATCH}, only the properties set are sent, an explicit {@code null} included; for a {@code POST}, the new entity's properties, nested patches for deep inserts and {@code @odata.bind} references to existing entities.\n\n<p>\n"
					+ (root != null ? "Extends {@link " + patchName(root).simpleName()
							+ "} and sends {@code @odata.type} {@code " + typeId(type) + "}. " : "")
					+ (extensible ? "Each subtype's patch extends this one. " : "")
					+ "Generated from {@code $metadata}.\n");
		if (root != null) {
			spec.superclass(patchName(root));
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
			b.superclass(patchName(root).nestedClass("Builder"));
			ctor.addStatement("this.values.put($S, $S)", "@odata.type", typeId(type));
			for (PatchProperty inherited : patchProperties(root)) {
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
		for (PatchProperty own : patchProperties(type)) {
			addPatchMethods(b, builder, own, false);
		}
		MethodSpec.Builder build = MethodSpec.methodBuilder("build")
			.addModifiers(Modifier.PUBLIC)
			.returns(name)
			.addJavadoc("The patch, with what's been set so far.\n@return the patch\n")
			.addStatement("return new $T(values)", name);
		if (root != null) {
			build.addAnnotation(Override.class);
		}
		b.addMethod(build.build());
		spec.addType(b.build());
		return spec;
	}

	private static void addPatchMethods(TypeSpec.Builder builder, ClassName builderName, PatchProperty property,
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
		if (!property.navigation()) {
			return;
		}
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

	/**
	 * What {@code Core.Revisions} says about an element: the version that added it, and
	 * the one that deprecated it with IBM's reason.
	 */
	private record Revisions(@Nullable String since, @Nullable String deprecatedIn, @Nullable String reason) {

		static final Revisions NONE = new Revisions(null, null, null);

		/** {@code @Since} and {@code @Deprecated(since = …)}, as they apply. */
		List<AnnotationSpec> annotations() {
			List<AnnotationSpec> annotations = new ArrayList<>();
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
				CsdlRevision.deprecatedIn(annotations).orElse(null), reason);
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

	private static String capitalize(String text) {
		return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}

	private static ParameterSpec component(TypeName type, String name, String json) {
		return ParameterSpec.builder(type.annotated(AnnotationSpec.builder(NULLABLE).build()), name)
			.addAnnotation(AnnotationSpec.builder(JSON_PROPERTY).addMember("value", "$S", json).build())
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
			.skipJavaLangImports(true)
			.indent("\t")
			.build();
	}

	private static String packageInfo() {
		return "/**\n * The TM1 REST API's model, generated from the {@code $metadata} snapshot by\n"
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
