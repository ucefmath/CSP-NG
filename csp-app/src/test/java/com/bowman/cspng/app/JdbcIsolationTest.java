package com.bowman.cspng.app;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Preuves d'isolation JDBC (plan §3.2) : JDBC confine a csp-app (Flyway).
 * L'isolation reelle est garantie par le enforcer Maven (bannedDependencies)
 * + dependency:tree ; ces tests verifient les effets visibles.
 */
class JdbcIsolationTest {

    @Test
    void hibernateClassesAreNotOnClasspath() {
        // Hibernate ORM est banni par le enforcer Maven
        for (String cls : new String[]{"org.hibernate.Session", "org.hibernate.SessionFactory"}) {
            try {
                Class.forName(cls);
                fail("Hibernate class should not be on classpath: " + cls);
            } catch (ClassNotFoundException e) {
                // Expected
            }
        }
    }

    @Test
    void postgresJdbcDriverIsOnClasspathForFlyway() {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            fail("PostgreSQL JDBC driver should be on classpath (Flyway in csp-app)");
        }
    }
}
