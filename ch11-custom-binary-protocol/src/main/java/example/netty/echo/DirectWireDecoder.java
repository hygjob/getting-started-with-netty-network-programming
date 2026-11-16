package example.netty.echo;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import java.util.List;

// 프레이밍과 헤더 해석을 한 클래스에서 수행하는 학습용 대안입니다.
public final class DirectWireDecoder extends ByteToMessageDecoder {
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in,
                          List<Object> out) throws Exception {
        if (in.readableBytes() < WireProtocol.HEADER_SIZE) {
            return; // 헤더가 부족하면 읽기 위치를 그대로 두고 다음 입력을 기다립니다.
        }
        int start = in.readerIndex();
        // readInt()로 길이를 소비하면 본문 부족 시 다음 호출에서 헤더 시작이 사라지므로 
        // 여기서는 getInt()를 사용합니다.
        int length = in.getInt(start + 12);
        if (length < 0 || length > WireProtocol.MAX_BODY) {
            throw new CorruptedFrameException("허용 범위를 벗어난 길이");
        }
        int total = WireProtocol.HEADER_SIZE + length;
        if (in.readableBytes() < total) {
            return;
        }
        // slice는 복사 없는 임시 뷰입니다.
        WireMessage message = WireProtocol.decode(in.slice(start, total));
        in.skipBytes(total);
        out.add(message);
    }
}
