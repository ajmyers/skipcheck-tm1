package net.ajmyers.tm1.client.auth;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import net.ajmyers.tm1.client.error.ODataError;
import net.ajmyers.tm1.client.error.Tm1ApiException;
import net.ajmyers.tm1.client.error.Tm1ErrorKind;
import net.ajmyers.tm1.client.error.Tm1ProtocolException;
import reactor.core.publisher.Mono;

/**
 * CAM passports from Cognos, for {@link Tm1Authenticator#camPassport}: the client logs on
 * to Cognos itself, rather than being handed a passport. Each supplier logs on again
 * every time it's asked, so every TM1 login (including the one after TM1 drops a session)
 * gets a fresh passport, and an expired one is never reused.
 *
 * <p>
 * Both log on with Cognos's REST API: {@code PUT <dispatcher>/api/v1/session} with the
 * logon parameters, answered with the passport in a {@code cam_passport} cookie. The
 * dispatcher is the address TM1 itself is pointed at ({@code ServerCAMURI}, such as
 * {@code http://cognos:9300}), reachable from the client; TM1 names it when it refuses a
 * login ({@code WWW-Authenticate: CAMPassport http://cognos:9300/bi/v1/disp}).
 *
 * <p>
 * A logon Cognos refuses fails with a
 * {@link net.ajmyers.tm1.client.error.Tm1AuthenticationException}
 * ({@link Tm1ErrorKind#CREDENTIAL_REJECTED}), which the session doesn't retry.
 *
 * <p>
 * Log on with a user name and password, for a passport each login:
 * {@snippet class = examples.CognosPassportsExamples region = logon}
 *
 * <p>
 * Exchange the ID token of a user the application signed in with OpenID Connect:
 * {@snippet class = examples.CognosPassportsExamples region = id-token}
 */
public final class CognosPassports {

	private CognosPassports() {
	}

	/**
	 * Passports for a user name and password in a namespace, such as an LDAP one
	 * ({@code CAMNamespace}, {@code CAMUsername}, {@code CAMPassword}).
	 * @param dispatcher the Cognos dispatcher's address, such as
	 * {@code http://cognos:9300}
	 * @param namespace the namespace's ID, such as {@code LDAP}
	 * @param user the user name
	 * @param password the password
	 * @return a supplier that logs on each time it's asked
	 */
	public static Supplier<Mono<String>> password(String dispatcher, String namespace, String user, String password) {
		Objects.requireNonNull(user, "user");
		Objects.requireNonNull(password, "password");
		return logon(dispatcher, namespace,
				() -> Mono.just(List.of(new Parameter("CAMUsername", user), new Parameter("CAMPassword", password))));
	}

	/**
	 * Passports for an OpenID Connect ID token the application already holds, through
	 * Cognos's OIDC namespace ({@code CAMNamespace}, {@code CAMOIDCIdToken}): no password
	 * ever reaches the client.
	 * @param dispatcher the Cognos dispatcher's address, such as
	 * {@code http://cognos:9300}
	 * @param namespace the OIDC namespace's ID, such as {@code OIDC}
	 * @param idToken supplies a current ID token; asked at each logon
	 * @return a supplier that logs on each time it's asked
	 */
	public static Supplier<Mono<String>> idToken(String dispatcher, String namespace, Supplier<Mono<String>> idToken) {
		Objects.requireNonNull(idToken, "idToken");
		return logon(dispatcher, namespace,
				() -> Mono.defer(idToken).map(token -> List.of(new Parameter("CAMOIDCIdToken", token))));
	}

	private static Supplier<Mono<String>> logon(String dispatcher, String namespace,
			Supplier<Mono<List<Parameter>>> credentials) {
		Objects.requireNonNull(namespace, "namespace");
		URI session = session(Objects.requireNonNull(dispatcher, "dispatcher"));
		WebClient web = WebClient.create();
		return () -> Mono.defer(credentials).flatMap(parameters -> {
			List<Parameter> body = new ArrayList<>();
			body.add(new Parameter("CAMNamespace", namespace));
			body.addAll(parameters);
			return web.put()
				.uri(session)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("parameters", body))
				.exchangeToMono(response -> passport(response, session));
		});
	}

	private static Mono<String> passport(ClientResponse response, URI session) {
		int status = response.statusCode().value();
		if (status == 401 || status == 403) {
			return response.bodyToMono(String.class)
				.defaultIfEmpty("")
				.flatMap(text -> Mono.error(Tm1ApiException.of(status, Tm1ErrorKind.CREDENTIAL_REJECTED,
						ODataError.fromText("Cognos refused the logon"), "PUT", session.toString())));
		}
		ResponseCookie cookie = response.cookies().getFirst("cam_passport");
		return response.releaseBody().then(Mono.defer(() -> {
			if (status >= 300 || cookie == null || cookie.getValue().isBlank()) {
				return Mono.error(new Tm1ProtocolException(
						"Cognos answered the logon with " + status + " and no cam_passport cookie", null, "PUT",
						session.toString(), null));
			}
			return Mono.just(cookie.getValue());
		}));
	}

	/**
	 * Cognos's session resource, on the dispatcher, with or without a {@code /bi} path.
	 */
	static URI session(String dispatcher) {
		String root = dispatcher.replaceAll("/+$", "").replaceAll("/bi$", "");
		return URI.create(root + "/api/v1/session");
	}

	/** One logon parameter, as Cognos takes it: {@code {"name":…,"value":…}}. */
	private record Parameter(String name, String value) {

		/** The value only ever goes to Cognos: never into logs. */
		@Override
		public String toString() {
			return "Parameter[" + name + "]";
		}

	}

}
