package com.claims;

import org.springframework.boot.SpringApplication;

public class TestClaimsBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(ClaimsBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
