// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import reactor.core.publisher.Mono;

/**
 * Supplies the credentials for logging in to TM1: the value of the {@code Authorization}
 * header of a login request.
 *
 * <p>
 * It's asked once per login, not once per session: a session logs in again when TM1
 * reports it expired, and credentials that expire themselves (a CAM passport) can be
 * fresh each time. Only the login carries credentials; every other request carries the
 * session cookie.
 *
 * {@snippet class = examples.Tm1AuthenticatorExamples region = kinds}
 *
 * <p>
 * Implementations must not log or otherwise reveal the credentials; {@link #describe()}
 * is what logs show.
 *
 * <p>
 * A {@code Tm1SessionPool} keeps one session for each set of a user's own credentials,
 * and tells them apart with {@code equals}. The authenticators here that hold their
 * credentials ({@link #basic}, {@link #camNamespace}, {@link #camPassport(String)},
 * {@link #cognosLogon}, {@link #bearer(String)}, {@link #application}) are equal when the
 * credentials are. One that asks a supplier ({@link #camPassport(Supplier)},
 * {@link #cognosIdToken}, {@link #bearer(Supplier)}) is only equal to itself, so keep it
 * and pass the same one each time; so is your own, unless it defines {@code equals}.
 */
public interface Tm1Authenticator {

	/**
	 * The {@code Authorization} header value for one login, such as
	 * {@code Basic YWRtaW46YXBwbGU=}. An error fails that login and reaches the caller
	 * unchanged.
	 * @return the header value
	 */
	Mono<String> authorization();

	/**
	 * What these credentials are, for logs, without any secret, such as
	 * {@code Basic admin}.
	 * @return the description
	 */
	String describe();

	/**
	 * The TM1 user name for TM1 12 application (service-to-service) authentication, when
	 * logging in through {@code /<instance>/auth/v1/session}. Empty for authenticators
	 * that send {@code Authorization} directly to the database's {@code ActiveSession}.
	 * @return the user name, or empty
	 */
	default Optional<String> applicationUser() {
		return Optional.empty();
	}

	/**
	 * Basic authentication, for TM1's own security (modes 1 and 2).
	 * @param user the TM1 user name
	 * @param password the password, or {@code null} for none
	 * @return the authenticator
	 */
	static Tm1Authenticator basic(String user, @Nullable String password) {
		Objects.requireNonNull(user, "user");
		String value = "Basic " + base64(user + ":" + (password == null ? "" : password));
		return new FixedAuthenticator(value, "Basic " + user);
	}

	/**
	 * Cognos Access Manager security with a user name and password in a CAM namespace
	 * (TM1 security mode 5): {@code CAMNamespace base64(user:password:namespace)}.
	 * @param user the user name in the namespace
	 * @param password the password
	 * @param namespace the CAM namespace ID, such as {@code LDAP}
	 * @return the authenticator
	 */
	static Tm1Authenticator camNamespace(String user, String password, String namespace) {
		Objects.requireNonNull(user, "user");
		Objects.requireNonNull(password, "password");
		Objects.requireNonNull(namespace, "namespace");
		String value = "CAMNamespace " + base64(user + ":" + password + ":" + namespace);
		return new FixedAuthenticator(value, "CAMNamespace " + namespace + "/" + user);
	}

	/**
	 * Cognos Access Manager security with one CAM passport the application already holds,
	 * such as the one a signed-in user's request carries: {@code CAMPassport <passport>}.
	 * The passport isn't renewed: once it expires, a login with it is refused. Two of
	 * these are equal when their passports are, so a {@code Tm1SessionPool} gives a
	 * user's renewed passport a session of its own.
	 * @param passport the passport
	 * @return the authenticator
	 */
	static Tm1Authenticator camPassport(String passport) {
		Objects.requireNonNull(passport, "passport");
		return new FixedAuthenticator("CAMPassport " + passport, "CAMPassport");
	}

	/**
	 * Cognos Access Manager security with a CAM passport, such as one obtained by
	 * exchanging an OIDC token with Cognos: {@code CAMPassport <passport>}. Passports
	 * expire, so {@code passport} is asked for a current one on every login. When TM1
	 * refuses a passport the login fails with
	 * {@link io.skipcheck.tm1.client.error.Tm1ErrorKind#CREDENTIAL_REJECTED}, and the
	 * session doesn't keep trying.
	 * @param passport supplies a current passport; called once per login
	 * @return the authenticator
	 */
	static Tm1Authenticator camPassport(Supplier<Mono<String>> passport) {
		Objects.requireNonNull(passport, "passport");
		return new Tm1Authenticator() {

			@Override
			public Mono<String> authorization() {
				return Mono.defer(passport).map(p -> "CAMPassport " + p);
			}

			@Override
			public String describe() {
				return "CAMPassport";
			}

			@Override
			public String toString() {
				return "Tm1Authenticator[CAMPassport]";
			}
		};
	}

	/**
	 * CAM security with a passport the client gets from Cognos itself, logging on with a
	 * user name and password ({@link CognosPassports#password}): a fresh passport for
	 * each login. Unlike {@link #camNamespace}, TM1 never sees the password, only Cognos
	 * does.
	 * @param dispatcher the Cognos dispatcher's address, such as
	 * {@code http://cognos:9300}
	 * @param namespace the namespace's ID, such as {@code LDAP}
	 * @param user the user name
	 * @param password the password
	 * @return the authenticator
	 */
	static Tm1Authenticator cognosLogon(String dispatcher, String namespace, String user, String password) {
		Tm1Authenticator passports = camPassport(CognosPassports.password(dispatcher, namespace, user, password));
		return new DescribedAuthenticator(passports, "CAMPassport from Cognos " + namespace + "/" + user,
				List.of(dispatcher, namespace, user, password));
	}

	/**
	 * CAM security with a passport the client gets from Cognos for an OpenID Connect ID
	 * token it already holds ({@link CognosPassports#idToken}): a fresh passport for each
	 * login.
	 * @param dispatcher the Cognos dispatcher's address, such as
	 * {@code http://cognos:9300}
	 * @param namespace the OIDC namespace's ID, such as {@code OIDC}
	 * @param idToken supplies a current ID token; asked at each login
	 * @return the authenticator
	 */
	static Tm1Authenticator cognosIdToken(String dispatcher, String namespace, Supplier<Mono<String>> idToken) {
		Tm1Authenticator passports = camPassport(CognosPassports.idToken(dispatcher, namespace, idToken));
		return new DescribedAuthenticator(passports, "CAMPassport from Cognos " + namespace + " (ID token)",
				List.of(dispatcher, namespace, idToken));
	}

	/**
	 * Bearer token authentication, for TM1 12 with OAuth2/OIDC (such as Keycloak or
	 * PAaaS): {@code Bearer <token>}. The token isn't renewed: once it expires, a login
	 * with it is refused. Two of these are equal when their tokens are, so a
	 * {@code Tm1SessionPool} gives a user's renewed token a session of its own.
	 * @param token the bearer token; not blank
	 * @return the authenticator
	 * @throws IllegalArgumentException if {@code token} is blank or contains control
	 * characters
	 */
	static Tm1Authenticator bearer(String token) {
		return new FixedAuthenticator("Bearer " + ApplicationAuthenticator.checkToken(token), "Bearer");
	}

	/**
	 * Bearer token authentication with a token supplied on every login, for TM1 12 with
	 * OAuth2/OIDC: {@code Bearer <token>}. Access tokens expire, so {@code token} is
	 * asked for a fresh one on every login and re-login. Equal only to itself, so keep it
	 * and pass the same one to a {@code Tm1SessionPool}.
	 * @param token supplies a current bearer token; called once per login
	 * @return the authenticator
	 */
	static Tm1Authenticator bearer(Supplier<Mono<String>> token) {
		Objects.requireNonNull(token, "token");
		return new Tm1Authenticator() {

			@Override
			public Mono<String> authorization() {
				return Mono.defer(token).map(t -> "Bearer " + ApplicationAuthenticator.checkToken(t));
			}

			@Override
			public String describe() {
				return "Bearer";
			}

			@Override
			public String toString() {
				return "Tm1Authenticator[Bearer]";
			}
		};
	}

	/**
	 * TM1 12 (Planning Analytics Engine) service-to-service application authentication.
	 * Logs in by posting {@code {"User": "<user>"}} to
	 * {@code /<instance>/auth/v1/session} with HTTP Basic {@code clientId:clientSecret},
	 * then resolves the session on the target database. Requires
	 * {@link io.skipcheck.tm1.client.version.Tm1Dialect#v12} and cannot be combined with
	 * {@code TM1-Impersonate}. Two of these are equal when their client ID, client secret
	 * and user all match.
	 * @param clientId the configured application client ID; not blank and without
	 * {@code :}
	 * @param clientSecret the configured application client secret; not blank
	 * @param user the TM1 user to act as; not blank
	 * @return the authenticator
	 * @throws IllegalArgumentException if any argument is blank or invalid
	 */
	static Tm1Authenticator application(String clientId, String clientSecret, String user) {
		return new ApplicationAuthenticator(clientId, clientSecret, user);
	}

	private static String base64(String text) {
		return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
	}

}
