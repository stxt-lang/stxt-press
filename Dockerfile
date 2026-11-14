# The image of stxt-press: the folder server/ as it is, on a Java 17 JRE, with its server.stxt listening on every
# address and without Save. Built after mvn package, so that server/stxt-press.jar is the jar of the code:
#
#   mvn package -Dnpm.install=ci
#   docker build -t stxt-press .
#
# The build and the tests need ../stxt-pdf beside the project, out of the context of Docker: the jar is built before,
# not in the image. .dockerignore lets in only server/.
FROM eclipse-temurin:17-jre

ARG VERSION=dev
LABEL org.opencontainers.image.title="stxt-press" \
      org.opencontainers.image.description="PDF and SVG from STXT layout documents, with their Slots filled with JSON data" \
      org.opencontainers.image.source="https://github.com/stxt-lang/stxt-press" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.version="${VERSION}"

ENV JAVA_TOOL_OPTIONS="-Djava.awt.headless=true" \
    STXT_PRESS_CONFIG=/opt/stxt-press/server.stxt

# A user without privileges, with a home for the caches of the JVM. The files of the image are root's, read-only for it.
RUN useradd --system --uid 10001 --user-group --create-home --home-dir /home/stxt-press --shell /usr/sbin/nologin \
        stxt-press

WORKDIR /opt/stxt-press
COPY server/ ./

# The server.stxt of the folder is for the machine it runs on: 127.0.0.1, and Save on for the editor of that machine.
# In a container it listens on every address, and a server open to others without a token does not write its files.
# Each grep fails the build if its line was not changed. DockerfileTest applies the -e of the second sed to
# server.stxt. The first one leaves its lines with LF, as a checkout with core.autocrlf (Windows) has them with CRLF.
RUN sed -i 's/\r$//' server.stxt \
    && sed -i -e 's/^\tHost: 127\.0\.0\.1$/\tHost: 0.0.0.0/' \
           -e 's/^\t\tSave: true$/\t\tSave: false/' server.stxt \
    && grep -qP '^\tHost: 0\.0\.0\.0$' server.stxt \
    && grep -qP '^\t\tSave: false$' server.stxt

USER stxt-press
EXPOSE 8080

# /health answers 503 while the server warms up: the start period covers it.
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD curl -fsS -o /dev/null http://127.0.0.1:8080/health || exit 1

# The exec form, so that java is the process that gets the SIGTERM of docker stop and stops in order.
ENTRYPOINT ["java", "-jar", "stxt-press.jar", "start"]
