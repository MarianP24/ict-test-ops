package com.hella.test_ops.controller;

import com.hella.test_ops.model.ERole;
import com.hella.test_ops.model.Role;
import com.hella.test_ops.model.User;
import com.hella.test_ops.repository.RoleRepository;
import com.hella.test_ops.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> getAllUsers() {
        List<User> users = userRepository.findAll();

        List<Map<String, Object>> userDTOs = users.stream()
                .map(user -> {
                    Map<String, Object> userDTO = new HashMap<>();
                    userDTO.put("id", user.getId());
                    userDTO.put("username", user.getUsername());
                    userDTO.put("email", user.getEmail());
                    userDTO.put("roles", user.getRoles().stream()
                            .map(role -> role.getName().name())
                            .collect(Collectors.toList()));
                    return userDTO;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(userDTOs);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getUserById(@PathVariable Long id) {
        Optional<User> userOptional = userRepository.findById(id);

        if (!userOptional.isPresent()) {
            return ResponseEntity.notFound().build();
        }

        User user = userOptional.get();
        Map<String, Object> userDTO = new HashMap<>();
        userDTO.put("id", user.getId());
        userDTO.put("username", user.getUsername());
        userDTO.put("email", user.getEmail());
        userDTO.put("roles", user.getRoles().stream()
                .map(role -> role.getName().name())
                .collect(Collectors.toList()));

        return ResponseEntity.ok(userDTO);
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateUserRoles(@PathVariable Long id, @RequestBody Map<String, List<String>> rolesRequest) {
        Optional<User> userOptional = userRepository.findById(id);

        if (!userOptional.isPresent()) {
            return ResponseEntity.notFound().build();
        }

        List<String> roleNames = rolesRequest.get("roles");
        if (roleNames == null || roleNames.isEmpty()) {
            return ResponseEntity.badRequest().body("No roles provided");
        }

        Set<Role> roles = new HashSet<>();
        for (String roleName : roleNames) {
            ERole eRole;
            try {
                eRole = ERole.valueOf(roleName);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body("Invalid role: " + roleName);
            }

            Role role = roleRepository.findByName(eRole)
                    .orElseThrow(() -> new RuntimeException("Role not found: " + roleName));
            roles.add(role);
        }

        User user = userOptional.get();
        user.setRoles(roles);
        userRepository.save(user);

        Map<String, Object> response = new HashMap<>();
        response.put("message", "User roles updated successfully");
        response.put("roles", roles.stream()
                .map(role -> role.getName().name())
                .collect(Collectors.toList()));

        return ResponseEntity.ok(response);
    }
}
