package com.codereferee.codereferee_server.referee;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/validations")
@RequiredArgsConstructor
public class RefereeController {

    private final RefereeService refereeService;

    @PostMapping("/repository")
    public ResponseEntity<Map<String, String>> submitValidation(@Valid @RequestBody RepositoryValidationRequest request) {
        String requestId = refereeService.submit(request);
        return ResponseEntity.accepted().body(Map.of("requestId", requestId));
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<TaskStatus> getStatus(@PathVariable String requestId) {
        return refereeService.getStatus(requestId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
