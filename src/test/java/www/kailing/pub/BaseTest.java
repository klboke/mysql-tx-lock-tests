package www.kailing.pub;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * @author: kl @kailing.pub
 * @date: 2019/4/4
 */
public class BaseTest {

    JdbcTemplate jdbcTemplate;

    DataSource dataSource;

    @Before
    public void beforeAll() {
        Properties properties = new Properties();
        properties.put("jdbcUrl", "jdbc:mysql://127.0.0.1:3306/test?characterEncoding=utf-8");
        properties.put("username", "root");
        properties.put("password", "sasa");
        System.getProperties().putAll(properties);
        this.dataSource = new HikariDataSource(new HikariConfig(properties));
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }
}
