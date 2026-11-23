// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.git;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;

/**
 * A Git remote TM1 can push to and pull from: {@code git daemon} serving one bare
 * repository, {@code model.git}, with one commit on {@code main}. It shares the TM1
 * container's network, so TM1 reaches it at {@code git://localhost/model.git}.
 *
 * <p>
 * Not a {@code file://} repository inside the TM1 container: TM1 can't push or pull
 * through the local transport ("shallow fetch is not supported"), and can't make a
 * repository's first commit, so the remote is seeded with one.
 */
final class GitDaemon extends GenericContainer<GitDaemon> {

	/** Where TM1 finds the repository. */
	static final String URL = "git://localhost/model.git";

	@SuppressWarnings("resource") // closed by the caller
	GitDaemon(Tm1ServerContainer tm1) {
		super("alpine:3.21");
		withNetworkMode("container:" + tm1.getContainerId());
		withCommand("sh", "-c", String.join(" && ", "apk add -q --no-cache git git-daemon",
				"git init -q --bare -b main /srv/model.git", "git clone -q /srv/model.git /tmp/seed", "cd /tmp/seed",
				"echo 'A TM1 model' > README.md", "git add README.md",
				"git -c user.name=seed -c user.email=seed@example.com commit -qm Seed", "git push -q origin main",
				"exec git daemon --verbose --reuseaddr --base-path=/srv --export-all --enable=receive-pack /srv"));
		waitingFor(Wait.forLogMessage(".*Ready to rumble.*", 1));
	}

	/**
	 * The files on {@code main}, as Git sees them.
	 * @return the paths, one per line
	 * @throws Exception if Git can't be asked
	 */
	String filesOnMain() throws Exception {
		return execInContainer("git", "--git-dir=/srv/model.git", "ls-tree", "-r", "--name-only", "main").getStdout();
	}

	/**
	 * Commits, on the remote's {@code main}, a copy of a dimension TM1 pushed, under
	 * another name: a change made elsewhere for TM1 to pull.
	 * @param dimension the pushed dimension
	 * @param copy the copy's name
	 * @throws Exception if Git fails
	 */
	void commitCopyOf(String dimension, String copy) throws Exception {
		var result = execInContainer("sh", "-c", String.join(" && ", "cd /tmp/seed", "git pull -q origin main",
				"mkdir -p dimensions/" + copy + ".hierarchies",
				"sed 's/" + dimension + "/" + copy + "/g' dimensions/" + dimension + ".json > dimensions/" + copy
						+ ".json",
				"sed 's/" + dimension + "/" + copy + "/g' dimensions/" + dimension + ".hierarchies/" + dimension
						+ ".json > dimensions/" + copy + ".hierarchies/" + copy + ".json",
				"git add dimensions",
				"git -c user.name=elsewhere -c user.email=elsewhere@example.com commit -qm 'Copy " + dimension + "'",
				"git push -q origin main"));
		if (result.getExitCode() != 0) {
			throw new IllegalStateException(result.getStdout() + result.getStderr());
		}
	}

}
