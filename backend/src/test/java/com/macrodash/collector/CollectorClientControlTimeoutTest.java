package com.macrodash.collector;

import com.macrodash.config.AppProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수집기가 연결은 받는데 응답을 안 줄 때, 제어용 호출이 수집기 타임아웃(기본 90초)만큼
 * 화면을 멈추지 않는지. 연결만 받고 아무것도 쓰지 않는 소켓으로 그 상황을 만듭니다.
 */
class CollectorClientControlTimeoutTest {

    private ServerSocket server;
    private final List<Socket> accepted = new ArrayList<>();
    private Thread acceptor;

    @BeforeEach
    void startHangingServer() throws IOException {
        server = new ServerSocket(0);
        acceptor = new Thread(() -> {
            try {
                while (!server.isClosed()) {
                    accepted.add(server.accept());   // 받기만 하고 응답하지 않습니다
                }
            } catch (IOException ignored) {
                // 서버를 닫으면 끝납니다
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterEach
    void stop() throws IOException {
        for (Socket socket : accepted) {
            socket.close();
        }
        server.close();
    }

    private CollectorClient client() {
        AppProperties properties = new AppProperties();
        properties.setCollectorUrl("http://127.0.0.1:" + server.getLocalPort());
        properties.setCollectorTimeoutSeconds(90);
        return new CollectorClient(properties);
    }

    @Test
    @DisplayName("상태 조회와 백그라운드 실행 요청은 몇 초 안에 포기한다")
    void controlCallsGiveUpQuickly() {
        CollectorClient client = client();
        long started = System.nanoTime();

        assertThat(client.status()).isEmpty();
        assertThat(client.runTask("fred_series", false)).isEmpty();

        long elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000L;
        assertThat(elapsedSeconds).as("두 호출 합쳐 제어 타임아웃(3초)×2 언저리")
                .isLessThan(CollectorClient.CONTROL_READ_TIMEOUT.getSeconds() * 2 + 5);
    }
}
