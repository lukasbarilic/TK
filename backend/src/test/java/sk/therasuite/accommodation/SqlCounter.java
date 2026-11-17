package sk.therasuite.accommodation;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Testovacie počítadlo SQL dotazov: obalí DataSource a eviduje text každého dotazu. */
@TestConfiguration(proxyBeanMethods = false)
public class SqlCounter {

  public static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

  @Bean
  static BeanPostProcessor sqlCountingDataSource() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String name) {
        if (bean instanceof DataSource ds && !(bean instanceof Proxy)) {
          return Proxy.newProxyInstance(
              SqlCounter.class.getClassLoader(),
              new Class<?>[] {DataSource.class},
              (p, m, a) -> {
                Object r = invoke(ds, m, a);
                return m.getName().equals("getConnection")
                    ? wrapConnection((java.sql.Connection) r)
                    : r;
              });
        }
        return bean;
      }
    };
  }

  private static Object invoke(Object target, java.lang.reflect.Method m, Object[] a)
      throws Throwable {
    try {
      return m.invoke(target, a);
    } catch (java.lang.reflect.InvocationTargetException e) {
      throw e.getCause();
    }
  }

  private static java.sql.Connection wrapConnection(java.sql.Connection c) {
    return (java.sql.Connection)
        Proxy.newProxyInstance(
            SqlCounter.class.getClassLoader(),
            new Class<?>[] {java.sql.Connection.class},
            (p, m, a) -> {
              if (m.getName().equals("prepareStatement") || m.getName().equals("prepareCall")) {
                STATEMENTS.add((String) a[0]);
              }
              return invoke(c, m, a);
            });
  }
}
