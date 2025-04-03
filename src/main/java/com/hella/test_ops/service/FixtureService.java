package com.hella.test_ops.service;

import com.hella.test_ops.entity.Fixture;
import com.hella.test_ops.model.FixtureDTO;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public interface FixtureService {
    @PreDestroy
    void shutdownExecutor();

    void save(FixtureDTO fixtureDTO);

    FixtureDTO findById(long id);

    List<FixtureDTO> findAll();

    Fixture findEntityById(long id);

    void update(long id, FixtureDTO fixtureDTO);

    void deleteById(long id);

    void addFixtureToMachine(long fixtureId, long machineId);

    void createMaintenanceFixtureReport();

    @Scheduled(cron = "0 45 13 * * ?")
    void scheduleBusinessLogic();

    void removeFixtureFromMachine(long fixtureId);

    String getCounterContent();

    List<Fixture> findAllEntities();

    List<FixtureDTO> findByFilters(
            String fileName,
            String programName,
            String productName,
            String business,
            Long fixtureCounterSet
    );
}