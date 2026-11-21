package net.ajmyers.tm1.testcontainers.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import net.ajmyers.tm1.testcontainers.RequiresTm1;
import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;
import net.ajmyers.tm1.testcontainers.Tm1Source;

/**
 * <b>A server running your own database.</b>
 *
 * <p>Point {@code withData} at a TM1 data folder: the {@code .cub}, {@code .dim}, {@code .pro}
 * files and so on, as in a server's {@code DataBaseDirectory}. In your project that's usually a
 * small fixture model checked in under {@code src/test/resources}. It's copied into the
 * container before TM1 starts, so tests can change it freely without touching your copy.
 *
 * <p>Set {@code withCredentials} to a login your database has. This example uses IBM's SData
 * sample folder from the TM1 install as "your" database, so it only runs when TM1 comes from an
 * install folder ({@code TM1_HOME_DIR}).
 */
@RequiresTm1
class OwnDatabaseExampleTest {

	@Test
	void runsADatabaseFromAFolder(Tm1ServerContainer shared) {
		assumeTrue(shared.getSource() instanceof Tm1Source.InstallDir, "needs TM1_HOME_DIR, to find a sample database folder");
		Path myDatabase = ((Tm1Source.InstallDir) shared.getSource()).dir().resolve("samples/tm1/SData");
		assumeTrue(Files.isDirectory(myDatabase), "this install has no SData sample");

		try (var server = new Tm1ServerContainer(shared.getSource())
				.withData(myDatabase)
				.withCredentials("admin", "apple")) {
			server.start();

			try (var tm1 = new Tm1Rest(server)) {
				assertThat(tm1.get("Cubes('SalesCube')?$select=Name")).containsIgnoringCase("SalesCube");
			}
		}
	}
}
