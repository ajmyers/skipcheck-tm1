# syntax=docker/dockerfile:1.7
#
# tm1-server: a runtime for IBM Planning Analytics / TM1 Server on Linux x86-64.
#
# A thin runtime shell: OS libraries + an entrypoint. It contains NO IBM software.
# You mount your own:
#   /opt/ibm/cognos/tm1_64   licensed TM1 Server install (bin64/tm1s.exe, ...)
#   /tm1/config              tm1s.cfg + tm1s-log.properties (defaults created if absent)
#   /tm1/data                database directory
#   /tm1/logs                log directory
# See README.md.

FROM --platform=linux/amd64 ubuntu:24.04

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      libcurl4t64 ca-certificates tini procps curl tzdata locales unzip python3 \
 && sed -i 's/^# *en_US.UTF-8/en_US.UTF-8/' /etc/locale.gen && locale-gen \
 && rm -rf /var/lib/apt/lists/*

ENV LANG=en_US.UTF-8 \
    TM1_HOME=/opt/ibm/cognos/tm1_64 \
    TM1_CONFIG=/tm1/config \
    TM1_DATA=/tm1/data \
    TM1_LOGS=/tm1/logs \
    TM1_BUNDLE_DIR=/ibm \
    LD_LIBRARY_PATH=/opt/ibm/cognos/tm1_64/bin64

COPY docker/entrypoint.sh /usr/local/bin/tm1-entrypoint
COPY docker/install-tm1.sh /usr/local/bin/tm1-install
COPY docker/unpack-tm1.py /usr/local/lib/tm1-server/unpack-tm1.py
COPY docker/healthcheck.sh /usr/local/bin/tm1-healthcheck
COPY docker/tm1s-log.properties docker/tm1s.cfg.default /usr/local/lib/tm1-server/
RUN chmod 755 /usr/local/bin/tm1-*

VOLUME ["/opt/ibm/cognos/tm1_64", "/tm1/config", "/tm1/data", "/tm1/logs"]
# 12345 = TM1 server (legacy API), 8010 = TM1 REST API, 5895/5898 = Admin server REST http/https
EXPOSE 12345 8010 5895 5898 5495 5498

# TM1 performs a clean save-and-shutdown on SIGINT (that's what IBM's shutdown_tm1s.sh sends)
STOPSIGNAL SIGINT
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=30 CMD ["/usr/local/bin/tm1-healthcheck"]

ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/tm1-entrypoint"]
CMD ["start"]
