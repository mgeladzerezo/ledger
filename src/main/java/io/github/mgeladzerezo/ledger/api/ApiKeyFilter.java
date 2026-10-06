package io.github.mgeladzerezo.ledger.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import io.github.mgeladzerezo.ledger.config.LedgerProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * API-key authentication for everything under {@code /api/}. The key travels in {@code X-API-Key}
 * and maps to a principal name, which becomes the scope of that client's idempotency keys and
 * the {@code createdBy} of its transactions. The static UI, the OpenAPI document and the health
 * endpoint are public.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    static final String PRINCIPAL_ATTRIBUTE = ApiKeyFilter.class.getName() + ".principal";

    private final Map<String, String> apiKeys;
    private final JsonMapper json;

    public ApiKeyFilter(LedgerProperties properties, JsonMapper json) {
        this.apiKeys = properties.security().apiKeys();
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String principal = principalFor(request.getHeader(HEADER));
        if (principal == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(Problem.MEDIA_TYPE);
            response.setHeader("WWW-Authenticate", "ApiKey header=\"" + HEADER + "\"");
            response.getOutputStream().write(json.writeValueAsBytes(Problem.of(401, "unauthorized", "Unauthorized",
                    "a valid API key is required in the " + HEADER + " header")));
            return;
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        chain.doFilter(request, response);
    }

    /** Compares against every configured key in constant time, so timing reveals nothing about any key. */
    private String principalFor(String presented) {
        if (presented == null) {
            return null;
        }
        byte[] candidate = presented.getBytes(StandardCharsets.UTF_8);
        String match = null;
        for (Map.Entry<String, String> entry : apiKeys.entrySet()) {
            if (MessageDigest.isEqual(candidate, entry.getValue().getBytes(StandardCharsets.UTF_8))) {
                match = entry.getKey();
            }
        }
        return match;
    }
}
