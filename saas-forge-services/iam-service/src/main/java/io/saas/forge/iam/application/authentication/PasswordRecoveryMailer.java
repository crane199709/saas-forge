package io.saas.forge.iam.application.authentication;

@FunctionalInterface
public interface PasswordRecoveryMailer {
    void send(String recipient, String token);
}
