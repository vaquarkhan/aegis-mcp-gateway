# Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Aegis gateway image: the shaded gateway jar on a JRE, as a non-root user, configured by a mounted file.
#
#   docker build -t aegis-gateway:2.0.0-SNAPSHOT .
#   docker run --rm -p 8080:8080 -v $PWD/aegis.yaml:/etc/aegis/aegis.yaml:ro -e AEGIS_SIGNING_KEY aegis-gateway:2.0.0-SNAPSHOT
#
# The configuration must listen on 0.0.0.0 inside the container (host.listen.host) and should write the
# audit journal under /var/lib/aegis, the only writable directory. Source plugins go in
# /opt/aegis/plugins (the a2a source is included); their dependencies go there too.
# Base images are pinned by digest; update the digest and the tag together.

FROM maven:3.9.11-eclipse-temurin-21-noble@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS build
WORKDIR /src
COPY . .
RUN mvn -B -q -DskipTests -Djacoco.skip=true -pl aegis-gateway,aegis-source-a2a -am package \
 && mkdir -p /out/plugins \
 && cp aegis-gateway/target/aegis-gateway-*-all.jar /out/aegis-gateway.jar \
 && cp aegis-source-a2a/target/aegis-source-a2a-*.jar /out/plugins/

FROM eclipse-temurin:21-jre-noble@sha256:22138efd69393501fccd8176ae16b01791ed71ff801b28f0359415389b17c766
LABEL org.opencontainers.image.title="Aegis MCP governance gateway" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="https://github.com/vaquarkhan/aegis-mcp-gateway"
RUN groupadd --system --gid 10001 aegis \
 && useradd --system --uid 10001 --gid aegis --home-dir /var/lib/aegis --shell /usr/sbin/nologin aegis \
 && mkdir -p /opt/aegis/plugins /etc/aegis /var/lib/aegis \
 && chown aegis:aegis /var/lib/aegis
COPY --from=build /out/ /opt/aegis/
USER 10001:10001
WORKDIR /var/lib/aegis
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-cp", "/opt/aegis/aegis-gateway.jar:/opt/aegis/plugins/*", "io.github.vaquarkhan.aegis.gateway.Main"]
CMD ["--config", "/etc/aegis/aegis.yaml"]
