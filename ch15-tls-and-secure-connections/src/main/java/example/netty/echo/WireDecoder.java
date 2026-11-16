package example.netty.echo;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import java.util.List;

// 이미 완성된 프레임만 받으므로 바이트를 더 기다리는 기능은 없습니다.
public final class WireDecoder extends MessageToMessageDecoder<ByteBuf> {
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf frame,
                          List<Object> out) throws Exception {
        // 새 record를 출력하므로 입력 ByteBuf를 직접 해제하거나 retain하지 않습니다.
        out.add(WireProtocol.decode(frame));
    }
}
