// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.version;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import io.skipcheck.tm1.client.odata.ODataPath;

/**
 * TM1 12 (Planning Analytics Engine) dialect: each database lives at
 * {@code /<instance>/api/v1/Databases('<db>')/}, and sessions close at the instance level
 * ({@code /<instance>/api/v1/ActiveSession/tm1s.Close}).
 */
final class V12Dialect implements Tm1Dialect {

	static final String DEFAULT_INSTANCE = "tm1";

	private static final Pattern INSTANCE_SEGMENT = Pattern.compile("[A-Za-z0-9._~-]+");

	private static final Pattern DATABASE_ROOT_SUFFIX = Pattern.compile("/api/v1/Databases\\([^/]+\\)$");

	private static final Pattern PERCENT_HEX = Pattern.compile("%[0-9a-fA-F]{2}");

	private final String instance;

	private final String database;

	private final String instancePath;

	private final String databaseSegment;

	V12Dialect(String instance, String database) {
		Objects.requireNonNull(instance, "instance");
		Objects.requireNonNull(database, "database");
		if (instance.isBlank()) {
			throw new IllegalArgumentException("instance must not be blank");
		}
		if (instance.equals(".") || instance.equals("..") || !INSTANCE_SEGMENT.matcher(instance).matches()) {
			throw new IllegalArgumentException("A TM1 12 instance name must be a single URL path segment: " + instance);
		}
		if (database.isBlank()) {
			throw new IllegalArgumentException("database must not be blank");
		}
		this.instance = instance;
		this.database = database;
		this.instancePath = "/" + instance + "/api/v1";
		this.databaseSegment = "/" + ODataPath.of("Databases").key(database);
	}

	@Override
	public String name() {
		return "TM1 12";
	}

	@Override
	public String serviceRoot(String address) {
		URI uri = parseHttpUri(address, "address",
				"A TM1 12 address is the gateway's scheme, host and HTTP port, such as http://tm1:4444: " + address);
		String path = stripTrailingSlashes(uri.getRawPath());
		String fullSuffix = instancePath + databaseSegment;
		String normalizedPath = uppercasePercentHex(path);
		if (normalizedPath.endsWith(fullSuffix)) {
			path = path.substring(0, path.length() - fullSuffix.length()) + fullSuffix;
		}
		else if (DATABASE_ROOT_SUFFIX.matcher(path).find()) {
			throw new IllegalArgumentException("The address already names another TM1 12 database: " + address);
		}
		else if (path.endsWith(instancePath)) {
			path = path + databaseSegment;
		}
		else {
			path = path + fullSuffix;
		}
		return uri.getScheme() + "://" + uri.getRawAuthority() + path + "/";
	}

	@Override
	public String closeUrl(String serviceRoot) {
		URI uri = parseHttpUri(serviceRoot, "serviceRoot", "Not a TM1 12 service root: " + serviceRoot);
		String path = stripTrailingSlashes(uri.getRawPath());
		int lastSlash = path.lastIndexOf('/');
		if (lastSlash <= 0) {
			throw new IllegalArgumentException(
					"A TM1 12 database service root must include its instance API path: " + serviceRoot);
		}
		String instanceRoot = path.substring(0, lastSlash);
		String lastSegment = path.substring(lastSlash + 1);
		if (!instanceRoot.endsWith("/api/v1") || !lastSegment.startsWith("Databases(") || !lastSegment.endsWith(")")) {
			throw new IllegalArgumentException(
					"A TM1 12 database service root must end with /api/v1/Databases('<db>')/: " + serviceRoot);
		}
		return uri.getScheme() + "://" + uri.getRawAuthority() + instanceRoot + "/ActiveSession/tm1s.Close";
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof V12Dialect that && instance.equals(that.instance) && database.equals(that.database);
	}

	@Override
	public int hashCode() {
		return Objects.hash(instance, database);
	}

	@Override
	public String toString() {
		return "Tm1Dialect[" + name() + ", " + instance + "/" + database + "]";
	}

	static String stripTrailingSlashes(@Nullable String rawPath) {
		String path = rawPath == null ? "" : rawPath;
		while (path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		return path;
	}

	static URI parseAbsoluteUri(String raw, String paramName, String message) {
		String trimmed = Objects.requireNonNull(raw, paramName).strip();
		if (trimmed.isEmpty() || trimmed.contains("?") || trimmed.contains("#")) {
			throw new IllegalArgumentException(message);
		}
		URI uri = URI.create(trimmed);
		if (!uri.isAbsolute() || uri.getRawAuthority() == null || uri.getHost() == null || uri.getHost().isEmpty()) {
			throw new IllegalArgumentException(message);
		}
		return uri;
	}

	private static URI parseHttpUri(String raw, String paramName, String message) {
		URI uri = parseAbsoluteUri(raw, paramName, message);
		String scheme = uri.getScheme();
		boolean http = scheme != null
				&& ("http".equals(scheme.toLowerCase(Locale.ROOT)) || "https".equals(scheme.toLowerCase(Locale.ROOT)));
		if (!http) {
			throw new IllegalArgumentException(message);
		}
		return uri;
	}

	private static String uppercasePercentHex(String path) {
		Matcher m = PERCENT_HEX.matcher(path);
		if (!m.find()) {
			return path;
		}
		StringBuilder sb = new StringBuilder(path.length());
		int last = 0;
		do {
			sb.append(path, last, m.start()).append(m.group().toUpperCase(Locale.ROOT));
			last = m.end();
		}
		while (m.find());
		sb.append(path, last, path.length());
		return sb.toString();
	}

}
