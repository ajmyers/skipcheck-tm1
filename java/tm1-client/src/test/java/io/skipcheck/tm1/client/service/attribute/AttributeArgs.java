package io.skipcheck.tm1.client.service.attribute;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.skipcheck.tm1.client.conformance.ConformanceCodec;
import tools.jackson.databind.JsonNode;

/**
 * {@link AttributeOwner}s and {@link AttributeSet}s made by their factories, by name, so
 * a conformance fixture records them as the factory call that made them: {@code {"$type":
 * "AttributeOwner", "factory": "view", "args": [...]}}.
 */
final class AttributeArgs {

	private static final Map<Object, JsonNode> MADE = Collections.synchronizedMap(new IdentityHashMap<>());

	static {
		ConformanceCodec.register(AttributeOwner.class, AttributeArgs::encoded);
		ConformanceCodec.register(AttributeSet.class, AttributeArgs::encoded);
	}

	private AttributeArgs() {
	}

	/** {@code AttributeOwner.<factory>(args)}. */
	static AttributeOwner owner(String factory, @Nullable Object... args) {
		return make(AttributeOwner.class, factory, args);
	}

	/** {@code AttributeSet.<factory>(args)}. */
	static AttributeSet set(String factory, @Nullable Object... args) {
		return make(AttributeSet.class, factory, args);
	}

	private static <T> T make(Class<T> type, String factory, @Nullable Object[] args) {
		Method m = Arrays.stream(type.getMethods())
			.filter(x -> Modifier.isStatic(x.getModifiers()) && x.getName().equals(factory)
					&& x.getParameterCount() == args.length)
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("No factory " + type.getSimpleName() + "." + factory));
		try {
			T made = type.cast(m.invoke(null, args));
			MADE.put(made, ConformanceCodec.factory(type.getSimpleName(), factory, args));
			return made;
		}
		catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static JsonNode encoded(Object value) {
		JsonNode node = MADE.get(value);
		if (node == null) {
			throw new IllegalArgumentException(value + " wasn't made through AttributeArgs, so it can't be recorded");
		}
		return node;
	}

}
