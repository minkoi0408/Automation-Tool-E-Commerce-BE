package com.example.toolecommerrce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class ToolECommerrceApplication {

	public static void main(String[] args) {
		SpringApplication.run(ToolECommerrceApplication.class, args);
	}

}
