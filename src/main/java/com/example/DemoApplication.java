package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class DemoApplication {

	@GetMapping("/")
	Foo hello() {
		return new Foo("Hello, world!");
	}

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}
}

record Foo(String name) {}