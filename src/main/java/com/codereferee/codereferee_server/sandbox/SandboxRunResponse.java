package com.codereferee.codereferee_server.sandbox;

public record SandboxRunResponse(
        String stdout,
        String stderr,
        Integer exitCode,
        boolean timedOut,
        long durationMillis
) {
}
