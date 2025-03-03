package com.hella.test_ops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

//@EnableScheduling
@SpringBootApplication
@EnableWebMvc
public class TestOpsApplication {

	public static void main(String[] args) {
		SpringApplication.run(TestOpsApplication.class, args);
	}

}
