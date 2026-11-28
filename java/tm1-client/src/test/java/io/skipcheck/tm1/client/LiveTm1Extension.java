// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client;

import java.util.Optional;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.testcontainers.DockerClientFactory;

import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.RequiresTm1Engine;
import io.skipcheck.tm1.testcontainers.Tm1EngineContainer;
import io.skipcheck.tm1.testcontainers.Tm1EngineSource;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import io.skipcheck.tm1.testcontainers.Tm1Source;

/**
 * The extension behind {@link LiveTm1}: a test runs against {@code TM1_URL} when it's
 * set, else against the shared TM1 11 container or TM1 v12 engine container; it skips
 * with none.
 */
final class LiveTm1Extension implements ExecutionCondition, ParameterResolver {

	@Override
	public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
		Optional<LiveServer> running = LiveServer.running();
		if (running.isPresent()) {
			return ConditionEvaluationResult.enabled("TM1 at " + running.get());
		}
		if (!DockerClientFactory.instance().isDockerAvailable()) {
			return ConditionEvaluationResult.disabled(
					"Docker isn't available, so there's no TM1 to test against. Or set TM1_URL for a server that's already running.");
		}
		// Throws if a source is set but invalid: a wrong setting should fail, not skip
		Optional<Tm1Source> source = Tm1Source.discover();
		if (source.isPresent()) {
			return ConditionEvaluationResult.enabled("TM1 from " + source.get().describe());
		}
		Optional<Tm1EngineSource> engine = Tm1EngineSource.discover();
		if (engine.isPresent()) {
			return ConditionEvaluationResult.enabled("TM1 v12 from " + engine.get().describe());
		}
		return ConditionEvaluationResult.disabled(RequiresTm1.NO_SOURCE_MESSAGE + " "
				+ RequiresTm1Engine.NO_SOURCE_MESSAGE + " Or set TM1_URL for a server that's already running.");
	}

	@Override
	public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
		return parameter.getParameter().getType() == LiveServer.class;
	}

	@Override
	public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
		return LiveServer.running()
			.orElseGet(() -> Tm1Source.discover().isPresent() ? LiveServer.of(Tm1ServerContainer.shared())
					: LiveServer.of(Tm1EngineContainer.shared()));
	}

}
