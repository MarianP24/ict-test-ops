package com.hella.test_ops.service.impl;

import com.hella.test_ops.entity.Fixture;
import com.hella.test_ops.entity.Machine;
import com.hella.test_ops.entity.VpnServer;
import com.hella.test_ops.model.FixtureDTO;
import com.hella.test_ops.repository.FixtureRepository;
import com.hella.test_ops.repository.MachineRepository;
import com.hella.test_ops.service.FixtureService;
import com.hella.test_ops.service.MachineService;
import com.hella.test_ops.specification.FixtureSpecification;
import jakarta.annotation.PreDestroy;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
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

    @Value("${vnc.viewer.path}")
    private String vncViewerPath;

    @Value("${network.share.username}")
    private String username;

    @Value("${network.share.password}")
    private String password;

    @Value("${network.share.vpnUsername}")
    private String vpnUsername;

    @Value("${network.share.vpnPassword}")
    private String vpnPassword;

    @Value("${machine.access.password}")
    private String machineAccessPassword;

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

        List<CompletableFuture<Void>> futures = fixturesByHostname.entrySet().stream()
                .map(entry -> CompletableFuture.runAsync(() ->
                        processHostnameFixtures(entry.getKey(), entry.getValue()), executorService))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        log.info("Maintenance report has been concluded for all fixtures with valid hostnames");
    }

    /**
     * Creates a maintenance report for a single fixture by processing it on all machines
     * it's connected to.
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

        // Process each hostname with the fixture in parallel
        List<CompletableFuture<Void>> futures = fixturesByHostname.entrySet().stream()
                .map(entry -> CompletableFuture.runAsync(() ->
                        processHostnameFixtures(entry.getKey(), entry.getValue()), executorService))
                .toList();

        // Wait for all processing to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        log.info("Maintenance report completed for fixture ID: {}", fixtureId);

        // Return a summary message
        int hostCount = fixturesByHostname.size();
        return String.format("Fixture %s (ID: %d) was processed on %d machine%s.",
                fixture.getFileName(),
                fixtureId,
                hostCount,
                hostCount == 1 ? "" : "s");
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

    private String createTemporaryConnection(String hostname) throws IOException {
        // Keep the original hostname for database lookup
        String originalHostname = hostname;
        Machine machine = machineService.findByHostname(originalHostname);

        String equipmentType = machine.getEquipmentType();
        String configPath = "";

        if ("SEICA".equals(equipmentType)) {
            log.info("SEICA detected for hostname {}", originalHostname);
            configPath = appConfigReader.getProperty("MtSeicaPath");
        } else if ("AEROFLEX".equals(equipmentType)) {
            log.info("AEROFLEX detected for hostname {}", originalHostname);
            configPath = appConfigReader.getProperty("MtAeroflexPath");
        } else {
            log.info("Ignoring equipment of type: {}", equipmentType);
        }

        String cleanPath = configPath.replace("C:", "");

        // Determine if this machine requires VPN connection
        boolean requiresVpn = machine.getVpnServer() != null;

        // Determine the connection target (hostname or IP)
        String connectionTarget = originalHostname;

        // Connect to VPN first if needed
        if (requiresVpn) {
            boolean vpnConnected = connectVpn(originalHostname);
            if (!vpnConnected) {
                throw new IOException("Failed to establish VPN connection for " + originalHostname);
            }

            // When VPN is used, try to use an IP from the destination network instead of hostname
            VpnServer vpnServer = machine.getVpnServer();
            String destinationNetwork = vpnServer.getDestinationNetwork();

            if (destinationNetwork != null && destinationNetwork.contains("/")) {
                String targetIp = getTargetIpFromNetwork(destinationNetwork, originalHostname);
                if (targetIp != null) {
                    connectionTarget = targetIp;
                    log.info("Using IP address {} instead of hostname {} for VPN connection",
                            targetIp, originalHostname);
                }
            }
        }

        // For network share access, ALWAYS use machine-specific credentials
        String connectionUsername = machine.getMachineUsername();
        String connectionPassword = machineAccessPassword;

        // Create the UNC path with the appropriate connection target
        String uncPath = String.format("\\\\%s\\C$%s", connectionTarget, cleanPath);
        log.info("Attempting to connect using UNC path: {}", uncPath);

        // Log the network connection command that will be used (redacting password)
        String netUseCommand = String.format("net use \\\\%s\\C$ [PASSWORD] /user:%s",
                connectionTarget, connectionUsername);
        log.info("Executing connection command: {}", netUseCommand);

        // Execute the actual connection command
        ProcessBuilder processBuilder = new ProcessBuilder(
                "net", "use", String.format("\\\\%s\\C$", connectionTarget),
                connectionPassword, "/user:" + connectionUsername
        );
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                log.info("Connection output: {}", line);
            }
        }

        try {
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Connection command timed out after 30 seconds");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IOException(String.format(
                        "Failed to create temporary connection to %s. Exit code: %d, Output: %s",
                        connectionTarget, exitCode, output.toString().trim()));
            }

            log.info("Successfully connected to {}", connectionTarget);
            return uncPath;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Connection process was interrupted", e);
        }
    }

    // Helper method to get a target IP from the destination network
    private String getTargetIpFromNetwork(String destinationNetwork, String originalHostname) {
        try {
            // Parse the destination network (e.g., "10.169.5.128/26")
            String[] parts = destinationNetwork.split("/");
            String networkAddress = parts[0];
            int cidr = Integer.parseInt(parts[1]);

            // For a /26 network like 10.169.5.128/26, the range is 10.169.5.129 - 10.169.5.190
            // Let's try to map the hostname to a specific IP within this range

            // Simple approach: use the first usable IP in the range
            String[] octets = networkAddress.split("\\.");
            int lastOctet = Integer.parseInt(octets[3]);

            // For /26, we have 64 addresses, but first and last are network/broadcast
            // So usable range is network+1 to network+62
            int firstUsableIp = lastOctet + 1;

            // You could implement more sophisticated mapping here based on hostname
            // For now, let's try a few common IPs in the range
            String baseNetwork = octets[0] + "." + octets[1] + "." + octets[2] + ".";

            // Try the first few usable IPs in the range
            String targetIp = baseNetwork + firstUsableIp;

            log.info("Calculated target IP {} from network {} for hostname {}",
                    targetIp, destinationNetwork, originalHostname);

            return targetIp;

        } catch (Exception e) {
            log.warn("Failed to calculate target IP from network {}: {}", destinationNetwork, e.getMessage());
            return null;
        }
    }

    private void removeConnection(String hostname) {
        try {
            // Get machine to see if it uses VPN
            Machine machine = machineService.findByHostname(hostname);
            String connectionTarget = hostname;

            // If VPN is used, see if we need to use the IP address
            if (machine != null && machine.getVpnServer() != null &&
                    machine.getVpnServer().getDestinationNetwork() != null) {
                connectionTarget = machine.getVpnServer().getDestinationNetwork();
                log.info("Using VPN network address {} for disconnecting from {}", connectionTarget, hostname);
            }

            ProcessBuilder processBuilder = new ProcessBuilder(
                    "cmd.exe", "/c", "net", "use", "\\\\" + connectionTarget + "\\C$", "/delete", "/y");
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            // Capture output for debugging
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            // Wait for the process to complete with a timeout
            boolean completed = process.waitFor(10, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                log.error("Removing connection to {} timed out", connectionTarget);
            } else {
                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    log.error("Failed to remove connection to {}. Exit code: {}, Output: {}",
                            connectionTarget, exitCode, output.toString().trim());
                }
            }

            // Always try to disconnect VPN, even if share disconnect failed
            disconnectVpn(hostname);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Connection removal interrupted for {}", hostname, e);
        } catch (IOException e) {
            log.error("Error removing connection to {}", hostname, e);
        }
    }

    // Check if a VPN profile with the given name exists and return its details
    private String checkVpnProfileDetails(String vpnName) throws IOException, InterruptedException {
        ProcessBuilder checkVpnBuilder = new ProcessBuilder(
                "powershell.exe", "-Command",
                "Get-VpnConnection -Name '" + vpnName + "' -ErrorAction SilentlyContinue"
        );
        checkVpnBuilder.redirectErrorStream(true);
        Process checkProcess = checkVpnBuilder.start();

        StringBuilder checkOutput = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(checkProcess.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                checkOutput.append(line).append("\n");
            }
        }

        boolean checkCompleted = checkProcess.waitFor(5, TimeUnit.SECONDS);
        String profileDetails = checkOutput.toString().trim();

        log.info("VPN profile check result: {}", profileDetails);

        // Return empty string if profile doesn't exist, otherwise return the details
        if (!checkCompleted || checkProcess.exitValue() != 0 || profileDetails.isEmpty()) {
            return "";
        }
        return profileDetails;
    }


    private boolean connectVpn(String hostname) throws IOException {
        log.info("Checking if VPN connection is needed for hostname: {}", hostname);

        // Check if the machine has a VPN connection configured
        Machine machine = machineService.findByHostname(hostname);
        if (machine == null) {
            log.error("Cannot find machine with hostname: {}", hostname);
            return false;
        }

        // Check if machine has VPN server configuration
        VpnServer vpnServer = machine.getVpnServer();
        if (vpnServer == null) {
            log.debug("No VPN configuration for hostname: {}", hostname);
            return true; // No VPN needed, continue with direct connection
        }

        String vpnName = vpnServer.getVpnName();
        String serverAddress = vpnServer.getServerAddress();
        String destinationNetwork = vpnServer.getDestinationNetwork();

        log.info("VPN configuration found for hostname {}: server={}, network={}",
                hostname, serverAddress, destinationNetwork);

        try {
            // Check if VPN is already connected with the correct settings
            String profileDetails = checkVpnProfileDetails(vpnName);

            // If already connected with PAP, check and add routing if needed
            if (profileDetails.contains("ConnectionStatus      : Connected") &&
                    profileDetails.contains("AuthenticationMethod  : {Pap}")) {
                log.info("VPN is already connected with PAP authentication, checking routing");
                addRouteForDestinationNetwork(vpnName, destinationNetwork);
                return true;
            }

            // If connected but not with PAP, disconnect first
            if (profileDetails.contains("ConnectionStatus      : Connected")) {
                disconnectVpn(hostname);
                // Wait for disconnection to complete
                Thread.sleep(2000);
            }

            // If profile exists, check if it uses PAP
            boolean needNewProfile = profileDetails.isEmpty() ||
                    !profileDetails.contains("AuthenticationMethod  : {Pap}");

            // Only recreate the profile if needed
            if (needNewProfile) {
                // Remove existing profile if any
                if (!profileDetails.isEmpty()) {
                    removeVpnProfile(vpnName);
                    Thread.sleep(1000); // Wait a bit after removal
                }

                // Create new profile with PAP
                createVpnProfile(vpnName, serverAddress);
            }

            // Try to connect with the VPN profile
            boolean connected = connectToVpn(vpnName);

            // Add routing after successful connection
            if (connected) {
                Thread.sleep(3000); // Wait for VPN to stabilize
                addRouteForDestinationNetwork(vpnName, destinationNetwork);
            }

            return connected;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("VPN connection process was interrupted", e);
            throw new IOException("VPN connection was interrupted", e);
        }
    }

    // Add this new method to handle routing
    private void addRouteForDestinationNetwork(String vpnName, String destinationNetwork) {
        if (destinationNetwork == null || !destinationNetwork.contains("/")) {
            log.warn("Invalid destination network format: {}", destinationNetwork);
            return;
        }

        try {
            log.info("Adding route for network: {} through VPN: {}", destinationNetwork, vpnName);

            // Use PowerShell New-NetRoute which might not require explicit elevation
            String powerShellCommand = String.format(
                    "New-NetRoute -DestinationPrefix '%s' -InterfaceAlias '%s' -PolicyStore ActiveStore -ErrorAction SilentlyContinue",
                    destinationNetwork, vpnName
            );

            ProcessBuilder addRouteBuilder = new ProcessBuilder("powershell.exe", "-Command", powerShellCommand);
            Process addRouteProcess = addRouteBuilder.start();

            String routeOutput = new String(addRouteProcess.getInputStream().readAllBytes());
            String errorOutput = new String(addRouteProcess.getErrorStream().readAllBytes());

            int exitCode = addRouteProcess.waitFor();
            if (exitCode == 0) {
                log.info("Successfully added route for {} through VPN {}: {}",
                        destinationNetwork, vpnName, routeOutput.trim());
            } else {
                log.warn("Failed to add route. Exit code: {}, Output: {}, Error: {}",
                        exitCode, routeOutput.trim(), errorOutput.trim());

                // Fallback: Try to add the route using Add-VpnConnectionRoute
                tryAddVpnConnectionRoute(vpnName, destinationNetwork);
            }

        } catch (Exception e) {
            log.error("Error adding route for destination network {}: {}", destinationNetwork, e.getMessage());
        }
    }

    // Fallback method using VPN-specific route addition
    private void tryAddVpnConnectionRoute(String vpnName, String destinationNetwork) {
        try {
            log.info("Trying VPN connection route method for {}", destinationNetwork);

            String powerShellCommand = String.format(
                    "Add-VpnConnectionRoute -ConnectionName '%s' -DestinationPrefix '%s' -ErrorAction SilentlyContinue",
                    vpnName, destinationNetwork
            );

            ProcessBuilder addVpnRouteBuilder = new ProcessBuilder("powershell.exe", "-Command", powerShellCommand);
            Process addVpnRouteProcess = addVpnRouteBuilder.start();

            String vpnRouteOutput = new String(addVpnRouteProcess.getInputStream().readAllBytes());
            String vpnErrorOutput = new String(addVpnRouteProcess.getErrorStream().readAllBytes());

            int vpnExitCode = addVpnRouteProcess.waitFor();
            if (vpnExitCode == 0) {
                log.info("Successfully added VPN route for {}: {}", destinationNetwork, vpnRouteOutput.trim());
            } else {
                log.warn("VPN route addition also failed. Exit code: {}, Output: {}, Error: {}",
                        vpnExitCode, vpnRouteOutput.trim(), vpnErrorOutput.trim());
            }

        } catch (Exception e) {
            log.error("Error with VPN route fallback: {}", e.getMessage());
        }
    }

    // Remove an existing VPN profile
    private void removeVpnProfile(String vpnName) throws IOException, InterruptedException {
        log.info("Removing existing VPN profile: {}", vpnName);
        ProcessBuilder removeBuilder = new ProcessBuilder(
                "powershell.exe", "-Command",
                "Remove-VpnConnection -Name '" + vpnName + "' -Force -ErrorAction SilentlyContinue"
        );
        removeBuilder.redirectErrorStream(true);
        Process removeProcess = removeBuilder.start();

        StringBuilder removeOutput = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(removeProcess.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                removeOutput.append(line).append("\n");
            }
        }

        boolean removeCompleted = removeProcess.waitFor(10, TimeUnit.SECONDS);
        log.info("VPN profile removal result: {}", removeOutput.toString().trim());
    }

    // Create a new VPN profile with PAP authentication
    private void createVpnProfile(String vpnName, String serverAddress)
            throws IOException, InterruptedException {

        log.info("Creating VPN profile with PAP authentication: {}", vpnName);
        ProcessBuilder createVpnBuilder = new ProcessBuilder(
                "powershell.exe", "-Command",
                "Add-VpnConnection -Name '" + vpnName + "' " +
                        "-ServerAddress '" + serverAddress + "' " +
                        "-TunnelType L2tp " +
                        "-EncryptionLevel Optional " +
                        "-RememberCredential:$false " +
                        "-SplitTunneling:$false " +
                        "-L2tpPsk 'Suahwere' " +
                        "-AuthenticationMethod Pap " +
                        "-UseWinlogonCredential:$false " +
                        "-Force"
        );
        createVpnBuilder.redirectErrorStream(true);
        Process createProcess = createVpnBuilder.start();

        StringBuilder createOutput = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(createProcess.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                createOutput.append(line).append("\n");
            }
        }

        boolean createCompleted = createProcess.waitFor(10, TimeUnit.SECONDS);
        log.info("VPN profile creation result: {}", createOutput.toString().trim());

        if (!createCompleted || createProcess.exitValue() != 0) {
            throw new IOException("Failed to create VPN profile: " + vpnName);
        }

        log.info("VPN profile created successfully with PAP authentication: {}", vpnName);
    }

    // Try to connect to the VPN using the profile
    private boolean connectToVpn(String vpnName) throws IOException, InterruptedException {
        log.info("Connecting to VPN: {}", vpnName);
        String rasdialPath = System.getenv("WINDIR") + "\\System32\\rasdial.exe";

        // Try with regular username first
        boolean connected = tryRasdialConnect(rasdialPath, vpnName, vpnUsername, vpnPassword);
        if (connected) {
            return true;
        }

        return false;
    }

    // Helper method to try a VPN connection with specific credentials
    private boolean tryRasdialConnect(String rasdialPath, String vpnName, String vpnUsername, String vpnPassword)
            throws IOException, InterruptedException {

        // Log the actual username (without logging the password)
        log.info("Attempting VPN connection with username: {}", vpnUsername);

        // For security, redact password in logs but log username length for troubleshooting
        log.debug("Password length: {}", vpnPassword != null ? vpnPassword.length() : 0);

        ProcessBuilder connectBuilder = new ProcessBuilder(
                rasdialPath, vpnName, vpnUsername, vpnPassword
        );
        connectBuilder.redirectErrorStream(true);
        Process connectProcess = connectBuilder.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connectProcess.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                log.info("RasDial output: {}", line);
            }
        }

        boolean completed = connectProcess.waitFor(15, TimeUnit.SECONDS);
        if (!completed) {
            connectProcess.destroyForcibly();
            log.error("VPN connection attempt timed out");
            return false;
        }

        int exitCode = connectProcess.exitValue();
        String outputStr = output.toString().trim();

        if (exitCode != 0) {
            log.error("VPN connection failed. Exit code: {}, Output: {}", exitCode, outputStr);
            log.error("Failed credentials - Username: {}, Password: [REDACTED]", vpnUsername);
            return false;
        }

        log.info("Successfully connected to VPN using username: {}", vpnUsername);
        return true;
    }

    /**
     * Disconnects from the VPN if it was established for the given hostname.
     *
     * @param hostname The hostname to check for VPN requirements
     */
    private void disconnectVpn(String hostname) {
        try {
            // Check if the machine has a VPN configuration
            Machine machine = machineService.findByHostname(hostname);
            if (machine == null || machine.getVpnServer() == null) {
                return; // No VPN to disconnect
            }

            String vpnName = machine.getVpnServer().getVpnName();
            log.info("Disconnecting from VPN: {}", vpnName);

            // 6. Disconnect from VPN using rasdial
            String rasdialPath = System.getenv("WINDIR") + "\\System32\\rasdial.exe";
            ProcessBuilder disconnectBuilder = new ProcessBuilder(
                    rasdialPath, vpnName, "/DISCONNECT"
            );
            Process disconnectProcess = disconnectBuilder.start();

            boolean completed = disconnectProcess.waitFor(5, TimeUnit.SECONDS);
            if (!completed) {
                disconnectProcess.destroyForcibly();
                log.warn("VPN disconnection timed out");
            } else if (disconnectProcess.exitValue() != 0) {
                log.warn("VPN disconnection returned non-zero exit code: {}", disconnectProcess.exitValue());
            } else {
                log.info("Successfully disconnected from VPN: {}", vpnName);
            }
        } catch (Exception e) {
            log.error("Error disconnecting from VPN: {}", e.getMessage());
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
