// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.spring.boot.autoconfigure;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The {@code tm1.servers.<name>.*} properties: several TM1 servers, each by a name of
 * your choosing, with the same properties as {@link Tm1Properties tm1.*}.
 *
 * {@snippet file = examples/application-servers.yml}
 *
 * <p>
 * Each name gets its own beans (see {@link Tm1AutoConfiguration}), qualified by the name.
 * {@code tm1.*} is the server named {@code default}: {@code tm1.servers.default.*} is the
 * same server, and setting both is an error. The default server's beans are the ones a
 * plain {@code Tm1Client} injection gets.
 *
 * <p>
 * The starter binds these itself, server by server, when it creates their beans; there's
 * no bean of this type.
 *
 * @param servers each server's properties, by name
 */
@ConfigurationProperties("tm1")
public record Tm1ServersProperties(@DefaultValue Map<String, Tm1Properties> servers) {

}
