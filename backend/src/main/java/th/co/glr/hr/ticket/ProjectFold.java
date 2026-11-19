package th.co.glr.hr.ticket;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * THE fold rule (สั่งเพิ่ม): every project-level list shows ONE row per project, however many add-on orders it has.
 * Used by the sales board reads and by {@code GET /api/tickets?fold=project}; mockApi mirrors it once.
 *
 * <ul>
 *   <li>Identity: the row shows the ROOT's code, title, customer and project.</li>
 *   <li>Lead order: the root when the root is open; else the open add-on with the latest activity. Every STATE field
 *       (stage, lifecycle, quiet, follow-up, payment-due chip) comes from the lead. No order open: from the root
 *       (or, when the viewer cannot see the root, from the viewer's latest order).</li>
 *   <li>Orders counted: every non-cancelled order of the chain the viewer may see, root first, then by order number;
 *       value: the sum of the OPEN ones only (none open: all of them).</li>
 *   <li>Link: the root's id when the viewer may see the root, else the lead order's id.</li>
 *   <li>Rows that are not part of an add-on chain (including bid-split children) pass through untouched.</li>
 * </ul>
 */
public final class ProjectFold {
    private ProjectFold() {}

    /** What the fold needs to know about a list row of type {@code T}. */
    public interface Ops<T> {
        long id(T t);

        /** The root's id when {@code t} is an ADD_ON order, else null. */
        Long parentId(T t);

        String code(T t);

        /** May the viewer see (own, or the list's scope covers) this order? Only visible orders are counted. */
        boolean visible(T t);

        /** ACTIVE and not CLOSED_PAID. */
        boolean open(T t);

        boolean cancelled(T t);

        Instant activity(T t);

        /** Builds the project row. */
        T project(Project<T> project);
    }

    /** One chain to fold. {@code orders}: counted orders, root first; {@code open}: those still open. */
    public record Project<T>(T root, T lead, List<T> orders, List<T> open, boolean mayOpenRoot) {
        public int count() {
            return orders.size();
        }

        /** The root's code (the project's number, always searchable) then each counted order's code, no repeats. */
        public List<String> codes(Function<T, String> code) {
            return java.util.stream.Stream.concat(java.util.stream.Stream.of(root), orders.stream())
                .map(code).distinct().toList();
        }
    }

    /**
     * @param rows       the list as the caller's query produced it (its own scope and filters)
     * @param related    the chain rows beyond {@code rows} for the given project keys: every add-on of those roots and
     *                   the roots themselves, whatever their lifecycle (the caller's {@code visible} decides)
     * @param skipIfOpen drop a project that has an open order: for the "returning from pause" list, whose projects
     *                   are on the board already
     */
    public static <T> List<T> fold(List<T> rows, Ops<T> ops, Function<Set<Long>, Collection<T>> related,
                                   boolean skipIfOpen) {
        Set<Long> keys = new LinkedHashSet<>();
        for (T r : rows) {
            Long parent = ops.parentId(r);
            keys.add(parent != null ? parent : ops.id(r));
        }
        if (keys.isEmpty()) {
            return rows;
        }
        Map<Long, T> byId = new LinkedHashMap<>();
        rows.forEach(r -> byId.put(ops.id(r), r));
        for (T r : related.apply(keys)) {
            byId.putIfAbsent(ops.id(r), r);
        }
        Map<Long, List<T>> addOns = new LinkedHashMap<>();
        for (T r : byId.values()) {
            Long parent = ops.parentId(r);
            if (parent != null) {
                addOns.computeIfAbsent(parent, k -> new ArrayList<>()).add(r);
            }
        }
        Set<Long> done = new HashSet<>();
        List<T> out = new ArrayList<>();
        for (T r : rows) {
            Long parent = ops.parentId(r);
            long key = parent != null ? parent : ops.id(r);
            T root = byId.get(key);
            if (!addOns.containsKey(key) || root == null) {
                out.add(r);
                continue;
            }
            if (!done.add(key)) {
                continue;
            }
            List<T> chain = new ArrayList<>();
            chain.add(root);
            addOns.get(key).stream().sorted(Comparator.comparingLong(ops::id)).forEach(chain::add);
            List<T> visible = chain.stream().filter(ops::visible).toList();
            List<T> counted = visible.stream().filter(o -> !ops.cancelled(o)).toList();
            if (counted.isEmpty()) {
                counted = visible;
            }
            if (counted.isEmpty()) {
                out.add(r);
                continue;
            }
            List<T> open = counted.stream().filter(ops::open).toList();
            if (skipIfOpen && !open.isEmpty()) {
                continue;
            }
            boolean rootVisible = ops.visible(root);
            Comparator<T> latest = Comparator.comparing(ops::activity, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingLong(ops::id);
            T lead;
            if (rootVisible && ops.open(root) && !ops.cancelled(root)) {
                lead = root;
            } else if (!open.isEmpty()) {
                lead = open.stream().max(latest).get();
            } else if (rootVisible) {
                lead = root;
            } else {
                lead = counted.stream().max(latest).get();
            }
            out.add(ops.project(new Project<>(root, lead, counted, open, rootVisible)));
        }
        return out;
    }
}
