// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.version;

import java.net.URI;
import java.util.Objects;

/**
 * How to reach a kind of TM1 deployment (plan §9, mechanism 3): structural differences
 * such as the REST API's root path. Only TM1 11 on-premises ({@link #V11}) is
 * implemented. The interface exists so TM1 12 (Planning Analytics Engine) and Planning
 * Analytics Cloud, which have different roots and authentication, can be added without a
 * redesign.
 */
public interface Tm1Dialect {

	/** TM1 11 on-premises: the REST API is at {@code http(s)://host:port/api/v1/}. */
	Tm1Dialect V11 = new Tm1Dialect() {

		@Override
		public String name() {
			return "TM1 11";
		}

		@Override
		public String serviceRoot(String address) {
			URI uri = URI.create(Objects.requireNonNull(address, "address").strip());
			if (!uri.isAbsolute() || uri.getRawAuthority() == null || uri.getRawQuery() != null
					|| uri.getRawFragment() != null) {
				throw new IllegalArgumentException(
						"A TM1 11 address is the server's scheme, host and HTTP port, such as http://tm1:8010: "
								+ address);
			}
			String path = uri.getRawPath() == null ? "" : uri.getRawPath();
			while (path.endsWith("/")) {
				path = path.substring(0, path.length() - 1);
			}
			if (!path.endsWith("/api/v1")) {
				path = path + "/api/v1";
			}
			return uri.getScheme() + "://" + uri.getRawAuthority() + path + "/";
		}

		@Override
		public String toString() {
			return "Tm1Dialect[" + name() + "]";
		}

	};

	/**
	 * The deployment kind, for logs and messages.
	 * @return the name, such as {@code TM1 11}
	 */
	String name();

	/**
	 * The REST API's service root for a server's address.
	 * @param address where the server is, such as {@code http://tm1:8010}; a full service
	 * root is accepted too
	 * @return the service root, ending in {@code /}
	 * @throws IllegalArgumentException if {@code address} isn't an address of this kind
	 */
	String serviceRoot(String address);

}
