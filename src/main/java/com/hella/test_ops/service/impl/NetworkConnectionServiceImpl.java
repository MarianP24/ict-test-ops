package com.hella.test_ops.service.impl;

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
import java.util.concurrent.*;

/**
 * Implementation of NetworkConnectionService that manages network connections
 * to remote machines with connection pooling, synchronization, and error handling.
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
    
    // Configuration
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
    
    // Constants
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
        // Get or create lock for this hostname
        Object lock = connectionLocks.computeIfAbsent(hostname, k -> new Object());
        
        synchronized (lock) {
            // Check if connection already exists and is still valid
            String existingConnection = activeConnections.get(hostname);
            if (existingConnection != null && isConnectionValid(hostname)) {
                log.debug("Reusing existing connection to {}: {}", hostname, existingConnection);
                return existingConnection;
            }
            
            // Remove any stale connection
            if (existingConnection != null) {
                log.info("Removing stale connection to {}", hostname);
                forceCleanupConnection(hostname);
            }
            
            // Establish new connection
            return establishNewConnection(hostname);
        }
    }
    
    @Override
    public void releaseConnection(String hostname) {
        Object lock = connectionLocks.get(hostname);
        if (lock != null) {
            synchronized (lock) {
                String connection = activeConnections.remove(hostname);
                connectionTimestamps.remove(hostname);
                if (connection != null) {
                    log.info("Releasing connection to {}", hostname);
                    performConnectionCleanup(hostname);
                }
            }
        }
    }
    
    @Override
    public boolean isConnectionActive(String hostname) {
        return activeConnections.containsKey(hostname) && isConnectionValid(hostname);
    }
    
    @Override
    public void cleanupAllConnections() {
        log.info("Cleaning up all active connections");
        for (String hostname : activeConnections.keySet()) {
            releaseConnection(hostname);
        }
        activeConnections.clear();
        connectionTimestamps.clear();
    }
    
    @Override
    @Scheduled(fixedRate = 300000) // Every 5 minutes
    public void cleanupStaleConnections() {
        long currentTime = System.currentTimeMillis();
        
        connectionTimestamps.entrySet().removeIf(entry -> {
            String hostname = entry.getKey();
            long timestamp = entry.getValue();
            
            if (currentTime - timestamp > CONNECTION_TIMEOUT) {
                log.info("Cleaning up stale connection to {}", hostname);
                forceCleanupConnection(hostname);
                activeConnections.remove(hostname);
                return true;
            }
            return false;
        });
    }
    
    private String establishNewConnection(String hostname) throws IOException {
        try {
            log.info("Attempting to establish connection to {}", hostname);

            if (!isHostReachable(hostname)) {
                throw new IOException("Host " + hostname + " is not reachable");
            }

            // Force cleanup any existing connections that might conflict
            forceCleanupConnection(hostname);
            
            // Handle specific Windows Error 1219 - force disconnect all connections
            forceDisconnectAllConnectionsToHost(hostname);
            
            String uncPath = createConnection(hostname);
            
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
            
            // Use IP from destination network if available
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
        
        // Check if connection has timed out
        if (System.currentTimeMillis() - timestamp > CONNECTION_TIMEOUT) {
            return false;
        }
        
        // Test connection by pinging
        return isHostReachable(hostname);
    }
    
    private void forceCleanupConnection(String hostname) {
        try {
            performConnectionCleanup(hostname);
        } catch (Exception e) {
            log.warn("Error during force cleanup of connection to {}: {}", hostname, e.getMessage());
        }
    }
    
    private void forceDisconnectAllConnectionsToHost(String hostname) {
        try {
            log.info("Force disconnecting all connections to {}", hostname);
            
            // Use net use command to list and disconnect all connections
            ProcessBuilder listBuilder = new ProcessBuilder("net", "use");
            listBuilder.redirectErrorStream(true);
            Process listProcess = listBuilder.start();
            
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(listProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    // Look for connections to this hostname
                    if (line.contains(hostname)) {
                        log.info("Found existing connection: {}", line);
                    }
                }
            }
            
            try {
                listProcess.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                listProcess.destroyForcibly();
            }
            
            // Force disconnect using /delete * for all connections
            ProcessBuilder disconnectBuilder = new ProcessBuilder("net", "use", "*", "/delete", "/y");
            disconnectBuilder.redirectErrorStream(true);
            Process disconnectProcess = disconnectBuilder.start();
            
            boolean completed;
            try {
                completed = disconnectProcess.waitFor(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                disconnectProcess.destroyForcibly();
                completed = false;
            }
            if (completed) {
                log.info("Forced cleanup of all network connections completed");
            } else {
                disconnectProcess.destroyForcibly();
                log.warn("Force cleanup timed out");
            }
            
        } catch (Exception e) {
            log.error("Error during force disconnect: {}", e.getMessage());
        }
    }
    
    private void performConnectionCleanup(String hostname) {
        try {
            Machine machine = machineService.findByHostname(hostname);
            boolean hasVpn = machine != null && machine.getVpnServer() != null;
            
            if (!isHostReachable(hostname)) {
                log.info("Host {} is not reachable, skipping network cleanup", hostname);
                if (hasVpn) {
                    disconnectVpn(hostname);
                }
                return;
            }
            
            String connectionTarget = hostname;
            if (hasVpn && machine.getVpnServer().getDestinationNetwork() != null) {
                connectionTarget = getTargetIpFromNetwork(
                    machine.getVpnServer().getDestinationNetwork(), hostname);
                if (connectionTarget == null) {
                    connectionTarget = hostname;
                }
            }
            
            ProcessBuilder processBuilder = new ProcessBuilder(
                "cmd.exe", "/c", "net", "use", "\\\\" + connectionTarget + "\\C$", "/delete", "/y");
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            
            boolean completed;
            try {
                completed = process.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                completed = false;
            }
            if (!completed) {
                process.destroyForcibly();
                log.warn("Connection cleanup timed out for {}", hostname);
            }
            
            if (hasVpn) {
                disconnectVpn(hostname);
            }
            
        } catch (Exception e) {
            log.error("Error cleaning up connection to {}: {}", hostname, e.getMessage());
        }
    }
    
    // VPN and connection methods moved from FixtureServiceImpl
    
    private boolean isHostReachable(String hostname) {
        try {
            ProcessBuilder pingBuilder = new ProcessBuilder("ping", "-n", "1", "-w", "5000", hostname);
            pingBuilder.redirectErrorStream(true);
            Process pingProcess = pingBuilder.start();
            
            boolean finished;
            try {
                finished = pingProcess.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pingProcess.destroyForcibly();
                finished = false;
            }
            if (!finished) {
                pingProcess.destroyForcibly();
                return false;
            }
            
            int exitCode = pingProcess.exitValue();
            return exitCode == 0;
            
        } catch (Exception e) {
            log.warn("Failed to ping host {}: {}", hostname, e.getMessage());
            return false;
        }
    }
    
    private boolean connectVpn(String hostname) throws IOException {
        Machine machine = machineService.findByHostname(hostname);
        if (machine == null) {
            log.error("Cannot find machine with hostname: {}", hostname);
            return false;
        }
        
        VpnServer vpnServer = machine.getVpnServer();
        if (vpnServer == null) {
            log.debug("No VPN configuration for hostname: {}", hostname);
            return true;
        }
        
        String vpnName = vpnServer.getVpnName();
        String serverAddress = vpnServer.getServerAddress();
        
        log.info("Establishing VPN connection for {}: {}", hostname, vpnName);
        
        try {
            String profileDetails = checkVpnProfileDetails(vpnName);
            
            if (profileDetails.contains("ConnectionStatus      : Connected") &&
                profileDetails.contains("AuthenticationMethod  : {Pap}")) {
                log.info("VPN already connected with PAP authentication");
                return true;
            }
            
            if (profileDetails.contains("ConnectionStatus      : Connected")) {
                disconnectVpn(hostname);
                Thread.sleep(2000);
            }
            
            boolean needNewProfile = profileDetails.isEmpty() ||
                !profileDetails.contains("AuthenticationMethod  : {Pap}");
            
            if (needNewProfile) {
                if (!profileDetails.isEmpty()) {
                    removeVpnProfile(vpnName);
                    Thread.sleep(1000);
                }
                createVpnProfile(vpnName, serverAddress);
            }
            
            return connectToVpn(vpnName);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("VPN connection interrupted", e);
        }
    }
    
    private void disconnectVpn(String hostname) {
        try {
            Machine machine = machineService.findByHostname(hostname);
            if (machine == null || machine.getVpnServer() == null) {
                return;
            }
            
            String vpnName = machine.getVpnServer().getVpnName();
            log.info("Disconnecting VPN: {}", vpnName);
            
            String rasdialPath = System.getenv("WINDIR") + "\\System32\\rasdial.exe";
            ProcessBuilder disconnectBuilder = new ProcessBuilder(rasdialPath, vpnName, "/DISCONNECT");
            Process disconnectProcess = disconnectBuilder.start();
            
            boolean completed;
            try {
                completed = disconnectProcess.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                disconnectProcess.destroyForcibly();
                completed = false;
            }
            if (!completed) {
                disconnectProcess.destroyForcibly();
                log.warn("VPN disconnection timed out");
            } else if (disconnectProcess.exitValue() == 0) {
                log.info("Successfully disconnected VPN: {}", vpnName);
            }
            
        } catch (Exception e) {
            log.error("Error disconnecting VPN: {}", e.getMessage());
        }
    }
    
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
        
        boolean checkCompleted;
        try {
            checkCompleted = checkProcess.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            checkProcess.destroyForcibly();
            checkCompleted = false;
        }
        String profileDetails = checkOutput.toString().trim();
        
        if (!checkCompleted || checkProcess.exitValue() != 0 || profileDetails.isEmpty()) {
            return "";
        }
        return profileDetails;
    }
    
    private void removeVpnProfile(String vpnName) throws IOException, InterruptedException {
        ProcessBuilder removeBuilder = new ProcessBuilder(
            "powershell.exe", "-Command",
            "Remove-VpnConnection -Name '" + vpnName + "' -Force -ErrorAction SilentlyContinue"
        );
        removeBuilder.redirectErrorStream(true);
        Process removeProcess = removeBuilder.start();
        
        try {
            removeProcess.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            removeProcess.destroyForcibly();
        }
        log.info("VPN profile {} removed", vpnName);
    }
    
    private void createVpnProfile(String vpnName, String serverAddress) throws IOException, InterruptedException {
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
            "-Force"
        );
        createVpnBuilder.redirectErrorStream(true);
        Process createProcess = createVpnBuilder.start();
        
        boolean createCompleted;
        try {
            createCompleted = createProcess.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            createProcess.destroyForcibly();
            createCompleted = false;
        }
        if (!createCompleted || createProcess.exitValue() != 0) {
            throw new IOException("Failed to create VPN profile: " + vpnName);
        }
        
        log.info("VPN profile created: {}", vpnName);
    }
    
    private boolean connectToVpn(String vpnName) throws IOException, InterruptedException {
        String rasdialPath = System.getenv("WINDIR") + "\\System32\\rasdial.exe";
        
        ProcessBuilder connectBuilder = new ProcessBuilder(rasdialPath, vpnName, vpnUsername, vpnPassword);
        connectBuilder.redirectErrorStream(true);
        Process connectProcess = connectBuilder.start();
        
        boolean completed;
        try {
            completed = connectProcess.waitFor(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            connectProcess.destroyForcibly();
            completed = false;
        }
        if (!completed) {
            connectProcess.destroyForcibly();
            return false;
        }
        
        int exitCode = connectProcess.exitValue();
        if (exitCode == 0) {
            log.info("Successfully connected to VPN: {}", vpnName);
            return true;
        }
        
        return false;
    }
    
    private String getTargetIpFromNetwork(String destinationNetwork, String originalHostname) {
        try {
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
    
    private String createDirectConnection(String connectionTarget, String connectionUsername,
                                        String connectionPassword, String uncPath) throws IOException {
        log.info("Creating direct connection for user: {}", connectionUsername);
        
        if (!isHostReachable(connectionTarget)) {
            throw new IOException("Host " + connectionTarget + " is not reachable");
        }
        
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
            }
        }
        
        boolean finished;
        try {
            finished = process.waitFor(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Connection process was interrupted", e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Connection command timed out");
        }
        
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String errorOutput = output.toString().trim();
            
            if (errorOutput.contains("System error 1219")) {
                throw new IOException("Multiple connections detected (Error 1219): " + errorOutput);
            }
            
            throw new IOException("Failed to create connection. Exit code: " + exitCode + ", Output: " + errorOutput);
        }
        
        log.info("Successfully connected to {}", connectionTarget);
        return uncPath;
    }
    
    private String createConnectionWithRunAsNetOnly(String connectionTarget, String connectionUsername,
                                                   String connectionPassword, String uncPath) throws IOException {
        log.info("Creating VPN connection with RunAs /netonly for user: {}", connectionUsername);
        
        String tempDir = System.getProperty("java.io.tmpdir");
        String batchFileName = "temp_connect_" + System.currentTimeMillis() + ".bat";
        Path batchFile = Paths.get(tempDir, batchFileName);
        
        try {
            String batchContent = String.format(
                "@echo off\n" +
                "net use \"\\\\%s\\C$\" \"%s\" /user:\"%s\" /persistent:no\n" +
                "echo Connection completed with exit code %%ERRORLEVEL%%\n",
                connectionTarget, connectionPassword, connectionUsername
            );
            
            Files.write(batchFile, batchContent.getBytes());
            
            ProcessBuilder processBuilder = new ProcessBuilder(
                "runas", "/netonly", "/user:" + connectionUsername,
                "cmd.exe /c \"" + batchFile.toString() + "\""
            );
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            
            try (PrintWriter writer = new PrintWriter(process.getOutputStream())) {
                writer.println(connectionPassword);
                writer.flush();
            }
            
            boolean finished;
            try {
                finished = process.waitFor(45, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IOException("RunAs connection process was interrupted", e);
            }
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("RunAs connection timed out");
            }
            
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IOException("Failed to create VPN connection. Exit code: " + exitCode);
            }
            
            log.info("Successfully connected to {} using RunAs /netonly", connectionTarget);
            return uncPath;
            
        } finally {
            try {
                Files.deleteIfExists(batchFile);
            } catch (IOException e) {
                log.warn("Could not delete temporary batch file: {}", batchFile, e);
            }
        }
    }
}