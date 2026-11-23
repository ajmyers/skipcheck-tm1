package examples;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.Git;
import io.skipcheck.tm1.client.service.git.GitCommitDetails;
import io.skipcheck.tm1.client.service.git.GitCredentials;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link io.skipcheck.tm1.client.service.git.GitService}, quoted in its
 * Javadoc.
 */
final class GitServiceExamples {

	Mono<Git> init(Tm1Client tm1, String token) {
		// @start region = init
		// The remote's branch must already have a commit: TM1 can't make the first one
		GitCredentials credentials = GitCredentials.password("tm1-bot", token);
		return tm1.git().init("https://git.example.com/finance/model.git", "dev", credentials, false, null);
		// @end
	}

	Mono<Void> push(Tm1Client tm1, GitCredentials credentials) {
		// @start region = push
		// Plan the commit, look at the files it would push, then push them
		GitCommitDetails commit = new GitCommitDetails("Add the Region dimension", "Ada Analyst", "ada@example.com");
		return tm1.git()
			.push("main", null, commit, credentials, false)
			.flatMap(plan -> plan.sourceFiles() == null || plan.sourceFiles().isEmpty() ? Mono.empty()
					: tm1.git().execute(plan));
		// @end
	}

	Mono<Void> pull(Tm1Client tm1, GitCredentials credentials, String newest) {
		// @start region = pull
		// TM1 11.8 plans the commit it had on the first pull after the remote moves on:
		// pull again until the plan is for the commit wanted, then carry it out
		return tm1.git()
			.pull("main", credentials, null, false)
			.filter(plan -> plan.commit() != null && newest.startsWith(plan.commit().id()))
			.repeatWhenEmpty(2, attempts -> attempts)
			.flatMap(plan -> tm1.git().execute(plan));
		// @end
	}

	private GitServiceExamples() {
	}

}
