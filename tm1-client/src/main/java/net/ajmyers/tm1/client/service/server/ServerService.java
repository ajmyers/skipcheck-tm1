package net.ajmyers.tm1.client.service.server;

import java.util.Map;
import java.util.Objects;

import net.ajmyers.tm1.client.Tm1Request;
import net.ajmyers.tm1.client.model.Configuration;
import net.ajmyers.tm1.client.model.LogLevel;
import net.ajmyers.tm1.client.model.Logger;
import net.ajmyers.tm1.client.model.Server;
import net.ajmyers.tm1.client.model.ServerSettings;
import net.ajmyers.tm1.client.model.ServerSettingsPatch;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.client.service.Tm1Endpoint;
import net.ajmyers.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The server: what it is ({@code Server}), how it's configured, and its loggers.
 *
 * <p>
 * The static configuration is {@code tm1s.cfg}'s, and the one to change; the active one
 * is what the server runs with now, read only.
 *
 * <p>
 * Read the server's name and version:
 * {@snippet class = examples.ServerServiceExamples region = version}
 *
 * <p>
 * Read a setting the server runs with now:
 * {@snippet class = examples.ServerServiceExamples region = data-directory}
 *
 * <p>
 * Change a logger's level:
 * {@snippet class = examples.ServerServiceExamples region = logger-level}
 */
public final class ServerService {

	private static final ODataPath LOGGERS = ODataPath.of("Loggers");

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public ServerService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code GET Server}: the server's name, version and ports. TM1 (11.8) sends no ETag
	 * for it.
	 * @param query what to select
	 * @return the server
	 */
	@Tm1Endpoint("Singleton Server")
	public Mono<Server> server(ODataQuery query) {
		return session.execute(Tm1Request.get(ODataPath.of("Server"), query, Server.class));
	}

	/**
	 * {@code GET StaticConfiguration}: the settings the server started with, from
	 * {@code tm1s.cfg}.
	 * @param query what to select
	 * @return the settings
	 */
	@Tm1Endpoint("Singleton StaticConfiguration")
	public Mono<ServerSettings> staticConfiguration(ODataQuery query) {
		return session.execute(Tm1Request.get(ODataPath.of("StaticConfiguration"), query, ServerSettings.class));
	}

	/**
	 * {@code PATCH StaticConfiguration}: changes {@code tm1s.cfg}; most settings take
	 * effect when the server next starts.
	 * @param patch the settings to change
	 * @return completes when they're written
	 */
	@Tm1Endpoint("Singleton StaticConfiguration")
	public Mono<Void> updateStaticConfiguration(ServerSettingsPatch patch) {
		return session.execute(Tm1Request.patch(ODataPath.of("StaticConfiguration"), patch));
	}

	/**
	 * {@code GET ActiveConfiguration}: the settings the server runs with now. Read only:
	 * TM1 (11.8) refuses a PATCH here ("PATCH not allowed on this resource"); settings
	 * change through {@link #updateStaticConfiguration}.
	 * @param query what to select
	 * @return the settings
	 */
	@Tm1Endpoint("Singleton ActiveConfiguration")
	public Mono<ServerSettings> activeConfiguration(ODataQuery query) {
		return session.execute(Tm1Request.get(ODataPath.of("ActiveConfiguration"), query, ServerSettings.class));
	}

	/**
	 * {@code GET Configuration}: the server's configuration in the older, flat form.
	 * @param query what to select
	 * @return the configuration
	 * @deprecated Deprecated in TM1 schema 11.0.0: use {@link #staticConfiguration} and
	 * {@link #activeConfiguration}.
	 */
	@Deprecated(since = "11.0.0")
	@Tm1Endpoint("Singleton Configuration")
	public Mono<Configuration> configuration(ODataQuery query) {
		return session.execute(Tm1Request.get(ODataPath.of("Configuration"), query, Configuration.class));
	}

	/**
	 * {@code GET Loggers}, streamed: the server's loggers and their levels.
	 * @param query what to select, filter and order
	 * @return the loggers
	 */
	@Tm1Endpoint("EntitySet Loggers")
	public Flux<Logger> loggers(ODataQuery query) {
		return session.stream(Tm1Request.list(LOGGERS, query, Logger.class));
	}

	/**
	 * {@code GET Loggers('name')}.
	 * @param name the logger's name, such as {@code TM1.Server}
	 * @param query what to select
	 * @return the logger, or a {@code Tm1NotFoundException} if there's none
	 */
	@Tm1Endpoint("EntitySet Loggers")
	public Mono<Logger> logger(String name, ODataQuery query) {
		return session.execute(Tm1Request.get(LOGGERS.key(Objects.requireNonNull(name, "name")), query, Logger.class));
	}

	/**
	 * {@code PATCH Loggers('name')}: changes a logger's level, at once.
	 * @param name the logger's name
	 * @param level the new level
	 * @return completes when it's changed
	 */
	@Tm1Endpoint("EntitySet Loggers")
	public Mono<Void> setLoggerLevel(String name, LogLevel level) {
		return session.execute(Tm1Request.patch(LOGGERS.key(Objects.requireNonNull(name, "name")),
				Map.of("Level", Objects.requireNonNull(level.json(), "level"))));
	}

}
