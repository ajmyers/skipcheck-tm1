# syntax=docker/dockerfile:1.7
#
# pa-setup: the setup tool for a whole Planning Analytics environment. It reads
# pa-environment.yaml, generates a compose project and runs it through the host's
# Docker daemon (the socket is mounted). Contains no IBM software. setup.sh and
# setup.ps1 run it; see README.md.
#
# Build context: docker/ (it ships ../paw/paw-local.sh), e.g.
#   docker build -f docker/pa-setup/Dockerfile -t pa-setup:local docker

FROM docker:cli

LABEL org.opencontainers.image.authors="Andrew Myers (https://ajmyers.net)" \
      org.opencontainers.image.vendor="Andrew Myers" \
      org.opencontainers.image.description="Sets up a local IBM Planning Analytics environment (TM1, TM1 Web, PAW, Cognos) in Docker from one file. Contains no IBM software. Not affiliated with IBM." \
      org.opencontainers.image.documentation="https://github.com/ajmyers/tm1-pa-developer-tools/blob/master/docker/pa-setup/README.md"

RUN apk add --no-cache python3 py3-yaml bash coreutils unzip

COPY pa-setup/pa_setup.py /usr/local/lib/pa-setup/pa_setup.py
COPY pa-setup/pa-environment.schema.json /usr/local/lib/pa-setup/
COPY paw/paw-local.sh /usr/local/lib/pa-setup/paw-local.sh
RUN chmod 755 /usr/local/lib/pa-setup/*.py /usr/local/lib/pa-setup/*.sh

WORKDIR /work
ENTRYPOINT ["python3", "/usr/local/lib/pa-setup/pa_setup.py"]
CMD ["up"]
