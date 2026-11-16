package example.netty.echo;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

// 페이지, 준비 상태,로그인,업그레이드 승인을 WebSocket 핸드셰이크 전에 처리하도록 합니다..
public final class ChatUpgradeGate extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final String COOKIE = "netty-chat-session";
    private static final System.Logger LOG = System.getLogger(ChatUpgradeGate.class.getName());
    private final byte[] page;
    private final ChatSettings settings;
    private final SessionStore sessions;
    private final ChatService service;
    private final ChannelGroup channels;
    private final String authority;
    private final String origin;

    public ChatUpgradeGate(byte[] page, ChatSettings settings, SessionStore sessions,
                           ChatService service, ChannelGroup channels) {
        this.page = page;
        this.settings = settings;
        this.sessions = sessions;
        this.service = service;
        this.channels = channels;
        this.authority = "localhost:" + settings.port();
        this.origin = (settings.tls() ? "https://" : "http://") + authority;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!request.decoderResult().isSuccess()) {
            respond(ctx, HttpResponseStatus.BAD_REQUEST, null);
            return;
        }
        if (!authority.equals(request.headers().get(HttpHeaderNames.HOST))) {
            respond(ctx, HttpResponseStatus.FORBIDDEN, null);
            return;
        }
        String path = request.uri();
        boolean get = request.method().equals(HttpMethod.GET);
        if (get && path.equals("/")) {
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(page));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
            HttpUtil.setContentLength(response, page.length);
            HttpUtil.setKeepAlive(response, false);
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        if (get && path.equals("/ready")) {
            // 종료를 시작하면 503을 응답해 앞단 부하 분산기가 새 사용자를 생성하지 않게 합니다.
            respond(ctx, service.draining() ? HttpResponseStatus.SERVICE_UNAVAILABLE : HttpResponseStatus.OK, null);
            return;
        }
        List<String> origins = request.headers().getAll(HttpHeaderNames.ORIGIN);
        boolean sameOrigin = origins.size() == 1 && origin.equals(origins.get(0));
        if (path.equals("/session")) {
            login(ctx, request, sameOrigin);
            return;
        }
        if (!path.equals("/ws")) {
            respond(ctx, HttpResponseStatus.NOT_FOUND, null);
            return;
        }
        if (!get || !sameOrigin) {
            respond(ctx, HttpResponseStatus.FORBIDDEN, null);
            return;
        }
        String user = sessions.user(cookie(request));
        if (user == null) {
            respond(ctx, HttpResponseStatus.FORBIDDEN, null);
            return;
        }
        // 최대 연결수 제한을 둡니다.
        if (service.draining() || channels.size() >= settings.maxConnections()) {
            respond(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, null);
            return;
        }
        // 인증한 사용자 이름을 채널에 붙이고, 승인된 요청의 소유권을 프로토콜 핸들러로 넘깁니다.
        ctx.channel().attr(ChatFrameHandler.USER).set(user);
        ctx.fireChannelRead(request.retain());
    }

    private void login(ChannelHandlerContext ctx, FullHttpRequest request, boolean sameOrigin) {
        if (!request.method().equals(HttpMethod.POST) || !sameOrigin) {
            respond(ctx, HttpResponseStatus.FORBIDDEN, null);
            return;
        }
        // user=...&password=... 형식의 폼 본문을 경로 없는 쿼리 문자열로 해석합니다.
        Map<String, List<String>> form = new QueryStringDecoder(
                request.content().toString(StandardCharsets.UTF_8), StandardCharsets.UTF_8, false).parameters();
        String user = single(form, "user");
        String token = sessions.login(user, single(form, "password"));
        if (token == null) {
            LOG.log(System.Logger.Level.WARNING, "event=login_rejected remote=" + ctx.channel().remoteAddress());
            respond(ctx, HttpResponseStatus.FORBIDDEN, null);
            return;
        }
        LOG.log(System.Logger.Level.INFO, "event=login user=" + user + " remote=" + ctx.channel().remoteAddress());
        respond(ctx, HttpResponseStatus.OK, COOKIE + "=" + token + "; Path=/; Max-Age="
                + settings.sessionMillis() / 1000 + "; HttpOnly; SameSite=Strict"
                + (settings.tls() ? "; Secure" : ""));
    }

    private static String single(Map<String, List<String>> form, String name) {
        List<String> values = form.get(name);
        return values != null && values.size() == 1 ? values.get(0) : null;
    }

    private static String cookie(FullHttpRequest request) {
        String header = request.headers().get(HttpHeaderNames.COOKIE);
        if (header == null) return null;
        for (Cookie cookie : ServerCookieDecoder.STRICT.decode(header)) {
            if (cookie.name().equals(COOKIE)) return cookie.value();
        }
        return null;
    }

    private void respond(ChannelHandlerContext ctx, HttpResponseStatus status, String setCookie) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        if (setCookie != null) {
            response.headers().set(HttpHeaderNames.SET_COOKIE, setCookie);
            response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        }
        HttpUtil.setContentLength(response, 0);
        HttpUtil.setKeepAlive(response, false);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
