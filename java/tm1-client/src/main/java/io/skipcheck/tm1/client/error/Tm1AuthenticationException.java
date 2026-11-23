// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.error;

import org.jspecify.annotations.Nullable;

/**
 * Not logged in, the session expired, or the credentials were refused
 * ({@link Tm1ErrorKind#CREDENTIAL_REJECTED}).
 */
public final class Tm1AuthenticationException extends Tm1ApiException {

	private static final long serialVersionUID = 1L;

	Tm1AuthenticationException(int status, Tm1ErrorKind kind, @Nullable ODataError error, String method, String url) {
		super(status, kind, error, method, url);
	}

}
