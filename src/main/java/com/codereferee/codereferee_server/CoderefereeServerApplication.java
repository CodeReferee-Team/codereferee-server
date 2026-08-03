package com.codereferee.codereferee_server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CoderefereeServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(CoderefereeServerApplication.class, args);
	}

}
