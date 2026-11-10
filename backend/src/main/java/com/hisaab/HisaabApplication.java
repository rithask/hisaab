package com.hisaab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.hisaab.service.Money;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
@EnableAsync
@ConfigurationPropertiesScan
public class HisaabApplication {
    public static void main(String[] args) {
        // One time zone everywhere: the JVM, and every database session (so SQL that turns a timestamp into a
        // date uses the same calendar as the Java code). Instants stay UTC in the database either way.
        TimeZone.setDefault(TimeZone.getTimeZone(Money.ZONE));
        System.setProperty("spring.datasource.hikari.connection-init-sql", "set time zone '" + Money.ZONE.getId() + "'");
        SpringApplication.run(HisaabApplication.class, args);
    }
}
