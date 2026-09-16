package io.saas.forge.iam.application.authentication;

import io.saas.forge.iam.domain.identity.CredentialType;
import io.saas.forge.iam.domain.identity.Identity;
import io.saas.forge.iam.domain.identity.IdentityRepository;
import io.saas.forge.iam.domain.identity.NormalizedEmail;
import io.saas.forge.iam.domain.identity.PasswordCredential;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** 两代协议共享一次凭据核验，权限服务失败不计为错误密码。 */
public final class PasswordAuthenticator {
    private final IdentityRepository identities;
    private final LoginProtection protection;
    private final PasswordVerifier verifier;
    private final Clock clock;

    public PasswordAuthenticator(IdentityRepository identities, LoginProtection protection,
                                 PasswordVerifier verifier, Clock clock) {
        this.identities = identities;
        this.protection = protection;
        this.verifier = verifier;
        this.clock = clock;
    }

    public Result authenticate(String email, String password) {
        NormalizedEmail normalized = NormalizedEmail.from(email);
        if (protection.isLocked(normalized)) throw new AuthenticationFailedException();
        Instant now = clock.instant();
        var identity = identities.findByEmail(normalized);
        List<PasswordCredential> credentials = identity.map(value -> identities.findCredentials(value.id()).stream()
                .filter(credential -> credential.isValidAt(now))
                .sorted(java.util.Comparator.comparingInt(credential ->
                        credential.type() == CredentialType.INITIAL_PLATFORM_PASSWORD ? 0 : 1))
                .toList()).orElseGet(List::of);
        if (credentials.isEmpty()) verifier.dummyMatches(password);
        var credential = credentials.stream().filter(candidate -> verifier.matches(password, candidate.passwordHash()))
                .findFirst();
        if (credential.isEmpty()) {
            protection.recordCredentialFailure(normalized);
            throw new AuthenticationFailedException();
        }
        protection.clearCredentialFailures(normalized);
        return new Result(identity.orElseThrow(), credential.orElseThrow(), now);
    }

    public record Result(Identity identity, PasswordCredential credential, Instant authenticatedAt) { }
}
