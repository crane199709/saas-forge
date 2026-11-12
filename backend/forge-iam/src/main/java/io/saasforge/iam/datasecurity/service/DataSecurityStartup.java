package io.saasforge.iam.datasecurity.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
class DataSecurityStartup implements ApplicationRunner {
    private final DataSecurityService security;

    DataSecurityStartup(DataSecurityService security) {
        this.security = security;
    }

    @Override
    public void run(ApplicationArguments args) {
        security.validateConfiguration();
    }
}
