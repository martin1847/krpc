package tech.krpc.examples.quickstart;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO. Uses jakarta.validation (SPEC §7) — a blank name throws
 * INVALID_ARGUMENT before the method runs.
 */
public class HelloRequest {

    @NotBlank
    private String name;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
