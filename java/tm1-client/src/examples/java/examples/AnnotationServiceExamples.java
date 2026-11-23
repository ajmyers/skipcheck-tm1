package examples;

import java.util.LinkedHashMap;
import java.util.Map;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.Annotation;
import io.skipcheck.tm1.client.model.AnnotationPatch;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link io.skipcheck.tm1.client.service.annotation.AnnotationService},
 * quoted in its Javadoc.
 */
final class AnnotationServiceExamples {

	Mono<Annotation> annotate(Tm1Client tm1) {
		// @start region = annotate
		// A comment on one cell, as Planning Analytics Workspace makes it: the cell's
		// elements by dimension, in the cube's dimension order
		Map<String, String> cell = new LinkedHashMap<>();
		cell.put("Version", "Forecast");
		cell.put("Region", "North");
		cell.put("Measure", "Amount");
		return tm1.annotations().create("Sales", cell, "Includes the March promotion");
		// @end
	}

	Flux<String> read(Tm1Client tm1) {
		// @start region = read
		// GET Annotations lists none on TM1 11.8, so read them by cube
		return tm1.annotations()
			.cubeAnnotations("Sales", ODataQuery.none())
			.map(annotation -> annotation.creator() + ": " + annotation.text());
		// @end
	}

	Mono<Void> edit(Tm1Client tm1, String id) {
		// @start region = edit
		// TM1 keeps Text and the commentValue PAW shows apart, so change both
		String text = "Includes the March and April promotions";
		return tm1.annotations()
			.update(id, AnnotationPatch.builder().text(text).property("commentValue", text).build());
		// @end
	}

	private AnnotationServiceExamples() {
	}

}
