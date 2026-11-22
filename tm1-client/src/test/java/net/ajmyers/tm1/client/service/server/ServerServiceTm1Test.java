package net.ajmyers.tm1.client.service.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.ajmyers.tm1.client.Tm1Client;
import net.ajmyers.tm1.client.Tm1Request;
import net.ajmyers.tm1.client.error.Tm1BadRequestException;
import net.ajmyers.tm1.client.model.LogLevel;
import net.ajmyers.tm1.client.model.Logger;
import net.ajmyers.tm1.client.model.Server;
import net.ajmyers.tm1.client.model.ServerSettings;
import net.ajmyers.tm1.client.model.ServerSettingsPatch;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.testcontainers.RequiresTm1;
import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;

/** ServerService: the server, its configurations and loggers. */
@RequiresTm1
class ServerServiceTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static Tm1ServerContainer server;

	private static Tm1Client tm1;

	@BeforeAll
	static void connect(Tm1ServerContainer container) {
		server = container;
		tm1 = Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
	}

	@AfterAll
	static void close() {
		tm1.close().block(WAIT);
	}

	@Test
	void readsTheServerAndItsConfigurations() {
		Server read = tm1.server().server(ODataQuery.none()).block(WAIT);
		assertThat(read.productVersion()).isEqualTo(server.getTm1Version().toString());
		assertThat(read.etag()).as("none for the Server").isNull();
		ServerSettings fixed = tm1.server().staticConfiguration(ODataQuery.none()).block(WAIT);
		ServerSettings active = tm1.server().activeConfiguration(ODataQuery.none()).block(WAIT);
		assertThat(fixed.serverName()).isEqualTo(read.name());
		assertThat(active.serverName()).isEqualTo(read.name());
		deprecated(read.name());

		tm1.server()
			.updateStaticConfiguration(ServerSettingsPatch.builder().serverName(fixed.serverName()).build())
			.block(WAIT);
		assertThatThrownBy(() -> tm1.session()
			.execute(Tm1Request.patch(ODataPath.of("ActiveConfiguration"),
					ServerSettingsPatch.builder().serverName(active.serverName()).build()))
			.block(WAIT)).as("the active configuration is read only").isInstanceOf(Tm1BadRequestException.class);
		assertThat(active.access().cam().clientPingCAMPassport().isString())
			.as("a duration here, though declared Int32")
			.isTrue();
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
