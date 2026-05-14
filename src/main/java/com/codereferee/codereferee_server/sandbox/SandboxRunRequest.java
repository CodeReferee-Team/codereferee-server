package com.codereferee.codereferee_server.sandbox;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record SandboxRunRequest(
        @NotBlank String code,
        String stdin,
        @Min(1) @Max(10) Integer timeoutSeconds
) {
    public int effectiveTimeoutSeconds() {
        return timeoutSeconds == null ? 3 : timeoutSeconds;
    }

    public String effectiveStdin() {
        return stdin == null ? "" : stdin;
    }
}
