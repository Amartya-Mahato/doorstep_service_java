package com.doorstep;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@OpenAPIDefinition(info = @Info(title = "Doorstep Service Booking API", version = "0.0.1",
        description = "Times are IST local date-times without offset (e.g. 2026-11-12T10:00:00). "
                + "Errors return {timestamp, status, code, message, path, details?}."))
public class DoorstepApplication {
    public static void main(String[] args) {
        SpringApplication.run(DoorstepApplication.class, args);
    }
}
