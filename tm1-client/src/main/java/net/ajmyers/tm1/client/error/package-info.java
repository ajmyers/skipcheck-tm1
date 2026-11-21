/**
 * The client's exceptions. Everything the client signals is a
 * {@link net.ajmyers.tm1.client.error.Tm1Exception}, unchecked, delivered through
 * {@code Mono.error}/{@code Flux.error}, never thrown when a request is assembled. Each
 * says whether retrying could help ({@code isRetryable()}) and whether the request may
 * already have taken effect ({@code mayHaveApplied()}).
 */
package net.ajmyers.tm1.client.error;
