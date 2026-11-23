// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An endpoint of the TM1 REST API that a service method implements, named as the coverage
 * gate names it: {@code EntitySet Dimensions}, {@code Navigation
 * Dimension/Hierarchies}, {@code Action Dimension/SaveAs},
 * {@code ActionImport ExecuteMDX}. For an operation, {@code Tm1Operation.endpoint()}
 * gives the name.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(Tm1Endpoint.List.class)
public @interface Tm1Endpoint {

	/**
	 * The endpoint's name, as the coverage gate writes it.
	 * @return the name
	 */
	String value();

	/** The container for a method implementing more than one endpoint. */
	@Documented
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.METHOD)
	@interface List {

		/**
		 * The endpoints.
		 * @return the endpoints
		 */
		Tm1Endpoint[] value();

	}

}
