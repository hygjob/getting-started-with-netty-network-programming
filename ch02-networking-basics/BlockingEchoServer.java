import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

// 연결 하나에서 한 줄을 받아 돌려준 뒤 종료하는 블로킹 서버입니다.
public class BlockingEchoServer {
    public static void main(String[] args) throws Exception {
        // try-with-resources는 정상 종료와 예외 발생 모두에서 대기 소켓을 닫습니다.
        try (ServerSocket server = new ServerSocket()) {
            // 같은 컴퓨터에서만 접속할 수 있도록 루프백 주소의 8000번 포트에 바인딩합니다.
            server.bind(new InetSocketAddress("127.0.0.1", 8000));
            System.out.println("서버 대기: 127.0.0.1:8000");

            // 연결이 올 때까지 main 스레드가 기다립니다. 반환값은 연결별 소켓입니다.
            // 이 안쪽 try가 끝나면 연결별 소켓도 자동으로 닫힙니다.
            try (Socket client = server.accept()) {
                // 소켓의 블로킹 읽기 대기에 5초 제한을 둡니다. 요청 전체의 제한 시간은 아닙니다.
                client.setSoTimeout(5000);
                System.out.println("연결: " + client.getRemoteSocketAddress());

                // 입력 바이트를 UTF-8 문자로 해석하고, 읽을 버퍼를 준비합니다.
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(client.getInputStream(),
                                StandardCharsets.UTF_8));
                // 출력 문자를 버퍼에 모았다가 UTF-8 바이트로 소켓에 씁니다.
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(client.getOutputStream(),
                                StandardCharsets.UTF_8));

                // 줄바꿈 또는 EOF까지 기다리며, 반환 문자열에는 줄바꿈이 포함되지 않습니다.
                String request = reader.readLine();
                // 아무 문자도 읽지 못한 채 입력이 끝난 경우입니다. 빈 줄인 ""과 구분합니다.
                if (request == null) {
                    System.out.println("메시지 없이 연결이 종료되었습니다.");
                    return;
                }

                System.out.println("수신: " + request);
                writer.write(request);
                // 상대의 readLine()이 응답의 끝을 알 수 있도록 줄바꿈을 붙입니다.
                writer.write('\n');
                // 출력 버퍼의 데이터를 내보냅니다. 상대의 읽기 횟수나 업무 완료를 보장하지는 않습니다.
                writer.flush();
                System.out.println("응답을 보냈습니다.");
            }
        }
    }
}
