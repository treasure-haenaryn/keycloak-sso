package com.example.singlesession;

import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.UserSessionProvider;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Placed as the LAST execution in a browser flow (after any MFA step) so it only fires once
 * a login has fully succeeded - revoking the old session before that point would destroy a
 * perfectly good session in exchange for a login attempt that might still fail a later factor.
 *
 * Silent SSO to a second app reuses the existing UserSession and never re-enters this
 * authenticator, so it does not trigger here - only a genuinely new login (no valid
 * KEYCLOAK_SESSION cookie yet) does, which is exactly the "logged in somewhere new" case.
 */
public class SingleSessionAuthenticator implements Authenticator {

    private static final Logger LOG = Logger.getLogger(SingleSessionAuthenticator.class);

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        KeycloakSession session = context.getSession();
        RealmModel realm = context.getRealm();
        UserModel user = context.getUser();
        UserSessionProvider sessions = session.sessions();

        List<UserSessionModel> existing = sessions.getUserSessionsStream(realm, user)
                .collect(Collectors.toList());

        for (UserSessionModel old : existing) {
            LOG.infof("[SINGLE-SESSION] revoking prior session %s for user %s (new login elsewhere)",
                    old.getId(), user.getUsername());
            sessions.removeUserSession(realm, old);
        }

        context.success();
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        // Never challenges, so Keycloak never calls this.
        context.success();
    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
    }

    @Override
    public boolean areRequiredActionsEnabled(KeycloakSession session, RealmModel realm) {
        return true;
    }

    @Override
    public void close() {
    }
}
