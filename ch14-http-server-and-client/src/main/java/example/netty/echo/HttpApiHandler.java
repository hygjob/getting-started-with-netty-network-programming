package example.netty.echo;

import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.stream.ChunkedStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

// 경로와 메서드를 함께 보고 JSON 또는 파일 응답을 만듭니다.
public final class HttpApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final byte[] download;

    public HttpApiHandler(byte[] download) {
        this.download = download; // 서버 시작 시 준비한 배열을 읽기 전용으로 공유합니다.
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!request.decoderResult().isSuccess()) {
            reply(ctx, request, HttpResponseStatus.BAD_REQUEST,
                    "{\"error\":\"bad request\"}", null, false);
            return;
        }
        String path;
        try {
            path = new QueryStringDecoder(request.uri()).path();
        } catch (IllegalArgumentException cause) {
            reply(ctx, request, HttpResponseStatus.BAD_REQUEST,
                    "{\"error\":\"bad URI\"}", null, false);
            return;
        }
        boolean readMethod = request.method().equals(HttpMethod.GET)
                || request.method().equals(HttpMethod.HEAD);
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        switch (path) {
            case "/health", "/hello" -> {
                if (!readMethod) {
                    methodNotAllowed(ctx, request, "GET, HEAD");
                    return;
                }
                String json = path.equals("/health")
                        ? "{\"status\":\"ok\"}" : "{\"message\":\"안녕하세요\"}";
                reply(ctx, request, HttpResponseStatus.OK, json, null, keepAlive);
            }
            case "/length" -> {
                if (!request.method().equals(HttpMethod.POST)) {
                    methodNotAllowed(ctx, request, "POST");
                    return;
                }
                String json = "{\"receivedBytes\":" + request.content().readableBytes() + "}";
                reply(ctx, request, HttpResponseStatus.OK, json, null, keepAlive);
            }
            case "/download", "/stream" -> {
                if (!readMethod) {
                    methodNotAllowed(ctx, request, "GET, HEAD");
                    return;
                }
                boolean chunked = path.equals("/stream");
                HttpResponse response = new DefaultHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream");
                response.headers().set(HttpHeaderNames.CONTENT_DISPOSITION,
                        "attachment; filename=sample.bin");
                if (chunked) HttpUtil.setTransferEncodingChunked(response, true);
                else HttpUtil.setContentLength(response, download.length);
                HttpUtil.setKeepAlive(response, keepAlive);
                ctx.write(response);
                ChannelFuture end;
                if (request.method().equals(HttpMethod.HEAD)) {
                    // HEAD는 본문 없이 마지막 내용 표시만 보냅니다.
                    end = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                } else {
                    // HttpChunkedInput이 마지막 LastHttpContent까지 내보내므로 따로 쓰지 않습니다.
                    end = ctx.writeAndFlush(new HttpChunkedInput(new ChunkedStream(
                            new ByteArrayInputStream(download), 8192)));
                }
                finish(end, keepAlive);
            }
            default -> reply(ctx, request, HttpResponseStatus.NOT_FOUND,
                    "{\"error\":\"not found\"}", null, keepAlive);
        }
    }

    private void methodNotAllowed(ChannelHandlerContext ctx, FullHttpRequest request,
                                  String allow) {
        reply(ctx, request, HttpResponseStatus.METHOD_NOT_ALLOWED,
                "{\"error\":\"method not allowed\"}", allow, HttpUtil.isKeepAlive(request));
    }

    private void reply(ChannelHandlerContext ctx, FullHttpRequest request,
                       HttpResponseStatus status, String json, String allow,
                       boolean keepAlive) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, request.method().equals(HttpMethod.HEAD)
                ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        // 문자 수가 아니라 인코딩된 바이트 배열 길이를 Content-Length로 사용합니다.
        HttpUtil.setContentLength(response, body.length);
        HttpUtil.setKeepAlive(response, keepAlive);
        if (allow != null) response.headers().set(HttpHeaderNames.ALLOW, allow);
        finish(ctx.writeAndFlush(response), keepAlive);
    }

    private void finish(ChannelFuture future, boolean keepAlive) {
        future.addListener(keepAlive ? ChannelFutureListener.CLOSE_ON_FAILURE
                : ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("HTTP 연결 오류: " + cause);
        ctx.close();
    }
}
