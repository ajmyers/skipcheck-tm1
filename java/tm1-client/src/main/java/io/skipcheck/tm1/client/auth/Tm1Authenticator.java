// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
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
 * <pre class="code">
 * Tm1Authenticator.basic("admin", "apple");                       // TM1's own security
 * Tm1Authenticator.camNamespace("alice", "secret", "LDAP");       // CAM, with a user and password
 * Tm1Authenticator.camPassport(() -&gt; passports.current());       // CAM, with a passport
 * Tm1Authenticator.cognosLogon("http://cognos:9300", "LDAP", "alice", "secret"); // a passport from Cognos
 * </pre>
 *
 * <p>
 * Implementations must not log or otherwise reveal the credentials; {@link #describe()}
 * is what logs show.
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
	 * Basic authentication, for TM1's own security (modes 1 and 2).
	 * @param user the TM1 user name
	 * @param password the password, or {@code null} for none
	 * @return the authenticator
	 */
	static Tm1Authenticator basic(String user, @Nullable String password) {
		Objects.requireNonNull(user, "user");
		String value = "Basic " + base64(user + ":" + (password == null ? "" : password));
		return new Fixed(value, "Basic " + user);
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
		return new Fixed(value, "CAMNamespace " + namespace + "/" + user);
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
		return described(passports, "CAMPassport from Cognos " + namespace + "/" + user);
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
		return described(passports, "CAMPassport from Cognos " + namespace + " (ID token)");
	}

	private static Tm1Authenticator described(Tm1Authenticator authenticator, String description) {
		return new Tm1Authenticator() {

			@Override
			public Mono<String> authorization() {
				return authenticator.authorization();
			}

			@Override
			public String describe() {
				return description;
			}

			@Override
			public String toString() {
				return "Tm1Authenticator[" + description + "]";
			}
		};
	}

	private static String base64(String text) {
		return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
	}

	/** Credentials that don't change: Basic and CAMNamespace. */
	final class Fixed implements Tm1Authenticator {

		private final String value;

		private final String description;

		private Fixed(String value, String description) {
			this.value = value;
			this.description = description;
		}

		@Override
		public Mono<String> authorization() {
			return Mono.just(value);
		}

		@Override
		public String describe() {
			return description;
		}

		/** The description only: never the credentials. */
		@Override
		public String toString() {
			return "Tm1Authenticator[" + description + "]";
		}

	}

}
