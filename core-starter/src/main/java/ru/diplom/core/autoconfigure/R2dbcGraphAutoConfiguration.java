package ru.diplom.core.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.r2dbc.core.DatabaseClient;
import ru.diplom.core.mapper.GraphResultSetExtractor;
import ru.diplom.core.repository.R2dbcGraphTemplate;
import ru.diplom.core.sql.SqlGenerator;

/**
 * Автоконфигурация модуля графовой загрузки.
 *
 * <p>При наличии в контексте {@link DatabaseClient} (его поднимает штатный
 * {@code spring-boot-starter-data-r2dbc}) собирает и регистрирует
 * {@link R2dbcGraphTemplate}, {@link SqlGenerator} и
 * {@link GraphResultSetExtractor}. Пользователю достаточно подключить
 * стартер как зависимость — никакой ручной {@code @Configuration} больше
 * не нужен.
 */
@AutoConfiguration(after = R2dbcAutoConfiguration.class)
@ConditionalOnClass(R2dbcGraphTemplate.class)
@ConditionalOnBean(DatabaseClient.class)
public class R2dbcGraphAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SqlGenerator sqlGenerator() {
        return new SqlGenerator();
    }

    @Bean
    @ConditionalOnMissingBean
    public GraphResultSetExtractor graphResultSetExtractor() {
        return new GraphResultSetExtractor();
    }

    @Bean
    @ConditionalOnMissingBean
    public R2dbcGraphTemplate r2dbcGraphTemplate(DatabaseClient databaseClient,
                                                 SqlGenerator sqlGenerator,
                                                 GraphResultSetExtractor extractor) {
        return new R2dbcGraphTemplate(databaseClient, sqlGenerator, extractor);
    }
}
