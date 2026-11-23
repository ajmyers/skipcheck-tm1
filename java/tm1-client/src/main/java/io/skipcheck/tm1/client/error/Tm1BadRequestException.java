// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.error;

import org.jspecify.annotations.Nullable;

/**
 * 400-range errors not covered by another type, e.g. invalid MDX or a refused cell write.
 */
public final class Tm1BadRequestException extends Tm1ApiException {

	private static final long serialVersionUID = 1L;

	Tm1BadRequestException(int status, Tm1ErrorKind kind, @Nullable ODataError error, String method, String url) {
		super(status, kind, error, method, url);
	}

}
