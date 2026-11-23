// SPDX-License-Identifier: Apache-2.0

/**
 * OData v4 protocol support that knows nothing about TM1: building correctly encoded
 * resource paths and query options, the {@code $filter} expression language, JSON control
 * information, and decoding collections as a stream.
 *
 * <p>
 * TM1 declares OData 4.0, so everything here produces 4.0 forms: lowercase,
 * {@code $}-prefixed system query options, single-quoted string literals with doubled
 * quotes, and {@code @odata.}-prefixed control information.
 *
 * <p>
 * Everything here is non-null unless marked {@link org.jspecify.annotations.Nullable}.
 */
@NullMarked
package io.skipcheck.tm1.client.odata;

import org.jspecify.annotations.NullMarked;
