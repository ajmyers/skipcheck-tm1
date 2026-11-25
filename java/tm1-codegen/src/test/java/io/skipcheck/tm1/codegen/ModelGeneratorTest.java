// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.palantir.javapoet.JavaFile;
import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlNavigationProperty;
import io.skipcheck.tm1.csdl.CsdlParser;
import io.skipcheck.tm1.csdl.CsdlStructuredType;

class ModelGeneratorTest {

	private static final String SAMPLE = """
			<edmx:Edmx xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx" Version="4.0">
			  <edmx:DataServices>
			    <Schema xmlns="http://docs.oasis-open.org/odata/ns/edm" Namespace="ibm.tm1.api.v1" Alias="tm1">
			      <EnumType Name="CubeKind">
			        <Member Name="Standard" Value="0"/>
			        <Member Name="NativeView" Value="1">
			          <Annotation Term="Core.Revisions"><Collection><Record><PropertyValue Property="Version"><String>11.8.10</String></PropertyValue><PropertyValue Property="Kind"><EnumMember>Core.RevisionKind/Added</EnumMember></PropertyValue></Record></Collection></Annotation>
			        </Member>
			        <Member Name="Unknown" Value="2"/>
			      </EnumType>
			      <ComplexType Name="Box" OpenType="true">
			        <Property Name="UIData" Type="Edm.String"/>
			        <Property Name="Default" Type="Edm.Int64"/>
			      </ComplexType>
			      <EntityType Name="Cube">
			        <Annotation Term="Core.Revisions"><Collection><Record><PropertyValue Property="Version"><String>10.2.2.0</String></PropertyValue><PropertyValue Property="Kind"><EnumMember>Core.RevisionKind/Added</EnumMember></PropertyValue></Record></Collection></Annotation>
			        <Key><PropertyRef Name="Name"/></Key>
			        <Property Name="Name" Type="Edm.String" Nullable="false">
			          <Annotation Term="Core.Description"><String>The cube's name &amp; key. Unique in a server.</String></Annotation>
			        </Property>
			        <Property Name="Kind" Type="tm1.CubeKind">
			          <Annotation Term="Core.Revisions"><Collection><Record><PropertyValue Property="Version"><String>11.8.20</String></PropertyValue><PropertyValue Property="Kind"><EnumMember>Core.RevisionKind/Added</EnumMember></PropertyValue></Record><Record><PropertyValue Property="Version"><String>11.8.30</String></PropertyValue><PropertyValue Property="Kind"><EnumMember>Core.RevisionKind/Deprecated</EnumMember></PropertyValue><PropertyValue Property="Description"><String>Use Box instead.</String></PropertyValue></Record></Collection></Annotation>
			        </Property>
			        <Property Name="Sizes" Type="Collection(Edm.Double)"/>
			        <Property Name="Box" Type="tm1.Box"/>
			        <Property Name="Content" Type="Edm.Stream"/>
			        <NavigationProperty Name="Views" Type="Collection(tm1.View)"/>
			      </EntityType>
			      <EntityType Name="View" Abstract="true">
			        <Key><PropertyRef Name="Name"/></Key>
			        <Property Name="Name" Type="Edm.String"/>
			      </EntityType>
			      <EntityType Name="NativeView" BaseType="tm1.View"/>
			      <Action Name="Reserve" IsBound="true">
			        <Annotation Term="Core.Description"><String>Reserves a cube.</String></Annotation>
			        <Parameter Name="Cube" Type="tm1.Cube" Nullable="false"/>
			        <Parameter Name="Until" Type="Edm.DateTimeOffset"/>
			      </Action>
			      <Function Name="Ping" IsComposable="false"><ReturnType Type="Collection(Edm.String)"/></Function>
			      <EntityContainer Name="API">
			        <FunctionImport Name="Ping" Function="tm1.Ping"/>
			      </EntityContainer>
			    </Schema>
			  </edmx:DataServices>
			</edmx:Edmx>
			""";

	@Test
	void namesComponentsAndConstantsTheJavaWay() {
		assertThat(ModelGenerator.componentName("Name")).isEqualTo("name");
		assertThat(ModelGenerator.componentName("UIData")).isEqualTo("uiData");
		assertThat(ModelGenerator.componentName("ID")).isEqualTo("id");
		assertThat(ModelGenerator.componentName("Default")).isEqualTo("defaultValue");
		assertThat(ModelGenerator.constantName("NativeView")).isEqualTo("NATIVE_VIEW");
		assertThat(ModelGenerator.constantName("MDX")).isEqualTo("MDX");
		assertThat(ModelGenerator.constantName("2D")).isEqualTo("_2D");
	}

	@Test
	void startsEveryFileWithItsLicense(@TempDir Path out) throws Exception {
		sample().writeTo(out);
		try (Stream<Path> files = Files.walk(out)) {
			assertThat(files.filter(Files::isRegularFile)).isNotEmpty()
				.allSatisfy(file -> assertThat(Files.readString(file))
					.startsWith("// SPDX-License-Identifier: Apache-2.0\n"));
		}
	}

	@Test
	void generatesEnumsWithAFallbackThatDoesntClash() {
		String kind = source(sample(), "CubeKind");
		assertThat(kind).contains("public enum CubeKind")
			.contains("STANDARD(\"Standard\")")
			.contains("NATIVE_VIEW(\"NativeView\")")
			.contains("UNKNOWN(\"Unknown\")")
			.as("TM1 already has Unknown, so the fallback is UNRECOGNIZED")
			.contains("UNRECOGNIZED((String) null)")
			.contains("@JsonCreator(mode = JsonCreator.Mode.DELEGATING)")
			.contains("@JsonValue")
			.contains("enum CubeKind implements ODataLiteral")
			.contains("return ODataLiterals.enumMember(\"ibm.tm1.api.v1.CubeKind\", json);");
	}

	@Test
	void generatesRecordsWithNullableComponentsNamedAfterTm1Properties() {
		String cube = source(sample(), "Cube");
		assertThat(cube).contains("@JsonIgnoreProperties(ignoreUnknown = true)")
			.contains("public static final ODataProperty NAME = ODataProperty.of(\"Name\");")
			.contains("public static final ODataProperty VIEWS = ODataProperty.of(\"Views\");")
			.contains("public record Cube(")
			.contains("@JsonProperty(\"@odata.etag\") @Nullable String etag")
			.contains("@JsonProperty(\"Name\") @Nullable String name")
			.contains("@Nullable CubeKind kind")
			.contains("@JsonProperty(\"Sizes\") @Nullable List<Double> sizes")
			.contains("@JsonProperty(\"Box\") @Nullable Box box")
			.as("a hierarchy, by its interface")
			.contains("@JsonProperty(\"Views\") @Nullable List<View> views")
			.as("IBM's description, escaped for Javadoc")
			.contains("@param name The cube's name &amp; key.")
			.doesNotContain("Content");
		String box = source(sample(), "Box");
		assertThat(box).contains("@Nullable String uiData")
			.contains("@Nullable Long defaultValue")
			.doesNotContain("etag");
	}

	@Test
	void generatesASealedInterfacePerHierarchyReadByOdataType() {
		assertThat(sample().generate()).extracting(f -> f.typeSpec().name())
			.containsExactly("CubeKind", "Box", "Cube", "NativeView", "View", "BoxPatch", "CubePatch",
					"NativeViewPatch", "ViewPatch", "Tm1Operations", "ReadBindings");
		String view = source(sample(), "View");
		assertThat(view).contains("public sealed interface View permits NativeView, View.Unknown")
			.contains(
					"@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = \"@odata.type\", defaultImpl = View.Unknown.class)")
			.contains(
					"@JsonSubTypes.Type(value = NativeView.class, name = \"#ibm.tm1.api.v1.NativeView\", names = \"#tm1.NativeView\")")
			.contains("@Nullable String name();")
			.as("an abstract root's fallback is never sent, so it writes no type")
			.contains("@JsonTypeInfo(use = JsonTypeInfo.Id.NONE) record Unknown(");
		assertThat(source(sample(), "NativeView")).contains("public record NativeView(")
			.contains("implements View")
			.as("asked for by name, it reads without @odata.type")
			.contains("defaultImpl = NativeView.class")
			.contains("@JsonTypeName(\"#ibm.tm1.api.v1.NativeView\")")
			.contains("@JsonProperty(\"Name\") @Nullable String name");
	}

	@Test
	void generatesTheSnapshotsModelAndItCompiles(@TempDir Path out) throws Exception {
		// As the build generates it: with the probe file beside the snapshot
		Tm1Codegen.Model model = Tm1Codegen.Model.of(Path.of(System.getProperty("tm1.snapshot")));
		new ModelGenerator(model.metadata(), model.overlay(), model.undeclared(), model.writes(), model.offers())
			.writeTo(out);

		List<Path> sources;
		try (Stream<Path> walk = Files.walk(out)) {
			sources = walk.filter(p -> p.toString().endsWith(".java")).toList();
		}
		assertThat(sources)
			.as("31 enums, 122 records, 7 hierarchies, 103 write types, the catalog, the read bindings, package-info")
			.hasSize(266);

		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
			List<String> options = new ArrayList<>(List.of("--release", "17", "-Xlint:all", "-Werror", "-proc:none",
					"-classpath", System.getProperty("java.class.path"), "-d",
					Files.createDirectories(out.resolve("classes")).toString()));
			boolean compiled = javac
				.getTask(null, files, diagnostics, options, null, files.getJavaFileObjectsFromPaths(sources))
				.call();
			assertThat(diagnostics.getDiagnostics()).extracting(Diagnostic::toString).isEmpty();
			assertThat(compiled).isTrue();
		}
	}

	@Test
	void generatesPatchesThatSendOnlyWhatsSetAndExtendTheirRoots() {
		String cube = source(sample(), "CubePatch");
		assertThat(cube).contains("public final class CubePatch")
			.contains("@JsonValue public final Map<String, @Nullable Object> json()")
			.contains("public Builder name(@Nullable String name) { this.values.put(\"Name\", name); return this; }")
			.as("nested values are patches too")
			.contains("public Builder box(@Nullable BoxPatch box)")
			.contains("public Builder kind(@Nullable CubeKind kind)")
			.as("a hierarchy's subtypes' patches are welcome in a collection")
			.contains("public Builder views(@Nullable List<? extends ViewPatch> views)")
			.contains(
					"public Builder bindViews(ODataPath... targets) { this.values.put(\"Views@odata.bind\", Arrays.stream(targets).map(ODataPath::toString).toList()); return this; }")
			.doesNotContain("etag")
			.doesNotContain("Content");
		assertThat(source(sample(), "ViewPatch")).contains("public class ViewPatch")
			.contains("public static class Builder");
		assertThat(source(sample(), "NativeViewPatch")).contains("public final class NativeViewPatch extends ViewPatch")
			.contains("public static final class Builder extends ViewPatch.Builder")
			.contains("this.values.put(\"@odata.type\", \"#ibm.tm1.api.v1.NativeView\");")
			.as("inherited setters keep the subtype's builder")
			.contains("@Override public Builder name(@Nullable String name) { super.name(name); return this; }")
			.contains("@Override public NativeViewPatch build()");
	}

	@Test
	void refusesARootPatchWhoseSubtypeHasNone() {
		Offers offers = new Offers(Map.of("ViewPatch", Map.of("Name", new Offers.Offer("verified", "applied"))));
		ModelGenerator generator = new ModelGenerator(CsdlParser.parse(SAMPLE), Overlay.NONE, Undeclared.NONE,
				Writes.NONE, offers);
		assertThatThrownBy(generator::generate).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("ViewPatch has a subtype without a patch, NativeView");
	}

	@Test
	void marksWhatRevisionsAddedAndDeprecatedWithIbmsReason() {
		String cube = source(sample(), "Cube");
		assertThat(cube).contains("@Since(\"10.2.2.0\") @SuppressWarnings(\"deprecation\") public record Cube(")
			.contains(
					"@JsonProperty(\"Kind\") @Since(\"11.8.20\") @Deprecated(since = \"11.8.30\") @Nullable CubeKind kind")
			.contains(
					"@param kind the {@code Kind} property (since 11.8.20). Deprecated in TM1 schema 11.8.30: Use Box instead.")
			.contains(
					"@deprecated Deprecated in TM1 schema 11.8.30: Use Box instead. */ @Since(\"11.8.20\") @Deprecated(since = \"11.8.30\") public static final ODataProperty KIND");
		assertThat(source(sample(), "CubeKind")).contains("@Since(\"11.8.10\") NATIVE_VIEW(\"NativeView\")");
		assertThat(source(sample(), "CubePatch"))
			.contains("@Since(\"11.8.20\") @Deprecated(since = \"11.8.30\") public Builder kind(");
	}

	@Test
	void catalogsEveryOperationByBindingOrImport() {
		assertThat(source(sample(), "Tm1Operations")).contains("public final class Tm1Operations")
			.contains(
					"Reserves a cube. * * <p> * An action, bound to {@code tm1.Cube}. * * <ul> * <li>{@code Until} ({@code Edm.DateTimeOffset}, optional)</li> * </ul>")
			.contains(
					"public static final Tm1Operation CUBE_RESERVE = new Tm1Operation(Tm1Operation.Kind.ACTION, \"tm1.Reserve\", \"tm1.Cube\", null, List.of(new Tm1Operation.Parameter(\"Until\", \"Edm.DateTimeOffset\", true)), null, null, null);")
			.contains(
					"public static final Tm1Operation PING = new Tm1Operation(Tm1Operation.Kind.FUNCTION, \"tm1.Ping\", null, \"Ping\", List.of(), \"Collection(Edm.String)\", null, null);")
			.contains("public static final List<Tm1Operation> ALL = List.of(CUBE_RESERVE, PING);");
	}

	@Test
	void keepsAnOpenTypesUndeclaredPropertiesAndLetsAPatchSetThem() {
		assertThat(source(sample(), "Box"))
			.contains("@JsonAnySetter @JsonAnyGetter @Nullable Map<String, JsonNode> dynamicProperties");
		assertThat(source(sample(), "BoxPatch"))
			.contains("public Builder property(String name, @Nullable Object value)");
		assertThat(source(sample(), "Cube")).doesNotContain("dynamicProperties");
	}

	@Test
	void makesAPropertyTm1SendsInAnotherTypeJson() {
		String sample = """
				<edmx:Edmx xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx" Version="4.0">
				  <edmx:DataServices>
				    <Schema xmlns="http://docs.oasis-open.org/odata/ns/edm" Namespace="ibm.tm1.api.v1" Alias="tm1">
				      <ComplexType Name="CAMSettings">
				        <Property Name="ClientPingCAMPassport" Type="Edm.Int32"/>
				      </ComplexType>
				    </Schema>
				  </edmx:DataServices>
				</edmx:Edmx>
				""";
		ModelGenerator generator = everyPatch(CsdlParser.parse(sample));
		assertThat(source(generator, "CAMSettings")).contains("@Nullable JsonNode clientPingCAMPassport")
			.contains("ISO 8601 duration");
		assertThat(source(generator, "CAMSettingsPatch"))
			.contains("public Builder clientPingCAMPassport(@Nullable Object");
	}

	@Test
	void readsADateTimeTm1FillsWithNonsenseLeniently() {
		String sample = """
				<edmx:Edmx xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx" Version="4.0">
				  <edmx:DataServices>
				    <Schema xmlns="http://docs.oasis-open.org/odata/ns/edm" Namespace="ibm.tm1.api.v1" Alias="tm1">
				      <EntityType Name="MessageLogEntry">
				        <Key><PropertyRef Name="ID"/></Key>
				        <Property Name="ID" Type="Edm.Int64" Nullable="false"/>
				        <Property Name="TimeStamp" Type="Edm.DateTimeOffset"/>
				      </EntityType>
				    </Schema>
				  </edmx:DataServices>
				</edmx:Edmx>
				""";
		assertThat(source(new ModelGenerator(CsdlParser.parse(sample)), "MessageLogEntry"))
			.contains("@JsonDeserialize(using = LenientOffsetDateTimeDeserializer.class)")
			.contains("0000-00-00T00:00Z");
	}

	private static ModelGenerator sample() {
		return everyPatch(CsdlParser.parse(SAMPLE));
	}

	/**
	 * A generator with the bundled overlay, offering a patch of every type with a setter
	 * for each of its properties and navigations (a binding, too, for one to an entity):
	 * what the patches' generated code looks like, without a probe.
	 */
	private static ModelGenerator everyPatch(CsdlDocument metadata) {
		Map<String, Map<String, Offers.Offer>> offers = new TreeMap<>();
		Offers.Offer offer = new Offers.Offer("kept", "the generator's test");
		metadata.structuredTypes().forEach(t -> {
			Map<String, Offers.Offer> setters = new TreeMap<>();
			for (CsdlStructuredType level = t; level != null; level = level.baseType() == null ? null
					: metadata.structuredType(metadata.qualify(level.baseType())).orElseThrow()) {
				level.properties()
					.stream()
					.filter(p -> !p.type().name().equals("Edm.Stream"))
					.forEach(p -> setters.put(p.name(), offer));
				for (CsdlNavigationProperty n : level.navigationProperties()) {
					setters.put(n.name(), offer);
					setters.put(n.name() + "@odata.bind", offer);
				}
			}
			offers.put(t.name() + "Patch", setters);
		});
		return new ModelGenerator(metadata, Overlay.bundled(), Undeclared.NONE, Writes.NONE, new Offers(offers));
	}

	private static String source(ModelGenerator generator, String type) {
		return generator.generate()
			.stream()
			.filter(f -> f.typeSpec().name().equals(type))
			.map(JavaFile::toString)
			// JavaPoet wraps annotations and parameters as it likes: compare on single
			// spaces
			.map(s -> s.replaceAll("\\s+", " ").replaceAll("\\( ", "(").replaceAll(" \\)", ")"))
			.findFirst()
			.orElseThrow();
	}

}
