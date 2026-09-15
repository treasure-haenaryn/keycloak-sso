package com.example.phonemfa;

import org.jboss.logging.Logger;

public class LoggingMessageSender implements MessageSender {

    private static final Logger LOG = Logger.getLogger(LoggingMessageSender.class);

    @Override
    public void send(String phoneNumber, String code) {
        LOG.infof("[PHONE-MFA] sending code %s to %s via SMS/Kakao gateway (stub)", code, phoneNumber);
    }
}
