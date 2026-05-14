package com.codereferee.codereferee_server.sandbox;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SandboxClient {
    private final RestClient sandboxRestClient;

    public SandboxClient(RestClient sandboxRestClient) {
        this.sandboxRestClient = sandboxRestClient;
    }

    public SandboxRunResponse run(SandboxRunRequest request) {
        var sandboxRequest = new SandboxRunRequest(
                request.code(),
                request.effectiveStdin(),
                request.effectiveTimeoutSeconds()
        );

        return sandboxRestClient.post()
                .uri("/run")
                .body(sandboxRequest)
                .retrieve()
                .body(SandboxRunResponse.class);
    }
}
