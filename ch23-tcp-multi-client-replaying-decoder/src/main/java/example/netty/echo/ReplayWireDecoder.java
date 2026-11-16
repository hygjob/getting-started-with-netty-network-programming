package example.netty.echo;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.ReplayingDecoder;
import java.util.List;

// 가변 길이 본문을 checkpoint 단위로 읽는 학습용 디코더입니다.
public final class ReplayWireDecoder
        extends ReplayingDecoder<ReplayWireDecoder.State> {
    enum State { HEADER, BODY }

    private int type;
    private long requestId;
    private int bodyLength;

    public ReplayWireDecoder() {
        super(State.HEADER);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in,
                          List<Object> out) throws Exception {
        switch (state()) {
            case HEADER:
                int magic = in.readUnsignedShort();
                int version = in.readUnsignedByte();
                type = in.readUnsignedByte();
                requestId = in.readLong();
                bodyLength = in.readInt();

                if (magic != WireProtocol.MAGIC || version != WireProtocol.VERSION) {
                    throw new CorruptedFrameException("매직 넘버 또는 버전 오류");
                }
                WireProtocol.validate(type, requestId, bodyLength);
                // 헤더는 모두 읽었으므로 본문 부족 시 여기로 되돌아옵니다.
                checkpoint(State.BODY);
                // break를 쓰지 않습니다. 같은 호출에서 본문까지 도착했다면 이어서 읽습니다.

            case BODY:
                byte[] bodyBytes = new byte[bodyLength];
                in.readBytes(bodyBytes);
                String body = WireProtocol.decodeBody(bodyBytes);
                out.add(new WireMessage(type, requestId, body));
                // 완전한 메시지를 출력한 뒤 다음 메시지는 다시 헤더부터 읽습니다.
                checkpoint(State.HEADER);
                break;

            default:
                throw new Error("알 수 없는 디코더 상태");
        }
    }
}
