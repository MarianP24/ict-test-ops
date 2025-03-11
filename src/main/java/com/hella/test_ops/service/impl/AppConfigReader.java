package com.hella.test_ops.service.impl;


import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

@Component
@Slf4j
public class AppConfigReader {
    private Properties properties;

    @PostConstruct
    public void init() {
        properties = new Properties();
        try (InputStream inputStream = getClass().getClassLoader()
                .getResourceAsStream("AppConfig.ini")) {
            if (inputStream == null) {
                throw new RuntimeException("AppConfig.ini not found in resources");
            }
            properties.load(inputStream);
            log.info("Successfully loaded AppConfig.ini from resources");
        } catch (IOException e) {
            log.error("Error loading AppConfig.ini", e);
            throw new RuntimeException("Failed to load configuration file", e);
        }
    }

    public String getProperty(String key) {
        return properties.getProperty(key);
    }
}