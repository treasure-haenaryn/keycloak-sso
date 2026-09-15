package com.example.phonemfa;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.common.util.Time;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.security.SecureRandom;

public class PhoneMfaAuthenticator implements Authenticator {

    static final int DEFAULT_TTL_SECONDS = 180;
    static final int DEFAULT_MAX_ATTEMPTS = 5;

    private static final String NOTE_CODE = "phone_mfa_code";
    private static final String NOTE_EXPIRES = "phone_mfa_expires";
    private static final String NOTE_ATTEMPTS = "phone_mfa_attempts";
    private static final String PHONE_ATTRIBUTE = "phone_number";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final MessageSender messageSender;

    public PhoneMfaAuthenticator(MessageSender messageSender) {
        this.messageSender = messageSender;
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        UserModel user = context.getUser();
        String phone = user.getFirstAttribute(PHONE_ATTRIBUTE);
        if (phone == null || phone.isBlank()) {
            Response challenge = context.form()
                    .setError("전화번호가 등록되지 않아 MFA를 진행할 수 없습니다. 관리자에게 문의하세요.")
                    .createErrorPage(Response.Status.BAD_REQUEST);
            context.failureChallenge(AuthenticationFlowError.INTERNAL_ERROR, challenge);
            return;
        }

        sendNewCode(context, phone);
        context.challenge(context.form().createForm("phone-otp-form.ftl"));
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();

        if (formData.containsKey("resend")) {
            String phone = context.getUser().getFirstAttribute(PHONE_ATTRIBUTE);
            sendNewCode(context, phone);
            context.challenge(context.form()
                    .setSuccess("새 코드를 보냈습니다.")
                    .createForm("phone-otp-form.ftl"));
            return;
        }

        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        String expected = authSession.getAuthNote(NOTE_CODE);
        long expiresAt = Long.parseLong(authSession.getAuthNote(NOTE_EXPIRES));

        if (Time.currentTime() > expiresAt) {
            context.challenge(context.form()
                    .setError("코드가 만료되었습니다. 재발송해주세요.")
                    .createForm("phone-otp-form.ftl"));
            return;
        }

        String submitted = formData.getFirst("code");
        if (submitted != null && submitted.equals(expected)) {
            authSession.removeAuthNote(NOTE_CODE);
            authSession.removeAuthNote(NOTE_EXPIRES);
            authSession.removeAuthNote(NOTE_ATTEMPTS);
            context.success();
            return;
        }

        int attempts = Integer.parseInt(authSession.getAuthNote(NOTE_ATTEMPTS)) + 1;
        authSession.setAuthNote(NOTE_ATTEMPTS, String.valueOf(attempts));

        if (attempts >= getMaxAttempts(context)) {
            Response challenge = context.form()
                    .setError("시도 횟수를 초과했습니다. 처음부터 다시 로그인해주세요.")
                    .createErrorPage(Response.Status.BAD_REQUEST);
            context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, challenge);
            return;
        }

        context.challenge(context.form()
                .setError("코드가 올바르지 않습니다. 다시 입력해주세요.")
                .createForm("phone-otp-form.ftl"));
    }

    private void sendNewCode(AuthenticationFlowContext context, String phone) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        long expiresAt = Time.currentTime() + getTtlSeconds(context);

        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        authSession.setAuthNote(NOTE_CODE, code);
        authSession.setAuthNote(NOTE_EXPIRES, String.valueOf(expiresAt));
        authSession.setAuthNote(NOTE_ATTEMPTS, "0");

        messageSender.send(phone, code);
    }

    private int getTtlSeconds(AuthenticationFlowContext context) {
        AuthenticatorConfigModel config = context.getAuthenticatorConfig();
        if (config == null) {
            return DEFAULT_TTL_SECONDS;
        }
        String value = config.getConfig().get(PhoneMfaAuthenticatorFactory.CONFIG_TTL_SECONDS);
        return value == null ? DEFAULT_TTL_SECONDS : Integer.parseInt(value);
    }

    private int getMaxAttempts(AuthenticationFlowContext context) {
        AuthenticatorConfigModel config = context.getAuthenticatorConfig();
        if (config == null) {
            return DEFAULT_MAX_ATTEMPTS;
        }
        String value = config.getConfig().get(PhoneMfaAuthenticatorFactory.CONFIG_MAX_ATTEMPTS);
        return value == null ? DEFAULT_MAX_ATTEMPTS : Integer.parseInt(value);
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
        // no self-enrollment flow in this toy - phone_number is provisioned up front
    }

    @Override
    public boolean areRequiredActionsEnabled(KeycloakSession session, RealmModel realm) {
        return true;
    }

    @Override
    public void close() {
    }
}
