package com.iasbank.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * Registers every use case implementation (classes ending in "UseCaseImpl") as a bean,
 * so the core stays free of framework annotations.
 */
@Configuration
@ComponentScan(
        basePackages = "com.iasbank.core.usecase",
        includeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "^.+UseCaseImpl$"),
        useDefaultFilters = false
)
public class UseCaseConfig {
}
