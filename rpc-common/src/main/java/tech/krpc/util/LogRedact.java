package tech.krpc.util;

import java.util.Locale;
import java.util.Set;

/**
 * OTEL-002 Fix 3: credential-safe rendering of request metadata for logs. Framework code that emits
 * inbound headers / cookies / gRPC metadata MUST route values through here so a user JWT (the
 * {@code access-token} cookie), an {@code Authorization} bearer token, or any deployment-specific
 * auth header never lands in a log line — not even at DEBUG, not even in staging (redaction
 * doctrine).
 *
 * <p><b>Mask by default (R1-4 review).</b> A deny-list cannot make a full arbitrary-header dump
 * safe: the next unlisted token-bearing header ({@code X-Token}, {@code X-Goog-Api-Key}, a custom
 * auth header) would leak. So every header value is masked UNLESS its name is on the explicit
 * {@link #DIAGNOSTIC_SAFE} allow-list of headers whose values are non-sensitive and useful for
 * debugging. {@code Cookie}/{@code Set-Cookie} are handled specially: cookie names are kept but
 * every value is masked (a bare, {@code =}-less segment is masked whole — it may itself be a
 * secret). The mask is a fixed sentinel — never a prefix/suffix of the live secret.
 *
 * <p>Transport-agnostic on purpose: it works on header <em>name</em> + <em>value</em> strings, so
 * both the netty HTTP face ({@code HttpHeaders}) and the gRPC face ({@code io.grpc.Metadata}) share
 * one policy instead of re-deriving a sensitive-key list per call site.
 */
public final class LogRedact {

    private LogRedact() {}

    /** Replacement for a masked value. Fixed sentinel — never reveals any part of the secret. */
    public static final String MASK = "<redacted>";

    /**
     * Header names (lower-case) whose values are safe and useful to log verbatim. Everything else
     * is masked. Kept deliberately small: transport/negotiation/observability metadata only — no
     * header on it carries a framework-recognised credential. (Values may still contain
     * caller-controlled text — a custom user-agent, x-request-id, or tracestate — so this is a
     * "no known secret" list, not a "no PII" guarantee.)
     */
    public static final Set<String> DIAGNOSTIC_SAFE = Set.of(
            // content negotiation / framing
            "content-type",
            "content-length",
            "content-encoding",
            "accept",
            "accept-encoding",
            "accept-language",
            "accept-charset",
            // transport / client identification (non-secret)
            "user-agent",
            "host",
            "connection",
            "date",
            "cache-control",
            // W3C trace context + correlation (non-secret; needed to debug propagation)
            "traceparent",
            "tracestate",
            "x-request-id",
            // krpc / MCP protocol metadata (non-secret)
            "mcp-protocol-version",
            "c-id",
            "x-krpc-http-status");

    /** True when a header's value is on the diagnostic-safe allow-list and may be logged verbatim. */
    public static boolean isDiagnosticSafe(String headerName) {
        return headerName != null && DIAGNOSTIC_SAFE.contains(headerName.toLowerCase(Locale.ROOT));
    }

    /**
     * Log-safe value for a single header. Allow-listed → returned unchanged. {@code cookie}/
     * {@code set-cookie} → each cookie name kept, every value (and any bare segment) masked.
     * Everything else → masked (default-deny).
     */
    public static String value(String headerName, String value) {
        if (value == null) {
            return null;
        }
        String lower = headerName == null ? "" : headerName.toLowerCase(Locale.ROOT);
        if ("cookie".equals(lower) || "set-cookie".equals(lower)) {
            return maskCookie(value);
        }
        return DIAGNOSTIC_SAFE.contains(lower) ? value : MASK;
    }

    /**
     * Masks every cookie value while keeping cookie names, e.g.
     * {@code "access-token=eyJ...; theme=dark"} → {@code "access-token=<redacted>; theme=<redacted>"}.
     * A bare ({@code =}-less) non-empty segment is masked whole — it is ambiguous whether it is a
     * flag or a secret, so default-deny applies and nothing verbatim survives.
     */
    public static String maskCookie(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            return cookieHeader;
        }
        StringBuilder sb = new StringBuilder(cookieHeader.length());
        String[] cookies = cookieHeader.split(";", -1);
        for (int i = 0; i < cookies.length; i++) {
            if (i > 0) {
                sb.append(';');
            }
            String cookie = cookies[i];
            int lead = 0;
            while (lead < cookie.length() && cookie.charAt(lead) == ' ') {
                lead++;
            }
            sb.append(cookie, 0, lead);
            String body = cookie.substring(lead);
            int eq = body.indexOf('=');
            if (eq < 0) {
                // Bare segment: empty stays empty; any non-empty value is masked whole (could be a secret).
                sb.append(body.isEmpty() ? "" : MASK);
            } else {
                sb.append(body, 0, eq + 1).append(MASK);
            }
        }
        return sb.toString();
    }
}
