package example.netty.echo;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

// 24바이트 고정 보고를 record로 바꿉니다. 변환 뒤에는 수신 버퍼를 참조하지 않습니다.
public record StatusReport(int deviceId, long bootId, int sequence, int loadPercent) {
    public static final int BYTES = 24;
    private static final int MAGIC = 0x5354;

    public StatusReport {
        if (deviceId <= 0 || bootId <= 0 || sequence <= 0
                || loadPercent < 0 || loadPercent > 100) {
            throw new IllegalArgumentException("상태 보고 필드 범위 오류");
        }
    }

    public ByteBuf encode(ByteBufAllocator allocator) {
        ByteBuf out = allocator.buffer(BYTES, BYTES);
        out.writeShort(MAGIC);
        out.writeByte(1); // 버전
        out.writeByte(1); // 상태 보고 타입
        out.writeInt(deviceId);
        out.writeLong(bootId);
        out.writeInt(sequence);
        out.writeInt(loadPercent);
        return out;
    }

    public static StatusReport decode(ByteBuf in) {
        // 12바이트 두 개를 합쳐 24바이트로 만들지 않습니다. 데이터그램 경계를 유지합니다.
        if (in.readableBytes() != BYTES) {
            throw new IllegalArgumentException("24바이트 데이터그램만 허용합니다.");
        }
        int start = in.readerIndex();
        if (in.getUnsignedShort(start) != MAGIC
                || in.getUnsignedByte(start + 2) != 1
                || in.getUnsignedByte(start + 3) != 1) {
            throw new IllegalArgumentException("매직 값, 버전 또는 타입 오류");
        }
        return new StatusReport(in.getInt(start + 4), in.getLong(start + 8),
                in.getInt(start + 16), in.getInt(start + 20));
    }
}
