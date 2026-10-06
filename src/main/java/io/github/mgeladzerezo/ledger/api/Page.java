package io.github.mgeladzerezo.ledger.api;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * One page of a keyset-paginated list.
 *
 * @param nextCursor opaque token for the following page, or {@code null} when this is the last one
 */
public record Page<T>(List<T> items, String nextCursor) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    /**
     * Builds a page from a query that fetched {@code limit + 1} rows: the extra row only signals
     * that another page exists and is not returned.
     *
     * @param position the keyset value of an item (its id or sequence), encoded into the cursor
     */
    public static <T> Page<T> of(List<T> fetched, int limit, ToLongFunction<T> position) {
        if (fetched.size() <= limit) {
            return new Page<>(fetched, null);
        }
        List<T> items = fetched.subList(0, limit);
        return new Page<>(List.copyOf(items), encode(position.applyAsLong(items.getLast())));
    }

    public static int limit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        BadRequestException.require(requested >= 1 && requested <= MAX_LIMIT,
                "limit must be between 1 and " + MAX_LIMIT);
        return requested;
    }

    /** @return the keyset position in {@code cursor}, or {@code first} when there is no cursor */
    public static long decode(String cursor, long first) {
        if (cursor == null || cursor.isBlank()) {
            return first;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            BadRequestException.require(decoded.startsWith("v1:"), "cursor is not valid");
            return Long.parseLong(decoded.substring(3));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("cursor is not valid");
        }
    }

    static String encode(long position) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("v1:" + position).getBytes(StandardCharsets.UTF_8));
    }
}
