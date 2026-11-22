/**
 * The services: one per REST resource family, in a subpackage per area (plan §6).
 *
 * <p>
 * Every service follows the same conventions, which {@code ArchitectureTest} checks:
 * <ul>
 * <li>it's a final class with one constructor, taking the {@code Tm1Session} it sends
 * requests through, and holds no other state, so creating one is free;</li>
 * <li>each public method is one REST call, returning a {@code Mono}, or a {@code Flux}
 * for a collection, which streams; nothing blocks;</li>
 * <li>each public method declares the endpoints it implements with {@link Tm1Endpoint},
 * which the coverage gate counts;</li>
 * <li>reads take an {@code ODataQuery}, so the caller chooses what's selected and
 * expanded; writes take the generated patches; an update can send an ETag
 * ({@code If-Match});</li>
 * <li>operations come from the generated {@code Tm1Operations}, so a server that lacks
 * one fails before anything is sent.</li>
 * </ul>
 */
@NullMarked
package net.ajmyers.tm1.client.service;

import org.jspecify.annotations.NullMarked;
