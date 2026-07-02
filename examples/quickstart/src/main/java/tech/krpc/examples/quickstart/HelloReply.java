package tech.krpc.examples.quickstart;

/**
 * Response DTO. Scalar fields are boxed types (SPEC §4): JSON serialization is
 * NON_NULL, so boxed types preserve the "absent vs zero" distinction that
 * primitives would destroy.
 */
public class HelloReply {

    private String message;
    private Long timestamp;

    public HelloReply() {
    }

    public HelloReply(String message, Long timestamp) {
        this.message = message;
        this.timestamp = timestamp;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }
}
