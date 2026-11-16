package example.netty.echo;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;

public final class ByteBufIndicesDemo {
    public static void main(String[] args) {
        // 초기 용량과 최대 용량을 모두 16바이트로 고정합니다.
        ByteBuf buffer = Unpooled.buffer(16, 16);
        try {
            show("처음", buffer);
            // 4바이트를 기록하면서 writerIndex를 4만큼 이동합니다.
            buffer.writeInt(7);
            // UTF-8의 3바이트를 추가하므로 총 7바이트를 읽을 수 있게 됩니다.
            buffer.writeBytes("가".getBytes(StandardCharsets.UTF_8));
            show("기록 후", buffer);

            // get은 지정한 위치를 조회하며 readerIndex를 이동하지 않습니다.
            System.out.println("getInt=" + buffer.getInt(0));
            show("getInt 후", buffer);
            // read는 현재 readerIndex에서 값을 읽고 인덱스를 4만큼 이동합니다.
            System.out.println("readInt=" + buffer.readInt());
            show("readInt 후", buffer);

            // 남은 3바이트를 UTF-8 문자로 읽습니다. ByteBuffer의 flip()은 필요하지 않습니다.
            String text = buffer.readCharSequence(
                    buffer.readableBytes(), StandardCharsets.UTF_8).toString();
            System.out.println("문자열=" + text);
            show("문자열 읽기 후", buffer);

            // 두 인덱스만 0으로 되돌립니다. 기존 바이트 내용은 지우지 않습니다.
            buffer.clear();
            show("clear 후", buffer);
            System.out.println("남아 있는 첫 정수=" + buffer.getInt(0));
        } finally {
            // 직접 만든 버퍼의 소유권을 finally에서 반환합니다.
            buffer.release();
        }
    }

    // 읽기·쓰기 위치와 읽을 수 있는 길이를 나란히 표시합니다.
    private static void show(String label, ByteBuf buffer) {
        System.out.printf("%s reader=%d writer=%d readable=%d capacity=%d%n",
                label, buffer.readerIndex(), buffer.writerIndex(),
                buffer.readableBytes(), buffer.capacity());
    }
}
