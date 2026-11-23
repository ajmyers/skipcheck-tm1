// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.docs;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;

import jdk.javadoc.doclet.Doclet;
import jdk.javadoc.doclet.DocletEnvironment;
import jdk.javadoc.doclet.Reporter;

/**
 * Writes the public API of the documented sources as one JSON file: each type with its
 * doc comment, and each public member with its signature, doc comment and
 * {@code @Tm1Endpoint} IDs. The docs site (api-site/scripts/reference.mjs) reads it
 * beside the same data for the other languages, so the doc comments are passed on as
 * written, inline tags and all, and rendered there.
 * <p>
 * Options: {@code -o <file>}, where to write it.
 */
public final class ModelDoclet implements Doclet {

	// Package names before a type's simple name, as in java.util.List or
	// io.skipcheck.tm1.Foo
	private static final Pattern QUALIFIER = Pattern.compile("\\b(?:[a-z_][\\w]*\\.)+(?=[A-Z])");

	// Type-use annotations, which toString() puts into a type: @Nullable String
	private static final Pattern TYPE_ANNOTATION = Pattern.compile("@[\\w.]+\\s+");

	private String output = "docs-model.json";

	private Elements elements;

	@Override
	public void init(Locale locale, Reporter reporter) {
	}

	@Override
	public String getName() {
		return "ModelDoclet";
	}

	@Override
	public Set<? extends Option> getSupportedOptions() {
		Set<Option> options = new HashSet<>();
		// The standard doclet's options, which the build sets on every Javadoc task:
		// accepted, and
		// ignored, since there is no HTML to apply them to
		STANDARD_OPTIONS.forEach((name, count) -> options.add(new Ignored(name, count)));
		options.add(new Option() {

			@Override
			public int getArgumentCount() {
				return 1;
			}

			@Override
			public String getDescription() {
				return "the file to write";
			}

			@Override
			public Kind getKind() {
				return Kind.STANDARD;
			}

			@Override
			public List<String> getNames() {
				return List.of("-o");
			}

			@Override
			public String getParameters() {
				return "<file>";
			}

			@Override
			public boolean process(String option, List<String> arguments) {
				ModelDoclet.this.output = arguments.get(0);
				return true;
			}

		});
		return options;
	}

	private static final Map<String, Integer> STANDARD_OPTIONS = Map.ofEntries(Map.entry("-d", 1),
			Map.entry("-overview", 1), Map.entry("-windowtitle", 1), Map.entry("-doctitle", 1), Map.entry("-link", 1),
			Map.entry("-linkoffline", 2), Map.entry("-notimestamp", 0), Map.entry("--syntax-highlight", 0),
			Map.entry("--snippet-path", 1), Map.entry("-bottom", 1), Map.entry("-header", 1), Map.entry("-footer", 1),
			Map.entry("-use", 0), Map.entry("-author", 0), Map.entry("-version", 0), Map.entry("-charset", 1),
			Map.entry("-docencoding", 1), Map.entry("--add-stylesheet", 1), Map.entry("-tag", 1),
			// The build writes -Xdoclint:all -quiet, which reaches the doclet as one
			// option and its value
			Map.entry("-Xdoclint:all", 1));

	/** A standard doclet option, accepted and ignored. */
	private record Ignored(String name, int count) implements Option {

		@Override
		public int getArgumentCount() {
			return this.count;
		}

		@Override
		public String getDescription() {
			return "ignored";
		}

		@Override
		public Kind getKind() {
			return Kind.STANDARD;
		}

		@Override
		public List<String> getNames() {
			return List.of(this.name);
		}

		@Override
		public String getParameters() {
			return "";
		}

		@Override
		public boolean process(String option, List<String> arguments) {
			return true;
		}

	}

	@Override
	public SourceVersion getSupportedSourceVersion() {
		return SourceVersion.latest();
	}

	@Override
	public boolean run(DocletEnvironment environment) {
		this.elements = environment.getElementUtils();
		List<TypeElement> types = new ArrayList<>();
		for (Element element : environment.getIncludedElements()) {
			if (element instanceof TypeElement type && type.getModifiers().contains(Modifier.PUBLIC)) {
				types.add(type);
			}
		}
		types.sort(Comparator.comparing((TypeElement type) -> type.getQualifiedName().toString()));
		Json json = new Json();
		json.open('[');
		for (TypeElement type : types) {
			type(json, type);
		}
		json.close(']');
		try (Writer writer = Files.newBufferedWriter(Path.of(this.output), StandardCharsets.UTF_8)) {
			writer.write(json.toString());
			writer.write('\n');
		}
		catch (IOException ex) {
			throw new IllegalStateException("Can't write " + this.output, ex);
		}
		return true;
	}

	private void type(Json json, TypeElement type) {
		json.open('{');
		json.field("name", outerNames(type));
		json.field("package", packageOf(type));
		json.field("kind", kind(type.getKind()));
		json.field("signature", typeSignature(type));
		json.field("doc", doc(type));
		json.field("deprecated", this.elements.isDeprecated(type));
		json.key("members").open('[');
		for (Element member : type.getEnclosedElements()) {
			if (member.getModifiers().contains(Modifier.PUBLIC) || type.getKind() == ElementKind.INTERFACE
					|| member.getKind() == ElementKind.ENUM_CONSTANT) {
				member(json, type, member);
			}
		}
		json.close(']');
		json.close('}');
	}

	private void member(Json json, TypeElement type, Element member) {
		switch (member.getKind()) {
			case METHOD, CONSTRUCTOR -> {
				ExecutableElement method = (ExecutableElement) member;
				json.open('{');
				json.field("kind", (member.getKind() == ElementKind.CONSTRUCTOR) ? "constructor" : "method");
				json.field("name", (member.getKind() == ElementKind.CONSTRUCTOR) ? type.getSimpleName().toString()
						: method.getSimpleName().toString());
				json.field("static", method.getModifiers().contains(Modifier.STATIC));
				json.field("signature", methodSignature(type, method));
				json.field("returns",
						(method.getReturnType().getKind() == TypeKind.VOID) ? "" : simple(method.getReturnType()));
				json.key("params").open('[');
				for (VariableElement parameter : method.getParameters()) {
					json.open('{');
					json.field("name", parameter.getSimpleName().toString());
					json.field("type", simple(parameter.asType()));
					json.close('}');
				}
				json.close(']');
				json.key("endpoints").open('[');
				for (String endpoint : endpoints(method)) {
					json.value(endpoint);
				}
				json.close(']');
				json.field("doc", doc(method));
				json.field("deprecated", this.elements.isDeprecated(method));
				json.close('}');
			}
			case FIELD, ENUM_CONSTANT -> {
				json.open('{');
				json.field("kind", (member.getKind() == ElementKind.FIELD) ? "field" : "constant");
				json.field("name", member.getSimpleName().toString());
				json.field("static", member.getModifiers().contains(Modifier.STATIC));
				json.field("signature", (member.getKind() == ElementKind.FIELD)
						? simple(member.asType()) + " " + member.getSimpleName() : member.getSimpleName().toString());
				json.field("returns", simple(member.asType()));
				json.key("params").open('[').close(']');
				json.key("endpoints").open('[').close(']');
				json.field("doc", doc(member));
				json.field("deprecated", this.elements.isDeprecated(member));
				json.close('}');
			}
			default -> {
				// Nested types are written as types of their own
			}
		}
	}

	private List<String> endpoints(ExecutableElement method) {
		List<String> endpoints = new ArrayList<>();
		for (AnnotationMirror annotation : method.getAnnotationMirrors()) {
			String name = annotation.getAnnotationType().asElement().getSimpleName().toString();
			if (name.equals("Tm1Endpoint") || name.equals("Tm1Endpoints")) {
				annotation.getElementValues().values().forEach((value) -> collect(value, endpoints));
			}
		}
		return endpoints;
	}

	private static void collect(AnnotationValue value, List<String> into) {
		Object raw = value.getValue();
		if (raw instanceof String text) {
			into.add(text);
		}
		else if (raw instanceof List<?> list) {
			for (Object item : list) {
				if (item instanceof AnnotationValue inner) {
					collect(inner, into);
				}
				else if (item instanceof AnnotationMirror mirror) {
					mirror.getElementValues().values().forEach((nested) -> collect(nested, into));
				}
			}
		}
		else if (raw instanceof AnnotationMirror mirror) {
			mirror.getElementValues().values().forEach((nested) -> collect(nested, into));
		}
	}

	private String doc(Element element) {
		String doc = this.elements.getDocComment(element);
		return (doc != null) ? doc : "";
	}

	private static String kind(ElementKind kind) {
		return switch (kind) {
			case INTERFACE -> "interface";
			case ENUM -> "enum";
			case RECORD -> "record";
			case ANNOTATION_TYPE -> "annotation";
			default -> "class";
		};
	}

	private static String packageOf(TypeElement type) {
		Element enclosing = type.getEnclosingElement();
		while (!(enclosing instanceof PackageElement)) {
			enclosing = enclosing.getEnclosingElement();
		}
		return ((PackageElement) enclosing).getQualifiedName().toString();
	}

	/** A nested type's name with its outer types', as Outer.Inner. */
	private static String outerNames(TypeElement type) {
		String name = type.getSimpleName().toString();
		Element enclosing = type.getEnclosingElement();
		while (enclosing instanceof TypeElement outer) {
			name = outer.getSimpleName() + "." + name;
			enclosing = outer.getEnclosingElement();
		}
		return name;
	}

	private static String typeSignature(TypeElement type) {
		StringBuilder signature = new StringBuilder();
		signature.append(kind(type.getKind()).replace("annotation", "@interface")).append(' ').append(outerNames(type));
		signature.append(typeParameters(type.getTypeParameters()));
		return signature.toString();
	}

	private static String methodSignature(TypeElement type, ExecutableElement method) {
		StringBuilder signature = new StringBuilder();
		if (method.getModifiers().contains(Modifier.STATIC)) {
			signature.append("static ");
		}
		String parameters = typeParameters(method.getTypeParameters());
		if (!parameters.isEmpty()) {
			signature.append(parameters).append(' ');
		}
		if (method.getKind() == ElementKind.CONSTRUCTOR) {
			signature.append(type.getSimpleName());
		}
		else {
			signature.append(simple(method.getReturnType())).append(' ').append(method.getSimpleName());
		}
		signature.append('(');
		List<? extends VariableElement> list = method.getParameters();
		for (int i = 0; i < list.size(); i++) {
			if (i > 0) {
				signature.append(", ");
			}
			String parameterType = simple(list.get(i).asType());
			if (method.isVarArgs() && i == list.size() - 1 && parameterType.endsWith("[]")) {
				parameterType = parameterType.substring(0, parameterType.length() - 2) + "...";
			}
			signature.append(parameterType).append(' ').append(list.get(i).getSimpleName());
		}
		return signature.append(')').toString();
	}

	private static String typeParameters(List<? extends TypeParameterElement> parameters) {
		if (parameters.isEmpty()) {
			return "";
		}
		List<String> names = new ArrayList<>();
		for (TypeParameterElement parameter : parameters) {
			StringBuilder name = new StringBuilder(parameter.getSimpleName());
			List<? extends TypeMirror> bounds = parameter.getBounds();
			if (bounds.size() == 1 && !bounds.get(0).toString().equals("java.lang.Object")) {
				name.append(" extends ").append(simple(bounds.get(0)));
			}
			names.add(name.toString());
		}
		return "<" + String.join(", ", names) + ">";
	}

	/**
	 * A type as it's written in code that imports it: List<Cube>, not
	 * java.util.List<io...Cube>.
	 */
	static String simple(TypeMirror type) {
		String text = TYPE_ANNOTATION.matcher(type.toString()).replaceAll("");
		return QUALIFIER.matcher(text).replaceAll("");
	}

	/** Just enough JSON to write the model: arrays, objects, strings and booleans. */
	private static final class Json {

		private final StringBuilder out = new StringBuilder();

		private boolean first = true;

		Json open(char bracket) {
			separate();
			this.out.append(bracket);
			this.first = true;
			return this;
		}

		Json close(char bracket) {
			this.out.append(bracket);
			this.first = false;
			return this;
		}

		Json key(String name) {
			separate();
			string(name);
			this.out.append(':');
			this.first = true;
			return this;
		}

		void field(String name, String value) {
			key(name);
			value(value);
		}

		void field(String name, boolean value) {
			key(name);
			separate();
			this.out.append(value);
			this.first = false;
		}

		void value(String value) {
			separate();
			string(value);
			this.first = false;
		}

		private void separate() {
			if (!this.first) {
				this.out.append(',');
			}
			this.first = false;
		}

		private void string(String value) {
			this.out.append('"');
			for (int i = 0; i < value.length(); i++) {
				char c = value.charAt(i);
				switch (c) {
					case '"' -> this.out.append("\\\"");
					case '\\' -> this.out.append("\\\\");
					case '\n' -> this.out.append("\\n");
					case '\r' -> this.out.append("\\r");
					case '\t' -> this.out.append("\\t");
					default -> {
						if (c < 0x20) {
							this.out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
						}
						else {
							this.out.append(c);
						}
					}
				}
			}
			this.out.append('"');
		}

		@Override
		public String toString() {
			return this.out.toString();
		}

	}

}
