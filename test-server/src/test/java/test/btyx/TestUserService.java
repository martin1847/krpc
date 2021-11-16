package test.btyx;

import javax.inject.Inject;

import com.btyx.test.UserService;
import com.btyx.test.dto.User;
import com.btyx.test.mapper.UserMapper;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@QuarkusTest
public class TestUserService {

    @Inject
    UserService userService;


    @BeforeAll
    public static void setup() {
        var mock = Mockito.mock(UserMapper.class);
        Mockito.when(mock.getUser(1)).thenReturn(new User(1,"mock 1"));
        QuarkusMock.installMockForType(mock, UserMapper.class);
    }

    @Test
    public void testGetUser() {

        var res = userService.getUser(1);

        Assertions.assertEquals(1, res.getData().getId());
    }
}
