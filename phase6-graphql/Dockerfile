# =============================================================================
# The public read-only demo, for Cloud Run.
#
#   docker build -t inventory-graphql-demo phase6-graphql
#   docker run --rm -p 8080:8080 inventory-graphql-demo
#
# Only the GraphQL facade ships. No PostgreSQL, no SOAP service, no Redis, no
# gateway - the demo profile binds DemoInventoryBackend, which serves a snapshot
# of the real database from a JSON resource. See phase6-graphql/README.md for
# what that keeps real and what it does not.
# =============================================================================

# --- build ------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first, so a source-only change does not re-download the world.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B clean package -DskipTests

# Unpack the layered jar so Docker can cache dependencies separately from the
# application. Spring Boot orders these layers by how often they change.
RUN java -Djarmode=tools -jar target/graphql-facade-0.0.1-SNAPSHOT.jar extract --layers --launcher --destination extracted

# --- run --------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Never root. Cloud Run does not require it, which is exactly why it is easy to
# forget and worth doing anyway.
RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build --chown=app:app /build/extracted/dependencies/ ./
COPY --from=build --chown=app:app /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /build/extracted/application/ ./

ENV SPRING_PROFILES_ACTIVE=demo
# Cloud Run sets PORT and marks the revision unhealthy if nothing listens on it.
ENV PORT=8080
EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: Cloud Run memory is set at deploy
# time, and a hardcoded heap either wastes the allocation or gets OOM-killed
# when someone changes it.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Xss512k"

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
