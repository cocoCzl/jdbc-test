FROM node:24.11.1-alpine AS web
WORKDIR /build/web
COPY jdbc-test-web/package.json jdbc-test-web/package-lock.json ./
RUN npm ci
COPY jdbc-test-web/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk-noble AS java-build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY jdbc-test-core/ jdbc-test-core/
COPY jdbc-test-suite/ jdbc-test-suite/
COPY jdbc-test-worker/ jdbc-test-worker/
COPY jdbc-test-server/ jdbc-test-server/
COPY compatibility/ compatibility/
COPY ddl/ ddl/
COPY dml/ dml/
COPY pool/ pool/
COPY adapters/ adapters/
RUN --mount=type=cache,id=jdbc-test-maven,target=/root/.m2,sharing=locked \
    ./mvnw -B -DskipTests package && \
    mkdir /artifacts && \
    cp jdbc-test-server/target/jdbc-test-server-*-exec.jar /artifacts/server.jar && \
    cp jdbc-test-worker/target/jdbc-test-worker-*-all.jar /artifacts/worker.jar

FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:1.24 AS runtime
ARG VERSION=1.2.1-SNAPSHOT
ARG REVISION=unknown
LABEL org.opencontainers.image.title="jdbc-test" \
      org.opencontainers.image.version="$VERSION" \
      org.opencontainers.image.revision="$REVISION"
USER 0
RUN groupadd --gid 10001 jdbc-test && useradd --uid 10001 --gid 10001 --no-create-home jdbc-test && \
    mkdir -p /app/server /app/worker /app/web /data/jdbc-test/drivers /data/jdbc-test/runs /data/jdbc-test/staging && \
    chown -R jdbc-test:jdbc-test /data/jdbc-test
COPY --from=java-build /artifacts/server.jar /app/server/jdbc-test-server.jar
COPY --from=java-build /artifacts/worker.jar /app/worker/jdbc-test-worker.jar
COPY --from=web /build/web/dist/ /app/web/
COPY LICENSE /app/LICENSE
RUN chmod -R a-w /app
ENV SPRING_PROFILES_ACTIVE=docker
WORKDIR /app
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=6 \
    CMD ["curl", "--fail", "--silent", "--output", "/dev/null", "http://127.0.0.1:8080/actuator/health"]
ENTRYPOINT ["java", "-jar", "/app/server/jdbc-test-server.jar"]
