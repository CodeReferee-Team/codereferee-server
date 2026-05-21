package com.codereferee.codereferee_server.referee;

import jakarta.validation.constraints.NotBlank;

public record SubmitRequest(
        @NotBlank String requirements
) {}
