/**
 * How the client authenticates to TM1:
 * {@link io.skipcheck.tm1.client.auth.Tm1Authenticator} and its implementations for TM1's
 * own security (Basic) and Cognos Access Manager ({@code CAMNamespace},
 * {@code CAMPassport}).
 *
 * <p>
 * Everything here is non-null unless marked {@link org.jspecify.annotations.Nullable}.
 */
@NullMarked
package io.skipcheck.tm1.client.auth;

import org.jspecify.annotations.NullMarked;
