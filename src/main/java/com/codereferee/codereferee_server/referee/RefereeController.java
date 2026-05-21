package com.codereferee.codereferee_server.referee;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/referee")
@RequiredArgsConstructor
public class RefereeController {

    private final RefereeService refereeService;

    @PostMapping("/submit")
    public ResponseEntity<Map<String, String>> submit(@Valid @RequestBody SubmitRequest request) {
        String taskId = refereeService.submit(request.requirements());
        return ResponseEntity.accepted().body(Map.of("taskId", taskId));
    }

    @GetMapping("/status/{taskId}")
    public ResponseEntity<TaskStatus> getStatus(@PathVariable String taskId) {
        return refereeService.getStatus(taskId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
