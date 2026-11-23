package io.skipcheck.tm1.client.session;

import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.skipcheck.tm1.client.auth.Tm1Authenticator;

/**
 * Who a session is: the identity a {@link Tm1SessionPool} keys its sessions by. In TM1
 * the identity is fixed when the session logs in, so one identity is one session (plan
 * §16.1).
 *
 * <ul>
 * <li>{@link #serviceAccount()}: the pool's own credentials, as they are.</li>
 * <li>{@link #impersonating(String)}: the pool's credentials, acting as a TM1 user
 * through {@code TM1-Impersonate}.</li>
 * <li>{@link #withCredentials(String, Tm1Authenticator)}: a user's own credentials, such
 * as their CAM passport.</li>
 * </ul>
 *
 * <p>
 * Two identities are equal when they're the same kind and their names match as TM1
 * matches user names, ignoring case and spaces, so {@code impersonating("Alice")} and
 * {@code impersonating("alice")} share a session. The authenticator isn't part of
 * equality: an identity's name says who it is.
 */
public final class Tm1Identity {

	private static final Tm1Identity SERVICE_ACCOUNT = new Tm1Identity(Kind.SERVICE_ACCOUNT, "", null);

	/** The kinds of identity. */
	public enum Kind {

		/** The pool's own credentials. */
		SERVICE_ACCOUNT,

		/** The pool's credentials, impersonating a user. */
		IMPERSONATING,

		/** A user's own credentials. */
		OWN_CREDENTIALS

	}

	private final Kind kind;

	private final String name;

	private final String comparableName;

	private final @Nullable Tm1Authenticator authenticator;

	private Tm1Identity(Kind kind, String name, @Nullable Tm1Authenticator authenticator) {
		this.kind = kind;
		this.name = name;
		this.comparableName = UserNames.comparable(name);
		this.authenticator = authenticator;
	}

	/**
	 * The pool's own credentials, with no impersonation.
	 * @return the identity
	 */
	public static Tm1Identity serviceAccount() {
		return SERVICE_ACCOUNT;
	}

	/**
	 * The pool's credentials, acting as TM1 user {@code user}. The pool's credentials
	 * must be an administrator's or a data administrator's, and administrators can't be
	 * impersonated; see {@link Tm1Session.Builder#impersonate(String)}.
	 * @param user the TM1 user to act as
	 * @return the identity
	 * @throws IllegalArgumentException if {@code user} is blank or has a control
	 * character
	 */
	public static Tm1Identity impersonating(String user) {
		return new Tm1Identity(Kind.IMPERSONATING, UserNames.checkImpersonated(user), null);
	}

	/**
	 * A user logging in with their own credentials.
	 * @param name who the credentials belong to, for the pool's key and for logs, such as
	 * the user name
	 * @param authenticator their credentials
	 * @return the identity
	 */
	public static Tm1Identity withCredentials(String name, Tm1Authenticator authenticator) {
		Objects.requireNonNull(name, "name");
		if (name.isBlank()) {
			throw new IllegalArgumentException("An identity's name must not be blank");
		}
		return new Tm1Identity(Kind.OWN_CREDENTIALS, name, Objects.requireNonNull(authenticator, "authenticator"));
	}

	/**
	 * What kind of identity this is.
	 * @return the kind
	 */
	public Kind kind() {
		return kind;
	}

	/**
	 * The user's name, as given; empty for the service account.
	 * @return the name
	 */
	public String name() {
		return name;
	}

	/**
	 * The user's own credentials, for {@link Kind#OWN_CREDENTIALS}.
	 * @return the authenticator, or empty for the other kinds
	 */
	public Optional<Tm1Authenticator> authenticator() {
		return Optional.ofNullable(authenticator);
	}

	/** Applies this identity to session settings copied from the pool's. */
	Tm1Session.Builder applyTo(Tm1Session.Builder settings) {
		return switch (kind) {
			case SERVICE_ACCOUNT -> settings;
			case IMPERSONATING -> settings.impersonate(name);
			case OWN_CREDENTIALS ->
				settings.withoutImpersonation().authenticator(Objects.requireNonNull(authenticator));
		};
	}

	@Override
	public boolean equals(@Nullable Object other) {
		return this == other || (other instanceof Tm1Identity identity && kind == identity.kind
				&& comparableName.equals(identity.comparableName));
	}

	@Override
	public int hashCode() {
		return Objects.hash(kind, comparableName);
	}

	@Override
	public String toString() {
		return switch (kind) {
			case SERVICE_ACCOUNT -> "Tm1Identity[service account]";
			case IMPERSONATING -> "Tm1Identity[impersonating " + name + "]";
			case OWN_CREDENTIALS -> "Tm1Identity[" + name + "]";
		};
	}

}
