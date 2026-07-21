package th.co.glr.hr.customer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ContactRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public ContactRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ContactDto> findByCustomer(long customerId) {
        return jdbc.query(
            """
            SELECT contact_id, customer_id, first_name, last_name, position, email, phone
              FROM customers.contact
             WHERE customer_id = :customerId
             ORDER BY first_name
            """,
            Map.of("customerId", customerId),
            (rs, i) -> map(rs)
        );
    }

    /**
     * Used by {@code PricingRequestService} to verify a pricing request's
     * {@code recipientContactId} actually belongs to the deal's customer
     * before persisting it.
     */
    public Optional<ContactDto> findById(long contactId) {
        return jdbc.query(
            "SELECT contact_id, customer_id, first_name, last_name, position, email, phone FROM customers.contact WHERE contact_id = :id",
            Map.of("id", contactId), (rs, i) -> map(rs)
        ).stream().findFirst();
    }

    public ContactDto create(long customerId, String firstName, String lastName,
                             String position, String email, String phone) {
        var kh = new GeneratedKeyHolder();
        jdbc.update(
            """
            INSERT INTO customers.contact (customer_id, first_name, last_name, position, email, phone)
            VALUES (:customerId, :firstName, :lastName, :position, :email, :phone)
            """,
            new MapSqlParameterSource()
                .addValue("customerId", customerId)
                .addValue("firstName", firstName)
                .addValue("lastName", lastName)
                .addValue("position", position)
                .addValue("email", email)
                .addValue("phone", phone),
            kh, new String[]{"contact_id"}
        );
        long id = kh.getKey().longValue();
        // Reuses findById rather than a second inline query — the row must
        // exist immediately after its own insert, so an empty result here
        // means something is badly wrong, not a normal "not found".
        return findById(id)
            .orElseThrow(() -> new IllegalStateException("Contact " + id + " not found immediately after insert"));
    }

    private ContactDto map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ContactDto(
            rs.getLong("contact_id"),
            rs.getLong("customer_id"),
            rs.getString("first_name"),
            rs.getString("last_name"),
            rs.getString("position"),
            rs.getString("email"),
            rs.getString("phone")
        );
    }
}
