package net.ajmyers.tm1.testcontainers.examples;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.ajmyers.tm1.testcontainers.RequiresTm1;
import net.ajmyers.tm1.testcontainers.Tm1Seed;
import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;

/**
 * <b>A server of your own, for one test class, with IBM's SData sample in it.</b>
 *
 * <p>Use this when tests need data to read, or when they'd get in the way of other classes on
 * the shared server. The class gets its own container, started before its tests and stopped
 * after them. {@code @RequiresTm1} still decides whether to run at all, and the shared
 * container's {@linkplain Tm1ServerContainer#getSource() source} tells this one where TM1 is.
 *
 * <p>{@link Tm1Seed#SDATA} also sets the sample's login, {@code admin} / {@code apple}.
 */
@RequiresTm1
class SampleDatabaseExampleTest {

	private static Tm1ServerContainer sdata;
	private static Tm1Rest tm1;

	@BeforeAll
	static void start(Tm1ServerContainer shared) {
		sdata = new Tm1ServerContainer(shared.getSource()).withSeed(Tm1Seed.SDATA);
		sdata.start();
		tm1 = new Tm1Rest(sdata);
	}

	@AfterAll
	static void stop() {
		tm1.close();
		sdata.stop();
	}

	@Test
	void readsTheSampleCubes() {
		assertThat(sdata.getPassword()).isEqualTo("apple");
		assertThat(tm1.modelCubeCount()).isPositive();
		assertThat(tm1.get("Cubes('SalesCube')?$select=Name")).containsIgnoringCase("\"Name\":\"SalesCube\"");
	}
}
