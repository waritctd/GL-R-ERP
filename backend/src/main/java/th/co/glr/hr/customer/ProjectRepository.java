package th.co.glr.hr.customer;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public ProjectRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ProjectDto> findByCustomer(long customerId) {
        return jdbc.query(
            """
            SELECT project_id, customer_id, name
              FROM sales.project
             WHERE customer_id = :customerId
             ORDER BY name
            """,
            Map.of("customerId", customerId),
            (rs, i) -> new ProjectDto(rs.getLong("project_id"), rs.getLong("customer_id"), rs.getString("name"))
        );
    }

    public ProjectDto create(long customerId, String name) {
        var kh = new GeneratedKeyHolder();
        jdbc.update(
            "INSERT INTO sales.project (customer_id, name) VALUES (:customerId, :name)",
            new MapSqlParameterSource().addValue("customerId", customerId).addValue("name", name),
            kh, new String[]{"project_id"}
        );
        long id = kh.getKey().longValue();
        return jdbc.queryForObject(
            "SELECT project_id, customer_id, name FROM sales.project WHERE project_id = :id",
            Map.of("id", id),
            (rs, i) -> new ProjectDto(rs.getLong("project_id"), rs.getLong("customer_id"), rs.getString("name"))
        );
    }
}
