package net.ajmyers.tm1.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import net.ajmyers.tm1.csdl.CsdlDocument;
import net.ajmyers.tm1.csdl.CsdlParser;

class ModelGeneratorTest {

	private static final String SAMPLE = """
			<edmx:Edmx xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx" Version="4.0">
			  <edmx:DataServices>
			    <Schema xmlns="http://docs.oasis-open.org/odata/ns/edm" Namespace="ibm.tm1.api.v1" Alias="tm1">
			      <EnumType Name="CubeKind">
			        <Member Name="Standard" Value="0"/>
			        <Member Name="NativeView" Value="1"/>
			        <Member Name="Unknown" Value="2"/>
			      </EnumType>
			      <ComplexType Name="Box">
			        <Property Name="UIData" Type="Edm.String"/>
			        <Property Name="Default" Type="Edm.Int64"/>
			      </ComplexType>
			      <EntityType Name="Cube">
			        <Key><PropertyRef Name="Name"/></Key>
			        <Property Name="Name" Type="Edm.String" Nullable="false">
			          <Annotation Term="Core.Description"><String>The cube's name &amp; key. Unique in a server.</String></Annotation>
			        </Property>
			        <Property Name="Kind" Type="tm1.CubeKind"/>
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
			.contains("@JsonProperty(\"Kind\") @Nullable CubeKind kind")
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
			.containsExactly("CubeKind", "Box", "Cube", "NativeView", "View");
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
		CsdlDocument snapshot;
		try (InputStream in = Files.newInputStream(Path.of(System.getProperty("tm1.snapshot")))) {
			snapshot = CsdlParser.parse(in);
		}
		new ModelGenerator(snapshot).writeTo(out);

		List<Path> sources;
		try (Stream<Path> walk = Files.walk(out)) {
			sources = walk.filter(p -> p.toString().endsWith(".java")).toList();
		}
		assertThat(sources).as("31 enums, 122 records, 7 hierarchies, package-info").hasSize(161);

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

	private static ModelGenerator sample() {
		return new ModelGenerator(CsdlParser.parse(SAMPLE));
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
