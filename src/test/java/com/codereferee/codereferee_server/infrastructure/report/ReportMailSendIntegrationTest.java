package com.codereferee.codereferee_server.infrastructure.report;

import com.codereferee.codereferee_server.application.report.ReportMailProperties;
import com.codereferee.codereferee_server.domain.validation.AgentStep;
import com.codereferee.codereferee_server.domain.validation.ChaosOptions;
import com.codereferee.codereferee_server.domain.validation.TaskStatus;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 SMTP 교환으로 발송을 검증한다. JVM 안에 최소 SMTP sink를 띄워 실제 JavaMailSender로
 * 보내고, 받은 MIME에서 PDF 첨부를 꺼내 %PDF인지 확인한다. 외부 SMTP·자격증명이 필요 없다.
 *
 * 추출한 PDF는 build/sent-report.pdf에 저장해 눈으로 열어 볼 수 있게 한다.
 */
class ReportMailSendIntegrationTest {

    @Test
    void sendsARealPdfOverSmtp() throws Exception {
        SmtpSink sink = new SmtpSink();
        int port = sink.start();
        try {
            JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
            mailSender.setHost("localhost");
            mailSender.setPort(port);
            Properties props = mailSender.getJavaMailProperties();
            props.put("mail.smtp.auth", "false");
            props.put("mail.smtp.starttls.enable", "false");

            ReportMailProperties properties = new ReportMailProperties(true, "CodeReferee <noreply@codereferee.dev>", 100);
            ReportPdfRenderer renderer = new ReportPdfRenderer();
            ReportMailSender sender = new ReportMailSender(mailSender, properties);

            TaskStatus task = TaskStatus.queued(
                            "local-test", "https://github.com/pallets/flask", "main", "abc1234",
                            "dev@example.com", ChaosOptions.NONE, null)
                    .withAiResult(AgentStep.FAILED, false, "fail", Map.of(
                            "judge_report", Map.of(
                                    "reason_category", "latency_slo_violation",
                                    "reason", "p95 720ms exceeds 300ms"),
                            "critic_feedback", Map.of(
                                    "root_cause", "동기 외부 호출이 요청 스레드를 붙잡는다"),
                            "refiner_report", Map.of(
                                    "summary", "해당 호출을 비동기로 돌리고 타임아웃을 건다")));

            byte[] pdf = renderer.render(task);
            sender.send("dev@example.com", task.repositoryUrl(), "FAILED", pdf);

            String raw = sink.awaitMessage();
            assertThat(raw).contains("To: dev@example.com");
            assertThat(raw).contains("Subject:");

            byte[] attachment = extractPdf(raw);
            assertThat(attachment).isNotEmpty();
            assertThat(new String(attachment, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");

            Path out = Path.of("build", "sent-report.pdf");
            Files.createDirectories(out.getParent());
            Files.write(out, attachment);
            System.out.println("[LocalSendTest] 발송 PDF 저장: " + out.toAbsolutePath() + " (" + attachment.length + " bytes)");
        } finally {
            sink.stop();
        }
    }

    /** 받은 MIME에서 application/pdf 파트를 디코드해 꺼낸다. */
    private static byte[] extractPdf(String raw) throws Exception {
        Session session = Session.getInstance(new Properties());
        MimeMessage message = new MimeMessage(session,
                new ByteArrayInputStream(raw.getBytes(StandardCharsets.ISO_8859_1)));
        Object content = message.getContent();
        if (content instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                Part part = mp.getBodyPart(i);
                if (part.getContentType() != null && part.getContentType().toLowerCase().contains("application/pdf")) {
                    return part.getInputStream().readAllBytes();
                }
            }
        }
        throw new AssertionError("PDF 첨부를 찾지 못했다");
    }

    /** 최소 SMTP 서버. 한 통을 받아 DATA 본문을 모은다. */
    private static final class SmtpSink {
        private ServerSocket serverSocket;
        private final CompletableFuture<String> received = new CompletableFuture<>();

        int start() throws IOException {
            serverSocket = new ServerSocket(0);
            Thread t = new Thread(this::accept, "smtp-sink");
            t.setDaemon(true);
            t.start();
            return serverSocket.getLocalPort();
        }

        private void accept() {
            try (Socket socket = serverSocket.accept();
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
                 OutputStream out = socket.getOutputStream()) {
                write(out, "220 localhost SMTP sink\r\n");
                StringBuilder data = new StringBuilder();
                boolean inData = false;
                String line;
                while ((line = in.readLine()) != null) {
                    if (inData) {
                        if (line.equals(".")) {
                            inData = false;
                            write(out, "250 OK\r\n");
                            received.complete(data.toString());
                            continue;
                        }
                        // dot-stuffing 해제
                        data.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
                        continue;
                    }
                    String upper = line.toUpperCase();
                    if (upper.startsWith("EHLO") || upper.startsWith("HELO")) {
                        write(out, "250 OK\r\n");
                    } else if (upper.startsWith("MAIL FROM") || upper.startsWith("RCPT TO")) {
                        write(out, "250 OK\r\n");
                    } else if (upper.startsWith("DATA")) {
                        write(out, "354 End data with <CR><LF>.<CR><LF>\r\n");
                        inData = true;
                    } else if (upper.startsWith("QUIT")) {
                        write(out, "221 Bye\r\n");
                        break;
                    } else {
                        write(out, "250 OK\r\n");
                    }
                }
            } catch (Exception e) {
                received.completeExceptionally(e);
            }
        }

        String awaitMessage() throws Exception {
            return received.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }

        void stop() throws IOException {
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        }

        private static void write(OutputStream out, String s) throws IOException {
            out.write(s.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }
    }
}
