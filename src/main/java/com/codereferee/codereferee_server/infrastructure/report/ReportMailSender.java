package com.codereferee.codereferee_server.infrastructure.report;

import com.codereferee.codereferee_server.application.report.ReportMailProperties;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * 검증 리포트 PDF를 메일로 보낸다. SMTP는 JavaMailSender(spring-boot-starter-mail)가 맡는다.
 */
@Component
@RequiredArgsConstructor
public class ReportMailSender {

    private final JavaMailSender mailSender;
    private final ReportMailProperties properties;

    public void send(String to, String repositoryUrl, String verdict, byte[] pdf) throws Exception {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(properties.from());
        helper.setTo(to);
        helper.setSubject("[CodeReferee] 검증 결과 " + verdict + " — " + repositoryUrl);
        helper.setText(
                "요청하신 레포지토리 검증이 끝났습니다.\n\n"
                        + "레포지토리: " + repositoryUrl + "\n"
                        + "판정: " + verdict + "\n\n"
                        + "자세한 내용은 첨부한 PDF 리포트를 확인하세요.",
                false);
        helper.addAttachment("codereferee-report.pdf", new ByteArrayResource(pdf), "application/pdf");
        mailSender.send(message);
    }
}
