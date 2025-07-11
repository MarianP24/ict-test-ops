package com.hella.test_ops.service;

import com.hella.test_ops.entity.Fixture;
import com.hella.test_ops.entity.Machine;
import com.hella.test_ops.model.FixtureDTO;
import jakarta.annotation.PreDestroy;
import jakarta.transaction.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Set;

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

    String createMaintenanceReportForSingleFixture(long fixtureId);

    @Scheduled(cron = "0 45 13 * * ?")
    void scheduleBusinessLogic();

    void removeFixtureFromMachine(long fixtureId);

    void removeFixtureFromSpecificMachine(long fixtureId, long machineId);

    String getCounterContent();

    List<Fixture> findAllEntities();

    List<FixtureDTO> findByFilters(
            String fileName,
            String programName,
            String productName,
            String business,
            Long fixtureCounterSet
    );

    @Transactional
    Set<Machine> getFixtureMachineMap(Long fixtureId);

    /**
     * Connects to a machine using VNC viewer
     * @param hostname The machine hostname or IP address to connect to
     * @throws IOException if the VNC connection fails
     */
    void connectVnc(String hostname) throws IOException;

    /**
     * Connects to C$ drive on a remote machine
     * @param hostname The machine hostname or IP address
     * @throws IOException if the connection fails
     */
    void connectToCDrive(String hostname) throws IOException;

    /**
     * Connects to D$ drive on a remote machine
     * @param hostname The machine hostname or IP address
     * @throws IOException if the connection fails
     */
    void connectToDDrive(String hostname) throws IOException;

}