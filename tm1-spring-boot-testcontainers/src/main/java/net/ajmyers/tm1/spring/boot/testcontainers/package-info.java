/**
 * Spring Boot {@code @ServiceConnection} support for {@code Tm1ServerContainer}: a Spring
 * test or a development-time application gets its TM1 connection from the container, with
 * no {@code tm1.*} properties.
 *
 * <pre class="code">
 * &#64;Bean
 * &#64;ServiceConnection
 * Tm1ServerContainer tm1() {
 *     return new Tm1ServerContainer(Tm1Source.discover().orElseThrow());
 * }
 * </pre>
 *
 * <p>
 * Everything here is non-null unless marked {@link org.jspecify.annotations.Nullable}.
 */
@NullMarked
package net.ajmyers.tm1.spring.boot.testcontainers;

import org.jspecify.annotations.NullMarked;
