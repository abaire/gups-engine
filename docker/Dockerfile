# The gups server image (M6 design §7.3): a slim JRE with git for deploys (Q10), and the
# installed gups distribution. Build it after `./gradlew :cli:installDist`:
#   docker build -f docker/Dockerfile -t gups .
FROM eclipse-temurin:21-jre-noble

RUN apt-get update \
 && apt-get install -y --no-install-recommends git openssh-client ca-certificates curl jq \
 && rm -rf /var/lib/apt/lists/* \
 # The world volume is owned by whoever mounts it; let git work there anyway.
 && git config --system --add safe.directory /world

COPY cli/build/install/gups /opt/gups
ENV PATH=/opt/gups/bin:$PATH

# The course repository's working tree, the database and backups, and the server
# configuration with its secrets (M6 design §2.1).
VOLUME ["/world", "/data", "/etc/gups"]
EXPOSE 4443 443 9090

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD curl -fsS http://127.0.0.1:9090/healthz || exit 1

# /etc/gups/server.gups sets world = "/world", db = "/data/world.db" and the rest.
ENTRYPOINT ["gups", "serve", "--prod", "--config", "/etc/gups/server.gups"]
