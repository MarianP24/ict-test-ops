package com.hella.test_ops.service.impl;

import com.hella.test_ops.entity.Fixture;
import com.hella.test_ops.entity.Machine;
import com.hella.test_ops.model.FixtureDTO;
import com.hella.test_ops.repository.FixtureRepository;
import com.hella.test_ops.repository.MachineRepository;
import com.hella.test_ops.service.FixtureService;
import com.hella.test_ops.service.MachineService;
import com.hella.test_ops.service.NetworkConnectionService;
import com.hella.test_ops.specification.FixtureSpecification;
import jakarta.annotation.PreDestroy;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;


@Slf4j
@Component
public class FixtureServiceImpl implements FixtureService {
    private final FixtureRepository fixtureRepository;
    private final MachineRepository machineRepository;
    private final NetworkConnectionService networkConnectionService;
    private final ExecutorService executorService;
    private final Map<Long, Integer> fixtureCounterTotals = new HashMap<>();
    private final AppConfigReader appConfigReader;
    private final Map<Long, Map<String, Integer>> fixtureHostnameCounters = new HashMap<>(); // Fixture ID -> (Hostname -> Counter)

    @Value("${vnc.viewer.path}")
    private String vncViewerPath;

    @Value("${network.share.username}")
    private String username;

    @Value("${network.share.password}")
    private String password;

    private final Set<String> unavailableMachines = ConcurrentHashMap.newKeySet();

    public FixtureServiceImpl(FixtureRepository fixtureRepository, MachineRepository machineRepository,
                              NetworkConnectionService networkConnectionService,
                             AppConfigReader appConfigReader) {
        this.fixtureRepository = fixtureRepository;
        this.machineRepository = machineRepository;
        this.networkConnectionService = networkConnectionService;
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
        log.info("Found {} fixtures (DTO)", fixtures.size());
        return fixtures.stream()
                .map(FixtureDTO::convertToDTO)
                .toList();
    }

    @Override
    public List<Fixture> findAllEntities() {
        List<Fixture> fixtures = fixtureRepository.findAll();
        log.info("Found {} fixtures", fixtures.size());
        return fixtures;
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

    @Transactional
    @Override
    public void deleteById(long id) {
        fixtureRepository.deleteById(id);
        log.info("Fixture with id {} has been deleted", id);
    }

    @Transactional
    @Override
    public void addFixtureToMachine(long fixtureId, long machineId) {
        Fixture fixture = fixtureRepository.findById(fixtureId)
                .orElseThrow(() -> new IllegalArgumentException("Fixture with id " + fixtureId + " not found"));
        Machine machine = machineRepository.findById(machineId)
                .orElseThrow(() -> new IllegalArgumentException("Machine with id " + machineId + " not found"));

        if (fixture.getMachines().contains(machine)) {
            log.info("Fixture {} is already associated with machine {}", fixture.getFileName(), machine.getEquipmentName());
            throw new IllegalStateException("Fixture '" + fixture.getFileName() + "' is already assigned to machine '" + machine.getEquipmentName() + "'");
        }

        fixture.getMachines().add(machine);

        fixtureRepository.save(fixture);
        log.info("Fixture {} has been added to machine {}", fixture.getFileName(), machine.getEquipmentName());
    }

    @Transactional
    @Override
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

        // Separate VPN and non-VPN machines
        Map<String, List<Fixture>> nonVpnMachines = new HashMap<>();
        Map<String, List<Fixture>> vpnMachines = new HashMap<>();

        fixturesByHostname.forEach((hostname, fixtureList) -> {
            Machine machine = machineRepository.findByHostname(hostname);
            if (machine != null && machine.getVpnServer() != null) {
                vpnMachines.put(hostname, fixtureList);
            } else {
                nonVpnMachines.put(hostname, fixtureList);
            }
        });

        log.info("Processing {} non-VPN machines in parallel", nonVpnMachines.size());
        log.info("Processing {} VPN machines sequentially", vpnMachines.size());

        // Process non-VPN machines in parallel first
        List<CompletableFuture<Void>> nonVpnFutures = nonVpnMachines.entrySet().stream()
                .map(entry -> CompletableFuture.runAsync(() ->
                        processHostnameFixtures(entry.getKey(), entry.getValue()), executorService))
                .toList();

        if (!nonVpnFutures.isEmpty()) {
            CompletableFuture.allOf(nonVpnFutures.toArray(new CompletableFuture[0])).join();
            log.info("Completed processing all non-VPN machines");
        }

        // Process VPN machines sequentially (one at a time)
        for (Map.Entry<String, List<Fixture>> vpnEntry : vpnMachines.entrySet()) {
            log.info("Processing VPN machine: {}", vpnEntry.getKey());
            processHostnameFixtures(vpnEntry.getKey(), vpnEntry.getValue());
            log.info("Completed processing VPN machine: {}", vpnEntry.getKey());
        }

        log.info("Maintenance report has been concluded for all fixtures with valid hostnames");
    }

    /**
     * Creates a maintenance report for a single fixture by processing it on all machines
     * it's connected to.
     *
     *
     * @param fixtureId The ID of the fixture to process
     * @return A summary of the processing result
     */
    @Transactional
    @Override
    public String createMaintenanceReportForSingleFixture(long fixtureId) {
        log.info("Starting maintenance processing for fixture ID: {}", fixtureId);

        // Clear counter for this specific fixture if it exists in the map
        fixtureCounterTotals.remove(fixtureId);

        // Retrieve the fixture
        Fixture fixture = fixtureRepository.findById(fixtureId)
                .orElseThrow(() -> new IllegalArgumentException("Fixture with ID " + fixtureId + " not found"));

        // Check if fixture has any machines
        if (fixture.getMachines().isEmpty()) {
            log.warn("Fixture ID {} is not associated with any machines", fixtureId);
            return "Fixture is not associated with any machines. No processing needed.";
        }

        // Group fixtures by hostname (for this single fixture)
        Map<String, List<Fixture>> fixturesByHostname = fixture.getMachines().stream()
                .filter(m -> m.getHostname() != null)
                .map(m -> new AbstractMap.SimpleEntry<>(m.getHostname(), fixture))
                .collect(Collectors.groupingBy(
                        Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));

        // Log machines with null hostnames
        long nullHostnameCount = fixture.getMachines().stream()
                .filter(m -> m.getHostname() == null)
                .count();

        if (nullHostnameCount > 0) {
            log.warn("Fixture ID {} has {} machines with null hostnames that will be skipped",
                    fixtureId, nullHostnameCount);
        }

        if (fixturesByHostname.isEmpty()) {
            log.warn("Fixture ID {} has no machines with valid hostnames", fixtureId);
            return "Fixture has no machines with valid hostnames. No processing performed.";
        }

        // Log processing information
        log.info("Processing fixture ID {} on {} unique hostnames", fixtureId, fixturesByHostname.size());
        fixturesByHostname.forEach((hostname, fixtureList) ->
                log.info("Hostname: {} has fixture {}", hostname, fixture.getFileName()));

        // Separate VPN and non-VPN machines
        Map<String, List<Fixture>> nonVpnMachines = new HashMap<>();
        Map<String, List<Fixture>> vpnMachines = new HashMap<>();

        fixturesByHostname.forEach((hostname, fixtureList) -> {
            Machine machine = machineRepository.findByHostname(hostname);
            if (machine != null && machine.getVpnServer() != null) {
                vpnMachines.put(hostname, fixtureList);
            } else {
                nonVpnMachines.put(hostname, fixtureList);
            }
        });

        log.info("Processing fixture ID {} on {} non-VPN machines in parallel", fixtureId, nonVpnMachines.size());
        log.info("Processing fixture ID {} on {} VPN machines sequentially", fixtureId, vpnMachines.size());

        // Process non-VPN machines in parallel first
        List<CompletableFuture<Void>> nonVpnFutures = nonVpnMachines.entrySet().stream()
                .map(entry -> CompletableFuture.runAsync(() ->
                        processHostnameFixtures(entry.getKey(), entry.getValue()), executorService))
                .toList();

        if (!nonVpnFutures.isEmpty()) {
            CompletableFuture.allOf(nonVpnFutures.toArray(new CompletableFuture[0])).join();
            log.info("Completed processing fixture ID {} on all non-VPN machines", fixtureId);
        }

        // Process VPN machines sequentially (one at a time)
        for (Map.Entry<String, List<Fixture>> vpnEntry : vpnMachines.entrySet()) {
            log.info("Processing fixture ID {} on VPN machine: {}", fixtureId, vpnEntry.getKey());
            processHostnameFixtures(vpnEntry.getKey(), vpnEntry.getValue());
            log.info("Completed processing fixture ID {} on VPN machine: {}", fixtureId, vpnEntry.getKey());
        }

        log.info("Maintenance report completed for fixture ID: {}", fixtureId);

        // Return a summary message
        int hostCount = fixturesByHostname.size();
        return String.format("Fixture %s (ID: %d) was processed on %d machine%s.",
                fixture.getFileName(),
                fixtureId,
                hostCount,
                hostCount == 1 ? "" : "s");
    }

    /**
     * Checks if a machine is marked as unavailable
     */
    private boolean isMachineUnavailable(String hostname) {
        return unavailableMachines.contains(hostname);
    }

    /**
     * Marks a machine as unavailable
     */
    private void markMachineUnavailable(String hostname) {
        unavailableMachines.add(hostname);
        log.warn("Machine {} marked as unavailable", hostname);
    }

    private void processHostnameFixtures(String hostname, List<Fixture> fixtures) {
        log.info("Starting to process {} fixtures for hostname {}", fixtures.size(), hostname);
        try {
            String uncBasePath = networkConnectionService.establishConnection(hostname);

            for (Fixture fixture : fixtures) {
                processSingleFixture(fixture, hostname, uncBasePath);
            }
            log.info("Completed processing all fixtures for hostname {}", hostname);
        } catch (IOException e) {
            log.error("Unable to process fixtures for hostname {}: {}. Skipping this host.",
                    hostname, e.getMessage());
        } finally {
            // Release connection asynchronously to avoid CORS timeout issues
            CompletableFuture.runAsync(() -> {
                networkConnectionService.releaseConnection(hostname);
            }, executorService);
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
                log.info("Total counter limit reached for fixture {}. Attempting to reset counters on reachable hosts only.", fixture.getFileName());

                // Reset counters only on reachable machines - skip unreachable ones
                List<String> successfulResets = new ArrayList<>();
                List<String> skippedMachines = new ArrayList<>();

                for (Map.Entry<String, Integer> entry : hostnameCounters.entrySet()) {
                    String contributingHostname = entry.getKey();

                    // Skip if machine is known to be unavailable
                    if (isMachineUnavailable(contributingHostname)) {
                        skippedMachines.add(contributingHostname);
                        log.info("Skipping counter reset for fixture {} on hostname {} - machine is unavailable",
                                fixture.getFileName(), contributingHostname);
                        continue;
                    }

                    try {
                        String hostUncPath = networkConnectionService.establishConnection(contributingHostname);
                        resetCounter(fixture.getFileName(),
                                hostUncPath + "\\" + fixture.getFileName(),
                                contributingHostname);
                        successfulResets.add(contributingHostname);
                        log.info("Counter reset for fixture {} on hostname {} due to total limit",
                                fixture.getFileName(), contributingHostname);
                        networkConnectionService.releaseConnection(contributingHostname);
                    } catch (IOException e) {
                        // Mark machine as unavailable and skip it
                        markMachineUnavailable(contributingHostname);
                        skippedMachines.add(contributingHostname);
                        log.warn("Skipping counter reset for fixture {} on hostname {} - machine became unreachable: {}",
                                fixture.getFileName(), contributingHostname, e.getMessage());
                    }
                }

                // Log summary of reset operations
                if (!successfulResets.isEmpty()) {
                    log.info("Successfully reset counters for fixture {} on hosts: {}",
                            fixture.getFileName(), successfulResets);
                }
                if (!skippedMachines.isEmpty()) {
                    log.info("Skipped counter reset for fixture {} on unavailable hosts: {}",
                            fixture.getFileName(), skippedMachines);
                }

                // Clear the counters map and update database regardless of skipped machines
                hostnameCounters.clear();
                fixture.setCounter(0);
                fixtureRepository.save(fixture);

                log.info("Fixture {} counter reset completed. Reset on {}/{} reachable hosts, skipped {} unavailable hosts.",
                        fixture.getFileName(), successfulResets.size(),
                        successfulResets.size() + skippedMachines.size(), skippedMachines.size());

            } else {
                fixture.setCounter(newTotal);
                fixtureRepository.save(fixture);
            }
        }
    }

    // reads the maintenance file
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

    @Transactional
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

    @Override
    public List<FixtureDTO> findByFilters(
            String fileName,
            String programName,
            String productName,
            String business,
            Long fixtureCounterSet) {

        List<Fixture> filteredFixtures = fixtureRepository.findAll(
                FixtureSpecification.withFilters(
                        fileName,
                        programName,
                        productName,
                        business,
                        fixtureCounterSet
                )
        );

        log.info("Found {} fixtures matching the filter criteria", filteredFixtures.size());

        return filteredFixtures.stream()
                .map(FixtureDTO::convertToDTO)
                .toList();
    }

    @Transactional
    @Override
    public Set<Machine> getFixtureMachineMap(Long fixtureId) {
        Fixture fixture = fixtureRepository.findById(fixtureId)
                .orElseThrow(() -> new IllegalArgumentException("Fixture with id " + fixtureId + " not found"));
        log.info("Retrieved {} machines for fixture with id {}", fixture.getMachines().size(), fixtureId);
        return fixture.getMachines();
    }

    @Transactional
    @Override
    public void removeFixtureFromSpecificMachine(long fixtureId, long machineId) {
        Fixture fixture = fixtureRepository.findById(fixtureId)
                .orElseThrow(() -> new IllegalArgumentException("Fixture with id " + fixtureId + " not found"));

        Machine machine = machineRepository.findById(machineId)
                .orElseThrow(() -> new IllegalArgumentException("Machine with id " + machineId + " not found"));

        if (fixture.getMachines().contains(machine)) {
            fixture.getMachines().remove(machine);
            fixtureRepository.save(fixture);
        } else {
            throw new IllegalArgumentException("Fixture is not assigned to this machine");
        }
    }

    /**
     * Connects to a machine using VNC viewer
     * @param hostname The machine hostname or IP address to connect to
     * @throws IOException if the VNC connection fails
     */
    public void connectVnc(String hostname) throws IOException {
        log.info("Attempting to connect to VNC on machine: {}", hostname);

        ProcessBuilder processBuilder = new ProcessBuilder(
                vncViewerPath,
                "-autoreconnect", "15",
                "-connect", hostname,
                "-quickoption", "7"
        );

        try {
            Process process = processBuilder.start();
            log.info("VNC connection initiated to {}, process: {}", hostname, process.pid());
        } catch (IOException e) {
            log.error("Failed to start VNC connection to {}: {}", hostname, e.getMessage());
            throw new IOException("Failed to connect to VNC on " + hostname, e);
        }
    }

    /**
     * Connects to C$ drive on a remote machine
     * @param hostname The machine hostname or IP address
     * @throws IOException if the connection fails
     */
    public void connectToCDrive(String hostname) throws IOException {
        log.info("Attempting to connect to C$ drive on machine: {}", hostname);

        String targetPath = "\\\\" + hostname + "\\C$";

        // First establish the network connection
        ProcessBuilder netUseBuilder = new ProcessBuilder(
                "cmd.exe", "/c", "net", "use",
                targetPath,
                password,
                "/user:" + username
        );
        netUseBuilder.redirectErrorStream(true);

        try {
            Process netUseProcess = netUseBuilder.start();
            boolean completed = netUseProcess.waitFor(30, TimeUnit.SECONDS);

            if (!completed) {
                netUseProcess.destroyForcibly();
                throw new IOException("Network connection to " + targetPath + " timed out");
            }

            int exitCode = netUseProcess.exitValue();
            if (exitCode != 0) {
                // Read error output
                StringBuilder errorOutput = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(netUseProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        errorOutput.append(line).append("\n");
                    }
                }
                log.error("Failed to connect to C$ drive on {}: {}", hostname, errorOutput.toString());
                throw new IOException("Failed to establish network connection to " + targetPath);
            }

            // Open the drive in Windows Explorer
            ProcessBuilder explorerBuilder = new ProcessBuilder("explorer.exe", targetPath);
            Process explorerProcess = explorerBuilder.start();

            log.info("Successfully connected and opened C$ drive on {}", hostname);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Connection to C$ drive was interrupted", e);
        }
    }

    /**
     * Connects to D$ drive on a remote machine
     * @param hostname The machine hostname or IP address
     * @throws IOException if the connection fails
     */
    public void connectToDDrive(String hostname) throws IOException {
        log.info("Attempting to connect to D$ drive on machine: {}", hostname);

        String targetPath = "\\\\" + hostname + "\\D$";

        // First establish the network connection
        ProcessBuilder netUseBuilder = new ProcessBuilder(
                "cmd.exe", "/c", "net", "use",
                targetPath,
                password,
                "/user:" + username
        );
        netUseBuilder.redirectErrorStream(true);

        try {
            Process netUseProcess = netUseBuilder.start();
            boolean completed = netUseProcess.waitFor(30, TimeUnit.SECONDS);

            if (!completed) {
                netUseProcess.destroyForcibly();
                throw new IOException("Network connection to " + targetPath + " timed out");
            }

            int exitCode = netUseProcess.exitValue();
            if (exitCode != 0) {
                // Read error output
                StringBuilder errorOutput = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(netUseProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        errorOutput.append(line).append("\n");
                    }
                }
                log.error("Failed to connect to D$ drive on {}: {}", hostname, errorOutput.toString());
                throw new IOException("Failed to establish network connection to " + targetPath);
            }

            // Open the drive in Windows Explorer
            ProcessBuilder explorerBuilder = new ProcessBuilder("explorer.exe", targetPath);
            Process explorerProcess = explorerBuilder.start();

            log.info("Successfully connected and opened D$ drive on {}", hostname);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Connection to D$ drive was interrupted", e);
        }
    }
}