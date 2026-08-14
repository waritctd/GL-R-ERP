package th.co.glr.hr.support;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A {@link DataSource} wrapper that records every SQL statement executed through it, so a test can
 * assert how many round trips a service call makes — and, more usefully, that it makes no statement
 * <em>twice</em>.
 *
 * <p><strong>Why this exists rather than a Spring hook.</strong> Every integration test in this
 * suite hand-wires its services with {@code new} (see {@link AbstractPostgresIntegrationTest}), so
 * there is no application context, no AOP, and nothing to attach a JDBC interceptor to. Counting
 * has to happen at the only seam the hand-wired object graph shares: the {@link DataSource} the
 * repositories' {@code NamedParameterJdbcTemplate} sits on. Wrap the datasource, hand the wrapped
 * one to the repositories under measurement, and every {@code execute*} they cause is recorded —
 * whichever repository issued it, and whether or not the caller knew about it.
 *
 * <p><strong>What is counted.</strong> One entry per {@code Statement.execute*} invocation, keyed
 * by the SQL text. For a {@link PreparedStatement} that is the SQL handed to
 * {@code Connection.prepareStatement}; for a plain {@link Statement} it is the SQL handed to the
 * {@code execute} call itself. {@code executeBatch()} counts as <em>one</em> entry — it is one
 * round trip, and treating it as N would make a batched write look like an N+1 when it is the
 * opposite. Statement <em>preparation</em> is deliberately not counted: a driver is free to cache
 * and reuse a prepared statement, so preparation count is an implementation detail while execution
 * count is the round trip that costs.
 *
 * <p><strong>Not thread-safe by design of use.</strong> The recording list is synchronized so a
 * stray background statement cannot corrupt it, but {@link #reset()}/{@link #total()} around a
 * concurrent call would still be meaningless. Measure single-threaded.
 *
 * <p><strong>Test-scope only.</strong> Nothing in {@code src/main} knows this exists; it wraps a
 * datasource a test already owns. Do not reach for it to instrument production.
 */
public final class StatementCountingDataSource implements DataSource {

    private final DataSource delegate;
    private final List<String> executed = Collections.synchronizedList(new ArrayList<>());

    public StatementCountingDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    // ── recording control ────────────────────────────────────────────────────

    /** Drops everything recorded so far. Call immediately before the measured invocation. */
    public void reset() {
        executed.clear();
    }

    /** Total number of statement executions recorded since the last {@link #reset()}. */
    public int total() {
        return executed.size();
    }

    /** Every recorded execution, in order, SQL text as issued. */
    public List<String> statements() {
        synchronized (executed) {
            return List.copyOf(executed);
        }
    }

    /**
     * How many times each distinct SQL string was executed, in first-execution order.
     *
     * <p>This is the shape an N+1 has: one SQL text with a count far above 1. A test asserting
     * "no statement ran twice" reads straight off this map, and its failure message names the
     * offending query instead of merely reporting a number that grew.
     */
    public Map<String, Integer> executionsBySql() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String sql : statements()) {
            counts.merge(sql, 1, Integer::sum);
        }
        return counts;
    }

    /** Every SQL string executed more than once since the last {@link #reset()}, with its count. */
    public Map<String, Integer> repeatedStatements() {
        Map<String, Integer> repeated = new LinkedHashMap<>();
        executionsBySql().forEach((sql, count) -> {
            if (count > 1) {
                repeated.put(sql, count);
            }
        });
        return repeated;
    }

    /** A compact, readable dump for an assertion failure message. */
    public String describe() {
        StringBuilder out = new StringBuilder(total() + " statement execution(s):");
        executionsBySql().forEach((sql, count) ->
            out.append("\n  ").append(count).append("x  ").append(oneLine(sql)));
        return out.toString();
    }

    private static String oneLine(String sql) {
        String flattened = sql.replaceAll("\\s+", " ").trim();
        return flattened.length() <= 160 ? flattened : flattened.substring(0, 157) + "...";
    }

    private void record(String sql) {
        executed.add(sql == null ? "<unknown sql>" : sql);
    }

    // ── the proxy chain: DataSource -> Connection -> Statement ───────────────

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(delegate.getConnection(username, password));
    }

    private Connection wrap(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
            StatementCountingDataSource.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                Object direct = handleObjectMethod(proxy, connection, method, args);
                if (direct != NOT_HANDLED) {
                    return direct;
                }
                Object result = invoke(connection, method, args);
                if (result instanceof Statement statement) {
                    // prepareStatement/prepareCall carry the SQL as the first argument;
                    // createStatement carries none and the SQL arrives at execute() instead.
                    String sql = args != null && args.length > 0 && args[0] instanceof String s ? s : null;
                    return wrapStatement(statement, sql);
                }
                return result;
            });
    }

    private Object wrapStatement(Statement statement, String preparedSql) {
        Class<?> iface = statement instanceof CallableStatement ? CallableStatement.class
            : statement instanceof PreparedStatement ? PreparedStatement.class
            : Statement.class;
        return Proxy.newProxyInstance(
            StatementCountingDataSource.class.getClassLoader(),
            new Class<?>[] {iface},
            (proxy, method, args) -> {
                Object direct = handleObjectMethod(proxy, statement, method, args);
                if (direct != NOT_HANDLED) {
                    return direct;
                }
                if (method.getName().startsWith("execute")) {
                    String sql = preparedSql != null ? preparedSql
                        : args != null && args.length > 0 && args[0] instanceof String s ? s : null;
                    record(sql);
                }
                return invoke(statement, method, args);
            });
    }

    private static final Object NOT_HANDLED = new Object();

    /**
     * {@code equals}/{@code hashCode}/{@code toString} must not be forwarded to the delegate:
     * {@code delegate.equals(proxy)} is false for every proxy, which would make a proxied
     * connection unequal to itself. Identity semantics on the proxy are what a pooled connection
     * already provides.
     */
    private static Object handleObjectMethod(Object proxy, Object delegate, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> args != null && args.length == 1 && proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "counting[" + delegate + "]";
            default -> NOT_HANDLED;
        };
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    // ── plain delegation ─────────────────────────────────────────────────────

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger(StatementCountingDataSource.class.getName());
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return iface.isInstance(this) ? iface.cast(this) : delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }
}
