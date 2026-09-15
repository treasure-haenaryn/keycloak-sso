package com.example.phonemfa;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.Arrays;
import java.util.List;

public class PhoneMfaAuthenticatorFactory implements AuthenticatorFactory {

    public static final String PROVIDER_ID = "phone-mfa-authenticator";

    static final String CONFIG_TTL_SECONDS = "code.ttl.seconds";
    static final String CONFIG_MAX_ATTEMPTS = "max.attempts";

    private static final PhoneMfaAuthenticator SINGLETON = new PhoneMfaAuthenticator(new LoggingMessageSender());

    private static final AuthenticationExecutionModel.Requirement[] REQUIREMENT_CHOICES = {
            AuthenticationExecutionModel.Requirement.REQUIRED,
            AuthenticationExecutionModel.Requirement.DISABLED
    };

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "Phone MFA (SMS/Kakao stub)";
    }

    @Override
    public String getReferenceCategory() {
        return "otp";
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return REQUIREMENT_CHOICES;
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public String getHelpText() {
        return "Sends a one-time code to the user's registered phone number "
                + "(stubbed as a log line in this toy) and requires it before completing login.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        ProviderConfigProperty ttl = new ProviderConfigProperty();
        ttl.setName(CONFIG_TTL_SECONDS);
        ttl.setLabel("Code TTL (seconds)");
        ttl.setType(ProviderConfigProperty.STRING_TYPE);
        ttl.setDefaultValue(String.valueOf(PhoneMfaAuthenticator.DEFAULT_TTL_SECONDS));
        ttl.setHelpText("How long a sent code stays valid.");

        ProviderConfigProperty maxAttempts = new ProviderConfigProperty();
        maxAttempts.setName(CONFIG_MAX_ATTEMPTS);
        maxAttempts.setLabel("Max attempts");
        maxAttempts.setType(ProviderConfigProperty.STRING_TYPE);
        maxAttempts.setDefaultValue(String.valueOf(PhoneMfaAuthenticator.DEFAULT_MAX_ATTEMPTS));
        maxAttempts.setHelpText("How many wrong codes are allowed before the login attempt fails.");

        return Arrays.asList(ttl, maxAttempts);
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return SINGLETON;
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }
}
