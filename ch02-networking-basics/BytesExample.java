import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

// 문자 인코딩과 정수의 바이트 순서를 눈으로 확인하는 예제입니다.
public class BytesExample {
    public static void main(String[] args) {
        String text = "가";
        // 문자열을 전송할 바이트로 변환합니다. "가"는 UTF-8에서 3바이트입니다.
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        // 바이트마다 공백을 넣은 16진수로 출력해 실제 값을 확인합니다.
        HexFormat hex = HexFormat.ofDelimiter(" ");

        // length()는 UTF-16 코드 단위 수이고, 배열 길이는 UTF-8 바이트 수입니다.
        System.out.println("String.length(): " + text.length());
        System.out.println("UTF-8 바이트 수: " + utf8.length);
        System.out.println("UTF-8 바이트: " + hex.formatHex(utf8));
        // 인코딩할 때와 같은 UTF-8 규칙으로 원래 문자열을 복원합니다.
        System.out.println("복원한 문자열: "
                + new String(utf8, StandardCharsets.UTF_8));

        // 4바이트 공간에 정수를 큰 자리부터 기록합니다. 결과는 00 00 01 02입니다.
        byte[] bigEndian = ByteBuffer.allocate(4)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(258)
                .array();
        // 같은 정수를 작은 자리부터 기록하면 02 01 00 00이 됩니다.
        byte[] littleEndian = ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(258)
                .array();

        System.out.println("빅 엔디언: " + hex.formatHex(bigEndian));
        System.out.println("리틀 엔디언: " + hex.formatHex(littleEndian));
    }
}
