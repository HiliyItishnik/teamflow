package com.teamflow.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class PasswordResetMail {
    private final ObjectProvider<JavaMailSender> sender;
    private final String from;
    private final String host;
    public PasswordResetMail(ObjectProvider<JavaMailSender> sender, @Value("${teamflow.mail.from:}") String from,
                             @Value("${spring.mail.host:}") String host) {
        this.sender = sender; this.from = from; this.host = host;
    }
    public boolean available() { return !host.isBlank() && !from.isBlank() && sender.getIfAvailable() != null; }

    public void send(String email, String token) {
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("TeamFlow: восстановление пароля");
        message.setText("Одноразовый код для восстановления пароля (действует 15 минут):\n\n" + token
                + "\n\nВведите его в клиенте TeamFlow. Если вы не запрашивали восстановление, проигнорируйте письмо.");
        sender.getObject().send(message);
    }
}
