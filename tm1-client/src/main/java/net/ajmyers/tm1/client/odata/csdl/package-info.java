/**
 * A read-only model of OData CSDL XML ({@code $metadata}), and a parser for it.
 *
 * <p>
 * The client uses it to read a server's {@code $metadata}: to check which operations the
 * connected server defines, to generate the model, and to measure endpoint coverage. It
 * covers the parts of CSDL 4.0/4.01 that TM1 uses: schemas, entity, complex and enum
 * types, navigation properties, actions and functions, the entity container, and
 * annotations, including {@code Core.Revisions}. Unknown elements and annotation terms
 * are kept or ignored, never an error, as the CSDL specification requires of clients.
 *
 * <p>
 * Names are kept as written in the document. A type or operation can be referred to by
 * its namespace ({@code ibm.tm1.api.v1.Cube}) or by its schema's alias
 * ({@code tm1.Cube}); use
 * {@link net.ajmyers.tm1.client.odata.csdl.CsdlDocument#qualify(String)} to compare them.
 */
package net.ajmyers.tm1.client.odata.csdl;
