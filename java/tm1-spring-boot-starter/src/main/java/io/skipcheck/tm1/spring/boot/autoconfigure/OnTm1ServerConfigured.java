package io.skipcheck.tm1.spring.boot.autoconfigure;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Matches when {@code tm1.address} or {@code tm1.base-url} is set. */
final class OnTm1ServerConfigured extends SpringBootCondition {

	@Override
	public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
		for (String property : new String[] { "tm1.address", "tm1.base-url" }) {
			if (context.getEnvironment().containsProperty(property)) {
				return ConditionOutcome.match(property + " is set");
			}
		}
		return ConditionOutcome.noMatch("neither tm1.address nor tm1.base-url is set");
	}

}
