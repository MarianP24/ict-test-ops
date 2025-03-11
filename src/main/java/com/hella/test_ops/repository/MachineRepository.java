package com.hella.test_ops.repository;

import com.hella.test_ops.entity.Machine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface MachineRepository extends JpaRepository<Machine, Long> {
    @Modifying
    @Query(value = "DELETE FROM fixture_machine WHERE machine_id = :machineId", nativeQuery = true)
    void deleteFixtureRelations(Long machineId);

    Machine findByHostname(String hostname);
}