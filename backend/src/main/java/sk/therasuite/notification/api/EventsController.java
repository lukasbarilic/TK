package sk.therasuite.notification.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import sk.therasuite.iam.application.PermissionChecker;
import sk.therasuite.iam.domain.Permissions;
import sk.therasuite.notification.application.LiveConnection;
import sk.therasuite.notification.application.LiveConnections;
import sk.therasuite.notification.application.LiveEvent;
import sk.therasuite.shared.AuthenticatedUser;

/** Server-Sent Events pre živé aktualizácie plánu (HLD kap. 6.4). */
@RestController
@RequestMapping("/api/events")
public class EventsController {

  private final PermissionChecker permissions;
  private final LiveConnections connections;

  public EventsController(PermissionChecker permissions, LiveConnections connections) {
    this.permissions = permissions;
    this.connections = connections;
  }

  @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(HttpServletRequest request, HttpServletResponse response) {
    permissions.require(Permissions.PLAN_VIEW);
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    long userId = ((AuthenticatedUser) auth.getPrincipal()).id();
    response.setHeader("Cache-Control", "no-cache");
    response.setHeader("X-Accel-Buffering", "no");

    // Bez časového limitu: spojenie sa ukončí až s reláciou (kontrola pri heartbeate) alebo
    // klientom.
    SseEmitter emitter = new SseEmitter(0L);
    SseConnection connection = new SseConnection(emitter, request.getSession(false));
    emitter.onCompletion(() -> connections.unregister(connection));
    emitter.onTimeout(emitter::complete);
    emitter.onError(e -> connections.unregister(connection));
    connections.register(userId, connection);
    return emitter;
  }

  /** SSE spojenie; relácia platí, kým by ju ako platnú ponechala aj bežná požiadavka. */
  private static final class SseConnection implements LiveConnection {
    private final SseEmitter emitter;
    private final HttpSession session;

    SseConnection(SseEmitter emitter, HttpSession session) {
      this.emitter = emitter;
      this.session = session;
    }

    @Override
    public void send(LiveEvent event) throws IOException {
      emitter.send(
          SseEmitter.event().name(LiveEvent.nameOf(event)).data(event, MediaType.APPLICATION_JSON));
    }

    @Override
    public boolean isSessionValid() {
      if (session == null) {
        return true;
      }
      try {
        long idleMs = System.currentTimeMillis() - session.getLastAccessedTime();
        return idleMs < session.getMaxInactiveInterval() * 1000L;
      } catch (IllegalStateException invalidated) {
        return false;
      }
    }

    @Override
    public void close() {
      try {
        emitter.complete();
      } catch (RuntimeException ignored) {
        // už ukončené
      }
    }
  }
}
