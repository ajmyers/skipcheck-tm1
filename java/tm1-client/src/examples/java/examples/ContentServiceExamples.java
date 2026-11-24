// SPDX-License-Identifier: Apache-2.0

package examples;

import java.nio.file.Path;

import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.CubeReference;
import io.skipcheck.tm1.client.model.CubeReferenceCreate;
import io.skipcheck.tm1.client.model.DocumentCreate;
import io.skipcheck.tm1.client.model.Entry;
import io.skipcheck.tm1.client.model.FolderCreate;
import io.skipcheck.tm1.client.model.LinkCreate;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.content.ContentPath;
import io.skipcheck.tm1.client.service.content.ContentScope;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link io.skipcheck.tm1.client.service.content.ContentService} and
 * {@link ContentPath}, quoted in their Javadoc.
 */
final class ContentServiceExamples {

	ContentPath path() {
		// @start region = path
		// Children by ID, which for most entries is the name plus a suffix for the kind
		ContentPath notes = ContentPath.applications().child("Planning").child("notes.txt.blob");
		// Contents('Applications')/Contents('Planning')/Contents('notes.txt.blob')
		// @end
		return notes;
	}

	Mono<Entry> application(Tm1Client tm1) {
		// @start region = application
		// An application: a folder with a link and a reference to a cube
		ContentPath planning = ContentPath.applications().child("Planning");
		return tm1.contents()
			.create(ContentPath.applications(), ContentScope.PUBLIC, FolderCreate.builder().name("Planning").build())
			.then(tm1.contents()
				.create(planning, ContentScope.PUBLIC,
						LinkCreate.builder().name("Guide").url("https://example.com/planning-guide").build()))
			.then(tm1.contents()
				.create(planning, ContentScope.PUBLIC,
						CubeReferenceCreate.builder()
							.name("Sales")
							.bindCube(ODataPath.of("Cubes").key("Sales"))
							.build()));
		// @end
	}

	Mono<Void> upload(Tm1Client tm1, Path file) {
		// @start region = upload
		// A document made in an application is a reference to one TM1 keeps in Blobs; the
		// reference, as create returns it, is where its content goes
		ContentPath planning = ContentPath.applications().child("Planning");
		return tm1.contents()
			.create(planning, ContentScope.PUBLIC, DocumentCreate.builder().name("notes.txt").build())
			.flatMap(reference -> tm1.contents()
				.upload(planning.child(reference),
						// read again if the session has to log in again
						DataBufferUtils.read(file, DefaultDataBufferFactory.sharedInstance, 8192)));
		// @end
	}

	Mono<Void> download(Tm1Client tm1, Path file) {
		// @start region = download
		// Streamed to the file, never held whole
		ContentPath notes = ContentPath.applications().child("Planning").child("notes.txt.blob");
		return DataBufferUtils.write(tm1.contents().content(notes), file);
		// @end
	}

	Flux<String> cubes(Tm1Client tm1) {
		// @start region = cubes
		// The cubes an application refers to, by a cast expand
		return tm1.contents()
			.contents(ContentPath.applications().child("Planning"), ContentScope.PUBLIC,
					ODataQuery.none().expand("tm1.CubeReference/Cube($select=Name)"))
			.ofType(CubeReference.class)
			.mapNotNull(reference -> reference.cube() == null ? null : reference.cube().name());
		// @end
	}

	private ContentServiceExamples() {
	}

}
