package th.co.glr.hr.customer;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CustomerRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public CustomerRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CustomerDto> search(String q) {
        String pattern = q == null || q.isBlank() ? "%" : "%" + q.trim() + "%";
        return jdbc.query(
            """
            SELECT customer_id, name, tax_id, address, branch, phone
              FROM sales.customer
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
}
