package com.doorstep.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Single clock for the whole app (IST) so tests can swap it. */
    @Bean
    public Clock clock() {
        return Clock.system(IST);
    }
}
