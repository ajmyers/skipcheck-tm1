// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import java.util.Objects;

import io.skipcheck.tm1.client.version.Tm1Version;

/**
 * A database of a {@link Tm1EngineContainer}: what a test of TM1 v12 uses.
 *
 * <p>
 * An engine holds instances, and an instance holds databases. The REST API of one
 * database is at {@link #getRestUrl()}; a user logs in to its instance
 * ({@link #login()}), and the session serves every database there.
 */
public final class Tm1Database {

	private final Tm1EngineContainer engine;

	private final String instance;

	private final String name;

	Tm1Database(Tm1EngineContainer engine, String instance, String name) {
		this.engine = engine;
		this.instance = instance;
		this.name = name;
	}

	/**
	 * The engine this database is in.
	 * @return the engine's container
	 */
	public Tm1EngineContainer getEngine() {
		return engine;
	}

	/**
	 * The instance this database is in; {@code tm1} unless one was named.
	 * @return the instance's name
	 */
	public String getInstance() {
		return instance;
	}

	/**
	 * The database's name.
	 * @return the name
	 */
	public String getName() {
		return name;
	}

	/**
	 * The REST API's service root as seen from the host, e.g.
	 * {@code http://localhost:54321/tm1/api/v1/Databases('planning')/}.
	 * @return the service root, ending in {@code /}
	 */
	public String getRestUrl() {
		return engine.getUrl() + instance + "/api/v1/Databases('" + name.replace("'", "''") + "')/";
	}

	/**
	 * The version of TM1 the engine runs. Only available once it has started.
	 * @return the version
	 */
	public Tm1Version getVersion() {
		return engine.getTm1Version();
	}

	/**
	 * A login as {@code admin}, the administrator every database has.
	 * @return the login
	 */
	public Tm1Login login() {
		return login("admin");
	}

	/**
	 * A login as a user. TM1 makes a session for any name; a user who isn't in this
	 * database is then refused here (403).
	 * @param user the user's name
	 * @return the login
	 */
	public Tm1Login login(String user) {
		return engine.login(instance, user);
	}

	/**
	 * A login through OAuth 2.0: as a user the engine's Keycloak knows the password of
	 * ({@link Tm1EngineContainer#withLogin}). The login gets a token from Keycloak with
	 * that password and sends it to TM1, as a program acting for the user would.
	 * @param user the user's name
	 * @return the login
	 * @throws IllegalStateException if the engine has no
	 * {@link Tm1EngineContainer#withOAuth2}
	 * @throws IllegalArgumentException if the user has no password
	 */
	public Tm1Login oauth2Login(String user) {
		return engine.oauth2Login(this, user);
	}

	/**
	 * A pass-through login as the TM1 11 server's own user
	 * ({@link Tm1ServerContainer#getUsername()}): the administrator of a server with its
	 * own users, or the first administrator of one in Cognos (CAM) security. The login
	 * sends TM1 v12 what that server takes, and TM1 v12 asks it who that is.
	 * @return the login
	 * @throws IllegalStateException if the engine has no
	 * {@link Tm1EngineContainer#withPassThrough}
	 */
	public Tm1Login passThroughLogin() {
		return engine.passThroughLogin(this, null, null);
	}

	/**
	 * A pass-through login as a user whose password the containers know: one of Cognos's
	 * test users when the TM1 11 server is in CAM security, and otherwise that server's
	 * own user only.
	 * @param user the user's name on the TM1 11 server, or in Cognos
	 * @return the login
	 * @throws IllegalStateException if the engine has no
	 * {@link Tm1EngineContainer#withPassThrough}
	 * @throws IllegalArgumentException if the user's password isn't known
	 */
	public Tm1Login passThroughLogin(String user) {
		return engine.passThroughLogin(this, Objects.requireNonNull(user, "user"), null);
	}

	/**
	 * A pass-through login with a name and password of your own: a user you created on
	 * the TM1 11 server, or a wrong password to see refused. They go as {@code Basic}, or
	 * as {@code CAMNamespace} when the server is in CAM security.
	 * @param user the user's name on the TM1 11 server, or in Cognos
	 * @param password the password
	 * @return the login
	 * @throws IllegalStateException if the engine has no
	 * {@link Tm1EngineContainer#withPassThrough}
	 */
	public Tm1Login passThroughLogin(String user, String password) {
		return engine.passThroughLogin(this, Objects.requireNonNull(user, "user"),
				Objects.requireNonNull(password, "password"));
	}

	/**
	 * A pass-through login with an {@code Authorization} header of your own, which TM1
	 * v12 passes on as it is: {@code "CAMPassport " + passport} from a Cognos logon, say.
	 * @param authorization the header's value
	 * @return the login, with no user name or password
	 * @throws IllegalStateException if the engine has no
	 * {@link Tm1EngineContainer#withPassThrough}
	 */
	public Tm1Login passThroughLoginWith(String authorization) {
		return engine.passThroughLoginWith(this, authorization);
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof Tm1Database that && engine.equals(that.engine) && instance.equals(that.instance)
				&& name.equals(that.name);
	}

	@Override
	public int hashCode() {
		return Objects.hash(engine, instance, name);
	}

	@Override
	public String toString() {
		return "Tm1Database[" + instance + "/" + name + "]";
	}

}
