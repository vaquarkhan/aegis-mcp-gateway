# Author: Viquar Khan
# Aegis MCP Governance Gateway
FROM eclipse-temurin:17-jre
WORKDIR /opt/aegis
COPY target/aegis-mcp-gateway-*-all.jar /opt/aegis/aegis-mcp-gateway.jar
ENV MCP_GW_TRANSPORT=stdio
ENTRYPOINT ["java","-jar","/opt/aegis/aegis-mcp-gateway.jar"]
