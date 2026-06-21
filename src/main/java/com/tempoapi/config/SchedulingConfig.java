package com.tempoapi.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on Spring's scheduled-task support for the waitlist hold sweep. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
