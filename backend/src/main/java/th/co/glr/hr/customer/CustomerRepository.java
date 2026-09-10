package th.co.glr.hr.customer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class CustomerRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public CustomerRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CustomerDto> findById(long id) {
        try {
            CustomerDto customer = jdbc.queryForObject(
                """
                SELECT customer_id, name, tax_id, address, branch, phone
                  FROM customers.customer
                 WHERE customer_id = :id
                """,
                Map.of("id", id),
                (rs, i) -> new CustomerDto(
                    rs.getLong("customer_id"),
                    rs.getString("name"),
                    rs.getString("tax_id"),
                    rs.getString("address"),
                    rs.getString("branch"),
                    rs.getString("phone")
                )
            );
            return Optional.ofNullable(customer);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }


    public List<CustomerDto> search(String q) {
        String pattern = q == null || q.isBlank() ? "%" : "%" + q.trim() + "%";
        return jdbc.query(
            """
            SELECT customer_id, name, tax_id, address, branch, phone
              FROM customers.customer
             WHERE name ILIKE :q OR tax_id ILIKE :q
             ORDER BY name
             LIMIT 30
            """,
            Map.of("q", pattern),
            (rs, i) -> new CustomerDto(
                rs.getLong("customer_id"),
                rs.getString("name"),
                rs.getString("tax_id"),
                rs.getString("address"),
                rs.getString("branch"),
                rs.getString("phone")
            )
        );
    }

    public CustomerDto create(String name, String taxId, String address, String branch, String phone) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO customers.customer (name, tax_id, address, branch, phone)
            VALUES (:name, :taxId, :address, :branch, :phone)
            """,
            new MapSqlParameterSource()
                .addValue("name", name)
                .addValue("taxId", taxId)
                .addValue("address", address)
                .addValue("branch", branch)
                .addValue("phone", phone),
            keyHolder, new String[]{"customer_id"});
        long id = keyHolder.getKey().longValue();
        return new CustomerDto(id, name, taxId, address, branch, phone);
    }

    /**
     * PATCH-shaped update (owner feedback F7, 2026-09-10): only the fields the caller actually
     * sent are written — a {@code null} argument means "leave this column alone", which is what
     * {@code COALESCE(:x, column)} expresses in one statement without a read-modify-write race.
     * A non-null BLANK is a real value and DOES overwrite (that is how a rep clears a wrong tax id
     * or phone); {@code name} and {@code branch} are {@code NOT NULL} in V16's DDL, so
     * {@code CustomerController} rejects a blank for those two before reaching this method.
     *
     * <p>Returns {@link Optional#empty()} when no row matched, so the controller can answer 404
     * rather than a silent 200 on a customer id that does not exist.
     */
    public Optional<CustomerDto> update(long id, String name, String taxId, String address,
                                        String branch, String phone) {
        int updated = jdbc.update("""
            UPDATE customers.customer
               SET name    = COALESCE(:name, name),
                   tax_id  = COALESCE(:taxId, tax_id),
                   address = COALESCE(:address, address),
                   branch  = COALESCE(:branch, branch),
                   phone   = COALESCE(:phone, phone)
             WHERE customer_id = :id
            """,
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("name", name)
                .addValue("taxId", taxId)
                .addValue("address", address)
                .addValue("branch", branch)
                .addValue("phone", phone));
        return updated == 0 ? Optional.empty() : findById(id);
    }
}
