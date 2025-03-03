package com.hella.test_ops.config;


import com.hella.test_ops.model.ERole;
import com.hella.test_ops.model.Role;
import com.hella.test_ops.model.User;
import com.hella.test_ops.repository.RoleRepository;
import com.hella.test_ops.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashSet;
import java.util.Set;

@Configuration
public class DataInitializer {

    @Bean
    public CommandLineRunner initData(
            RoleRepository roleRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder) {

        return args -> {
            // Initialize roles if not exist
            initRoles(roleRepository);

            // Create admin user if not exists
            createAdminUserIfNotExists(userRepository, roleRepository, passwordEncoder);

            System.out.println("Data initialization completed.");
        };
    }

    private void initRoles(RoleRepository roleRepository) {
        // Check if roles already exist
        if (roleRepository.count() == 0) {
            System.out.println("Initializing roles...");

            Role userRole = new Role();
            userRole.setName(ERole.ROLE_USER);
            roleRepository.save(userRole);

            Role adminRole = new Role();
            adminRole.setName(ERole.ROLE_ADMIN);
            roleRepository.save(adminRole);

            System.out.println("Roles initialized successfully.");
        }
    }

    private void createAdminUserIfNotExists(
            UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder) {

        // Check if admin user already exists
        if (!userRepository.existsByUsername("admin")) {
            System.out.println("Creating admin user...");

            // Create admin user
            User adminUser = new User();
            adminUser.setUsername("admin");
            adminUser.setEmail("admin@example.com");
            adminUser.setPassword(passwordEncoder.encode("admin123")); // Change this to a secure password

            // Assign admin role
            Set<Role> roles = new HashSet<>();
            Role adminRole = roleRepository.findByName(ERole.ROLE_ADMIN)
                    .orElseThrow(() -> new RuntimeException("Admin role not found."));
            roles.add(adminRole);
            adminUser.setRoles(roles);

            userRepository.save(adminUser);

            System.out.println("Admin user created successfully.");
            System.out.println("Admin credentials: username='admin', password='admin123'");
        }
    }
}
