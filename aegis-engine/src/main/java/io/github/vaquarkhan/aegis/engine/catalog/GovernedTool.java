/*
 * Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vaquarkhan.aegis.engine.catalog;

import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.engine.model.ControlTokens;
import io.github.vaquarkhan.aegis.engine.model.ToolClass;
import io.github.vaquarkhan.aegis.engine.model.ToolDescriptor;
import io.github.vaquarkhan.aegis.engine.spi.ToolExecutor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A tool as the gateway governs it: the upstream definition (untrusted) joined with the operator's
 * governance settings (trusted). Built by a {@link ToolBinder} from configuration.
 *
 * @param exposedName name clients call (upstream name, optionally prefixed by the source)
 * @param sourceId configured source id
 * @param descriptor upstream definition; its digest is pinned
 * @param toolClass effective class; never lower than any hint (see {@link ToolBinder})
 * @param resources resource extraction rules
 * @param dryRun dry-run mode used for the validate phase of VRP
 * @param allowedGroups when non-empty, only these groups may see or call the tool (a per-tool allow
 *     list, like the per-topology restriction common in Knox deployments)
 * @param actionScopes exact verified OAuth grants required by trusted operator configuration
 * @param executor capability that performs the call
 * @param timeoutMillis backend budget for one call
 * @param operatorSchema operator-written input schema that the arguments must ALSO satisfy, or
 *     null. The upstream schema is what the server published and what the pin covers; this one is
 *     what the operator is willing to forward. A server that publishes {@code additionalProperties:
 *     true} (or no schema at all) can be tightened here without trusting the server to do it.
 * @param executionContract trusted description of how arguments become effects
 * @param outputPolicy trusted rule for what backend output may reach the model
 */
public record GovernedTool(
    String exposedName,
    String sourceId,
    ToolDescriptor descriptor,
    ToolClass toolClass,
    List<ResourceRule> resources,
    DryRunSpec dryRun,
    Set<String> allowedGroups,
    Set<String> actionScopes,
    ToolExecutor executor,
    long timeoutMillis,
    Map<String, Object> operatorSchema,
    ExecutionContract executionContract,
    OutputPolicy outputPolicy) {

  /** Compatibility constructor for governed tools written before execution/output policy. */
  public GovernedTool(
      String exposedName,
      String sourceId,
      ToolDescriptor descriptor,
      ToolClass toolClass,
      List<ResourceRule> resources,
      DryRunSpec dryRun,
      Set<String> allowedGroups,
      Set<String> actionScopes,
      ToolExecutor executor,
      long timeoutMillis,
      Map<String, Object> operatorSchema) {
    this(
        exposedName,
        sourceId,
        descriptor,
        toolClass,
        resources,
        dryRun,
        allowedGroups,
        actionScopes,
        executor,
        timeoutMillis,
        operatorSchema,
        ExecutionContract.UNKNOWN,
        OutputPolicy.PASS_THROUGH);
  }

  /** Compatibility constructor for governed tools without an operator schema. */
  public GovernedTool(
      String exposedName,
      String sourceId,
      ToolDescriptor descriptor,
      ToolClass toolClass,
      List<ResourceRule> resources,
      DryRunSpec dryRun,
      Set<String> allowedGroups,
      Set<String> actionScopes,
      ToolExecutor executor,
      long timeoutMillis) {
    this(
        exposedName,
        sourceId,
        descriptor,
        toolClass,
        resources,
        dryRun,
        allowedGroups,
        actionScopes,
        executor,
        timeoutMillis,
        null);
  }

  public GovernedTool {
    if (exposedName == null || exposedName.isBlank()) {
      throw new IllegalArgumentException("exposed tool name must be set");
    }
    if (sourceId == null || sourceId.isBlank()) {
      throw new IllegalArgumentException("source id must be set");
    }
    if (descriptor == null || toolClass == null || executor == null) {
      throw new IllegalArgumentException(
          "descriptor, class and executor must be set for " + exposedName);
    }
    resources = resources == null ? List.of() : List.copyOf(resources);
    dryRun = dryRun == null ? DryRunSpec.GATEWAY : dryRun;
    allowedGroups = allowedGroups == null ? Set.of() : Set.copyOf(allowedGroups);
    actionScopes = actionScopes == null ? Set.of() : Set.copyOf(actionScopes);
    timeoutMillis = timeoutMillis <= 0 ? 30_000 : timeoutMillis;
    operatorSchema = operatorSchema == null ? null : Map.copyOf(operatorSchema);
    executionContract = executionContract == null ? ExecutionContract.UNKNOWN : executionContract;
    outputPolicy = outputPolicy == null ? OutputPolicy.PASS_THROUGH : outputPolicy;
  }

  /** Digest of trusted operator policy that must invalidate approvals when it changes. */
  public String policyDigest() {
    Map<String, Object> policy = new LinkedHashMap<>();
    if (operatorSchema != null) {
      policy.put("inputSchema", operatorSchema);
    }
    if (!executionContract.equals(ExecutionContract.UNKNOWN)) {
      policy.put("executionContract", executionContract.toMap());
    }
    if (!outputPolicy.equals(OutputPolicy.PASS_THROUGH)) {
      policy.put("outputPolicy", outputPolicy.toMap());
    }
    return policy.isEmpty()
        ? null
        : io.github.vaquarkhan.aegis.engine.model.Digests.canonicalSha256(policy);
  }

  /**
   * Definition digest used by approval/receipt binding, including trusted operator policy when
   * present.
   */
  public String bindingDigest(String upstreamDefinitionDigest) {
    String policy = policyDigest();
    return policy == null
        ? upstreamDefinitionDigest
        : io.github.vaquarkhan.aegis.engine.model.Digests.canonicalSha256(
            Map.of("upstreamDefinition", upstreamDefinitionDigest, "operatorPolicy", policy));
  }

  /**
   * The schema a client is told about and that the call must satisfy: the operator's when one is
   * configured (it is the narrower contract), else the upstream definition's.
   */
  public Map<String, Object> effectiveInputSchema() {
    return operatorSchema != null ? operatorSchema : descriptor.inputSchema();
  }

  /**
   * An operator schema that forbids every property the upstream schema does not declare, whatever
   * the upstream says about {@code additionalProperties}. The upstream's own property definitions
   * are kept so a tightened schema still validates the declared arguments the same way.
   */
  public static Map<String, Object> strictProperties(
      Map<String, Object> upstreamSchema, Map<String, Object> operatorSchema) {
    Map<String, Object> base = operatorSchema != null ? operatorSchema : upstreamSchema;
    Map<String, Object> out = new LinkedHashMap<>(base == null ? Map.of() : base);
    out.putIfAbsent("type", "object");
    Map<String, Object> props = JsonValues.object(out, "properties");
    if (props == null) {
      Map<String, Object> upstreamProps = JsonValues.object(upstreamSchema, "properties");
      out.put("properties", upstreamProps == null ? Map.of() : upstreamProps);
    }
    out.put("additionalProperties", false);
    return out;
  }

  /** Compatibility constructor for governed tools without action-scope requirements. */
  public GovernedTool(
      String exposedName,
      String sourceId,
      ToolDescriptor descriptor,
      ToolClass toolClass,
      List<ResourceRule> resources,
      DryRunSpec dryRun,
      Set<String> allowedGroups,
      ToolExecutor executor,
      long timeoutMillis) {
    this(
        exposedName,
        sourceId,
        descriptor,
        toolClass,
        resources,
        dryRun,
        allowedGroups,
        Set.of(),
        executor,
        timeoutMillis);
  }

  public String upstreamName() {
    return descriptor.name();
  }

  /** Property names declared by the upstream input schema or the operator schema. */
  public Set<String> declaredProperties() {
    Map<String, Object> props = JsonValues.object(descriptor.inputSchema(), "properties");
    Map<String, Object> ops =
        operatorSchema == null ? null : JsonValues.object(operatorSchema, "properties");
    if (ops == null) {
      return props == null ? Set.of() : props.keySet();
    }
    Set<String> all = new java.util.LinkedHashSet<>(ops.keySet());
    if (props != null) {
      all.addAll(props.keySet());
    }
    return all;
  }

  /**
   * Wire form for {@code tools/list}. The authority hints are rewritten from the operator's class
   * (the gateway is now the server, and its hints must be truthful); other upstream annotations are
   * kept. For write tools, the 0.1 control arguments are advertised as optional properties so
   * clients that cannot set {@code _meta} can still pass them.
   */
  public Map<String, Object> advertise(boolean advertiseControlArguments) {
    Map<String, Object> m = new LinkedHashMap<>(descriptor.toMcpMap());
    m.put("name", exposedName);
    Map<String, Object> ann = new LinkedHashMap<>();
    if (descriptor.annotations() != null) {
      ann.putAll(descriptor.annotations());
    }
    ann.put("readOnlyHint", toolClass == ToolClass.READ);
    ann.put("destructiveHint", toolClass == ToolClass.DESTRUCTIVE);
    m.put("annotations", ann);
    if (operatorSchema != null) {
      // Clients are told the contract the gateway will enforce, not the looser one upstream
      // published.
      m.put("inputSchema", operatorSchema);
    }
    if (advertiseControlArguments && toolClass.isWrite()) {
      m.put("inputSchema", withControlProperties(effectiveInputSchema()));
    }
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put(ControlTokens.META_PREFIX + "toolClass", toolClass.name());
    meta.put(ControlTokens.META_PREFIX + "source", sourceId);
    meta.put(ControlTokens.META_PREFIX + "executionKind", executionContract.kind().name());
    meta.put(ControlTokens.META_PREFIX + "outputMode", outputPolicy.mode().name());
    m.put("_meta", meta);
    return m;
  }

  private Map<String, Object> withControlProperties(Map<String, Object> schema) {
    Map<String, Object> s = new LinkedHashMap<>(schema);
    Map<String, Object> props = new LinkedHashMap<>();
    Map<String, Object> existing = JsonValues.object(schema, "properties");
    if (existing != null) {
      props.putAll(existing);
    }
    props.putIfAbsent(
        ControlTokens.ARG_APPROVAL,
        Map.of(
            "type",
            "string",
            "description",
            "Aegis approval token for this exact call (single use)."));
    if (toolClass == ToolClass.DESTRUCTIVE) {
      props.putIfAbsent(
          ControlTokens.ARG_DRY_RUN,
          Map.of(
              "type",
              "boolean",
              "description",
              "Run the Aegis validate phase only and return a dry-run receipt."));
      props.putIfAbsent(
          ControlTokens.ARG_RECEIPT,
          Map.of(
              "type",
              "string",
              "description",
              "Aegis dry-run receipt from the validate phase (single use)."));
    }
    s.put("properties", props);
    return s;
  }
}
