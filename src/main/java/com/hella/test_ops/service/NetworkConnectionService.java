package com.hella.test_ops.service;

import com.hella.test_ops.entity.Fixture;

import java.io.IOException;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Service interface for managing network connections to remote machines.
 * Handles connection pooling, synchronization, and cleanup to prevent
 * Windows Error 1219 (multiple connections to same server).
 */
public interface NetworkConnectionService {
    
    /**
     * Establishes a network connection to the specified hostname.
     * If a connection already exists, returns the existing connection path.
     * Handles VPN connections, credential management, and error recovery.
     * 
     * @param hostname The hostname to connect to
     * @return The UNC path to the connected resource
     * @throws IOException If connection fails after retries
     */
    String establishConnection(String hostname) throws IOException;
    
    /**
     * Releases the network connection to the specified hostname.
     * Cleans up network shares and VPN connections if necessary.
     * 
     * @param hostname The hostname to disconnect from
     */
    void releaseConnection(String hostname);
    
    /**
     * Checks if an active connection exists to the specified hostname.
     * 
     * @param hostname The hostname to check
     * @return true if the connection is active, false otherwise
     */
    boolean isConnectionActive(String hostname);
    
    /**
     * Forces cleanup of all active connections.
     * Used during application shutdown or error recovery.
     */
    void cleanupAllConnections();
    
    /**
     * Forces cleanup of stale connections that have exceeded timeout.
     * Called periodically to prevent connection leaks.
     */
    void cleanupStaleConnections();

    /**
     * Executes the given operation with an established connection.
     * Ensures connection and file access happen in the same process context.
     */

    void processFilesWithConnection(String hostname, List<Fixture> fixtures,
                                    BiConsumer<Fixture, String> fileProcessor) throws IOException;
}