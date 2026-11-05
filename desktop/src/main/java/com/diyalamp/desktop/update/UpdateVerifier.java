package com.diyalamp.desktop.update;

import java.nio.file.Path;

/** The fixed helper runs this existing-app code before it moves either application. */
public final class UpdateVerifier {
  public static void main(String[] args) {
    try {
      if (args.length != 4 || !args[3].matches("[a-f0-9]{64}"))
        throw new IllegalArgumentException("Invalid update verification request.");
      Path app = Path.of(args[0]).toAbsolutePath().normalize();
      UpdateFiles.validateApp(app, args[1], args[2]);
      if (!args[3].equals(UpdateFiles.digest(app)))
        throw new IllegalArgumentException("The prepared application changed.");
    } catch (Exception error) {
      System.err.println("Update canceled: the prepared application could not be verified.");
      System.exit(1);
    }
  }
  private UpdateVerifier() {}
}
