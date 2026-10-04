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
