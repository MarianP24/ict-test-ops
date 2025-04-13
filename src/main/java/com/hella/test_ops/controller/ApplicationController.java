package com.hella.test_ops.controller;

import com.hella.test_ops.service.ApplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/application-service")
@RequiredArgsConstructor
public class ApplicationController {

    private final ApplicationService applicationService;

    @PostMapping("/shutdown")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<String> shutdownApplication() {
        applicationService.shutdownApplication();
        return ResponseEntity.ok("Application shutdown initiated");
    }

    @PostMapping("/restart")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<String> restartApplication() {
        applicationService.restartApplication();
        return ResponseEntity.ok("Application restart initiated");
    }

    @GetMapping(value = "/logs", produces = MediaType.TEXT_PLAIN_VALUE)
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<String> getApplicationLogs() {
        try {
            String logContent = applicationService.getLogContent();
            return ResponseEntity.ok(logContent);
        } catch (Exception e) {
            log.error("Error retrieving log content", e);
            return ResponseEntity.internalServerError()
                    .body("Error retrieving log content: " + e.getMessage());
        }
    }
}
