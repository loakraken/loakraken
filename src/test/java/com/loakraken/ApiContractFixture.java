package com.loakraken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ApiContractFixture {
  private ApiContractFixture() {}

  public static void write(String filename, String json) throws IOException {
    var target = Path.of("build/generated", filename);
    Files.createDirectories(target.getParent());
    Files.writeString(target, json);
  }
}
