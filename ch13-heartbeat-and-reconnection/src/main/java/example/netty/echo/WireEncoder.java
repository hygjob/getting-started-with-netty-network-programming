package example.netty.echo;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import java.nio.ByteBuffer;

// WireMessage를 헤더와 본문 바이트로 바꿉니다. encode 안에서 별도로 write하지 않습니다.
public final class WireEncoder extends MessageToByteEncoder<WireMessage> {
    @Override
    protected void encode(ChannelHandlerContext ctx, WireMessage message,
                          ByteBuf out) throws Exception {
        ByteBuffer body = WireProtocol.encodeBody(message.body());
        // 문자 수가 아니라 인코딩된 바이트 수를 길이 필드에 기록합니다.
        int length = body.remaining();
        WireProtocol.validate(message.type(), message.requestId(), length);
        out.writeShort(WireProtocol.MAGIC);
        out.writeByte(WireProtocol.VERSION);
        out.writeByte(message.type());
        out.writeLong(message.requestId());
        out.writeInt(length);
        out.writeBytes(body);
    }
}
