package com.hella.test_ops.service;

public interface ApplicationService {
    void shutdownApplication();

    void restartApplication();

    String getLogContent();
}
