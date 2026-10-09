package com.macrodash.feature.auth;

import com.macrodash.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * 계정 메일 — 가입 인증·비밀번호 재설정·이미 가입된 주소 안내. 텍스트 메일입니다.
 *
 * <p>{@code MAIL_HOST}가 비면 보내지 않고, 그 기능(가입·재설정)을 "메일 설정 필요"로 끕니다. 링크의 토큰은
 * 로그에 남기지 않습니다 — 로그를 보는 사람이 남의 계정을 인증·재설정할 수 있게 됩니다.
 */
@Component
public class AccountMail {

    private static final Logger log = LoggerFactory.getLogger(AccountMail.class);

    private final ObjectProvider<JavaMailSender> sender;
    private final Environment environment;
    private final AppProperties properties;

    public AccountMail(ObjectProvider<JavaMailSender> sender, Environment environment, AppProperties properties) {
        this.sender = sender;
        this.environment = environment;
        this.properties = properties;
    }

    public boolean configured() {
        String host = environment.getProperty("spring.mail.host");
        return host != null && !host.isBlank() && sender.getIfAvailable() != null;
    }

    public void sendVerification(String to, String token) {
        send(to, "[매크로 대시보드] 메일 주소를 확인해 주세요",
                "가입을 마치려면 아래 링크를 여세요(24시간 동안 유효).\n\n"
                        + properties.linkBase() + "/verify?token=" + token + "\n\n"
                        + "직접 가입하지 않았다면 이 메일을 무시하세요. 링크를 열지 않으면 계정은 쓰이지 않습니다.");
    }

    public void sendReset(String to, String token) {
        send(to, "[매크로 대시보드] 비밀번호 재설정",
                "비밀번호를 다시 정하려면 아래 링크를 여세요(30분 동안, 한 번만 유효).\n\n"
                        + properties.linkBase() + "/reset?token=" + token + "\n\n"
                        + "요청하지 않았다면 이 메일을 무시하세요. 비밀번호는 바뀌지 않습니다.");
    }

    /** 이미 가입된 주소로 가입을 시도했을 때 — 화면에는 같은 안내를 보여 계정 존재를 드러내지 않고, 주인에게만 알립니다. */
    public void sendAlreadyRegistered(String to) {
        send(to, "[매크로 대시보드] 이미 가입된 메일 주소입니다",
                "이 주소로 가입하려는 요청이 있었지만 이미 계정이 있습니다. 로그인하거나 비밀번호를 재설정하세요.\n\n"
                        + properties.linkBase() + "/login\n\n요청하지 않았다면 무시하세요.");
    }

    private void send(String to, String subject, String body) {
        JavaMailSender mail = sender.getIfAvailable();
        if (!configured() || mail == null) {
            throw new IllegalStateException("메일 발송이 설정되지 않았습니다(MAIL_HOST).");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        String from = properties.getMailFrom() != null && !properties.getMailFrom().isBlank()
                ? properties.getMailFrom() : environment.getProperty("spring.mail.username");
        if (from != null && !from.isBlank()) {
            message.setFrom(from);
        }
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        try {
            mail.send(message);
        } catch (MailException e) {
            // 받는 주소·토큰은 남기지 않습니다(개인정보·비밀값).
            log.warn("계정 메일 발송 실패({}): {}", subject, e.getClass().getSimpleName());
            throw e;
        }
    }
}
