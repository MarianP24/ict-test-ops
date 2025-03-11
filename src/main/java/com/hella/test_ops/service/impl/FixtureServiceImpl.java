package com.hella.test_ops.service.impl;

import com.hella.test_ops.entity.Fixture;
import com.hella.test_ops.entity.Machine;
import com.hella.test_ops.model.FixtureDTO;
import com.hella.test_ops.repository.FixtureRepository;
import com.hella.test_ops.repository.MachineRepository;
import com.hella.test_ops.service.FixtureService;
import com.hella.test_ops.service.MachineService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


@Slf4j
@Component
public class FixtureServiceImpl implements FixtureService {
    private final FixtureRepository fixtureRepository;
    private final MachineRepository machineRepository;
    private final MachineService machineService;
    private final ExecutorService executorService;
    private final Map<Long, Integer> fixtureCounterTotals = new HashMap<>();
    private final AppConfigReader appConfigReader;
    private final Map<Long, Map<String, Integer>> fixtureHostnameCounters = new HashMap<>(); // Fixture ID -> (Hostname -> Counter)

    @Value("${network.share.username}")
    private String username;

    @Value("${network.share.password}")
    private String password;

    public FixtureServiceImpl(FixtureRepository fixtureRepository, MachineRepository machineRepository, MachineService machineService, AppConfigReader appConfigReader) {
        this.fixtureRepository = fixtureRepository;
        this.machineRepository = machineRepository;
        this.machineService = machineService;
        this.appConfigReader = appConfigReader;
        this.executorService = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors()
        );
    }

    @PreDestroy
    public void shutdownExecutor() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void save(FixtureDTO fixtureDTO) {
        fixtureRepository.save(fixtureDTO.convertToEntity());
        log.info("Fixture {} has been saved", fixtureDTO.fileName());
    }

    @Override
    public FixtureDTO findById(long id) {
        Fixture fixture = fixtureRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Fixture with id " + id + " not found"));
        log.info("Fixture {} has been found", fixture.getFileName());
        return FixtureDTO.convertToDTO(fixture);
    }

    @Override
    public List<FixtureDTO> findAll() {
        List<Fixture> fixtures = fixtureRepository.findAll();
        log.info("Found {} fixtures", fixtures.size());
        return fixtures.stream()
                .map(FixtureDTO::convertToDTO)
                .toList();
    }

    @Override
    public Fixture findEntityById(long id) {
        return fixtureRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Fixture with id " + id + " not found"));
    }


    @Override
    public void update(long id, FixtureDTO fixtureDTO) {
        Fixture oldFixture = fixtureRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Fixture with id " + id + " not found"));
        oldFixture.setFileName(fixtureDTO.fileName());
        oldFixture.setBusiness(fixtureDTO.business());
        oldFixture.setProductName(fixtureDTO.productName());
        oldFixture.setProgramName(fixtureDTO.programName());
        oldFixture.setFixtureCounterSet(fixtureDTO.fixtureCounterSet());
        fixtureRepository.save(oldFixture);
        log.info("Fixture {} has been updated", fixtureDTO.fileName());
    }

    @Override
    public void deleteById(long id) {
        fixtureRepository.deleteById(id);
        log.info("Fixture with id {} has been deleted", id);
    }

    @Override
    public void addFixtureToMachine(long fixtureId, long machineId) {
        Fixture fixture = fixtureRepository.findById(fixtureId)
                .orElseThrow(() -> new IllegalArgumentException("Fixture with id " + fixtureId + " not found"));
        Machine machine = machineRepository.findById(machineId)
                .orElseThrow(() -> new IllegalArgumentException("Machine with id " + machineId + " not found"));

        if (fixture.getMachines().contains(machine)) {
            log.info("Fixture {} is already associated with machine {}", fixture.getFileName(), machine.getEquipmentName());
            return;
        }
        fixture.getMachines().add(machine);

        fixtureRepository.save(fixture);
        log.info("Fixture {} has been added to machine {}", fixture.getFileName(), machine.getEquipmentName());
    }

    public void createMaintenanceFixtureReport() {
        fixtureCounterTotals.clear();
        List<Machine> allMachines = machineRepository.findAll();
        log.info("Total machines in database: {}", allMachines.size());
        log.info("All machine hostnames: {}",
                allMachines.stream()
                        .map(Machine::getHostname)
                        .toList());

        List<Fixture> fixtures = fixtureRepository.findAll();

        // First, log machines with null hostnames
        allMachines.stream()
                .filter(m -> m.getHostname() == null)
                .forEach(m -> log.warn("Machine {} does not have a hostname",
                        m.getEquipmentName()));

        // Get all valid hostnames from machines repository
        Set<String> allMachineHostnames = allMachines.stream()
                .map(Machine::getHostname)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<String, List<Fixture>> fixturesByHostname = fixtures.stream()
                .filter(f -> !f.getMachines().isEmpty())
                .flatMap(f -> f.getMachines().stream()
                        .filter(m -> m.getHostname() != null)
                        .map(m -> new AbstractMap.SimpleEntry<>(m.getHostname(), f)))
                .collect(Collectors.groupingBy(
                        Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));

        // Log hostnames from all machines that don't have fixtures
        allMachineHostnames.forEach(hostname -> {
            if (!fixturesByHostname.containsKey(hostname)) {
                log.warn("Hostname {} does not have any fixture", hostname);
            }
        });

        log.info("Number of unique hostnames to process: {}", fixturesByHostname.size());
        fixturesByHostname.forEach((hostname, fixtureList) ->
                log.info("Hostname: {} has {} fixtures", hostname, fixtureList.size()));

        List<CompletableFuture<Void>> futures = fixturesByHostname.entrySet().stream()
                .map(entry -> CompletableFuture.runAsync(() ->
                        processHostnameFixtures(entry.getKey(), entry.getValue()), executorService))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        log.info("Maintenance report has been concluded for all fixtures with valid hostnames");
    }

    private void processHostnameFixtures(String hostname, List<Fixture> fixtures) {
        log.info("Starting to process {} fixtures for hostname {}", fixtures.size(), hostname);
        try {
            String uncBasePath = createTemporaryConnection(hostname);

            for (Fixture fixture : fixtures) {
                processSingleFixture(fixture, hostname, uncBasePath);
            }
            log.info("Completed processing all fixtures for hostname {}", hostname);
        } catch (IOException e) {
            log.error("Unable to process fixtures for hostname {}: {}. Skipping this host.",
                    hostname, e.getMessage(), e);
        } finally {
            try {
                removeConnection(hostname);
                log.info("Successfully removed connection to hostname {}", hostname);
            } catch (Exception e) {
                log.error("Failed to remove connection to {}: {}. Continuing execution.",
                        hostname, e.getMessage(), e);
            }
        }
    }

    @PreDestroy
    private void clearCounters() {
        fixtureCounterTotals.clear();
    }

    private void processSingleFixture(Fixture fixture, String hostname, String uncBasePath) {
        int counter = processFixture(fixture, uncBasePath, hostname);

        // filePath example: \\ROF01BEA28\C$\seica/Maintenance\013997_7774GF0001.wtg
        // Check individual counter first
        if (counter >= fixture.getFixtureCounterSet()) {
            log.info("uncBasePath is {}", uncBasePath);
            // uncBasePath example: \\ROF01BEA28\C$\seica/Maintenance
            resetCounter(fixture.getFileName(), uncBasePath + "\\" + fixture.getFileName(), hostname);
            log.info("Counter has been reset for fixture {} on hostname {}",
                    fixture.getFileName(), hostname);
            return;
        }

        // Synchronize access to the shared map
        synchronized (fixtureHostnameCounters) {
            Map<String, Integer> hostnameCounters = fixtureHostnameCounters
                    .computeIfAbsent(fixture.getId(), k -> new HashMap<>());
            hostnameCounters.put(hostname, counter);

            int newTotal = hostnameCounters.values().stream()
                    .mapToInt(Integer::intValue)
                    .sum();

            if (newTotal >= fixture.getFixtureCounterSet()) {
                try {
                    // Reset all contributing counters
                    for (Map.Entry<String, Integer> entry : hostnameCounters.entrySet()) {
                        String contributingHostname = entry.getKey();
                        String hostUncPath = createTemporaryConnection(contributingHostname);
                        resetCounter(fixture.getFileName(),
                                hostUncPath + "\\" + fixture.getFileName(),
                                contributingHostname);
                        log.info("Counter reset for fixture {} on hostname {} due to total limit",
                                fixture.getFileName(), contributingHostname);
                    }
                    hostnameCounters.clear();
                    fixture.setCounter(0);
                    fixtureRepository.save(fixture);
                } catch (Exception e) {
                    log.error("Error processing fixture counters reset", e);
                }
            } else {
                fixture.setCounter(newTotal);
                fixtureRepository.save(fixture);
            }
        }
    }

//    private String createTemporaryConnection(String hostname) throws IOException {
//        String mtSeicaPath = appConfigReader.getProperty("MtSeicaPath")
//                .replace("C:", ""); // Remove C: from the path since we'll use network share
//        String uncPath = String.format("\\\\%s\\C$%s", hostname, mtSeicaPath);
//
//        ProcessBuilder processBuilder = new ProcessBuilder(
//                "cmd.exe", "/c", "net", "use", "\\\\" + hostname + "\\C$", password, "/user:" + username);
//
//        Process process = processBuilder.start();
//
//        try {
//            int exitCode = process.waitFor();
//            if (exitCode != 0) {
//                throw new IOException("Failed to create temporary connection to " + hostname);
//            }
//            log.info("Successfully connected to hostname {}", hostname);
//        } catch (InterruptedException e) {
//            Thread.currentThread().interrupt();
//            throw new IOException("Connection interrupted", e);
//        }
//
//        return uncPath;
//    }

    private String createTemporaryConnection(String hostname) throws IOException {
        Machine machine = machineService.findByHostname(hostname);

        String equipmentType = machine.getEquipmentType();
        String configPath = "";

        if ("SEICA".equals(equipmentType)) {
            log.info("SEICA detected for hostname {}", hostname);
            configPath = appConfigReader.getProperty("MtSeicaPath");
        } else if ("AEROFLEX".equals(equipmentType)) {
            log.info("AEROFLEX detected for hostname {}", hostname);
            configPath = appConfigReader.getProperty("MtAeroflexPath");
        } else {
            log.info("Ignoring equipment of type: {}", equipmentType);
        }

        String cleanPath = configPath.replace("C:", "");
        String uncPath = String.format("\\\\%s\\C$%s", hostname, cleanPath);

        ProcessBuilder processBuilder = new ProcessBuilder(
                "cmd.exe", "/c", "net", "use", "\\\\" + hostname + "\\C$", password, "/user:" + username);

        Process process = processBuilder.start();

        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("Failed to create temporary connection to " + hostname);
            }
            log.info("Successfully connected to hostname {}", hostname);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Connection interrupted", e);
        }

        return uncPath;
    }


    private void removeConnection(String hostname) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "cmd.exe", "/c", "net", "use", "\\\\" + hostname + "\\C$", "/delete", "/y");

            Process process = processBuilder.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                log.error("Failed to remove connection to {}. Exit code: {}", hostname, exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Connection removal interrupted for {}", hostname, e);
        } catch (IOException e) {
            log.error("Error removing connection to {}", hostname, e);
        }
    }

    private int processFixture(Fixture fixture, String basePath, String hostname) {
        String fullPath = basePath + "\\" + fixture.getFileName();
        File file = new File(fullPath);

        if (!file.exists()) {
            log.error("File {} does not exist at path: {}", fixture.getFileName(), fullPath);
            return 0;
        }

        try (Scanner scanner = new Scanner(file)) {
            if (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                log.info("Line {} has been read from file {} on hostname {}", line, fixture.getFileName(), hostname);
                String[] words = line.split("\\s+");
                return Integer.parseInt(words[0]);
            }
        } catch (FileNotFoundException e) {
            log.error("File not found: {}", fullPath, e);
        }
        return 0;
    }

    private void resetCounter(String fixtureFileName, String filePath, String hostname) {
        // filePAth is \\ROF01BEA28\C$\seica/Maintenance\013997_7774GF0001.wtg
        // counterPathLog is the place where it writes the report \logs\ContoareResetate.txt
        Path counterPathLog = Paths.get(getContoareResetatePath());
        log.info("Attempting to write to counterPathLog file at: {}", counterPathLog);

        try (FileWriter wtgFileWriter = new FileWriter(filePath);
             FileWriter countersFileWriter = new FileWriter(counterPathLog.toString(), true)) { // true = append mode

            wtgFileWriter.write("0 0 n");
            countersFileWriter.write("Contorul fixture-ului " + fixtureFileName +
                    " a fost resetat la 0 in data de: " + java.time.LocalDate.now() +
                    " pe hostname-ul " + hostname + "\n");

        } catch (IOException e) {
            log.error("An error occurred while resetting the counter", e);
            throw new IllegalStateException("Failed to write to file: " + counterPathLog, e);
        }
    }

    @Scheduled(cron = "0 45 13 * * ?")
    public void scheduleBusinessLogic() {
        createMaintenanceFixtureReport();
    }

    @Override
    public void removeFixtureFromMachine(long fixtureId) {
        Fixture fixture = fixtureRepository.findById(fixtureId)
                .orElseThrow(() -> new IllegalArgumentException("Fixture with id " + fixtureId + " not found"));
        fixture.getMachines().clear();
        fixtureRepository.save(fixture);
    }

    // display what contors have been reset
    @Override
    public String getCounterContent() {
        try {
            Path counterPath = Paths.get(getContoareResetatePath());
            log.info("Attempting to read counter file from: {}", counterPath);

            if (!Files.exists(counterPath)) {
                log.warn("Counter file not found at path: {}", counterPath);
                return "Counter file not found at: " + counterPath;
            }

            return Files.readString(counterPath);

        } catch (IOException e) {
            log.error("Error reading counter file", e);
            throw new RuntimeException("Failed to read counter file: " + e.getMessage(), e);
        }
    }

    // gets the path where reset of the contors is written
    private String getContoareResetatePath() {
        String counterPathFromConfig = appConfigReader.getProperty("CounterLogPath");
        if (counterPathFromConfig == null) {
            throw new RuntimeException("CounterPath not found in configuration");
        }

        Path basePath = Paths.get(System.getProperty("user.dir"));
        return basePath.resolve(counterPathFromConfig.replace("\\", File.separator)).toString();
    }
}
