package com.hella.test_ops.service;

import com.hella.test_ops.entity.Machine;
import com.hella.test_ops.model.MachineDTO;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public interface MachineService {
    void save(MachineDTO machineDTO);

    MachineDTO findById(long id);

    List<MachineDTO> findAll();

    void update(long id, MachineDTO machineDTO);

    @Transactional
    void deleteById(long id);

    Machine findEntityById(Long id);
}
