package io.saas.forge.iam.config;

import io.saas.forge.iam.application.authentication.PasswordSetupDeliveryUnavailableException;
import io.saas.forge.iam.application.authentication.PasswordSetupMailer;
import io.saas.forge.iam.application.authentication.PasswordRecoveryMailer;
import io.saas.forge.iam.infrastructure.mail.SmtpPasswordSetupMailer;
import java.net.URI;
import java.time.Duration;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailException;

@Configuration(proxyBeanMethods = false)
public class PasswordSetupMailConfiguration {
    private static final URI DEVELOPMENT_PAGE_URI = URI.create("https://console.saas.forge.test/password-setup");

    @Bean
    PasswordSetupMailSettings passwordSetupMailSettings(
            @Value("${saas.forge.environment:dev}") String environment,
            @Value("${saas.forge.iam.password-setup.smtp.host:}") String host,
            @Value("${saas.forge.iam.password-setup.smtp.port:1025}") int port,
            @Value("${saas.forge.iam.password-setup.smtp.username:}") String username,
            @Value("${saas.forge.iam.password-setup.smtp.password:}") String password,
            @Value("${saas.forge.iam.password-setup.smtp.from:}") String from,
            @Value("${saas.forge.iam.password-setup.smtp.starttls:false}") boolean startTls,
            @Value("${saas.forge.iam.password-setup.smtp.timeout:PT5S}") String timeout,
            @Value("${saas.forge.iam.password-setup.page-uri:}") String pageUri) {
        boolean production = "prod".equalsIgnoreCase(environment) || "production".equalsIgnoreCase(environment);
        if (production && (host.isBlank() || username.isBlank() || password.isBlank() || from.isBlank()
                || pageUri.isBlank() || !startTls)) {
            throw new IllegalStateException("生产环境必须显式配置 SMTP、STARTTLS、发件人和 Password Setup 页面");
        }
        if (!host.isBlank() && from.isBlank()) {
            throw new IllegalStateException("启用 SMTP 时必须显式配置发件人");
        }
        URI configuredPageUri = pageUri.isBlank() ? DEVELOPMENT_PAGE_URI : URI.create(pageUri);
        return new PasswordSetupMailSettings(
                host, port, username, password, from, startTls, Duration.parse(timeout), configuredPageUri);
    }

    @Bean
    PasswordSetupMailer passwordSetupMailer(PasswordSetupMailSettings settings) {
        if (settings.host().isBlank()) {
            return mail -> { throw new PasswordSetupDeliveryUnavailableException(); };
        }
        JavaMailSenderImpl sender = sender(settings);
        return new SmtpPasswordSetupMailer(sender, settings.from());
    }

    @Bean
    PasswordRecoveryMailer passwordRecoveryMailer(PasswordSetupMailSettings settings) {
        if (settings.host().isBlank()) return (recipient, token) -> {
            throw new PasswordSetupDeliveryUnavailableException();
        };
        JavaMailSenderImpl sender = sender(settings);
        URI page = settings.pageUri().resolve("/password-recovery");
        return (recipient, token) -> {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(settings.from());
            message.setTo(recipient);
            message.setSubject("恢复你的 SaaS Forge 密码");
            message.setText("请在 30 分钟内使用以下一次性链接恢复密码：\n\n"
                    + page + "#token=" + token + "\n\n如果你没有发起此操作，请忽略本邮件。");
            try {
                sender.send(message);
            } catch (MailException exception) {
                throw new PasswordSetupDeliveryUnavailableException(exception);
            }
        };
    }

    private JavaMailSenderImpl sender(PasswordSetupMailSettings settings) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.host());
        sender.setPort(settings.port());
        if (!settings.username().isBlank()) {
            sender.setUsername(settings.username());
            sender.setPassword(settings.password());
        }
        int timeoutMillis = Math.toIntExact(settings.timeout().toMillis());
        Properties properties = sender.getJavaMailProperties();
        properties.setProperty("mail.smtp.connectiontimeout", Integer.toString(timeoutMillis));
        properties.setProperty("mail.smtp.timeout", Integer.toString(timeoutMillis));
        properties.setProperty("mail.smtp.writetimeout", Integer.toString(timeoutMillis));
        properties.setProperty("mail.smtp.starttls.enable", Boolean.toString(settings.startTls()));
        return sender;
    }

    public record PasswordSetupMailSettings(
            String host,
            int port,
            String username,
            String password,
            String from,
            boolean startTls,
            Duration timeout,
            URI pageUri) {
        public PasswordSetupMailSettings {
            if (host == null || port < 1 || port > 65_535 || username == null || password == null
                    || from == null || timeout == null || timeout.isNegative() || timeout.isZero() || pageUri == null) {
                throw new IllegalArgumentException("Password Setup 邮件配置不合法");
            }
        }

        @Override
        public String toString() {
            return "PasswordSetupMailSettings[host=[redacted], port=" + port
                    + ", username=[redacted], password=[redacted], from=[redacted], startTls=" + startTls
                    + ", timeout=" + timeout + ", pageUri=" + pageUri + "]";
        }
    }
}
