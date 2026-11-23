// SPDX-License-Identifier: Apache-2.0

/**
 * Spring Boot auto-configuration for the TM1 client: for each TM1 server, named by
 * {@code tm1.servers.<name>.*} or, for the server named {@code default}, {@code tm1.*},
 * {@link io.skipcheck.tm1.spring.boot.autoconfigure.Tm1ConnectionDetails} (so
 * {@code @ServiceConnection} can supply the connection) and beans for the session, the
 * client and, when enabled, the session pool.
 *
 * <p>
 * Everything here is non-null unless marked {@link org.jspecify.annotations.Nullable}.
 */
@NullMarked
package io.skipcheck.tm1.spring.boot.autoconfigure;

import org.jspecify.annotations.NullMarked;
