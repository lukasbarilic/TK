package sk.therasuite.notification.application;

import java.io.IOException;

/** Otvorené spojenie jedného prehliadača (nezávislé od transportu, kvôli testom). */
public interface LiveConnection {

  /** Odošle udalosť; pri zlyhaní (odpojený klient) vyhodí {@link IOException}. */
  void send(LiveEvent event) throws IOException;

  /** {@code false}, ak relácia používateľa už vypršala alebo zanikla. */
  boolean isSessionValid();

  /** Ukončí spojenie. */
  void close();
}
