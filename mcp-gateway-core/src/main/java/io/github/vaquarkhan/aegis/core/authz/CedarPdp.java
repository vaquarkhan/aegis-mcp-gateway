package io.github.vaquarkhan.aegis.core.authz;

import io.github.vaquarkhan.aegis.core.auth.CallerIdentity;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cedar policy decision point.
 *
 * <p>Stub for 0.1.0 that denies every call. The class exists so {@code MCP_GW_PDP=cedar} selects a
 * real object rather than failing at startup, and denying is the only safe answer while no policy
 * set is actually evaluated.
 *
 * <p>TODO: load the Cedar policy set and entity store from {@code MCP_GW_POLICY_FILE}, map the
 * caller to a principal, the tool to an action and the scoped resource to a Cedar entity, and
 * evaluate. See DESIGN section 5 and LLD section 5.
 *
 * @author Viquar Khan
 */
public final class CedarPdp implements PolicyDecisionPoint {

    private static final Logger LOG = LoggerFactory.getLogger(CedarPdp.class);

    private final String policyFile;
    private final AtomicBoolean warned = new AtomicBoolean();

    public CedarPdp(String policyFile) {
        this.policyFile = policyFile;
        LOG.warn("Cedar PDP is a stub and denies every call (policyFile={})",
                policyFile == null ? "-" : policyFile);
    }

    public String policyFile() {
        return policyFile;
    }

    @Override
    public boolean allows(CallerIdentity caller, String tool, Map<String, Object> args) {
        if (warned.compareAndSet(false, true)) {
            LOG.error("MCP_GW_PDP=cedar is not implemented; denying every call including {}", tool);
        }
        return false;
    }
}
