# Example container image for dmnspwn, e.g. for Amazon EKS.
#
#   docker build -t dmnspwn .
#   docker buildx build --platform linux/amd64,linux/arm64 -t <account>.dkr.ecr.<region>.amazonaws.com/dmnspwn:<tag> --push .
#
# Runs as a non-root user (UID 10001) on port 8080. With more than one replica use storage: s3 (see README),
# with credentials from IRSA or EKS Pod Identity.

ARG JAVA_VERSION=21

# ---- build ------------------------------------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-${JAVA_VERSION} AS build
WORKDIR /src

# Resolve dependencies in their own layer so that source changes do not download them again.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
 && cp target/dmnspwn-*.jar app.jar \
 && java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# ---- runtime ----------------------------------------------------------------------------------------------------
FROM eclipse-temurin:${JAVA_VERSION}-jre-noble

RUN groupadd --system --gid 10001 dmnspwn \
 && useradd --system --uid 10001 --gid dmnspwn --home-dir /app --shell /usr/sbin/nologin dmnspwn \
 && mkdir -p /app/dmn-models \
 && chown dmnspwn:dmnspwn /app/dmn-models

WORKDIR /app

# Least frequently changed layers first.
COPY --from=build /src/extracted/dependencies/ ./
COPY --from=build /src/extracted/spring-boot-loader/ ./
COPY --from=build /src/extracted/snapshot-dependencies/ ./
COPY --from=build /src/extracted/application/ ./

USER 10001:10001

# Size the heap from the pod's memory limit rather than the node's memory.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    DMNSPWN_STORAGE_DIRECTORY=/app/dmn-models

EXPOSE 8080

# Probes: /actuator/health/liveness and /actuator/health/readiness
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
