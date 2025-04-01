package com.hella.test_ops.repository;

import com.hella.test_ops.entity.Machine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MachineRepository extends JpaRepository<Machine, Long> {
    @Modifying
    @Query(value = "DELETE FROM fixture_machine WHERE machine_id = :machineId", nativeQuery = true)
    void deleteFixtureRelations(Long machineId);

    Machine findByHostname(String hostname);

    /**
     * Finds machines matching the provided filter criteria.
     * Any parameter can be null, in which case it won't be used for filtering.
     * String parameters use case-insensitive partial matching.
     *
     * @param equipmentName    Optional filter for equipment name (partial, case-insensitive)
     * @param internalFactory  Optional filter for internal factory
     * @param serialNumber     Optional filter for serial number (partial, case-insensitive)
     * @param equipmentType    Optional filter for equipment type (partial, case-insensitive)
     * @param hostname         Optional filter for hostname (partial, case-insensitive)
     * @return List of machines matching the criteria
     */
    @Query(value = "SELECT * FROM machine m WHERE " +
            "(:equipmentName IS NULL OR LOWER(m.equipment_name::text) LIKE LOWER(CONCAT('%', :equipmentName, '%'))) AND " +
            "(:internalFactory IS NULL OR m.internal_factory = :internalFactory) AND " +
            "(:serialNumber IS NULL OR LOWER(m.serial_number::text) LIKE LOWER(CONCAT('%', :serialNumber, '%'))) AND " +
            "(:equipmentType IS NULL OR LOWER(m.equipment_type::text) LIKE LOWER(CONCAT('%', :equipmentType, '%'))) AND " +
            "(:hostname IS NULL OR LOWER(m.hostname::text) LIKE LOWER(CONCAT('%', :hostname, '%')))",
            nativeQuery = true)
    List<Machine> findByFilters(
            @Param("equipmentName") String equipmentName,
            @Param("internalFactory") Integer internalFactory,
            @Param("serialNumber") String serialNumber,
            @Param("equipmentType") String equipmentType,
            @Param("hostname") String hostname
    );
}