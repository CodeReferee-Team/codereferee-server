package com.codereferee.codereferee_server.referee;

import com.codereferee.codereferee_server.config.AiCoreProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class AiCoreClient {

    private final RestClient aiCoreRestClient;
    private final AiCoreProperties aiCoreProperties;

    public RepositoryValidationResponse validate(RepositoryValidationRequest request) {
        return aiCoreRestClient.post()
                .uri(aiCoreProperties.repositoryValidationPath())
                .body(request)
                .retrieve()
                .body(RepositoryValidationResponse.class);
    }
}
