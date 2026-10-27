package com.bowman.cspng.app;

import com.bowman.cspng.config.ConfigHolder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Producer CDI pour {@link ConfigHolder}.
 * Bean separe pour eviter la dependance circulaire dans CspBootstrap
 * (qui injecte ConfigHolder et ne peut pas le produire lui-meme).
 */
@ApplicationScoped
public class ConfigProducer {

    private final ConfigHolder holder;

    public ConfigProducer() {
        this.holder = new ConfigHolder();
    }

    @Produces
    @Singleton
    ConfigHolder configHolder() {
        return holder;
    }
}
