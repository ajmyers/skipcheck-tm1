// SPDX-License-Identifier: Apache-2.0

/**
 * Probes a running TM1 for what its {@code $metadata} doesn't declare, such as properties
 * it sends only when they're asked for by name. The results go into a file beside the
 * {@code $metadata} snapshot, one per schema version, so a new TM1 version's differences
 * show as a diff. A build tool: never published, and nothing at run time depends on it.
 */
@NullMarked
package io.skipcheck.tm1.probe;

import org.jspecify.annotations.NullMarked;
