// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;

import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;

class Tm1RequestTest {

	@Test
	void describesAGet() {
		Tm1Request<JsonNode> get = Tm1Request.get(ODataPath.of("Cubes").key("Sales"), ODataQuery.none().select("Name"),
				JsonNode.class);
		assertThat(get.method()).isEqualTo(HttpMethod.GET);
		assertThat(get.relativeUri()).isEqualTo("Cubes('Sales')?$select=Name");
		assertThat(get.response()).isEqualTo(Tm1Request.Response.SINGLE);
		assertThat(get.isIdempotent()).isTrue();
		assertThat(get).hasToString("GET Cubes('Sales')?$select=Name");
	}

	@Test
	void givesActionsAJsonBodyEvenWithoutParameters() {
		Tm1Request<Void> reserve = Tm1Request.post(ODataPath.of("Cubes").key("Sales").action("tm1.Reserve"), null,
				Void.class);
		assertThat(reserve.body()).isEqualTo(Map.of());
		assertThat(reserve.response()).isEqualTo(Tm1Request.Response.NONE);
		assertThat(reserve.isIdempotent()).isFalse();
	}

	@Test
	void describesOtherKinds() {
		assertThat(Tm1Request.list(ODataPath.of("Cubes"), ODataQuery.none(), JsonNode.class).response())
			.isEqualTo(Tm1Request.Response.COLLECTION);
		assertThat(Tm1Request.text(ODataPath.of("Dimensions").count()).response()).isEqualTo(Tm1Request.Response.TEXT);
		assertThat(Tm1Request.patch(ODataPath.of("Cubes").key("x"), Map.of("Rules", "")).isIdempotent()).isFalse();
		assertThat(Tm1Request.delete(ODataPath.of("Cubes").key("x")).isIdempotent()).isTrue();
	}

	@Test
	void isImmutable() {
		Tm1Request<Void> delete = Tm1Request.delete(ODataPath.of("Cubes").key("x"));
		Tm1Request<Void> guarded = delete.ifMatch("W/\"1\"").withQuery(ODataQuery.none().option("!sandbox", "s"));
		assertThat(delete.headers()).isEmpty();
		assertThat(guarded.headers()).containsEntry("If-Match", "W/\"1\"");
		assertThat(guarded.relativeUri()).isEqualTo("Cubes('x')?!sandbox=s");
	}

	@Test
	void carriesItsOwnTimeoutThroughCopies() {
		Tm1Request<Void> run = Tm1Request.post(ODataPath.of("Processes").key("p").action("tm1.ExecuteWithReturn"), null,
				Void.class);
		assertThat(run.timeout()).isEmpty();
		Tm1Request<Void> slow = run.withTimeout(Duration.ofHours(2)).withHeader("Prefer", "respond-async");
		assertThat(slow.timeout()).hasValue(Duration.ofHours(2));
		assertThat(run.timeout()).isEmpty();
		assertThatIllegalArgumentException().isThrownBy(() -> run.withTimeout(Duration.ZERO));
	}

	@Test
	void postsAndStreamsACollection() {
		Tm1Request<String> request = Tm1Request.postList(ODataPath.of("Cubes").key("c").action("tm1.CheckRules"),
				Map.of(), String.class);
		assertThat(request.method()).isEqualTo(HttpMethod.POST);
		assertThat(request.response()).isEqualTo(Tm1Request.Response.COLLECTION);
		assertThat(request.isIdempotent()).isFalse();
	}

	@Test
	void describesRawBytesBothWays() {
		Tm1Request<DataBuffer> download = Tm1Request
			.bytes(ODataPath.of("Contents").key("Blobs").nav("Contents").key("a.txt"));
		assertThat(download.response()).isEqualTo(Tm1Request.Response.BYTES);
		assertThat(download.method()).isEqualTo(HttpMethod.GET);
		Flux<DataBuffer> content = Flux.empty();
		Tm1Request<Void> upload = Tm1Request.put(ODataPath.of("Contents").key("Blobs"), content);
		assertThat(upload.method()).isEqualTo(HttpMethod.PUT);
		assertThat(upload.isIdempotent()).isTrue();
		assertThat(upload.body()).isEqualTo(new Tm1Request.Bytes(content));
		assertThat(upload.response()).isEqualTo(Tm1Request.Response.NONE);
	}

}
