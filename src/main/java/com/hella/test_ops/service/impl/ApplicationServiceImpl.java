package com.hella.test_ops.service.impl;

import com.hella.test_ops.exception.ApplicationRestartException;
import com.hella.test_ops.exception.ApplicationShutdownException;
import com.hella.test_ops.service.ApplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicationServiceImpl implements ApplicationService {

    private final ApplicationContext applicationContext;
    private final AppConfigReader appConfigReader;


    @Override
    public void shutdownApplication() {
        log.info("Initiating application shutdown");

        if (applicationContext instanceof ConfigurableApplicationContext configurableContext) {
            CompletableFuture.runAsync(() -> {
                        try {
                            configurableContext.close();
                        } catch (Exception e) {
                            log.error("Error during shutdown", e);
                            throw new ApplicationShutdownException("Shutdown failed", e);
                        }
                    }).orTimeout(30, TimeUnit.SECONDS)
                    .exceptionally(throwable -> {
                        log.error("Shutdown timed out or failed", throwable);
                        return null;
                    });
        }
    }

    @Override
    public void restartApplication() {
        log.info("Initiating application restart");
        ApplicationArguments args = applicationContext.getBean(ApplicationArguments.class);

        Thread restartThread = new Thread(() -> {
            try {
                log.info("Executing application restart");
                ConfigurableApplicationContext context = (ConfigurableApplicationContext) applicationContext;

                context.close();

                Thread.sleep(2000);

                Class<?> mainClass = getMainApplicationClass();
                String[] sourceArgs = args.getSourceArgs();

                startNewApplication(mainClass, sourceArgs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApplicationRestartException("Application restart interrupted", e);
            }
        }, "RestartThread");

        restartThread.setDaemon(false);
        restartThread.start();
    }

    private void startNewApplication(Class<?> mainClass, String[] sourceArgs) {
        ConfigurableApplicationContext newContext = null;
        try {
            SpringApplication app = new SpringApplication(mainClass);
            app.setRegisterShutdownHook(false);
            newContext = app.run(sourceArgs);

            if (newContext != null && newContext.isRunning()) {
                log.info("Application restarted successfully");
            } else {
                throw new ApplicationRestartException("Failed to restart the application - context not running");
            }
        } catch (Exception e) {
            if (newContext != null) {
                newContext.close();
            }
            throw new ApplicationRestartException("Failed to restart application", e);
        }
    }

    private Class<?> getMainApplicationClass() {
        try {
            return Class.forName("com.hella.test_ops.TestOpsApplication");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Main application class not found", e);
        }
    }

    @Override
    public String getLogContent() {
        try {
            String logPathFromConfig = appConfigReader.getProperty("AppLogsPath");
            if (logPathFromConfig == null) {
                throw new RuntimeException("AppLogsPath not found in configuration");
            }

            // Resolve the full path
            Path basePath = Paths.get(System.getProperty("user.dir"));
            Path logPath = basePath.resolve(logPathFromConfig.replace("\\", File.separator));

            log.info("Attempting to read log file from: {}", logPath);

            if (!Files.exists(logPath)) {
                log.warn("Log file not found at path: {}", logPath);
                return "Log file not found at: " + logPath;
            }

            return Files.readString(logPath);

        } catch (IOException e) {
            log.error("Error reading log file", e);
            throw new RuntimeException("Failed to read log file: " + e.getMessage(), e);
        }
    }
}
