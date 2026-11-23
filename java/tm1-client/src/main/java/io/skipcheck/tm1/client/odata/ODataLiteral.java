package io.skipcheck.tm1.client.odata;

/**
 * A value that knows how to write itself as an OData literal, such as the generated enums
 * ({@code ibm.tm1.api.v1.ElementType'Numeric'}); {@link ODataLiterals#of} uses it.
 */
public interface ODataLiteral {

	/**
	 * The literal, exactly as it should appear before percent-encoding.
	 * @return the literal
	 */
	String odataLiteral();

}
