package net.ajmyers.tm1.client.version;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import net.ajmyers.tm1.client.error.Tm1UnsupportedOperationException;
import net.ajmyers.tm1.client.odata.csdl.CsdlDocument;
import net.ajmyers.tm1.client.odata.csdl.CsdlEntityContainer;
import net.ajmyers.tm1.client.odata.csdl.CsdlNavigationProperty;
import net.ajmyers.tm1.client.odata.csdl.CsdlProperty;
import net.ajmyers.tm1.client.odata.csdl.CsdlStructuredType;

/**
 * What a connected TM1 server supports, read from its own {@code $metadata}: the way
 * OData intends clients to detect features, with no version tables (plan §9, mechanism
 * 1). It works for any TM1, including versions the client has never seen.
 *
 * <pre class="code">
 * session.capabilities()
 *     .map(c -&gt; c.supports(Tm1Capability.boundOperation("tm1.Cube", "tm1.Reserve")));
 * </pre>
 */
public final class Tm1Capabilities {

	private final CsdlDocument metadata;

	private final @Nullable Tm1Version productVersion;

	private Tm1Capabilities(CsdlDocument metadata, @Nullable Tm1Version productVersion) {
		this.metadata = metadata;
		this.productVersion = productVersion;
	}

	/**
	 * The capabilities a {@code $metadata} document describes.
	 * @param metadata the server's {@code $metadata}, parsed
	 * @param productVersion the server's version ({@code Server.ProductVersion}), or
	 * {@code null} if not known
	 * @return the capabilities
	 */
	public static Tm1Capabilities of(CsdlDocument metadata, @Nullable Tm1Version productVersion) {
		return new Tm1Capabilities(Objects.requireNonNull(metadata, "metadata"), productVersion);
	}

	/**
	 * The REST API schema version the server reports in {@code Core.SchemaVersion}, such
	 * as {@code 11.8.35}. Not the product version: a different number.
	 * @return the schema version, or empty if the server doesn't report one
	 */
	public Optional<String> schemaVersion() {
		return metadata.schemaVersion();
	}

	/**
	 * The server's product version, such as {@code 11.8.03500.6}.
	 * @return the version, or empty if not known
	 */
	public Optional<Tm1Version> productVersion() {
		return Optional.ofNullable(productVersion);
	}

	/**
	 * The server's {@code $metadata}, for anything the checks here don't cover.
	 * @return the parsed document
	 */
	public CsdlDocument metadata() {
		return metadata;
	}

	/**
	 * Whether the server defines {@code capability}.
	 * @param capability what's needed
	 * @return {@code true} if the server's {@code $metadata} defines it
	 */
	public boolean supports(Tm1Capability capability) {
		Objects.requireNonNull(capability, "capability");
		String name = capability.name();
		return switch (capability.kind()) {
			case ENTITY_SET ->
				container().map(c -> c.entitySets().stream().anyMatch(s -> s.name().equals(name))).orElse(false);
			case SINGLETON ->
				container().map(c -> c.singletons().stream().anyMatch(s -> s.name().equals(name))).orElse(false);
			case UNBOUND_OPERATION ->
				container().map(c -> c.actionImports().stream().anyMatch(a -> a.name().equals(name))
						|| c.functionImports().stream().anyMatch(f -> f.name().equals(name)))
					.orElse(false);
			case BOUND_OPERATION -> hierarchy(capability).stream()
				.anyMatch(type -> metadata.qualifiedName(type)
					.map(t -> metadata.boundTo(t)
						.stream()
						.anyMatch(op -> op.name().equals(simple(name))
								&& metadata.qualify(name).endsWith("." + op.name())))
					.orElse(false));
			case PROPERTY -> hierarchy(capability).stream()
				.anyMatch(type -> type.properties().stream().map(CsdlProperty::name).anyMatch(name::equals)
						|| type.navigationProperties()
							.stream()
							.map(CsdlNavigationProperty::name)
							.anyMatch(name::equals));
		};
	}

	/**
	 * Checks the server defines {@code capability}.
	 * @param capability what's needed
	 * @param server the server, for the message, such as its service root
	 * @return empty if it's defined, otherwise the exception to fail with, saying what's
	 * missing and comparing schema versions when the version that added it is known
	 */
	public Optional<Tm1UnsupportedOperationException> check(Tm1Capability capability, String server) {
		if (supports(capability)) {
			return Optional.empty();
		}
		String missing = capability.describe();
		StringBuilder message = new StringBuilder(missing).append(" isn't defined by the TM1 server at ")
			.append(server);
		capability.addedInVersion().ifPresent(added -> message.append(": it was added in schema ").append(added));
		message.append(capability.addedInVersion().isPresent() ? ", and " : " (");
		message.append(schemaVersion().map(v -> "the server reports schema " + v)
			.orElse("the server reports no schema version"));
		productVersion().ifPresent(v -> message.append(", product version ").append(v));
		message.append(capability.addedInVersion().isPresent() ? "" : ")");
		return Optional.of(new Tm1UnsupportedOperationException(message.toString(), missing));
	}

	private Optional<CsdlEntityContainer> container() {
		return metadata.entityContainer();
	}

	private List<CsdlStructuredType> hierarchy(Tm1Capability capability) {
		return metadata.typeHierarchy(Objects.requireNonNull(capability.type()));
	}

	private static String simple(String qualified) {
		return qualified.substring(qualified.lastIndexOf('.') + 1);
	}

	@Override
	public String toString() {
		return "Tm1Capabilities[schema " + schemaVersion().orElse("unknown") + ", product "
				+ (productVersion == null ? "unknown" : productVersion) + "]";
	}

}
