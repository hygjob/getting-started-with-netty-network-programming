package example.netty.echo;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ResourceLeakDetector;

public final class AllocatorDemo {
    public static void main(String[] args) {
        // 할당 전에 누수 탐지 수준을 정합니다. 모든 누수를 즉시 잡는다는 의미는 아닙니다.
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.ADVANCED);
        // 기본 할당자의 실제 구현을 출력해 실행 환경의 설정을 확인합니다.
        ByteBufAllocator allocator = ByteBufAllocator.DEFAULT;
        System.out.println("할당자=" + allocator.getClass().getSimpleName());
        System.out.println("누수 탐지=" + ResourceLeakDetector.getLevel());

        // 초기 32바이트, 최대 1,024바이트인 다이렉트 버퍼를 요청합니다.
        ByteBuf buffer = allocator.directBuffer(32, 1024);
        try {
            // 누수 진단에서 소유 위치를 추적하는 데 쓸 힌트를 남깁니다.
            buffer.touch("AllocatorDemo가 소유합니다.");
            buffer.writeInt(42);
            System.out.println("direct=" + buffer.isDirect());
            System.out.println("값=" + buffer.readInt());
        } finally {
            // 다이렉트 버퍼도 사용이 끝나면 참조를 명시적으로 반환합니다.
            buffer.release();
        }
    }
}
