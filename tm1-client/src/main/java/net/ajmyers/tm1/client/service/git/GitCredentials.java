package net.ajmyers.tm1.client.service.git;

import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * How TM1 signs in to the Git remote: nothing (a public or {@code git://} remote), a user
 * name and password or token (HTTPS), or an SSH key pair. TM1 doesn't keep them, so every
 * call that reaches the remote takes them again. {@link #toString} leaves the secrets
 * out.
 *
 * @param username the user name, for HTTPS
 * @param password the password or access token, for HTTPS
 * @param publicKey the SSH public key
 * @param privateKey the SSH private key
 * @param passphrase the private key's passphrase
 */
public record GitCredentials(@Nullable String username, @Nullable String password, @Nullable String publicKey,
		@Nullable String privateKey, @Nullable String passphrase) {

	/**
	 * No credentials.
	 * @return them
	 */
	public static GitCredentials none() {
		return new GitCredentials(null, null, null, null, null);
	}

	/**
	 * A user name and password, or access token, for an HTTPS remote.
	 * @param username the user name
	 * @param password the password or token
	 * @return them
	 */
	public static GitCredentials password(String username, String password) {
		return new GitCredentials(username, password, null, null, null);
	}

	/**
	 * An SSH key pair, for an SSH remote.
	 * @param publicKey the public key
	 * @param privateKey the private key
	 * @param passphrase its passphrase, or {@code null} for none
	 * @return them
	 */
	public static GitCredentials sshKey(String publicKey, String privateKey, @Nullable String passphrase) {
		return new GitCredentials(null, null, publicKey, privateKey, passphrase);
	}

	void addTo(Map<String, Object> parameters) {
		put(parameters, "Username", username);
		put(parameters, "Password", password);
		put(parameters, "PublicKey", publicKey);
		put(parameters, "PrivateKey", privateKey);
		put(parameters, "Passphrase", passphrase);
	}

	static void put(Map<String, Object> parameters, String name, @Nullable Object value) {
		if (value != null) {
			parameters.put(name, value);
		}
	}

	/** Which credentials these are, without the secrets. */
	@Override
	public String toString() {
		return "GitCredentials[username=" + username + ", password=" + (password == null ? "none" : "***") + ", sshKey="
				+ (privateKey == null ? "none" : "***") + "]";
	}

}
