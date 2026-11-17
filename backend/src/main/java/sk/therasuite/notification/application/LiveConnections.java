package sk.therasuite.notification.application;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import sk.therasuite.iam.application.PermissionChecker;
import sk.therasuite.iam.domain.Permissions;

/**
 * Register otvorených spojení v pamäti. Udalosť viazanú na recepciu dostane len spojenie
 * používateľa, ktorý má PLAN_VIEW pre túto recepciu (kontroluje sa v čase odoslania). Odpojené
 * spojenia a spojenia s vypršanou reláciou sa upratujú pri odoslaní a pri heartbeate.
 */
@Component
public class LiveConnections {

  private static final Logger log = LoggerFactory.getLogger(LiveConnections.class);

  private final Map<LiveConnection, Long> connections = new ConcurrentHashMap<>();
  private final PermissionChecker permissions;
  private final Duration heartbeat;
  private ScheduledExecutorService scheduler;

  public LiveConnections(
      PermissionChecker permissions,
      @Value("${app.live-events.heartbeat:PT25S}") Duration heartbeat) {
    this.permissions = permissions;
    this.heartbeat = heartbeat;
  }

  @PostConstruct
  void start() {
    scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
          Thread t = new Thread(r, "live-events-heartbeat");
          t.setDaemon(true);
          return t;
        });
    scheduler.scheduleAtFixedRate(
        this::heartbeat, heartbeat.toMillis(), heartbeat.toMillis(), TimeUnit.MILLISECONDS);
  }

  @PreDestroy
  void stop() {
    scheduler.shutdownNow();
    connections.keySet().forEach(LiveConnection::close);
    connections.clear();
  }

  /** Zaregistruje spojenie používateľa a hneď mu pošle heartbeat (otvorí tok cez proxy). */
  public void register(long userId, LiveConnection connection) {
    connections.put(connection, userId);
    deliver(connection, new LiveEvent.Heartbeat());
  }

  public void unregister(LiveConnection connection) {
    connections.remove(connection);
  }

  public int size() {
    return connections.size();
  }

  /** Odošle udalosť oprávneným spojeniam. */
  public void dispatch(LiveEvent event) {
    Long receptionId =
        switch (event) {
          case LiveEvent.PlanChanged e -> e.receptionId();
          case LiveEvent.BusinessDayChanged e -> e.receptionId();
          case LiveEvent.Heartbeat e -> null;
        };
    for (Map.Entry<LiveConnection, Long> entry : Map.copyOf(connections).entrySet()) {
      if (receptionId == null || allowed(entry.getValue(), receptionId)) {
        deliver(entry.getKey(), event);
      }
    }
  }

  void heartbeat() {
    Iterator<LiveConnection> it = connections.keySet().iterator();
    while (it.hasNext()) {
      LiveConnection c = it.next();
      if (!c.isSessionValid()) {
        c.close();
        it.remove();
      } else {
        deliver(c, new LiveEvent.Heartbeat());
      }
    }
  }

  private boolean allowed(long userId, long receptionId) {
    try {
      return permissions.hasForUser(userId, Permissions.PLAN_VIEW, receptionId);
    } catch (RuntimeException e) {
      log.warn("Kontrola oprávnenia pre živú udalosť zlyhala", e);
      return false;
    }
  }

  private void deliver(LiveConnection connection, LiveEvent event) {
    try {
      connection.send(event);
    } catch (IOException | RuntimeException e) {
      connections.remove(connection);
      connection.close();
    }
  }
}
