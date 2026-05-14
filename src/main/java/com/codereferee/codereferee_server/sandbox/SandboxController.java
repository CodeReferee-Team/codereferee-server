package com.codereferee.codereferee_server.sandbox;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sandbox")
public class SandboxController {
    private final SandboxClient sandboxClient;

    public SandboxController(SandboxClient sandboxClient) {
        this.sandboxClient = sandboxClient;
    }

    @PostMapping("/run")
    public SandboxRunResponse run(@Valid @RequestBody SandboxRunRequest request) {
        return sandboxClient.run(request);
    }
}
