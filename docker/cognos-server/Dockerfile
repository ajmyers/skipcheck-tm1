# syntax=docker/dockerfile:1.7
# SPDX-License-Identifier: Apache-2.0

#
# cognos-server: a runtime for IBM Cognos Analytics 12 on Linux x86-64.
#
# A thin runtime shell: OS libraries, PostgreSQL for the content store, and an
# entrypoint. It contains NO IBM software. You bring your own licensed Cognos
# Analytics repository zip (casrv-<version>-linuxi38664h.zip), installed with the
# `install` command or on first start:
#   /ibm                         a folder holding the zip
#   /opt/ibm/cognos/analytics    the install (mount a volume to keep it)
#   /cognos/postgres             the embedded content store (mount a volume to keep it)
# See README.md.

FROM --platform=linux/amd64 ubuntu:24.04

LABEL org.opencontainers.image.authors="Andrew Myers (https://ajmyers.net)" \
      org.opencontainers.image.url="https://skipcheck.io" \
      org.opencontainers.image.vendor="Andrew Myers" \
      org.opencontainers.image.description="A local IBM Cognos Analytics runtime for development and tests. Contains no IBM software. Not affiliated with IBM." \
      org.opencontainers.image.documentation="https://github.com/ajmyers/tm1-pa-developer-tools/blob/master/docker/cognos-server/README.md"

ARG POSTGRESQL_JDBC_VERSION=42.7.4

# The postgresql package would create a default cluster; the entrypoint makes its own.
RUN mkdir -p /etc/postgresql-common/createcluster.d \
 && echo 'create_main_cluster = false' > /etc/postgresql-common/createcluster.d/no-main.conf \
 && apt-get update \
 && echo 'slapd slapd/no_configuration boolean true' | debconf-set-selections \
 && apt-get install -y --no-install-recommends \
      ca-certificates tini procps curl tzdata locales python3 \
      fontconfig libfreetype6 libnsl2 libnss3 postgresql slapd ldap-utils \
 && sed -i 's/^# *en_US.UTF-8/en_US.UTF-8/' /etc/locale.gen && locale-gen \
 && rm -rf /var/lib/apt/lists/*

# libnss3: Cognos's LDAP namespace provider (libCAM_AAA_AuthLDAP.so) links against
# NSS/NSPR, and fails with CAM-AAA-0022 without them.
# Cognos ships no PostgreSQL JDBC driver. It's BSD-licensed, so the image carries it.
RUN mkdir -p /usr/local/lib/cognos-server/drivers \
 && curl -fsSL -o /usr/local/lib/cognos-server/drivers/postgresql-${POSTGRESQL_JDBC_VERSION}.jar \
      https://repo1.maven.org/maven2/org/postgresql/postgresql/${POSTGRESQL_JDBC_VERSION}/postgresql-${POSTGRESQL_JDBC_VERSION}.jar

ENV LANG=en_US.UTF-8 \
    COGNOS_HOME=/opt/ibm/cognos/analytics \
    COGNOS_ZIP_DIR=/ibm \
    COGNOS_PG_DATA=/cognos/postgres

COPY docker/entrypoint.sh /usr/local/bin/cognos-entrypoint
COPY docker/install-cognos.sh /usr/local/bin/cognos-install
COPY docker/embedded-postgres.sh /usr/local/bin/cognos-postgres
COPY docker/embedded-ldap.sh /usr/local/bin/cognos-ldap
COPY docker/healthcheck.sh /usr/local/bin/cognos-healthcheck
COPY docker/unpack-cognos.py docker/configure-cognos.py docker/users.py docker/keycloak-setup.py docker/bi-interop.py docker/banner.txt /usr/local/lib/cognos-server/
RUN chmod 755 /usr/local/bin/cognos-*

# Deliberately no VOLUME: unmounted paths stay in the container and go away with it.
RUN mkdir -p /opt/ibm/cognos/analytics /cognos/postgres
# 9300: the Cognos dispatcher (http://localhost:9300/bi/)
EXPOSE 9300

# Cognos needs minutes to start, and a minute or two to stop: give it
# docker stop --time 180 (docker run --stop-timeout 180; compose: stop_grace_period: 3m).
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=15s --timeout=10s --start-period=600s --retries=20 CMD ["/usr/local/bin/cognos-healthcheck"]

ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/cognos-entrypoint"]
CMD ["start"]
