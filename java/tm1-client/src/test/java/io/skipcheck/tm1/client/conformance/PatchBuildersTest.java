// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.model.ChoreCreate;
import io.skipcheck.tm1.client.model.CubeCreate;
import io.skipcheck.tm1.client.model.EncryptionAtRestProviderSettingsPatch;
import io.skipcheck.tm1.client.model.NativeViewPatch;
import io.skipcheck.tm1.client.model.ServerSettingsPatch;
import io.skipcheck.tm1.client.model.VaultSettingsPatch;
import io.skipcheck.tm1.client.odata.ODataPath;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A scenario's {@code $patch} goes through the generated setters (#354): what a caller
 * can't build, a scenario can't send.
 */
class PatchBuildersTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static JsonNode json(String text) {
		return JSON.readTree(text);
	}

	@Test
	void buildsAWriteWithItsSettersAndBindsEachPathOnce() {
		Object built = PatchBuilders.build(CubeCreate.class,
				json("{\"Name\": \"c\", \"Rules\": null, \"Dimensions@odata.bind\": [\"Dimensions('%7DClients')\"]}"));
		assertThat(built).isEqualTo(CubeCreate.builder()
			.name("c")
			.rules(null)
			.bindDimensions(ODataPath.of("Dimensions").key("}Clients"))
			.build());
		assertThat(((CubeCreate) built).json()).containsEntry("Dimensions@odata.bind",
				List.of("Dimensions('%7DClients')"));
	}

	@Test
	void refusesAKeyNoSetterWrites() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> PatchBuilders.build(CubeCreate.class, json("{\"Name\": \"c\", \"Nope\": 1}")))
			.withMessage("CubeCreate has no setter for Nope");
	}

	@Test
	void buildsANestedValueWithItsOwnBuilderAndARootsBySubtype() {
		Object built = PatchBuilders.build(ServerSettingsPatch.class, json(
				"{\"EncryptionAtRest\": {\"@odata.type\": \"#ibm.tm1.api.v1.VaultSettings\", \"KeyRef\": \"k\"}}"));
		Object value = ((ServerSettingsPatch) built).json().get("EncryptionAtRest");
		assertThat(value).isInstanceOf(VaultSettingsPatch.class);
		assertThat(((EncryptionAtRestProviderSettingsPatch) value).json())
			.containsEntry("@odata.type", "#ibm.tm1.api.v1.VaultSettings")
			.containsEntry("KeyRef", "k");
	}

	@Test
	void setsAnOpenTypesUndeclaredPropertyByName() {
		Object built = PatchBuilders.build(EncryptionAtRestProviderSettingsPatch.class,
				json("{\"KeyRef\": \"k\", \"Extra\": \"x\"}"));
		assertThat(((EncryptionAtRestProviderSettingsPatch) built).json()).containsEntry("Extra", "x");
	}

	@Test
	void takesABindingsPathAsWrittenEncoded() {
		// A key's %2F is a slash in the key, not between segments
		Object built = PatchBuilders.build(CubeCreate.class,
				json("{\"Name\": \"c\", \"Dimensions@odata.bind\": [\"Dimensions('a%2Fb')\"]}"));
		assertThat(((CubeCreate) built).json()).containsEntry("Dimensions@odata.bind", List.of("Dimensions('a%2Fb')"));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> PatchBuilders.build(CubeCreate.class,
					json("{\"Name\": \"c\", \"Dimensions@odata.bind\": \"Dimensions('d')\"}")))
			.withMessageContaining("binds a list of paths");
	}

	@Test
	void refusesADeclaredKeyNoSetterWritesOnAnOpenType() {
		// NativeView is open, but declares Name, which its patch doesn't offer
		assertThatIllegalArgumentException()
			.isThrownBy(() -> PatchBuilders.build(NativeViewPatch.class,
					json("{\"@odata.type\": \"#ibm.tm1.api.v1.NativeView\", \"Name\": \"renamed\"}")))
			.withMessage("NativeViewPatch has no setter for Name");
	}

	@Test
	void refusesAValueThatDoesntReadBackAsWritten() {
		assertThatIllegalArgumentException().isThrownBy(
				() -> PatchBuilders.build(ChoreCreate.class, json("{\"Name\": \"c\", \"ExecutionMode\": \"Nope\"}")))
			.withMessageContaining("doesn't read back as written");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> PatchBuilders.build(CubeCreate.class,
					json("{\"@odata.type\": \"#ibm.tm1.api.v1.Cube\", \"Name\": \"c\"}")))
			.withMessageContaining("doesn't send @odata.type");
	}

	@Test
	void keepsADateTimesOffset() {
		Object built = PatchBuilders.build(ChoreCreate.class,
				json("{\"Name\": \"c\", \"StartTime\": \"2026-03-04T05:06:07.5+02:00\"}"));
		assertThat(built).isEqualTo(
				ChoreCreate.builder().name("c").startTime(OffsetDateTime.parse("2026-03-04T05:06:07.5+02:00")).build());
	}

}
