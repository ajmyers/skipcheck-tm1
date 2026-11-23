# syntax=docker/dockerfile:1.7
# SPDX-License-Identifier: Apache-2.0

#
# tm1-web: a runtime for IBM Planning Analytics Spreadsheet Services (TM1 Web) on
# Linux x86-64.
#
# A thin runtime shell: OS libraries + an entrypoint. It contains NO IBM software.
# TM1 Web brings its own Java and WebSphere Liberty; you supply the install (required):
#   /opt/ibm/cognos/tm1web   a TM1 Web install (wlp/, jre/, webapps/), or an IBM
#                            bundle at /ibm, installed on first start
# and optionally:
#   /tm1-web/config          your own tm1web_config.xml (the install's is used if absent)
#   /tm1-web/logs            TM1 Web and Liberty logs
# See README.md.

FROM --platform=linux/amd64 ubuntu:24.04

LABEL org.opencontainers.image.authors="Andrew Myers (https://ajmyers.net)" \
      org.opencontainers.image.url="https://skipcheck.io" \
      org.opencontainers.image.vendor="Andrew Myers" \
      org.opencontainers.image.description="A local IBM Planning Analytics Spreadsheet Services (TM1 Web) runtime for development and tests. Contains no IBM software. Not affiliated with IBM." \
      org.opencontainers.image.documentation="https://github.com/ajmyers/skipcheck-tm1/blob/master/docker/tm1-web/README.md"

# fontconfig + fonts: TM1 Web renders charts and exports (Aspose, POI) with Java2D,
# which needs at least one font on the system.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates tini procps curl tzdata locales unzip python3 \
      fontconfig libfreetype6 fonts-dejavu-core \
 && sed -i 's/^# *en_US.UTF-8/en_US.UTF-8/' /etc/locale.gen && locale-gen \
 && rm -rf /var/lib/apt/lists/*

ENV LANG=en_US.UTF-8 \
    TM1_WEB_HOME=/opt/ibm/cognos/tm1web \
    TM1_WEB_CONFIG=/tm1-web/config \
    TM1_WEB_LOGS=/tm1-web/logs \
    TM1_WEB_BUNDLE_DIR=/ibm

COPY docker/entrypoint.sh /usr/local/bin/tm1-web-entrypoint
COPY docker/install-tm1-web.sh /usr/local/bin/tm1-web-install
COPY docker/healthcheck.sh /usr/local/bin/tm1-web-healthcheck
COPY docker/unpack-tm1-web.py docker/configure-tm1-web.py docker/banner.txt /usr/local/lib/tm1-web/
RUN chmod 755 /usr/local/bin/tm1-web-*

# Deliberately no VOLUME (as in tm1-server): unmounted paths live in the container and
# go away with it, so a test's TM1 Web leaves nothing behind.
RUN mkdir -p /tm1-web/config /tm1-web/logs
# Fixed container port (map it to any host port with -p): 9510 TM1 Web (HTTP),
# serving /tm1web/ and /evalservice/
EXPOSE 9510

HEALTHCHECK --interval=10s --timeout=5s --start-period=120s --retries=30 CMD ["/usr/local/bin/tm1-web-healthcheck"]

# -g: signals go to the whole process group, so the log tail stops with Liberty.
# Liberty stops cleanly on SIGTERM (the default stop signal).
ENTRYPOINT ["/usr/bin/tini", "-g", "--", "/usr/local/bin/tm1-web-entrypoint"]
CMD ["start"]
