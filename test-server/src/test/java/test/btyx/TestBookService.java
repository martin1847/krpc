package test.btyx;

import com.testbt.dto.Book;
import jakarta.inject.Inject;

import com.testbt.BookService;
import com.testbt.mapper.BookMapper;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@QuarkusTest
public class TestBookService {

    @Inject
    BookService bookService;


    @BeforeAll
    public static void setup() {
        var mock = Mockito.mock(BookMapper.class);
        Mockito.when(mock.getUser(1)).thenReturn(new Book(1,"mock 1"));
        QuarkusMock.installMockForType(mock, BookMapper.class);
    }

    @Test
    public void testGetUser() {

        var res = bookService.getBook(1);

        Assertions.assertEquals(1, res.getData().getId());
    }
}
