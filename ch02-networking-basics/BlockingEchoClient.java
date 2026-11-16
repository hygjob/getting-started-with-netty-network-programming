import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

// 한 줄의 요청을 보낸 뒤 같은 내용의 응답을 확인하는 클라이언트입니다.
public class BlockingEchoClient {
    public static void main(String[] args) throws Exception {
        // 명령행 인수가 없으면 기본 메시지를 사용합니다.
        String message = args.length == 0 ? "안녕하세요, 소켓!" : args[0];
        // 본문에 줄바꿈이 있으면 한 요청이 여러 줄이 되므로 미리 거부합니다.
        if (message.contains("\n") || message.contains("\r")) {
            throw new IllegalArgumentException("한 줄의 메시지만 보낼 수 있습니다.");
        }

        // 응답 확인이 끝나거나 예외가 발생하면 소켓을 자동으로 닫습니다.
        try (Socket socket = new Socket()) {
            // 연결 수립은 최대 3초 동안 기다립니다. 단위는 밀리초입니다.
            socket.connect(new InetSocketAddress("127.0.0.1", 8000), 3000);
            // 연결 후의 블로킹 읽기 대기에는 별도로 5초 제한을 둡니다.
            socket.setSoTimeout(5000);

            // 수신 바이트를 UTF-8로 해석한 뒤 줄 단위로 읽습니다.
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(),
                            StandardCharsets.UTF_8));
            // 송신 문자열도 서버와 같은 UTF-8로 변환하도록 설정합니다.
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(),
                            StandardCharsets.UTF_8));

            // 이 프로토콜에서는 클라이언트가 먼저 요청을 보내야 서버가 응답할 수 있습니다.
            writer.write(message);
            // 한 요청의 끝을 나타내는 줄바꿈을 추가합니다.
            writer.write('\n');
            // 버퍼에 머물 수 있는 데이터를 내보낸 뒤 응답 읽기로 넘어갑니다.
            writer.flush();
            System.out.println("송신: " + message);

            // 응답 한 줄을 기다립니다. TCP가 나누어 전달해도 스트림 계층에서 이어서 읽습니다.
            String response = reader.readLine();
            // 응답 문자를 하나도 읽지 못한 채 EOF가 오면 실패로 처리합니다.
            if (response == null) {
                throw new EOFException("응답을 받기 전에 연결이 종료되었습니다.");
            }
            // 연결 성공뿐 아니라 실제 응답 내용도 확인합니다.
            if (!message.equals(response)) {
                throw new IllegalStateException("송신한 내용과 응답이 다릅니다.");
            }
            System.out.println("수신: " + response);
        }
    }
}
