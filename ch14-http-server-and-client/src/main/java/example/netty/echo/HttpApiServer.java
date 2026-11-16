package example.netty.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

// HTTP 파이프라인을 구성하고, TLS 인자가 있으면 같은 기능을 HTTPS로 실행합니다.
public final class HttpApiServer {
    public static void main(String[] args) throws Exception {
        run(null, 8080, args.length == 0 ? null : Path.of(args[0]));
    }

    public static void run(SslContext tls, int port, Path file) throws Exception {
        byte[] download;
        if (file == null) {
            download = "Netty HTTP 파일 전송 예제\n".getBytes(StandardCharsets.UTF_8);
        } else {
            // 디스크 읽기는 연결을 받기 전 메인 스레드에서 수행합니다.
            try (InputStream input = Files.newInputStream(file)) {
                download = input.readNBytes(1024 * 1024 + 1);
            }
            if (download.length > 1024 * 1024) {
                throw new IllegalArgumentException("실습 파일은 1MiB 이하로 준비하십시오.");
            }
        }
        final byte[] content = download;
        EventLoopGroup acceptor = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup workers = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        Channel server = null;
        try {
            ServerBootstrap bootstrap = new ServerBootstrap().group(acceptor, workers)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            if (tls != null) {
                                // TLS는 HTTP 코덱보다 앞에 두어 복호화된 바이트를 해석하게 합니다.
                                SslHandler ssl = tls.newHandler(ch.alloc());
                                ssl.setHandshakeTimeoutMillis(5000);
                                ch.pipeline().addLast(ssl);
                            }
                            ch.pipeline().addLast(new HttpServerCodec());
                            ch.pipeline().addLast(new HttpObjectAggregator(64 * 1024));
                            ch.pipeline().addLast(new ChunkedWriteHandler());
                            ch.pipeline().addLast(new HttpApiHandler(content));
                        }
                    });
            ChannelFuture bind = bootstrap.bind("127.0.0.1", port);
            server = bind.channel();
            bind.sync();
            System.out.println("HTTP 계열 서버 시작: " + port + ". Enter: 종료");
            System.in.read();
        } finally {
            if (server != null) server.close().syncUninterruptibly();
            workers.shutdownGracefully();
            acceptor.shutdownGracefully();
            workers.terminationFuture().syncUninterruptibly();
            acceptor.terminationFuture().syncUninterruptibly();
        }
    }
}
