package com.hella.test_ops.service.impl;

import com.hella.test_ops.entity.Fixture;
import com.hella.test_ops.entity.Machine;
import com.hella.test_ops.entity.VpnServer;
import com.hella.test_ops.service.MachineService;
import com.hella.test_ops.service.NetworkConnectionService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Implementation of NetworkConnectionService that manages network connections
 * to remote machines with connection pooling, synchronization, and error handling.
 * Uses reference counting to prevent premature connection cleanup.
 */

@Slf4j
@Service
public class NetworkConnectionServiceImpl implements NetworkConnectionService {

    private final MachineService machineService;
    private final AppConfigReader appConfigReader;
    private final ExecutorService executorService;

    // Connection management
    private final ConcurrentHashMap<String, String> activeConnections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> connectionTimestamps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> connectionLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> connectionReferences = new ConcurrentHashMap<>();

    @Value("${network.share.vpnUsername}")
    private String vpnUsername;

    @Value("${network.share.vpnPassword}")
    private String vpnPassword;

    @Value("${machine.access.password}")
    private String machineAccessPassword;

    // Constants
    private static final String POWERSHELL_EXE = "powershell.exe";
    private static final String POWERSHELL_COMMAND_FLAG = "-Command";
    private static final long CONNECTION_TIMEOUT = 300000; // 5 minutes

    public NetworkConnectionServiceImpl(MachineService machineService, AppConfigReader appConfigReader) {
        this.machineService = machineService;
        this.appConfigReader = appConfigReader;
        this.executorService = Executors.newFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors() / 2)
        );
    }

    @PreDestroy
    public void shutdown() {
        cleanupAllConnections();
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
    public String establishConnection(String hostname) throws IOException {
        // Get or create the lock for this hostname
        Object lock = connectionLocks.computeIfAbsent(hostname, k -> new Object());

        synchronized (lock) {
            // Check if the connection already exists and is still valid
            String existingConnection = activeConnections.get(hostname);
            if (existingConnection != null && isConnectionValid(hostname)) {
                // Increment reference count
                AtomicInteger refCount = connectionReferences.computeIfAbsent(hostname, k -> new AtomicInteger(0));
                int currentRefs = refCount.incrementAndGet();
                log.debug("Reusing existing connection to {} (refs: {})", hostname, currentRefs);

                // Update timestamp for active connection
                connectionTimestamps.put(hostname, System.currentTimeMillis());
                return existingConnection;
            }

            // Remove any stale connection
            if (existingConnection != null) {
                log.info("Removing stale connection to {}", hostname);
                forceCleanupConnectionInternal(hostname);
            }

            // Establish the new connection
            String newConnection = establishNewConnection(hostname);

            // Set the initial reference count to 1
            connectionReferences.put(hostname, new AtomicInteger(1));
            log.debug("New connection established to {} (refs: 1)", hostname);

            return newConnection;
        }
    }

    @Override
    public void releaseConnection(String hostname) {
        Object lock = connectionLocks.get(hostname);
        if (lock == null) {
            log.warn("No lock found for hostname: {}", hostname);
            return;
        }

        synchronized (lock) {
            AtomicInteger refCount = connectionReferences.get(hostname);
            if (refCount == null) {
                log.warn("No reference count found for hostname: {}", hostname);
                return;
            }

            int currentRefs = refCount.decrementAndGet();
            log.debug("Released reference for connection to {} (refs: {})", hostname, currentRefs);

            if (currentRefs <= 0) {
                // Only cleanup when no more references
                String connection = activeConnections.remove(hostname);
                connectionTimestamps.remove(hostname);
                connectionReferences.remove(hostname);

                if (connection != null) {
                    log.info("Releasing connection to {} (all references released)", hostname);
                    performConnectionCleanup(hostname);
                }
            } else {
                log.debug("Connection to {} still has {} active references", hostname, currentRefs);
            }
        }
    }

    @Override
    public boolean isConnectionActive(String hostname) {
        AtomicInteger refCount = connectionReferences.get(hostname);
        return refCount != null && refCount.get() > 0 &&
                activeConnections.containsKey(hostname) &&
                isConnectionValid(hostname);
    }

    @Override
    public void cleanupAllConnections() {
        log.info("Cleaning up all active connections");

        // Create a copy of hostnames to avoid ConcurrentModificationException
        activeConnections.keySet().forEach(hostname -> {
            Object lock = connectionLocks.get(hostname);
            if (lock != null) {
                synchronized (lock) {
                    // Force cleanup regardless of reference count
                    log.info("Force cleaning up connection to {} during shutdown", hostname);
                    forceCleanupConnectionInternal(hostname);
                }
            }
        });

        // Clear all tracking maps
        activeConnections.clear();
        connectionTimestamps.clear();
        connectionReferences.clear();
    }

    @Override
    @Scheduled(fixedRate = 300000) // Every 5 minutes
    public void cleanupStaleConnections() {
        long currentTime = System.currentTimeMillis();
        List<String> toRemove = new ArrayList<>();
        
        // First pass: identify potentially stale connections
        connectionTimestamps.forEach((hostname, timestamp) -> {
            if (currentTime - timestamp > CONNECTION_TIMEOUT) {
                toRemove.add(hostname);
            }
        });
        
        // Early exit if no stale connections found
        if (toRemove.isEmpty()) {
            log.debug("No stale connections found during cleanup");
            return;
        }
        
        log.debug("Found {} potentially stale connections to cleanup", toRemove.size());
        
        // Second pass: cleanup with double-check for race condition protection
        toRemove.forEach(hostname -> {
            Object lock = connectionLocks.get(hostname);
            if (lock != null) {
                synchronized (lock) {
                    // Double-check if still stale (prevents race conditions)
                    Long currentTimestamp = connectionTimestamps.get(hostname);
                    if (currentTimestamp != null && 
                        currentTime - currentTimestamp > CONNECTION_TIMEOUT) {
                        
                        AtomicInteger refCount = connectionReferences.get(hostname);
                        log.info("Force cleaning up stale connection to {} due to timeout (refs: {})",
                                hostname, refCount != null ? refCount.get() : 0);
                        
                        forceCleanupConnectionInternal(hostname);
                        activeConnections.remove(hostname);
                        connectionReferences.remove(hostname);
                        connectionTimestamps.remove(hostname);
                    }
                }
            }
        });
    }

    // new method
    private String establishNewConnection(String hostname) throws IOException {
        try {
            log.info("Attempting to establish connection to {}", hostname);

            if (!isHostReachable(hostname)) {
                throw new IOException("Host " + hostname + " is not reachable");
            }

            // **OPTIMIZED**: Only do cleanup if we detect Windows Error 1219
            String uncPath = createConnectionWithErrorHandling(hostname);

            // Store successful connection
            activeConnections.put(hostname, uncPath);
            connectionTimestamps.put(hostname, System.currentTimeMillis());

            log.info("Successfully established connection to {}: {}", hostname, uncPath);
            return uncPath;

        } catch (IOException e) {
            log.warn("Connection failed for {}: {}", hostname, e.getMessage());
            throw e;
        }
    }

    /**
     * Creates a connection with intelligent error handling for Windows Error 1219
     */
    // new method
    private String createConnectionWithErrorHandling(String hostname) throws IOException {
        try {
            return createConnection(hostname);
        } catch (IOException e) {
            if (e.getMessage().contains("1219") || e.getMessage().contains("multiple connections")) {
                log.info("Windows Error 1219 detected for {}. Cleaning up existing connections.", hostname);
                forceDisconnectAllConnectionsToHost(hostname);

                // Brief pause to allow cleanup to complete
                try {
                    TimeUnit.SECONDS.sleep(1); // More semantic than MILLISECONDS.sleep(1000)
                    return createConnection(hostname);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Connection retry was interrupted", ex);
                }
            }
            throw e;
        }
    }

    private String createConnection(String hostname) throws IOException {
        Machine machine = machineService.findByHostname(hostname);
        if (machine == null) {
            throw new IOException("Machine not found: " + hostname);
        }

        String equipmentType = machine.getEquipmentType();
        String configPath = getConfigPath(equipmentType);
        String cleanPath = configPath.replace("C:", "");

        // Determine connection parameters
        boolean requiresVpn = machine.getVpnServer() != null;
        String connectionTarget = hostname;

        // Handle VPN connection
        if (requiresVpn) {
            boolean vpnConnected = connectVpn(hostname);
            if (!vpnConnected) {
                throw new IOException("Failed to establish VPN connection for " + hostname);
            }

            // Use IP from the destination network if available
            VpnServer vpnServer = machine.getVpnServer();
            String destinationNetwork = vpnServer.getDestinationNetwork();
            if (destinationNetwork != null && destinationNetwork.contains("/")) {
                String targetIp = getTargetIpFromNetwork(destinationNetwork, hostname);
                if (targetIp != null) {
                    connectionTarget = targetIp;
                    log.info("Using IP address {} for VPN connection to {}", targetIp, hostname);
                }
            }
        }

        // Use machine-specific credentials
        String connectionUsername = machine.getMachineUsername();
        String connectionPassword = machineAccessPassword;

        String uncPath = String.format("\\\\%s\\C$%s", connectionTarget, cleanPath);
        log.info("Creating connection to UNC path: {}", uncPath);

        // Create the connection
        if (requiresVpn) {
            return createConnectionWithRunAsNetOnly(connectionTarget, connectionUsername, connectionPassword, uncPath);
        } else {
            return createDirectConnection(connectionTarget, connectionUsername, connectionPassword, uncPath);
        }
    }

    private String getConfigPath(String equipmentType) {
        if ("SEICA".equals(equipmentType)) {
            return appConfigReader.getProperty("MtSeicaPath");
        } else if ("AEROFLEX".equals(equipmentType)) {
            return appConfigReader.getProperty("MtAeroflexPath");
        }
        return "";
    }

    private boolean isConnectionValid(String hostname) {
        Long timestamp = connectionTimestamps.get(hostname);
        if (timestamp == null) {
            return false;
        }

        // Check if the connection has timed out
        if (System.currentTimeMillis() - timestamp > CONNECTION_TIMEOUT) {
            return false;
        }

        // Test connection by pinging
        return isHostReachable(hostname);
    }

    /**
     * Internal method for force cleanup without reference counting
     */
    private void forceCleanupConnectionInternal(String hostname) {
        try {
            performConnectionCleanup(hostname);
        } catch (Exception e) {
            log.warn("Error during force cleanup of connection to {}: {}", hostname, e.getMessage());
        }
    }

    private void forceDisconnectAllConnectionsToHost(String hostname) {
        try {
            log.info("Force disconnecting application-created connections to {}", hostname);

            // Only disconnect the specific C$ administrative share that our application creates
            String targetPath = "\\\\" + hostname + "\\C$";
            
            boolean success = executeNetworkDisconnect(targetPath);
            if (success) {
                log.info("Application-specific connection cleanup completed for {}", hostname);
            } else {
                log.warn("Application-specific connection cleanup failed for {}", hostname);
            }

        } catch (Exception e) {
            log.warn("Error during force disconnect of connections to {}: {}", hostname, e.getMessage());
        }
    }

    private boolean executeNetworkDisconnect(String targetPath) {
        Process disconnectProcess = null;
        
        try {
            ProcessBuilder disconnectBuilder = new ProcessBuilder("net", "use", targetPath, "/delete", "/y");
            disconnectBuilder.redirectErrorStream(true);
            disconnectProcess = disconnectBuilder.start();
            
            StringBuilder output = readProcessOutput(disconnectProcess);
            
            boolean completed = disconnectProcess.waitFor(10, TimeUnit.SECONDS);
            if (!completed) {
                disconnectProcess.destroyForcibly();
                log.warn("Disconnection of {} timed out", targetPath);
                return false;
            }
            
            return handleDisconnectResult(targetPath, disconnectProcess, output);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Disconnect process for {} was interrupted, forcing termination", targetPath);
            disconnectProcess.destroyForcibly();
            return false;
            
        } catch (IOException e) {
            log.warn("Failed to disconnect {}: {}", targetPath, e.getMessage());
            return false;
        }
    }

    private StringBuilder readProcessOutput(Process process) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        return output;
    }

    private boolean handleDisconnectResult(String targetPath, Process process, StringBuilder output) {
        int exitCode = process.exitValue();
        if (exitCode == 0) {
            log.info("Successfully disconnected application connection: {}", targetPath);
            return true;
        } else {
            log.debug("Disconnect result for {}: exit code {}, output: {}", 
                    targetPath, exitCode, output.toString().trim());
            return false;
        }
    }

    private void performConnectionCleanup(String hostname) {
        try {
            log.info("Performing connection cleanup for {}", hostname);
            
            // Clean up application-specific network connections first
            forceDisconnectAllConnectionsToHost(hostname);

            // Disconnect VPN if needed (do this last to avoid network disruption)
            Machine machine = machineService.findByHostname(hostname);
            if (machine != null && machine.getVpnServer() != null) {
                disconnectVpn(hostname);
            }

            log.info("Connection cleanup completed for {}", hostname);

        } catch (Exception e) {
            log.warn("Error during connection cleanup for {}: {}", hostname, e.getMessage());
        }
    }

    // checked with older code
    private boolean isHostReachable(String hostname) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("ping", "-n", "1", hostname);
            Process process = processBuilder.start();

            // Add timeout to prevent hanging indefinitely
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Host reachability check for {} timed out", hostname);
                return false;
            }

            return process.exitValue() == 0;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Host reachability check for {} was interrupted", hostname);
            return false;
        } catch (Exception e) {
            log.warn("Error checking host reachability for {}: {}", hostname, e.getMessage());
            return false;
        }
    }

    // checked with older code

    private boolean connectVpn(String hostname) throws IOException {
        log.info("Checking if VPN connection is needed for hostname: {}", hostname);

        VpnConnectionContext context = validateAndGetVpnContext(hostname);
        if (context == null) {
            return false;
        }

        if (context.vpnServer == null) {
            log.debug("No VPN configuration for hostname: {}", hostname);
            return true;
        }

        log.info("VPN configuration found for hostname {}: server={}, network={}",
                hostname, context.vpnServer.getServerAddress(), context.vpnServer.getDestinationNetwork());

        try {
            return establishVpnConnection(context);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("VPN connection process was interrupted", e);
            throw new IOException("VPN connection was interrupted", e);
        }
    }

    private VpnConnectionContext validateAndGetVpnContext(String hostname) {
        Machine machine = machineService.findByHostname(hostname);
        if (machine == null) {
            log.error("Cannot find machine with hostname: {}", hostname);
            return null;
        }
        return new VpnConnectionContext(machine, machine.getVpnServer());
    }

    private boolean establishVpnConnection(VpnConnectionContext context) throws InterruptedException, IOException {
        String vpnName = context.vpnServer.getVpnName();
        String serverAddress = context.vpnServer.getServerAddress();
        String destinationNetwork = context.vpnServer.getDestinationNetwork();

        String profileDetails = checkVpnProfileDetails(vpnName);

        if (isVpnAlreadyConnectedWithPap(profileDetails)) {
            log.info("VPN is already connected with PAP authentication, skipping setup");
            return true;
        }

        handleExistingConnection(profileDetails, context);
        ensureVpnProfileExists(profileDetails, vpnName, serverAddress);

        boolean connected = connectToVpn(vpnName);
        if (connected) {
            waitAndAddRouting(vpnName, destinationNetwork);
        }

        return connected;
    }

    private boolean isVpnAlreadyConnectedWithPap(String profileDetails) {
        return profileDetails.contains("ConnectionStatus      : Connected") &&
                profileDetails.contains("AuthenticationMethod  : {Pap}");
    }

    private void handleExistingConnection(String profileDetails, VpnConnectionContext context) {
        if (profileDetails.contains("ConnectionStatus      : Connected")) {
            disconnectVpn(context.machine.getHostname()); // Fixed: use hostname, not vpnName
            waitNonBlocking(2);
        }
    }

    private void ensureVpnProfileExists(String profileDetails, String vpnName, String serverAddress)
            throws InterruptedException, IOException {
        boolean needNewProfile = !profileDetails.contains("AuthenticationMethod  : {Pap}");

        if (needNewProfile) {
            if (!profileDetails.isEmpty()) {
                removeVpnProfile(vpnName);
                waitNonBlocking(1);
            }
            createVpnProfile(vpnName, serverAddress);
        }
    }

    private void waitAndAddRouting(String vpnName, String destinationNetwork) {
        waitNonBlocking(3);
        addRouteForDestinationNetwork(vpnName, destinationNetwork);
    }

    private void waitNonBlocking(int seconds) {
        try {
            CompletableFuture.runAsync(() -> {}).get(seconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Continue execution after timeout
        }
    }

    private static class VpnConnectionContext {
        final Machine machine;
        final VpnServer vpnServer;

        VpnConnectionContext(Machine machine, VpnServer vpnServer) {
            this.machine = machine;
            this.vpnServer = vpnServer;
        }
    }


    private void disconnectVpn(String hostname) {
        try {
            Machine machine = machineService.findByHostname(hostname);
            if (machine == null || machine.getVpnServer() == null) {
                return;
            }

            String vpnName = machine.getVpnServer().getVpnName();
            log.info("Disconnecting from VPN: {}", vpnName);

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

    // Check if a VPN profile with the given name exists and return its details
    // checked with older code
    private String checkVpnProfileDetails(String vpnName) throws IOException, InterruptedException {
        ProcessBuilder checkVpnBuilder = new ProcessBuilder(
                POWERSHELL_EXE, POWERSHELL_COMMAND_FLAG,
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

        if (!checkCompleted || checkProcess.exitValue() != 0 || profileDetails.isEmpty()) {
            return "";
        }
        return profileDetails;
    }

    // Remove an existing VPN profile
    // checked with older code
    private void removeVpnProfile(String vpnName) throws IOException, InterruptedException {
        log.info("Removing existing VPN profile: {}", vpnName);
        ProcessBuilder removeBuilder = new ProcessBuilder(
                POWERSHELL_EXE, POWERSHELL_COMMAND_FLAG,
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

        removeProcess.waitFor(10, TimeUnit.SECONDS);
        log.info("VPN profile removal result: {}", removeOutput.toString().trim());
    }

    // Create a new VPN profile with PAP authentication
    // checked with older code
    private void createVpnProfile(String vpnName, String serverAddress) throws IOException, InterruptedException {
        log.info("Creating VPN profile with PAP authentication: {}", vpnName);
        ProcessBuilder createVpnBuilder = new ProcessBuilder(
                POWERSHELL_EXE, POWERSHELL_COMMAND_FLAG,
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
    // checked with older code
    private boolean connectToVpn(String vpnName) throws IOException, InterruptedException {
        log.info("Connecting to VPN: {}", vpnName);
        String rasdialPath = System.getenv("WINDIR") + "\\System32\\rasdial.exe";

        return tryRasdialConnect(rasdialPath, vpnName, vpnUsername, vpnPassword);
    }

    // Helper method to try a VPN connection with specific credentials
    // checked with older code
    private boolean tryRasdialConnect(String rasdialPath, String vpnName, String vpnUsername, String vpnPassword)
            throws IOException, InterruptedException {

        log.info("Attempting VPN connection with username: {}", vpnUsername);

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

    // Helper method to get a target IP from the destination network
    // checked with older code
    private String getTargetIpFromNetwork(String destinationNetwork, String originalHostname) {
        try {
            // Parse the destination network (e.g., "10.169.5.128/26")
            String[] parts = destinationNetwork.split("/");
            String networkAddress = parts[0];

            String[] octets = networkAddress.split("\\.");
            int lastOctet = Integer.parseInt(octets[3]);
            int firstUsableIp = lastOctet + 1;

            String baseNetwork = octets[0] + "." + octets[1] + "." + octets[2] + ".";
            String targetIp = baseNetwork + firstUsableIp;

            log.info("Calculated target IP {} from network {} for hostname {}",
                    targetIp, destinationNetwork, originalHostname);

            return targetIp;

        } catch (Exception e) {
            log.warn("Failed to calculate target IP from network {}: {}", destinationNetwork, e.getMessage());
            return null;
        }
    }

    // checked with older code
    private String createDirectConnection(String connectionTarget, String connectionUsername,
                                          String connectionPassword, String uncPath) throws IOException {
        try {
            log.info("Creating direct connection for user: {}", connectionUsername);

            // Check if the host is reachable first
            if (!isHostReachable(connectionTarget)) {
                throw new IOException(String.format("Host %s is not reachable (machine may be shut down or network issue)", connectionTarget));
            }

            ProcessBuilder processBuilder = new ProcessBuilder(
                    "net", "use", uncPath,
                    "/user:" + connectionUsername, connectionPassword, "/persistent:no"
            );

            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.info("Successfully connected to {}", connectionTarget);
                return uncPath;
            } else {
                String errorOutput = output.toString();
                // Check for specific Windows Error 1219
                if (errorOutput.contains("1219")) {
                    throw new IOException("Windows Error 1219: Multiple connections to server or shared resource. " + errorOutput);
                }
                throw new IOException("Failed to connect to " + connectionTarget + ": " + errorOutput);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Connection interrupted", e);
        }
    }

    // checked with older code
    private String createConnectionWithRunAsNetOnly(String connectionTarget, String connectionUsername,
                                                   String connectionPassword, String uncPath) throws IOException {

        log.info("Using runas /netonly to create separate credential context for VPN connection");

        // Create a temporary batch file to handle the credentials securely
        String tempDir = System.getProperty("java.io.tmpdir");
        String batchFileName = "temp_connect_" + System.currentTimeMillis() + ".bat";
        Path batchFile = Paths.get(tempDir, batchFileName);

        try {
            // Create batch file content
            String batchContent = String.format(
                    """
                            @echo off
                            net use "\\\\%s\\C$" "%s" /user:"%s" /persistent:no
                            echo Connection completed with exit code %%ERRORLEVEL%%
                            """,
                    connectionTarget, connectionPassword, connectionUsername
            );

            Files.write(batchFile, batchContent.getBytes());

            log.info("Executing connection with separate credential context for user: {}", connectionUsername);

            // Execute using runas /netonly
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "runas",
                    "/netonly",
                    "/user:" + connectionUsername,
                    "cmd.exe /c \"" + batchFile + "\""
            );

            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            // Provide password to runas
            try (PrintWriter writer = new PrintWriter(process.getOutputStream())) {
                writer.println(connectionPassword);
                writer.flush();
            }

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    log.info("RunAs output: {}", line);
                }
            }

            try {
                boolean finished = process.waitFor(45, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    throw new IOException("RunAs connection command timed out after 45 seconds");
                }

                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    throw new IOException(String.format(
                            "Failed to create connection to %s using RunAs. Exit code: %d, Output: %s",
                            connectionTarget, exitCode, output.toString().trim()));
                }

                log.info("Successfully connected to {} using RunAs /netonly", connectionTarget);
                return uncPath;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("RunAs connection process was interrupted", e);
            }

        } finally {
            // Clean up the temporary batch file
            try {
                Files.deleteIfExists(batchFile);
            } catch (IOException e) {
                log.warn("Could not delete temporary batch file: {}", batchFile, e);
            }
        }
    }

    // checked with older code
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

            ProcessBuilder addRouteBuilder = new ProcessBuilder(POWERSHELL_EXE, POWERSHELL_COMMAND_FLAG, powerShellCommand);
            Process addRouteProcess = addRouteBuilder.start();

            String routeOutput = new String(addRouteProcess.getInputStream().readAllBytes());
            String errorOutput = new String(addRouteProcess.getErrorStream().readAllBytes());

            int exitCode = addRouteProcess.waitFor();
            if (exitCode == 0) {
                log.info("Successfully added route for {} through VPN {}", destinationNetwork, vpnName);
            } else {
                log.warn("Failed to add route. Exit code: {}, Output: {}, Error: {}",
                        exitCode, routeOutput.trim(), errorOutput.trim());
            }

        } catch (Exception e) {
            log.error("Error adding route for destination network {}: {}", destinationNetwork, e.getMessage());
        }
    }

    @Override
    public void processFilesWithConnection(String hostname, List<Fixture> fixtures,
                                           BiConsumer<Fixture, String> fileProcessor) throws IOException {
        String connection = establishConnection(hostname);
        try {
            for (Fixture fixture : fixtures) {
                fileProcessor.accept(fixture, connection);
            }
        } finally {
            releaseConnection(hostname);
        }
    }
}