package com.example.agent.tools;

import com.example.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Property-backed {@link CredentialResolver}:
 *
 * <pre>
 * agent.credentials.per-user.cistern.alice: ${ALICE_CISTERN_TOKEN}
 * </pre>
 *
 * Plain-text properties are the v1 seam, not the end state — production
 * deployments should feed these from a secret manager. The interface is the
 * contract; swap the implementation without touching tools.
 */
@Component
public class ConfiguredCredentialResolver implements CredentialResolver {

    private final AgentProperties props;

    public ConfiguredCredentialResolver(AgentProperties props) {
        this.props = props;
    }

    @Override
    public String resolve(String service, ToolContext context) {
        if (service == null || context == null) return null;
        Map<String, String> forService = props.getCredentials().getPerUser().get(service);
        if (forService == null) return null;
        String token = forService.get(context.userId());
        return (token == null || token.isBlank()) ? null : token;
    }
}
