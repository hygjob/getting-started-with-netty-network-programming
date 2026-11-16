package example.netty.echo;

import java.nio.charset.CharacterCodingException;
import java.util.Locale;

// Netty API와 분리한 세 가지 업무 규칙입니다.
public final class BusinessService {
    public BusinessResult execute(String requestBody) {
        int separator = requestBody.indexOf(' ');
        String operation = separator < 0
                ? requestBody
                : requestBody.substring(0, separator);
        String argument = separator < 0
                ? ""
                : requestBody.substring(separator + 1);

        String result = switch (operation) {
            case "UPPER" -> requireText(operation, argument).toUpperCase(Locale.ROOT);
            case "REVERSE" -> reverseCodePoints(requireText(operation, argument));
            case "SUM" -> sum(argument);
            default -> throw new IllegalArgumentException("지원하지 않는 업무입니다.");
        };
        return new BusinessResult(operation, requireResultFits(result));
    }

    private static String requireText(String operation, String argument) {
        if (argument.isBlank()) {
            throw new IllegalArgumentException(operation + "에는 비어 있지 않은 본문이 필요합니다.");
        }
        return argument;
    }

    private static String reverseCodePoints(String text) {
        int[] codePoints = text.codePoints().toArray();
        StringBuilder reversed = new StringBuilder(text.length());
        for (int index = codePoints.length - 1; index >= 0; index--) {
            reversed.appendCodePoint(codePoints[index]);
        }
        return reversed.toString();
    }

    private static String sum(String argument) {
        String trimmed = argument.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("SUM에는 정수를 하나 이상 넣어야 합니다.");
        }
        String[] values = trimmed.split("\\s+");
        if (values.length > 100) {
            throw new IllegalArgumentException("SUM은 정수를 최대 100개까지 받습니다.");
        }
        long total = 0;
        for (String value : values) {
            try {
                total = Math.addExact(total, Long.parseLong(value));
            } catch (NumberFormatException cause) {
                throw new IllegalArgumentException("SUM의 입력에 정수가 아닌 값이 있습니다.", cause);
            }
        }
        return Long.toString(total);
    }

    private static String requireResultFits(String result) {
        try {
            WireProtocol.encodeBody(result);
            return result;
        } catch (CharacterCodingException cause) {
            throw new IllegalArgumentException("결과를 UTF-8로 인코딩할 수 없습니다.", cause);
        }
    }

    public record BusinessResult(String operation, String body) { }
}
