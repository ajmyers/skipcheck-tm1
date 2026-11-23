// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.version;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The TM1 schema version ({@code Core.SchemaVersion}) that added a type, property, enum
 * member or operation, from its {@code Core.Revisions} in {@code $metadata}. The
 * generated model carries it; a server older than this doesn't have the element.
 *
 * <p>
 * It's information, not a check: a request checks what it needs with a
 * {@link Tm1Capability}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT })
public @interface Since {

	/**
	 * The schema version, such as {@code 11.8.20}.
	 * @return the version
	 */
	String value();

}
