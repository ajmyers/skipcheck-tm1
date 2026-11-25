# syntax=docker/dockerfile:1.7
# SPDX-License-Identifier: Apache-2.0

#
# tm1-engine: a runtime for IBM TM1 v12 (the TM1 engine of Planning Analytics 3.x,
# "TM1 v12 Standalone") on Linux x86-64.
#
# A thin runtime shell: OS libraries + an entrypoint. It contains NO IBM software and no
# MongoDB. You bring your own licensed TM1 v12 (tm1-v12.*-standalone-linux.tgz), and a
# MongoDB for it to keep its metadata in (TM1_DATASTORE_URL, e.g. a mongo:8.0 container).
#   /opt/ibm/tm1-engine   the TM1 install (bin/tm1sd.exe, ...), or a bundle at /ibm
# and optionally, to keep them outside the container:
#   /tm1/deployment       configuration, databases and logs
#   /tm1/credentials      the root and application credentials
# See README.md.

FROM --platform=linux/amd64 ubuntu:26.04

LABEL org.opencontainers.image.authors="Andrew Myers (https://ajmyers.net)" \
      org.opencontainers.image.url="https://skipcheck.io" \
      org.opencontainers.image.vendor="Andrew Myers" \
      org.opencontainers.image.description="A local IBM TM1 v12 engine (Planning Analytics 3.x) runtime for development and tests. Contains no IBM software. Not affiliated with IBM." \
      org.opencontainers.image.documentation="https://github.com/ajmyers/skipcheck-tm1/blob/master/docker/tm1-engine/README.md"

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      libcurl4t64 ca-certificates tini procps curl tzdata locales python3 \
 && sed -i 's/^# *en_US.UTF-8/en_US.UTF-8/' /etc/locale.gen && locale-gen \
 && rm -rf /var/lib/apt/lists/*

ENV LANG=en_US.UTF-8 \
    TM1_ENGINE_HOME=/opt/ibm/tm1-engine \
    TM1_ENGINE_DEPLOYMENT=/tm1/deployment \
    TM1_ENGINE_CREDENTIALS=/tm1/credentials \
    TM1_ENGINE_BUNDLE_DIR=/ibm

COPY docker/entrypoint.sh /usr/local/bin/tm1-engine-entrypoint
COPY docker/install-tm1-engine.sh /usr/local/bin/tm1-engine-install
COPY docker/engine.py /usr/local/bin/tm1-engine
COPY docker/healthcheck.sh /usr/local/bin/tm1-engine-healthcheck
COPY docker/unpack-tm1-engine.py docker/mongod-wrapper.sh docker/banner.txt /usr/local/lib/tm1-engine/
RUN chmod 755 /usr/local/bin/tm1-engine*

# Deliberately no VOLUME: unmounted folders live in the container and go away with it,
# which is what tests want (see tm1-server's Dockerfile).
RUN mkdir -p /tm1/deployment /tm1/credentials
# Fixed container port (map it to any host port with -p): 4444, TM1's gateway, for the
# REST API (/<instance>/api/v1/...), logins and the management API (/manage/v1/...)
EXPOSE 4444

# The entrypoint stops each database cleanly, then TM1, on SIGTERM. Give it time:
# docker stop -t 120, or stop_grace_period in compose.
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=10s --timeout=10s --start-period=120s --retries=30 CMD ["/usr/local/bin/tm1-engine-healthcheck"]

ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/tm1-engine-entrypoint"]
CMD ["start"]
