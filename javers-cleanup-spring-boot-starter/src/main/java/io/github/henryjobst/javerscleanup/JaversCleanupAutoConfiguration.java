package io.github.henryjobst.javerscleanup;

import org.javers.core.Javers;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Auto-configures {@link JaversCleanupService} and {@link JaversMigrationService}
 * when Javers and Spring JDBC are present on the classpath.
 *
 * <p>All three beans support {@code @ConditionalOnMissingBean} — override any of them
 * in your own {@code @Configuration} class to customise behaviour.
 */
@AutoConfiguration
@ConditionalOnClass({Javers.class, JdbcTemplate.class})
public class JaversCleanupAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    SnapshotPromoter snapshotPromoter(Javers javers, JdbcTemplate jdbc) {
        return new SnapshotPromoter(javers, jdbc);
    }

    @Bean
    @ConditionalOnMissingBean
    public JaversCleanupService javersCleanupService(
            Javers javers,
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate namedJdbc,
            SnapshotPromoter promoter) {
        return new JaversCleanupService(javers, jdbc, namedJdbc, promoter);
    }

    @Bean
    @ConditionalOnMissingBean
    public JaversMigrationService javersMigrationService(
            Javers javers,
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate namedJdbc,
            SnapshotPromoter promoter) {
        return new JaversMigrationService(javers, jdbc, namedJdbc, promoter);
    }
}
