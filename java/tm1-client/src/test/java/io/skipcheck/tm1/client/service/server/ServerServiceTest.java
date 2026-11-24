// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.skipcheck.tm1.client.conformance.Conformance;
import io.skipcheck.tm1.client.conformance.ConformanceTm1;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.model.AdministrationSettingsPatch;
import io.skipcheck.tm1.client.model.Configuration;
import io.skipcheck.tm1.client.model.LogLevel;
import io.skipcheck.tm1.client.model.Logger;
import io.skipcheck.tm1.client.model.LoggerPatch;
import io.skipcheck.tm1.client.model.Server;
import io.skipcheck.tm1.client.model.ServerSettings;
import io.skipcheck.tm1.client.model.ServerSettingsPatch;
import io.skipcheck.tm1.client.odata.Filter;
import io.skipcheck.tm1.client.odata.ODataQuery;

/**
 * ServerService against a fake TM1: what each method sends, and how it reads TM1's
 * answer. Every call is recorded to
 * {@code spec/conformance/calls/ServerServiceTest.json}, which the Python, .NET and
 * TypeScript clients replay.
 */
@SuppressWarnings("deprecation")
class ServerServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	@Test
	void readsTheServer() {
		tm1().json("{\"Name\":\"Planning Sample\",\"ProductVersion\":\"11.8.03500.6\",\"HTTPPortNumber\":12354}");
		Server server = conformance.call("server", "server", ODataQuery.none().select("Name", "ProductVersion"));
		assertThat(server.name()).isEqualTo("Planning Sample");
		assertThat(tm1().last().key()).isEqualTo("GET Server?$select=Name,ProductVersion");

		tm1().json("{\"Name\":\"Planning Sample\"}");
		conformance.call("server", "server", ODataQuery.none());
		assertThat(tm1().last().key()).isEqualTo("GET Server");
	}

	@Test
	void readsTheConfigurations() {
		tm1().json("{\"ServerName\":\"Planning Sample\",\"Administration\":{\"DataBaseDirectory\":\"/data\"}}");
		ServerSettings active = conformance.call("server", "activeConfiguration", ODataQuery.none());
		assertThat(active.serverName()).isEqualTo("Planning Sample");
		assertThat(tm1().last().key()).isEqualTo("GET ActiveConfiguration");

		tm1().json("{\"ServerName\":\"Planning Sample\"}");
		conformance.call("server", "staticConfiguration", ODataQuery.none().select("ServerName"));
		assertThat(tm1().last().key()).isEqualTo("GET StaticConfiguration?$select=ServerName");

		tm1().json("{\"ServerName\":\"Planning Sample\",\"DataBaseDirectory\":\"/data\",\"PortNumber\":12354}");
		Configuration configuration = conformance.call("server", "configuration", ODataQuery.none());
		assertThat(configuration.serverName()).isEqualTo("Planning Sample");
		assertThat(tm1().last().key()).isEqualTo("GET Configuration");
	}

	@Test
	void updatesTheStaticConfiguration() {
		tm1().status(204);
		conformance.call("server", "updateStaticConfiguration",
				ServerSettingsPatch.builder()
					.administration(AdministrationSettingsPatch.builder().disableSandboxing(true).build())
					.build());
		assertThat(tm1().last().key()).isEqualTo("PATCH StaticConfiguration");
		assertThat(tm1().last().text()).isEqualTo("{\"Administration\":{\"DisableSandboxing\":true}}");
	}

	@Test
	void aSettingTm1RefusesFails() {
		tm1().error(400, "278", "Invalid value for ServerName");
		conformance.fail("server", "updateStaticConfiguration",
				ServerSettingsPatch.builder()
					.administration(AdministrationSettingsPatch.builder().serverName("renamed").build())
					.build());
	}

	@Test
	void readsAndSetsLoggers() {
		tm1().json(
				"{\"value\":[{\"Name\":\"TM1.Server\",\"Level\":\"Info\"},{\"Name\":\"TM1.Process\",\"Level\":\"Off\"}]}");
		List<Logger> loggers = conformance.call("server", "loggers",
				ODataQuery.none().filter(Filter.raw("startswith(Name,'TM1.')")));
		assertThat(loggers).extracting(Logger::level).containsExactly(LogLevel.INFO, LogLevel.OFF);
		assertThat(tm1().last().target()).isEqualTo("Loggers?$filter=startswith(Name,'TM1.')");

		tm1().json("{\"Name\":\"TM1.Server\",\"Level\":\"Unknown\"}");
		Logger logger = conformance.call("server", "logger", "TM1.Server", ODataQuery.none().select("Level"));
		assertThat(logger.level()).isEqualTo(LogLevel.UNKNOWN);
		assertThat(tm1().last().target()).isEqualTo("Loggers('TM1.Server')?$select=Level");

		tm1().json("{\"Name\":\"TM1.Server\",\"Level\":\"Trace\"}");
		Logger newer = conformance.call("server", "logger", "TM1.Server", ODataQuery.none());
		assertThat(newer.level()).isEqualTo(LogLevel.UNRECOGNIZED);

		tm1().status(204);
		conformance.call("server", "setLoggerLevel", "TM1.Process", LogLevel.DEBUG);
		assertThat(tm1().last().key()).isEqualTo("PATCH Loggers('TM1.Process')");
		assertThat(tm1().last().text()).isEqualTo("{\"Level\":\"Debug\"}");
	}

	@Test
	void updatesALoggerWithAPatch() {
		tm1().status(204);
		conformance.call("server", "updateLogger", "TM1.Process", LoggerPatch.builder().level(LogLevel.DEBUG).build());
		assertThat(tm1().last().key()).isEqualTo("PATCH Loggers('TM1.Process')");
		assertThat(tm1().last().text()).isEqualTo("{\"Level\":\"Debug\"}");
	}

	@Test
	void aMissingLoggerIsNotFound() {
		tm1().error(404, "278", "'TM1.Nope' can not be found in collection of type 'Logger'.");
		Throwable error = conformance.fail("server", "logger", "TM1.Nope", ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void refusesALevelItDoesntKnow() {
		Throwable error = conformance.fail("server", "setLoggerLevel", "TM1.Process", LogLevel.UNRECOGNIZED);
		assertThat(error).isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

	@Test
	void refusesAMissingName() {
		Throwable error = conformance.fail("server", "logger", null, ODataQuery.none());
		assertThat(error).isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

}
