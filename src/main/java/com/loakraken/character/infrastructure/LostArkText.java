package com.loakraken.character.infrastructure;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class LostArkText {
  private static final Pattern LINE_BREAK = Pattern.compile("(?i)<br\\s*/?>");
  private static final Pattern TAG = Pattern.compile("</?[A-Za-z][^<>]*>");

  private LostArkText() {}

  static String plain(String source) {
    if (source == null) return null;
    // 상한을 넘는 칸은 깨진 응답으로 보고 읽지 않는다(뒤 계산이 이 글에 패턴을 여러 번 돌린다).
    if (!com.loakraken.character.domain.OfficialTextLimit.fits(source)) return "";
    String text = LINE_BREAK.matcher(source).replaceAll("\n");
    text = TAG.matcher(text).replaceAll("");
    text =
        text.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&");
    return text.lines().map(line -> line.strip()).collect(Collectors.joining("\n")).strip();
  }

  static List<String> plain(List<String> source) {
    return source == null
        ? List.of()
        : source.stream().filter(Objects::nonNull).map(line -> plain(line)).toList();
  }
}
