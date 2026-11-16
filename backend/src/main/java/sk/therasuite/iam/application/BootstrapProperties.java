package sk.therasuite.iam.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Premenné {@code APP_BOOTSTRAP_ADMIN_USERNAME} a {@code APP_BOOTSTRAP_ADMIN_PASSWORD}. */
@ConfigurationProperties("app.bootstrap.admin")
public record BootstrapProperties(String username, String password) {}
