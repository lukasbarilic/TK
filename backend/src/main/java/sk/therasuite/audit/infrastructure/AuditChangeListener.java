package sk.therasuite.audit.infrastructure;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.stereotype.Component;
import sk.therasuite.audit.domain.AuditViolationException;

/**
 * Hibernate listener (PostInsert, PostUpdate, PostDelete): každú zmenu entity zapíše ako
 * audit_change k otvorenému audit záznamu. Bez otvoreného záznamu zmenu odmietne (fail closed) a
 * transakcia sa zruší.
 */
@Component
public class AuditChangeListener
    implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

  private final EntityManagerFactory entityManagerFactory;
  private final AuditRepository repository;
  private final AuditJson json;

  public AuditChangeListener(
      EntityManagerFactory entityManagerFactory, AuditRepository repository, AuditJson json) {
    this.entityManagerFactory = entityManagerFactory;
    this.repository = repository;
    this.json = json;
  }

  @PostConstruct
  void register() {
    EventListenerRegistry registry =
        entityManagerFactory
            .unwrap(SessionFactoryImplementor.class)
            .getServiceRegistry()
            .getService(EventListenerRegistry.class);
    registry.appendListeners(EventType.POST_INSERT, this);
    registry.appendListeners(EventType.POST_UPDATE, this);
    registry.appendListeners(EventType.POST_DELETE, this);
  }

  @Override
  public void onPostInsert(PostInsertEvent event) {
    AuditContext.Current ctx = requireContext(event.getPersister());
    String[] names = event.getPersister().getPropertyNames();
    Map<String, Object> after = state(names, event.getState());
    write(
        ctx,
        event.getPersister(),
        event.getId(),
        "CREATE",
        null,
        after,
        present(names, event.getState()));
  }

  @Override
  public void onPostUpdate(PostUpdateEvent event) {
    AuditContext.Current ctx = requireContext(event.getPersister());
    String[] names = event.getPersister().getPropertyNames();
    Object[] oldState = event.getOldState();
    List<String> changed = new ArrayList<>();
    for (int i = 0; i < names.length; i++) {
      if (oldState == null || !Objects.equals(oldState[i], event.getState()[i])) {
        changed.add(names[i]);
      }
    }
    Map<String, Object> before = oldState == null ? null : state(names, oldState);
    write(
        ctx,
        event.getPersister(),
        event.getId(),
        "UPDATE",
        before,
        state(names, event.getState()),
        changed);
  }

  @Override
  public void onPostDelete(PostDeleteEvent event) {
    AuditContext.Current ctx = requireContext(event.getPersister());
    String[] names = event.getPersister().getPropertyNames();
    Object[] deleted = event.getDeletedState();
    Map<String, Object> before = deleted == null ? null : state(names, deleted);
    write(
        ctx,
        event.getPersister(),
        event.getId(),
        "DELETE",
        before,
        null,
        deleted == null ? List.of() : present(names, deleted));
  }

  @Override
  public boolean requiresPostCommitHandling(EntityPersister persister) {
    return false;
  }

  private AuditContext.Current requireContext(EntityPersister persister) {
    AuditContext.Current ctx = AuditContext.get();
    if (ctx == null) {
      throw new AuditViolationException(
          "Zmena entity " + persister.getEntityName() + " mimo otvoreného audit záznamu.");
    }
    return ctx;
  }

  private void write(
      AuditContext.Current ctx,
      EntityPersister persister,
      Object id,
      String changeType,
      Map<String, Object> before,
      Map<String, Object> after,
      List<String> changedFields) {
    repository.insertChange(
        ctx,
        entityType(persister),
        id instanceof Number n ? n.longValue() : null,
        changeType,
        json.toJson(before),
        json.toJson(after),
        json.toJson(changedFields));
  }

  /** Stav entity bez polí s heslami a hashmi. */
  private static Map<String, Object> state(String[] names, Object[] values) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i < names.length; i++) {
      Object v = values[i];
      if (!AuditJson.sensitive(names[i], v)) {
        out.put(names[i], plain(v));
      }
    }
    return out;
  }

  private static List<String> present(String[] names, Object[] values) {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < names.length; i++) {
      if (values[i] != null) {
        out.add(names[i]);
      }
    }
    return out;
  }

  private static Object plain(Object v) {
    if (v == null || v instanceof Number || v instanceof Boolean || v instanceof CharSequence) {
      return v;
    }
    return v instanceof Enum<?> e ? e.name() : v.toString();
  }

  /** Názov entity v snake_case (zodpovedá názvu tabuľky), napr. UserAccount -> user_account. */
  private static String entityType(EntityPersister persister) {
    String name = persister.getEntityName();
    String simple = name.substring(name.lastIndexOf('.') + 1);
    return simple.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
  }
}
