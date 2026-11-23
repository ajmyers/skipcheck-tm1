/**
 * The client's exceptions. Everything the client signals is a
 * {@link io.skipcheck.tm1.client.error.Tm1Exception}, unchecked, delivered through
 * {@code Mono.error}/{@code Flux.error}, never thrown when a request is assembled. Each
 * says whether retrying could help ({@code isRetryable()}) and whether the request may
 * already have taken effect ({@code mayHaveApplied()}).
 *
 * <p>
 * Everything here is non-null unless marked {@link org.jspecify.annotations.Nullable}.
 */
@NullMarked
package io.skipcheck.tm1.client.error;

import org.jspecify.annotations.NullMarked;
