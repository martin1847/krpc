package test.krpc.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import tech.test.krpc.BookService;
import tech.test.krpc.dto.Book;

/**
 * TESTCONTAINERS-001: round-trip proof that the mybatis + Agroal + MySQL wiring of
 * {@code test-server} actually works, against a container-provisioned database.
 *
 * <p>Why it exists: the module already declared a MySQL datasource and a mapper, but every test
 * mocked {@link tech.test.krpc.mapper.BookMapper} away, so the JDBC path — the ext-mybatis
 * {@code QuarkusDataSourceFactory} bridge, the {@code @Transactional} service, the paging
 * interceptor — was configured but never executed by any gate.
 *
 * <p>{@link TestResourceScope#GLOBAL} so the container starts once for the whole module run and
 * Quarkus boots a single time (the mocked tests share the same application instance).
 */
@QuarkusTest
@WithTestResource(value = MySqlTestResource.class, scope = TestResourceScope.GLOBAL)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BookMapperIT {

    static final int ID = 4711;

    @Inject
    BookService bookService;

    @Test
    @Order(1)
    void saveThenGet_roundTripsThroughMysql() {
        var saved = bookService.saveBook(new Book(ID, "hermetic"));
        assertTrue(saved.isOk(), "saveBook failed: " + saved.getMsg());

        var found = bookService.getBook(ID);
        assertTrue(found.isOk());
        assertNotNull(found.getData(), "row written by saveBook was not readable");
        assertEquals(ID, found.getData().getId());
        assertEquals("hermetic", found.getData().getName());
    }

    /** saveBook is delete-then-insert under @Transactional: re-saving must not violate the PK. */
    @Test
    @Order(2)
    void reSave_replacesTheRow() {
        assertTrue(bookService.saveBook(new Book(ID, "replaced")).isOk());

        var found = bookService.getBook(ID);
        assertTrue(found.isOk());
        assertEquals("replaced", found.getData().getName());
    }

    // NOT covered here: listBook(PagedQuery) / the ext-mybatis paging interceptor. Handing a
    // base-classloader `tech.krpc.model.PagedQuery` to the QuarkusClassLoader-side service throws
    // LinkageError (loader constraint violation) — the @QuarkusTest split-classloader issue that
    // examples/quickstart works around with quarkus.class-loading.parent-first-artifacts in its
    // test-scope application.properties. Gating that path needs the same treatment for this module;
    // it is orthogonal to where the database comes from, so it is left out of this change.
}
