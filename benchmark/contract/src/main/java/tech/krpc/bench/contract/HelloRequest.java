package tech.krpc.bench.contract;

/**
 * Request DTO. Boxed field per SPEC §4. No validation annotations: the benchmark
 * isolates transport + executor cost, not the validator.
 */
public class HelloRequest {

    private String name;

    public HelloRequest() {
    }

    public HelloRequest(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
