package com.doorstep;

import com.doorstep.config.AppConfig;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Shared helpers for the API-level tests. */
public final class ApiTestSupport {

    public static final long BATTERY = 1L;
    public static final long JUMPSTART = 2L;
    public static final long DIAGNOSTICS = 3L;

    /** Zone 1 pincode; BATTERY_FIT technicians there: Asha(1) and Chitra(3). */
    public static final String SOUTH = "560001";

    private ApiTestSupport() {
    }

    /** A bookable slot comfortably inside the 2h..7d window. */
    public static LocalDateTime slot(int daysAhead, int hour, int minute) {
        return LocalDate.now(AppConfig.IST).plusDays(daysAhead).atTime(hour, minute);
    }

    public static Map<String, Object> body(long serviceId, String pincode, LocalDateTime start, String phone) {
        return Map.of("serviceId", serviceId, "pincode", pincode, "startTime", start.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME), "customerPhone", phone);
    }

    @SuppressWarnings("rawtypes")
    public static ResponseEntity<Map> post(TestRestTemplate rest, String url, Object body, String idemKey) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (idemKey != null) {
            h.set("Idempotency-Key", idemKey);
        }
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }
}
