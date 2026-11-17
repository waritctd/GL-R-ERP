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

/** A DataSource wrapper that records every SQL statement executed through it, so a test can assert how many
 * round trips a service call makes — and, more usefully, that it makes no statement twice. Test-scope only. */
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

    /** How many times each distinct SQL string was executed, in first-execution order. This is the shape an N+1
     * has: one SQL text with a count far above 1. */
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
                    // prepareStatement/prepareCall carry the SQL as the first argument; createStatement carries none and the SQL
                    // arrives at execute() instead.
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

    /** equals/hashCode/toString must not be forwarded to the delegate: delegate.equals(proxy) is false for every
     * proxy, which would make a proxied connection unequal to itself. Identity semantics on the proxy are what a
     * pooled connection already provides. */
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
