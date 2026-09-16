package io.saas.forge.iam.application.authentication;

import io.saas.forge.iam.domain.identity.Identity;
import io.saas.forge.iam.domain.session.RefreshTokenFamily;
import java.util.List;

public record ConsoleSessionSnapshot(long revision, RefreshTokenFamily family, Identity identity,
                                     State state, boolean platform, List<AccessibleMembership> companies) {
    public enum State { PASSWORD_CHANGE_REQUIRED, NO_AVAILABLE_CONTEXT, CONTEXT_SELECTION_REQUIRED, AUTHENTICATED }
    public ConsoleSessionSnapshot { companies = List.copyOf(companies); }
}
