// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

/** Wiring only: nothing here starts a container. */
class Tm1EngineWiringTest {

	private static final Tm1EngineSource SOURCE = new Tm1EngineSource.PrivateImage("tm1-engine:test");

	@TempDir
	Path temp;

	@Test
	void hasOneDatabaseUnlessSomeAreNamed() {
		var engine = new Tm1EngineContainer(SOURCE);
		engine.configure();
		assertThat(engine.getEnvMap()).containsEntry("TM1_DATABASES", "tm1/tm1").doesNotContainKey("TM1_USERS");
		assertThat(engine.database("tm1").getInstance()).isEqualTo("tm1");

		var named = new Tm1EngineContainer(SOURCE).withDatabase("planning").withDatabase("other", "sales");
		named.configure();
		assertThat(named.getEnvMap()).containsEntry("TM1_DATABASES", "tm1/planning,other/sales");
		assertThat(named.database("other", "sales").getName()).isEqualTo("sales");
		assertThatIllegalArgumentException().isThrownBy(() -> named.database("tm1"))
			.withMessageContaining("No database tm1/tm1 was added");
	}

	@Test
	void passesTheUsersAndTheirGroupsToTheImage() {
		var engine = new Tm1EngineContainer(SOURCE).withUser("analyst", "Analysts", "Planners").withUser("viewer");
		engine.configure();
		assertThat(engine.getEnvMap()).containsEntry("TM1_USERS", "analyst:Analysts:Planners,viewer");
	}

	@Test
	void refusesNamesTheImageCantTake() {
		var engine = new Tm1EngineContainer(SOURCE);
		// TM1 wants at least three characters
		assertThatIllegalArgumentException().isThrownBy(() -> engine.withDatabase("ab"));
		assertThatIllegalArgumentException().isThrownBy(() -> engine.withDatabase("two words"));
		assertThatIllegalArgumentException().isThrownBy(() -> engine.withDatabase("a/b", "sales"));
		assertThatIllegalArgumentException().isThrownBy(() -> engine.withUser("two words"));
		assertThatIllegalArgumentException().isThrownBy(() -> engine.withUser("analyst", "a:b"));
		engine.withUser("analyst");
		assertThatIllegalArgumentException().isThrownBy(() -> engine.withUser("analyst", "Again"))
			.withMessageContaining("already");
	}

	@Test
	void runsItsDatastoreOnItsOwnNetwork() {
		var engine = new Tm1EngineContainer(SOURCE);
		engine.configure();
		var datastore = engine.getDatastore();
		assertThat(image(datastore)).isEqualTo("mongo:8.0");
		assertThat(engine.getNetwork()).isNotNull().isSameAs(datastore.getNetwork());
		assertThat(datastore.getNetworkAliases()).contains(Tm1EngineContainer.DATASTORE_ALIAS);
		assertThat(engine.getDependencies()).contains(datastore);
		assertThat(engine.getEnvMap().get("TM1_DATASTORE_URL")).startsWith("mongodb://")
			.endsWith("@tm1-datastore:27017");
		// Both are this library's, like every container it starts
		assertThat(engine.getLabels()).containsEntry("io.skipcheck.tm1.testcontainers", "java");
		assertThat(datastore.getLabels()).containsEntry("io.skipcheck.tm1.testcontainers", "java");
	}

	@Test
	void itsDatastoreIsOnItsNetworkBeforeAnythingStarts() {
		// Not only once the engine starts or is configured: an environment starts what
		// the
		// engine depends on first, and TM1 couldn't reach a datastore left off the
		// network
		var engine = new Tm1EngineContainer(SOURCE);
		assertThat(engine.getNetwork()).isNotNull().isSameAs(engine.getDatastore().getNetwork());
		Network network = Network.newNetwork();
		engine.withNetwork(network);
		assertThat(engine.getDatastore().getNetwork()).isSameAs(network);
	}

	@Test
	void joinsANetworkItIsGiven() {
		Network network = Network.newNetwork();
		var engine = new Tm1EngineContainer(SOURCE).withNetwork(network);
		engine.configure();
		assertThat(engine.getNetwork()).isSameAs(network);
		assertThat(engine.getDatastore().getNetwork()).isSameAs(network);
	}

	@Test
	void mountsEachSourceWhereTheImageLooks() throws IOException {
		Path install = Files.createDirectories(temp.resolve("install/bin")).getParent();
		Files.createFile(install.resolve("bin/tm1sd.exe"));
		assertThat(binds(new Tm1EngineSource.InstallDir(install))).containsExactly(install + ":/opt/ibm/tm1-engine:ro");
		assertThat(binds(new Tm1EngineSource.InstallVolume("tm1-engine")))
			.containsExactly("tm1-engine:/opt/ibm/tm1-engine:rw");

		Files.createFile(temp.resolve("tm1-v12.6.7-standalone-linux.tgz"));
		var bundle = new Tm1EngineContainer(new Tm1EngineSource.Bundle(temp, "tm1-v12.6.7-standalone-linux.tgz"));
		assertThat(bundle.sourceBinds().stream().map(Object::toString)).containsExactly(temp + ":/ibm:ro",
				"tm1-engine-testcontainers-cache:/tm1/cache:rw");
		assertThat(bundle.getEnvMap()).containsEntry("TM1_ENGINE_BUNDLE", "tm1-v12.6.7-standalone-linux.tgz");

		var url = new Tm1EngineContainer(new Tm1EngineSource.BundleUrl(URI.create("https://example.com/p.tgz"), "abc"));
		assertThat(url.sourceBinds().stream().map(Object::toString))
			.containsExactly("tm1-engine-testcontainers-cache:/tm1/cache:rw");
		assertThat(url.getEnvMap()).containsEntry("TM1_ENGINE_BUNDLE_URL", "https://example.com/p.tgz")
			.containsEntry("TM1_ENGINE_BUNDLE_SHA256", "abc");

		assertThat(binds(SOURCE)).isEmpty();
		assertThat(image(new Tm1EngineContainer(SOURCE))).isEqualTo("tm1-engine:test");
	}

	@Test
	void aSubclassKeepsTheAnnotationsOptions() {
		// JUnit runs the extension for a subclass of an annotated class, so the options
		// have to come with it
		var inherited = Sub.class.getAnnotation(RequiresTm1Engine.class);
		assertThat(inherited).isNotNull();
		assertThat(inherited.shareDatabase()).isTrue();
		assertThat(inherited.minVersion()).isEqualTo("12.6");
	}

	@RequiresTm1Engine(shareDatabase = true, minVersion = "12.6")
	abstract static class Base {

	}

	static final class Sub extends Base {

	}

	@Test
	void namesAClassesDatabaseAfterTheClass() {
		assertThat(Tm1EngineExtension.databaseName("CubeTest", "k3f9")).isEqualTo("cubetest_k3f9");
		// A short suffix is padded, so the name is always long enough for TM1
		assertThat(Tm1EngineExtension.databaseName("T", "7")).isEqualTo("t_0007");
		assertThat(Tm1EngineExtension.databaseName("$$", "ab")).isEqualTo("test_00ab");
		assertThat(Tm1EngineExtension.databaseName("AVeryLongTestClassNameThatGoesOnAndOn", "zzzz"))
			.isEqualTo("averylongtestclassnameth_zzzz");
	}

	/** The image a container runs, without pulling it as getDockerImageName does. */
	private static String image(GenericContainer<?> container) {
		Matcher name = Pattern.compile("imageName=([^,)]+)").matcher(container.getImage().toString());
		assertThat(name.find()).as(container.getImage().toString()).isTrue();
		return name.group(1);
	}

	private static List<String> binds(Tm1EngineSource source) {
		return new Tm1EngineContainer(source).sourceBinds().stream().map(Object::toString).toList();
	}

}
