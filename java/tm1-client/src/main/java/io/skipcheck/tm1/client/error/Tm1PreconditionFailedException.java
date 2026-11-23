// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.error;

import org.jspecify.annotations.Nullable;

/** An {@code If-Match} ETag was stale (412), or required and missing (428). */
public final class Tm1PreconditionFailedException extends Tm1ApiException {

	private static final long serialVersionUID = 1L;

	Tm1PreconditionFailedException(int status, Tm1ErrorKind kind, @Nullable ODataError error, String method,
			String url) {
		super(status, kind, error, method, url);
	}

}
