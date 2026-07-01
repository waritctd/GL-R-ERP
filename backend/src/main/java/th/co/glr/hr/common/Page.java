package th.co.glr.hr.common;

import java.util.List;

/** A single page of results plus the metadata a caller needs to page through the rest (issue #29). */
public record Page<T>(List<T> items, int page, int size, int total) {
}
