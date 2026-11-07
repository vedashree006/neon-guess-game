# ---- build stage -------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
# Tests run in CI (`mvn verify`); the image build only packages.
RUN mvn -B -q -DskipTests package

# ---- runtime stage -----------------------------------------------------------
FROM tomcat:10.1-jre17-temurin
# Remove Tomcat's sample apps, deploy as the root application, run as an unprivileged user.
RUN rm -rf /usr/local/tomcat/webapps/* \
 && groupadd --system neon \
 && useradd --system --gid neon --home-dir /data neon \
 && mkdir -p /data \
 && chown -R neon:neon /data /usr/local/tomcat
COPY --from=build /build/target/neon-guess-game.war /usr/local/tomcat/webapps/ROOT.war
ENV NEON_DB_DIR=/data \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/urandom"
VOLUME /data
USER neon
EXPOSE 8080
CMD ["catalina.sh", "run"]
