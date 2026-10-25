package com.vladdancea.viennapulse.database;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/// Sets up the database client for short statements next to the one Spring Boot configures.
@Configuration(proxyBeanMethods = false)
class DatabaseConfiguration {
    // The API's queries take about a second in production, and recording a round's live departures
    // far less. One that takes five holds a connection of the small pool that other requests wait
    // for, so it is cancelled instead of waited for.
    private static final int QUERY_TIMEOUT_SECONDS = 5;
    // PgJDBC cancels a query over the limit by asking the database, which a database that stopped
    // answering ignores. Twice the query limit leaves the cancellation time to arrive.
    private static final int NETWORK_TIMEOUT_MILLISECONDS = 10_000;

    /// The database client of the API services and of the recorder of live departures, which cancels
    /// any statement that runs for more than 5 seconds and gives up on a database that sends nothing
    /// for 10. It is not a default candidate, so Spring Boot still configures the importer's client,
    /// whose statements take minutes, and injects this one only where [TimeLimited] asks.
    @Bean(defaultCandidate = false)
    @TimeLimited
    JdbcClient timeLimitedJdbcClient(DataSource dataSource) {
        var template = new JdbcTemplate(new NetworkTimeoutDataSource(dataSource));
        template.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        return JdbcClient.create(template);
    }

    // Hikari sets a borrowed connection's network timeout back to the pool's when it returns, so the
    // importer's connections keep waiting as long as their statements take.
    private static final class NetworkTimeoutDataSource extends DelegatingDataSource {
        NetworkTimeoutDataSource(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public Connection getConnection() throws SQLException {
            var connection = super.getConnection();
            try {
                // PgJDBC reads with a socket timeout and leaves the executor unused.
                connection.setNetworkTimeout(Runnable::run, NETWORK_TIMEOUT_MILLISECONDS);
            } catch (SQLException exception) {
                connection.close();
                throw exception;
            }
            return connection;
        }
    }
}
