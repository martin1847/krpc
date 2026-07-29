package test.krpc.db;

import java.time.Duration;
import java.util.Map;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * TESTCONTAINERS-001: provisions the MySQL the {@code test-server} tests run against.
 *
 * <p>Before this, {@code quarkus.datasource.jdbc.url} in {@code application.properties} pointed at
 * a host that only resolved on the maintainer's network, so {@code gradle build} could not be run
 * anywhere else and the documented workaround was {@code -x test} — i.e. the DB-backed path had no
 * gate at all. The container makes the dependency hermetic: Docker is the only prerequisite.
 *
 * <p>One container per test JVM: the field is static and {@link #start()} is idempotent, so a
 * Quarkus restart (different profile / test resources) rebinds to the running container instead of
 * paying the ~10s MySQL boot again.
 *
 * <p>Fail-fast is the point (the original pain was a silent hang, not a failure): Docker
 * availability is probed up front and the JDBC connect timeout is bounded, so a machine without a
 * usable Docker daemon gets an actionable error in seconds rather than a build that never returns.
 */
public class MySqlTestResource implements QuarkusTestResourceLifecycleManager {

    /** Pinned tag: an unpinned `mysql:latest` would silently change what the gate tests. */
    static final DockerImageName IMAGE = DockerImageName.parse("mysql:8.4");

    static final String DB = "example";
    static final String USER = "example";
    static final String PASSWORD = "example";

    private static MySQLContainer container;

    @Override
    public Map<String, String> start() {
        if (container == null) {
            // Catch-and-retranslate rather than only pre-checking: DockerClientFactory can THROW
            // (unreachable daemon, unusable Ryuk, broken docker config) instead of returning false,
            // and a raw docker-java stack trace does not tell the reader what to do about it.
            try {
                if (!DockerClientFactory.instance().isDockerAvailable()) {
                    throw new IllegalStateException("no usable Docker environment found");
                }
                container = new MySQLContainer(IMAGE)
                        .withDatabaseName(DB)
                        .withUsername(USER)
                        .withPassword(PASSWORD)
                        .withInitScript("db/init.sql")
                        // Bound every socket the driver opens: a half-open container must surface
                        // as a failed test, never as a build that hangs.
                        .withUrlParam("connectTimeout", "5000")
                        .withUrlParam("socketTimeout", "30000")
                        .withStartupTimeout(Duration.ofMinutes(2));
                container.start();
            } catch (RuntimeException | Error e) {
                container = null;
                throw new IllegalStateException(
                        "test-server DB tests need a working Docker daemon — Testcontainers provisions "
                                + IMAGE + " for them. Start Docker and retry, or run "
                                + "`gradle build -x test` in an environment that has no Docker. "
                                + "Underlying failure: " + e, e);
            }
        }
        return Map.of(
                "quarkus.datasource.jdbc.url", container.getJdbcUrl(),
                "quarkus.datasource.username", container.getUsername(),
                "quarkus.datasource.password", container.getPassword());
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
            container = null;
        }
    }
}
