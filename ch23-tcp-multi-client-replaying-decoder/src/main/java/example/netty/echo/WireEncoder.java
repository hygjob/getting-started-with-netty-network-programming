package example.netty.echo;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

// WireMessage를 11장의 헤더와 UTF-8 본문으로 기록합니다.
public final class WireEncoder extends MessageToByteEncoder<WireMessage> {
    @Override
    protected void encode(ChannelHandlerContext ctx, WireMessage message,
                          ByteBuf out) throws Exception {
        byte[] body = WireProtocol.encodeBody(message.body());
        WireProtocol.validate(message.type(), message.requestId(), body.length);
        out.writeShort(WireProtocol.MAGIC);
        out.writeByte(WireProtocol.VERSION);
        out.writeByte(message.type());
        out.writeLong(message.requestId());
        out.writeInt(body.length);
        out.writeBytes(body);
    }
}
