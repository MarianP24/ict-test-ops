package com.hella.test_ops.controller;

import com.hella.test_ops.service.ApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/application")
@RequiredArgsConstructor
public class ApplicationController {
    private final ApplicationService applicationService;

    @PostMapping("/shutdown")
    public ResponseEntity<String> shutdownApplication() {
        applicationService.shutdownApplication();
        return ResponseEntity.ok("Application shutdown initiated");
    }

    @PostMapping("/restart")
    public ResponseEntity<String> restartApplication() {
        applicationService.restartApplication();
        return ResponseEntity.ok("Application restart initiated");
    }
}
