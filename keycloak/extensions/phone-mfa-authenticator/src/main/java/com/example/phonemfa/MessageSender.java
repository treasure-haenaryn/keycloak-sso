package com.example.phonemfa;

/**
 * Single swap point for a real SMS/KakaoTalk gateway - production wiring calls whatever
 * client the CMS already uses here instead of {@link LoggingMessageSender}.
 */
public interface MessageSender {
    void send(String phoneNumber, String code);
}
