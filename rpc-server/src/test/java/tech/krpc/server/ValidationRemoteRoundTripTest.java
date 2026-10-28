package tech.krpc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tech.krpc.annotation.RpcService;
import tech.krpc.annotation.UnsafeWeb;
import tech.krpc.client.RpcClientFactory;
import tech.krpc.model.RpcResult;
import tech.krpc.server.invoke.ValidationTestStubs;

/**
 * AGENT-002 r2 (cross-face regression): the CLASSIC gRPC face contract — {@code INVALID_ARGUMENT}
 * is field-level self-correctable — must survive to a REMOTE client that never sees the JVM-local
 * {@code ValidationException.violations()}. The field-level detail therefore rides the
 * {@code io.grpc.Status} <b>description</b> ({@code "Dto : field(message)[; …]"}), value-free.
 *
 * <p>Real in-process round-trip (netty {@link RpcServerBuilder} server + {@link RpcClientFactory}
 * client over a {@link ManagedChannel}, the {@code OtelProductionChainTest} pattern). Asserts the
 * remote client's status description carries {@code email(must not be blank)} AND that a secret
 * password value appears NOWHERE in the remote status or trailers.
 */
class ValidationRemoteRoundTripTest {

    static final String APP = "validation-rt-it";
    static final String SECRET = "hunter2-TOP-SECRET-pw";

    public static class LoginRequest {
        @NotBlank
        private String email;
        @Size(min = 8)
        private String password;

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    @UnsafeWeb
    @RpcService("Login")
    public interface LoginService {
        RpcResult<String> login(LoginRequest req);
    }

    public static final class LoginServiceImpl implements LoginService {
        @Override
        public RpcResult<String> login(LoginRequest req) {
            return RpcResult.ok("unreachable: validation fails first");
        }
    }

    private Server server;
    private ManagedChannel channel;
    private ExecutorService exec;
    private LoginService client;

    @BeforeEach
    void setUp() throws Exception {
        // The stub validator reports two failing fields; the password's invalid value is the
        // secret, proving that even a failing field's value never reaches the wire.
        ServerContext.regValidator(ValidationTestStubs.validator(Set.of(
                ValidationTestStubs.violation("email", "must not be blank", ""),
                ValidationTestStubs.violation("password", "size must be between 8 and 64", SECRET))));

        int port = freePort();
        exec = Executors.newVirtualThreadPerTaskExecutor();
        server = new RpcServerBuilder.Builder(APP, port).executor(exec)
                .addService(new LoginServiceImpl()).build().startServer();
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
        client = new RpcClientFactory(APP, channel).get(LoginService.class);
    }

    @AfterEach
    void tearDown() {
        if (channel != null) channel.shutdownNow();
        if (server != null) server.shutdownNow();
        if (exec != null) exec.shutdownNow();
        ServerContext.regValidator(null);
    }

    @Test
    void remoteValidationError_carriesFieldLevelDetail_neverSecret() {
        var req = new LoginRequest();
        req.setEmail("");
        req.setPassword(SECRET); // the secret genuinely travels client -> server in the request

        var ex = assertThrows(StatusRuntimeException.class, () -> client.login(req));

        Status status = ex.getStatus();
        assertEquals(Status.Code.INVALID_ARGUMENT, status.getCode(),
                () -> "classic face: validation is INVALID_ARGUMENT, got " + status);

        String desc = status.getDescription();
        // Field-level self-correction survives to the remote client (the regression this fixes).
        assertTrue(null != desc && desc.contains("email(must not be blank)"),
                () -> "remote status must carry field-level detail: " + desc);
        assertTrue(desc.contains("password(size must be between 8 and 64)"),
                () -> "second field's constraint present: " + desc);

        // But the rejected password value must appear NOWHERE the remote client can observe.
        String haystack = desc + "|" + status.getCause() + "|" + ex.getTrailers();
        assertFalse(haystack.contains(SECRET),
                () -> "rejected secret leaked into remote status/trailers: " + haystack);
    }

    private static int freePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }
}
