package th.co.glr.hr.factory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FactoryConfigRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public FactoryConfigRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<FactoryConfigDto> findAll() {
        return jdbc.query(
            "SELECT factory_config_id, factory_name, email, currency, unit, country FROM sales.factory_config ORDER BY factory_name",
            Map.of(),
            (rs, i) -> map(rs));
    }

    public Optional<FactoryConfigDto> findByName(String name) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                "SELECT factory_config_id, factory_name, email, currency, unit, country FROM sales.factory_config WHERE factory_name = :name",
                Map.of("name", name),
                (rs, i) -> map(rs)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private FactoryConfigDto map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FactoryConfigDto(
            rs.getLong("factory_config_id"),
            rs.getString("factory_name"),
            rs.getString("email"),
            rs.getString("currency"),
            rs.getString("unit"),
            rs.getString("country"));
    }
}
