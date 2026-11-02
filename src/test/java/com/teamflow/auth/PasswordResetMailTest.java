package com.teamflow.auth;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

class PasswordResetMailTest {
    @Test
    void deliversUtf8CodeToLocalSmtpWithoutExternalMail() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var executor = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(10000);
            var delivered = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(10000);
                    var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    var output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), true);
                    output.print("220 localhost SMTP\r\n"); output.flush();
                    var mime = new StringBuilder();
                    boolean data = false;
                    for (String line; (line = input.readLine()) != null;) {
                        if (data && !line.equals(".")) { mime.append(line).append("\r\n"); continue; }
                        if (data) { data = false; output.print("250 accepted\r\n"); }
                        else if (line.equals("DATA")) { data = true; output.print("354 send data\r\n"); }
                        else if (line.equals("QUIT")) { output.print("221 bye\r\n"); output.flush(); break; }
                        else output.print("250 localhost\r\n");
                        output.flush();
                    }
                    return mime.toString();
                }
            });
            var sender = new JavaMailSenderImpl();
            sender.setHost("127.0.0.1");
            sender.setPort(server.getLocalPort());
            sender.setDefaultEncoding("UTF-8");
            sender.getJavaMailProperties().setProperty("mail.smtp.timeout", "5000");
            sender.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "5000");
            var factory = new StaticListableBeanFactory();
            factory.addBean("sender", sender);
            var mail = new PasswordResetMail(factory.getBeanProvider(org.springframework.mail.javamail.JavaMailSender.class),
                    "sender@example.test", "127.0.0.1");
            assertThat(mail.available()).isTrue();
            mail.send("receiver@example.test", "synthetic-test-code");
            var message = new MimeMessage(Session.getInstance(new Properties()),
                    new ByteArrayInputStream(delivered.get(10, TimeUnit.SECONDS).getBytes(StandardCharsets.UTF_8)));
            assertThat(message.getSubject()).isEqualTo("TeamFlow: восстановление пароля");
            assertThat(message.getAllRecipients()[0].toString()).isEqualTo("receiver@example.test");
            assertThat(message.getContent().toString()).contains("synthetic-test-code", "15 минут", "Если вы не запрашивали");
        }
    }

    @Test
    void mailIsUnavailableWithoutHostFromOrSender() {
        var factory = new StaticListableBeanFactory();
        var provider = factory.getBeanProvider(org.springframework.mail.javamail.JavaMailSender.class);
        assertThat(new PasswordResetMail(provider, "sender@example.test", "smtp.example.test").available()).isFalse();
        factory.addBean("sender", new JavaMailSenderImpl());
        assertThat(new PasswordResetMail(provider, "", "smtp.example.test").available()).isFalse();
        assertThat(new PasswordResetMail(provider, "sender@example.test", "").available()).isFalse();
    }
}
