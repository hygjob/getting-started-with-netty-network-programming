package example.netty.echo;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public final class ByteBufViewsDemo {
    public static void main(String[] args) {
        ByteBuf source = Unpooled.buffer(4, 4);
        ByteBuf copied = null;
        ByteBuf retained = null;
        try {
            source.writeBytes(new byte[] {10, 20, 30, 40});
            // 원본의 1번 위치부터 2바이트를 보는 뷰입니다. 참조 수는 증가하지 않습니다.
            ByteBuf slice = source.slice(1, 2);
            // 전체 저장 공간을 공유하지만 읽기·쓰기 인덱스는 독립적인 뷰입니다.
            ByteBuf duplicate = source.duplicate();
            // 새 저장 공간으로 복사하므로 이후 원본 변경의 영향을 받지 않습니다.
            copied = source.copy();
            // 저장 공간을 공유하면서 참조 수를 늘려 별도의 보유 책임을 얻습니다.
            retained = source.retainedSlice(1, 2);

            // 뷰의 0번 위치는 원본의 1번 위치입니다. 원본과 duplicate에서도 99가 보입니다.
            slice.setByte(0, 99);
            System.out.println("원본[1]=" + source.getUnsignedByte(1));
            System.out.println("duplicate[1]=" + duplicate.getUnsignedByte(1));
            System.out.println("copy[1]=" + copied.getUnsignedByte(1));

            // 데이터는 공유해도 slice의 readerIndex 이동은 원본 인덱스를 바꾸지 않습니다.
            slice.readByte();
            System.out.println("slice reader=" + slice.readerIndex());
            System.out.println("원본 reader=" + source.readerIndex());

            // 원본이 보유한 참조 하나를 반환합니다.
            source.release();
            // finally에서 원본의 참조를 중복 반환하지 않도록 표시합니다.
            source = null;
            // retained가 얻은 참조가 남아 있으므로 그 뷰의 데이터는 계속 사용할 수 있습니다.
            System.out.println("원본 소유권 반환 후 retained[0]="
                    + retained.getUnsignedByte(0));
        } finally {
            // retained와 copy의 보유 책임을 각각 정리합니다. 일반 slice·duplicate는 따로 해제하지 않습니다.
            if (retained != null) {
                retained.release();
            }
            if (copied != null) {
                copied.release();
            }
            // 중간 예외로 원본 참조를 아직 반환하지 못한 경우에만 정리합니다.
            if (source != null) {
                // 원본이 보유한 참조 하나를 반환합니다.
                source.release();
            }
        }
    }
}
