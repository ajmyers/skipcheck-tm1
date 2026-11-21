package net.ajmyers.tm1.client;

import java.util.Objects;

import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.micrometer.observation.ObservationRegistry;
import net.ajmyers.tm1.client.auth.Tm1Authenticator;
import net.ajmyers.tm1.client.error.Tm1ErrorClassifier;
import net.ajmyers.tm1.client.session.Tm1Session;
import net.ajmyers.tm1.client.session.Tm1Timeouts;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * The client for one TM1 server: one {@link Tm1Session}, and every service built on it
 * (plan §6). Services arrive with the endpoint phases; until then, {@link #session()}
 * sends any request.
 *
 * <pre>{@code
 * Tm1Client tm1 = Tm1Client.builder()
 *     .baseUrl("http://localhost:8010/api/v1/")
 *     .basicAuth("admin", "")
 *     .build();
 * tm1.session().stream(Tm1Request.list(ODataPath.of("Cubes"), ODataQuery.none().select("Name"), JsonNode.class))
 *     .map(cube -> cube.get("Name").asString())
 *     .doOnNext(System.out::println)
 *     .then(tm1.close())   // close after the list completes, not alongside it
 *     .block();            // in a standalone main() only
 * }</pre>
 */
public final class Tm1Client {

	private final Tm1Session session;

	private Tm1Client(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * A builder for a client of one TM1 server.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * A client over an existing session, such as one from a session pool or a Spring
	 * bean.
	 * @param session the session to send requests on
	 * @return a client over {@code session}
	 */
	public static Tm1Client of(Tm1Session session) {
		return new Tm1Client(session);
	}

	/**
	 * The session every service uses.
	 * @return the session
	 */
	public Tm1Session session() {
		return session;
	}

	/**
	 * A client whose services all run in sandbox {@code name}, sharing this client's
	 * session.
	 * @param name the sandbox's name
	 * @return a new client; this one is unchanged
	 */
	public Tm1Client withSandbox(String name) {
		return new Tm1Client(session.withSandbox(name));
	}

	/**
	 * Logs in now rather than on the first request, and completes with this client.
	 * @return a {@link Mono} emitting this client once logged in
	 */
	public Mono<Tm1Client> connect() {
		return session.connect().thenReturn(this);
	}

	/**
	 * Closes the session; see {@link Tm1Session#close()}.
	 * @return a {@link Mono} completing when the session is closed
	 */
	public Mono<Void> close() {
		return session.close();
	}

	@Override
	public String toString() {
		return "Tm1Client[" + session + "]";
	}

	/**
	 * Builds a {@link Tm1Client}: the same settings as {@link Tm1Session.Builder}, which
	 * it delegates to.
	 */
	public static final class Builder {

		private final Tm1Session.Builder session = Tm1Session.builder();

		private Builder() {
		}

		/**
		 * See {@link Tm1Session.Builder#baseUrl}.
		 * @param baseUrl the service root, such as {@code http://localhost:8010/api/v1/}
		 * @return this builder
		 */
		public Builder baseUrl(String baseUrl) {
			session.baseUrl(baseUrl);
			return this;
		}

		/**
		 * See {@link Tm1Session.Builder#basicAuth}.
		 * @param user the user name
		 * @param password the password
		 * @return this builder
		 */
		public Builder basicAuth(String user, String password) {
			session.basicAuth(user, password);
			return this;
		}

		/**
		 * See {@link Tm1Session.Builder#authenticator}.
		 * @param authenticator supplies the credentials for each login
		 * @return this builder
		 */
		public Builder authenticator(Tm1Authenticator authenticator) {
			session.authenticator(authenticator);
			return this;
		}

		/**
		 * See {@link Tm1Session.Builder#sessionContext}.
		 * @param sessionContext the session context sent to TM1
		 * @return this builder
		 */
		public Builder sessionContext(String sessionContext) {
			session.sessionContext(sessionContext);
			return this;
		}

		/**
		 * See {@link Tm1Session.Builder#timeouts}.
		 * @param timeouts the timeouts
		 * @return this builder
		 */
		public Builder timeouts(Tm1Timeouts timeouts) {
			session.timeouts(timeouts);
			return this;
		}

		/**
		 * See {@link Tm1Session.Builder#errorClassifier}.
		 * @param classifier the classifier for error responses
		 * @return this builder
		 */
		public Builder errorClassifier(Tm1ErrorClassifier classifier) {
			session.errorClassifier(classifier);
			return this;
		}

		/**
		 * See {@link Tm1Session.Builder#jsonMapper}.
		 * @param mapper the JSON mapper
		 * @return this builder
		 */
		public Builder jsonMapper(ObjectMapper mapper) {
			session.jsonMapper(mapper);
			return this;
		}

		/**
		 * See {@link Tm1Session.Builder#observationRegistry}.
		 * @param observations the registry to record observations in
		 * @return this builder
		 */
		public Builder observationRegistry(ObservationRegistry observations) {
			session.observationRegistry(observations);
			return this;
		}

		/**
		 * See {@link Tm1Session.Builder#webClient}.
		 * @param webClient the builder to build the {@link WebClient} from
		 * @return this builder
		 */
		public Builder webClient(WebClient.Builder webClient) {
			session.webClient(webClient);
			return this;
		}

		/**
		 * See {@link Tm1Session.Builder#connector}.
		 * @param connector the HTTP connector
		 * @return this builder
		 */
		public Builder connector(ClientHttpConnector connector) {
			session.connector(connector);
			return this;
		}

		/**
		 * The client. Nothing is sent to TM1 until the first request.
		 * @return the client
		 */
		public Tm1Client build() {
			return new Tm1Client(session.build());
		}

	}

}
