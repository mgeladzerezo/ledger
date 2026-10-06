package io.github.mgeladzerezo.ledger.api;

import io.github.mgeladzerezo.ledger.domain.LedgerException;

/**
 * RFC 9457 problem details, the one error shape of the API. {@code code} is an extension member
 * carrying the same stable identifier as the last segment of {@code type}.
 */
public record Problem(String type, String title, int status, String detail, String code) {

    public static final String MEDIA_TYPE = "application/problem+json";

    public static Problem of(int status, String code, String title, String detail) {
        return new Problem("urn:ledger:problem:" + code, title, status, detail, code);
    }

    public static Problem of(LedgerException rejection) {
        return of(rejection.status(), rejection.code(), rejection.title(), rejection.getMessage());
    }
}
