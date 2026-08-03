package io.github.vaquarkhan.aegis.core.authz;

import io.github.vaquarkhan.aegis.core.auth.CallerIdentity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cedar-shaped policy decision point for 0.1.x.
 *
 * <p>A full Cedar runtime is not bundled. This class evaluates a <strong>cedar-lite</strong> deny
 * file so {@code MCP_GW_PDP=cedar} is not a silent stub: each non-empty, non-comment line is a
 * deny glob {@code principal|action} (shell-style {@code *} wildcards), matching
 * {@link BuiltinPolicyEngine} semantics. Unreadable or missing files deny everything (fail-closed).
 *
 * <p>When {@code MCP_GW_POLICY_FILE} is an http(s) URL, decisions are delegated to {@link OpaPdp}
 * against that URL (operators can front a Cedar agent that speaks the same JSON input contract).
 *
 * @author Viquar Khan
 */
public final class CedarPdp implements PolicyDecisionPoint {

    private static final Logger LOG = LoggerFactory.getLogger(CedarPdp.class);

    private final PolicyDecisionPoint delegate;
    private final AtomicBoolean logged = new AtomicBoolean();

    public CedarPdp(String policyFile) {
        String path = policyFile == null || policyFile.isBlank() ? null : policyFile.trim();
        if (path != null && (path.startsWith("http://") || path.startsWith("https://"))) {
            LOG.info("Cedar PDP delegating to HTTP decision URL {}", path);
            this.delegate = new OpaPdp(path);
        } else {
            this.delegate = loadLite(path);
        }
    }

    @Override
    public boolean allows(CallerIdentity caller, String tool, Map<String, Object> args) {
        boolean ok = delegate.allows(caller, tool, args);
        if (!ok && logged.compareAndSet(false, true)) {
            LOG.info("Cedar PDP denied (subsequent denials at DEBUG)");
        } else if (!ok) {
            LOG.debug("Cedar PDP denied tool={}", tool);
        }
        return ok;
    }

    private static PolicyDecisionPoint loadLite(String path) {
        if (path == null) {
            LOG.warn("Cedar PDP has no policy file; denying every call");
            return PolicyDecisionPoint.denyAll();
        }
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) {
            LOG.error("Cedar PDP policy file missing {}; denying every call", path);
            return PolicyDecisionPoint.denyAll();
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<String[]> rules = new ArrayList<>();
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                    continue;
                }
                // cedar-lite: deny principalGlob actionGlob
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 2 && "deny".equalsIgnoreCase(parts[0])) {
                    rules.add(new String[] {parts[1].toLowerCase(Locale.ROOT), parts[2].toLowerCase(Locale.ROOT)});
                } else if (parts.length == 1 || (parts.length >= 2 && !parts[0].equalsIgnoreCase("permit"))) {
                    // bare principal|action or principal action (same as builtin globs)
                    String principal = parts[0].toLowerCase(Locale.ROOT);
                    String action = parts.length >= 2 ? parts[1].toLowerCase(Locale.ROOT) : "*";
                    if (principal.contains("|")) {
                        String[] pipe = principal.split("\\|", 2);
                        rules.add(new String[] {pipe[0], pipe[1]});
                    } else {
                        rules.add(new String[] {principal, action});
                    }
                }
            }
            LOG.info("Cedar-lite PDP loaded deny rules={} from {}", rules.size(), path);
            return (caller, tool, args) -> {
                String principal = caller == null ? "-" : caller.callerId().toLowerCase(Locale.ROOT);
                String action = tool == null ? "-" : tool.toLowerCase(Locale.ROOT);
                for (String[] rule : rules) {
                    if (glob(rule[0], principal) && glob(rule[1], action)) {
                        return false;
                    }
                }
                return true;
            };
        } catch (IOException e) {
            LOG.error("Cedar PDP failed to read {}; denying every call ({})", path, e.getMessage());
            return PolicyDecisionPoint.denyAll();
        }
    }

    private static boolean glob(String pattern, String value) {
        if ("*".equals(pattern)) {
            return true;
        }
        if (pattern.endsWith("*")) {
            return value.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        if (pattern.startsWith("*")) {
            return value.endsWith(pattern.substring(1));
        }
        return pattern.equals(value);
    }
}
