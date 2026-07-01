package th.co.glr.hr.common;

/**
 * Normalized, bounded pagination parameters for list endpoints (issue #29).
 *
 * <p>Pagination is opt-in: when a caller supplies no {@code size}, {@link #resolve} returns a
 * request capped at {@link #DEFAULT_SIZE} so existing clients keep receiving effectively the full
 * list at today's volumes, while the query is always bounded by a {@code LIMIT}.
 */
public record PageRequest(int page, int size) {
    /** Applied when the caller supplies no explicit page size. */
    public static final int DEFAULT_SIZE = 500;
    /** Hard upper bound so a caller cannot request an unbounded page. */
    public static final int MAX_SIZE = 500;

    public static PageRequest resolve(Integer page, Integer size) {
        int resolvedPage = (page == null || page < 0) ? 0 : page;
        int resolvedSize = (size == null || size <= 0) ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return new PageRequest(resolvedPage, resolvedSize);
    }

    public long offset() {
        return (long) page * size;
    }
}
