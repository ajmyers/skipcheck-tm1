// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.LiveServer;
import io.skipcheck.tm1.client.LiveTm1;
import io.skipcheck.tm1.client.ServerAssumptions;
import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.error.Tm1BadRequestException;
import io.skipcheck.tm1.client.model.AdministrationSettingsPatch;
import io.skipcheck.tm1.client.model.LogLevel;
import io.skipcheck.tm1.client.model.Logger;
import io.skipcheck.tm1.client.model.Server;
import io.skipcheck.tm1.client.model.ServerSettings;
import io.skipcheck.tm1.client.model.ServerSettingsPatch;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;

/** ServerService: the server, its configurations and loggers. */
@SuppressWarnings("deprecation")
@LiveTm1
class ServerServiceTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static LiveServer server;

	private static Tm1Client tm1;

	@BeforeAll
	static void connect(LiveServer container) {
		server = container;
		tm1 = server.clientBuilder().build();
	}

	@AfterAll
	static void close() {
		tm1.close().block(WAIT);
	}

	@Test
	void readsTheServerAndItsConfigurations() {
		boolean v12 = ServerAssumptions.isV12(tm1);
		Server read = tm1.server().server(ODataQuery.none()).block(WAIT);
		if (v12) {
			assertThat(tm1.server().server(ODataQuery.none().select("ProductVersion")).block(WAIT).productVersion())
				.isEqualTo(server.getTm1Version().toString());
		}
		else {
			assertThat(read.productVersion()).isEqualTo(server.getTm1Version().toString());
		}
		assertThat(read.etag()).as("none for the Server").isNull();
		ServerSettings fixed = tm1.server().staticConfiguration(ODataQuery.none()).block(WAIT);
		ServerSettings active = tm1.server().activeConfiguration(ODataQuery.none()).block(WAIT);
		assertThat(fixed.serverName()).isEqualTo(read.name());
		assertThat(active.serverName()).isEqualTo(read.name());
		deprecated(read.name());

		tm1.server()
			.updateStaticConfiguration(ServerSettingsPatch.builder()
				.administration(
						AdministrationSettingsPatch.builder().serverName(fixed.administration().serverName()).build())
				.build())
			.block(WAIT);
		assertThatThrownBy(() -> tm1.session()
			.execute(Tm1Request.patch(ODataPath.of("ActiveConfiguration"), ServerSettingsPatch.builder()
				.administration(
						AdministrationSettingsPatch.builder().serverName(active.administration().serverName()).build())
				.build()))
			.block(WAIT)).as("the active configuration is read only").isInstanceOf(Tm1BadRequestException.class);
		if (v12) {
			assertThat(active.access()).as("TM1 v12 omits Access from ActiveConfiguration").isNull();
		}
		else {
			assertThat(active.access().cam().clientPingCAMPassport().isString())
				.as("a duration here, though declared Int32")
				.isTrue();
		}
	}

	@SuppressWarnings("deprecation")
	private static void deprecated(String name) {
		assertThat(tm1.server().configuration(ODataQuery.none()).block(WAIT).serverName()).isEqualTo(name);
	}

	@Test
	void readsAndSetsLoggerLevels() {
		List<Logger> loggers = tm1.server().loggers(ODataQuery.none()).collectList().block(WAIT);
		assertThat(loggers).isNotEmpty();
		// Not the first, TM1's root logger: at DEBUG it floods the message log of the
		// server every test shares, with lines alike to the millisecond
		Logger first = loggers.stream().filter(l -> "TM1.Blob".equals(l.name())).findFirst().orElseThrow();
		assertThat(tm1.server().logger(first.name(), ODataQuery.none()).block(WAIT).level()).isEqualTo(first.level());
		LogLevel other = first.level() == LogLevel.DEBUG ? LogLevel.INFO : LogLevel.DEBUG;
		tm1.server().setLoggerLevel(first.name(), other).block(WAIT);
		try {
			assertThat(tm1.server().logger(first.name(), ODataQuery.none()).block(WAIT).level()).isEqualTo(other);
		}
		finally {
			tm1.server().setLoggerLevel(first.name(), first.level()).block(WAIT);
		}
	}

}
