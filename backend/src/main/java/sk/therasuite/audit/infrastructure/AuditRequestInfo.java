package sk.therasuite.audit.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Údaje o aktuálnej HTTP požiadavke pre audit a hlavička odpovede s identifikátorom záznamu. */
public final class AuditRequestInfo {

  public static final String EVENT_ID_HEADER = "X-Audit-Event-Id";
  private static final String REQUEST_ID_HEADER = "X-Request-Id";
  private static final String REQUEST_ID_ATTRIBUTE =
      AuditRequestInfo.class.getName() + ".requestId";

  /** Identifikátor požiadavky, IP a user agent (mimo požiadavky len náhodné request_id). */
  public record Info(String requestId, String clientIp, String userAgent) {}

  private AuditRequestInfo() {}

  public static Info current() {
    if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
      return new Info(UUID.randomUUID().toString(), null, null);
    }
    HttpServletRequest request = attrs.getRequest();
    String requestId = (String) request.getAttribute(REQUEST_ID_ATTRIBUTE);
    if (requestId == null) {
      String header = request.getHeader(REQUEST_ID_HEADER);
      requestId =
          header == null || header.isBlank() ? UUID.randomUUID().toString() : cut(header, 100);
      request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
    }
    return new Info(
        requestId, cut(request.getRemoteAddr(), 100), cut(request.getHeader("User-Agent"), 500));
  }

  /** Nastaví hlavičku X-Audit-Event-Id na odpoveď aktuálnej požiadavky (ak nejaká beží). */
  public static void setEventIdHeader(long eventId) {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
      HttpServletResponse response = attrs.getResponse();
      if (response != null && !response.isCommitted()) {
        response.setHeader(EVENT_ID_HEADER, Long.toString(eventId));
      }
    }
  }

  private static String cut(String value, int max) {
    return value == null || value.length() <= max ? value : value.substring(0, max);
  }
}
