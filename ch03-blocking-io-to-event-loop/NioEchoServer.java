import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

// main 스레드의 이벤트 루프 하나가 여러 연결의 준비된 I/O를 처리합니다.
public class NioEchoServer {
    public static void main(String[] args) throws IOException {
        // 콘솔 스레드와 I/O 스레드가 종료 여부를 안전하게 공유합니다.
        AtomicBoolean running = new AtomicBoolean(true);

        // Selector와 서버 채널을 함께 정리할 수 있도록 자원으로 선언합니다.
        try (Selector selector = Selector.open();
             ServerSocketChannel server = ServerSocketChannel.open()) {
            // Selector에 등록할 채널은 논블로킹 모드로 설정해야 합니다.
            server.configureBlocking(false);
            server.bind(new InetSocketAddress("127.0.0.1", 8000));
            // 서버 채널에서는 새 연결을 받아들일 준비 상태에 관심을 등록합니다.
            server.register(selector, SelectionKey.OP_ACCEPT);

            System.out.println("NIO 서버 대기: 127.0.0.1:8000");
            System.out.println("서버 터미널에서 Enter를 누르면 종료합니다.");

            // 종료 입력의 블로킹 대기를 분리해 I/O 이벤트 루프가 계속 동작하게 합니다.
            Thread console = new Thread(() -> {
                try {
                    System.in.read();
                } catch (IOException e) {
                    System.err.println("콘솔 입력 오류: " + e.getMessage());
                } finally {
                    running.set(false);
                    // 종료 플래그만 바꾸면 select()가 계속 기다릴 수 있으므로 대기를 깨웁니다.
                    selector.wakeup();
                }
            }, "console-stop");
            // I/O 스레드가 끝났을 때 콘솔 입력 대기만으로 JVM이 남아 있지 않도록 합니다.
            console.setDaemon(true);
            console.start();

            try {
                while (running.get()) {
                    // 여러 채널 중 처리할 준비 상태가 생기기를 한곳에서 기다립니다.
                    selector.select();
                    if (!running.get()) {
                        break;
                    }

                    // 이번에 준비 상태가 감지된 키들을 순회합니다.
                    Iterator<SelectionKey> keys =
                            selector.selectedKeys().iterator();
                    while (keys.hasNext()) {
                        SelectionKey key = keys.next();
                        // 처리할 키를 선택된 집합에서 제거합니다. 채널 등록 취소와는 다릅니다.
                        keys.remove();
                        // 이미 취소된 키의 준비 상태는 확인하지 않습니다.
                        if (!key.isValid()) {
                            continue;
                        }

                        try {
                            // 현재 키의 준비 상태에 맞는 I/O를 시도합니다.
                            if (key.isAcceptable()) {
                                acceptOne(server, selector);
                            } else if (key.isReadable()) {
                                read(key);
                            } else if (key.isWritable()) {
                                write(key);
                            }
                        } catch (IOException e) {
                            // 대기 채널 오류는 서버 전체로 전파하고, 개별 연결 오류는 해당 연결만 닫습니다.
                            if (key.channel() == server) {
                                throw e;
                            }
                            System.err.println("연결 I/O 오류: " + e.getMessage());
                            close(key);
                        }
                    }
                }
            } finally {
                // Selector를 닫기 전에 등록된 연결 채널까지 직접 닫습니다.
                for (SelectionKey key : selector.keys()) {
                    close(key);
                }
            }
        }
        System.out.println("NIO 서버를 종료했습니다.");
    }

    // 연결 하나를 받아 논블로킹 읽기에 필요한 상태를 준비합니다.
    private static void acceptOne(ServerSocketChannel server, Selector selector)
            throws IOException {
        SocketChannel client = server.accept();
        // 준비 통지 이후에도 지금 받아들일 연결이 없으면 null일 수 있습니다.
        if (client == null) {
            return;
        }
        try {
            // 받아들인 연결도 서버 채널과 별도로 논블로킹 모드를 설정합니다.
            client.configureBlocking(false);
            // 연결마다 전용 버퍼를 키에 붙여 읽기와 미완료 쓰기의 상태를 보관합니다.
            client.register(selector, SelectionKey.OP_READ, ByteBuffer.allocate(32));
            System.out.println("연결: " + client.getRemoteAddress());
        } catch (IOException | RuntimeException e) {
            // 등록 전에 실패한 연결은 Selector의 종료 정리 대상에서 빠질 수 있어 직접 닫습니다.
            client.close();
            throw e;
        }
    }

    // 현재 읽을 수 있는 만큼만 받아 에코 응답을 준비합니다.
    private static void read(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        // 등록할 때 이 연결의 키에 붙인 버퍼를 꺼냅니다.
        ByteBuffer buffer = (ByteBuffer) key.attachment();
        // 양수는 수신 바이트 수, 0은 현재 읽은 데이터 없음, -1은 입력 종료입니다.
        // 0이면 아래 분기에서 상태를 바꾸지 않고 다음 읽기 기회를 기다립니다.
        int count = client.read(buffer);

        // 이 예제에서는 입력이 끝나면 연결 전체를 닫습니다.
        if (count == -1) {
            close(key);
        } else if (count > 0) {
            System.out.println("수신 바이트 수: " + count);
            // 받은 바이트를 읽어 보낼 수 있도록 limit을 수신 길이로, position을 0으로 바꿉니다.
            buffer.flip();
            // 응답을 모두 보낼 때까지 새 읽기를 멈추고 쓰기 준비 상태를 기다립니다.
            key.interestOps(SelectionKey.OP_WRITE);
        }
    }

    // 지난번에 보내지 못한 부분부터 논블로킹 쓰기를 이어갑니다.
    private static void write(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        // 등록할 때 이 연결의 키에 붙인 버퍼를 꺼냅니다.
        ByteBuffer buffer = (ByteBuffer) key.attachment();
        // 일부만 쓰거나 0바이트를 쓸 수 있습니다. position은 실제 쓴 만큼 이동합니다.
        client.write(buffer);

        // 남은 데이터가 있으면 버퍼와 OP_WRITE를 유지해 다음 기회에 이어서 씁니다.
        if (!buffer.hasRemaining()) {
            // 모두 보낸 뒤에만 위치와 한계를 초기화합니다. 바이트 내용을 지우지는 않습니다.
            buffer.clear();
            // 보낼 것이 없으므로 쓰기 관심을 제거하고 다음 읽기를 준비합니다.
            key.interestOps(SelectionKey.OP_READ);
        }
    }

    // 관심 등록과 실제 채널 자원을 함께 정리합니다.
    private static void close(SelectionKey key) {
        // 키를 취소하는 것만으로 소켓이 닫히지는 않으므로 아래에서 채널도 닫습니다.
        key.cancel();
        try {
            key.channel().close();
        } catch (IOException e) {
            System.err.println("채널 종료 오류: " + e.getMessage());
        }
    }
}
