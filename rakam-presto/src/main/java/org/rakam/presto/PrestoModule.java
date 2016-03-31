package org.rakam.presto;

import com.facebook.presto.sql.tree.QualifiedName;
import com.google.auto.service.AutoService;
import com.google.common.eventbus.Subscribe;
import com.google.inject.Binder;
import com.google.inject.Scopes;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.name.Names;
import org.rakam.analysis.ApiKeyService;
import org.rakam.analysis.ContinuousQueryService;
import org.rakam.analysis.EventExplorer;
import org.rakam.analysis.FunnelQueryExecutor;
import org.rakam.analysis.JDBCPoolDataSource;
import org.rakam.analysis.MaterializedViewService;
import org.rakam.analysis.RetentionQueryExecutor;
import org.rakam.analysis.TimestampToEpochFunction;
import org.rakam.analysis.metadata.Metastore;
import org.rakam.config.JDBCConfig;
import org.rakam.config.MetadataConfig;
import org.rakam.plugin.EventMapper;
import org.rakam.plugin.RakamModule;
import org.rakam.plugin.SystemEvents;
import org.rakam.plugin.TimestampEventMapper;
import org.rakam.plugin.user.AbstractUserService;
import org.rakam.plugin.user.UserPluginConfig;
import org.rakam.postgresql.analysis.PostgresqlApiKeyService;
import org.rakam.postgresql.plugin.user.AbstractPostgresqlUserStorage;
import org.rakam.presto.analysis.PrestoConfig;
import org.rakam.presto.analysis.PrestoContinuousQueryService;
import org.rakam.presto.analysis.PrestoEventExplorer;
import org.rakam.presto.analysis.PrestoFunnelQueryExecutor;
import org.rakam.presto.analysis.PrestoMaterializedViewService;
import org.rakam.presto.analysis.PrestoMetastore;
import org.rakam.presto.analysis.PrestoQueryExecutor;
import org.rakam.presto.analysis.PrestoRetentionQueryExecutor;
import org.rakam.presto.analysis.PrestoUserService;
import org.rakam.presto.plugin.user.PrestoExternalUserStorageAdapter;
import org.rakam.report.QueryExecutor;
import org.rakam.report.eventexplorer.EventExplorerConfig;
import org.rakam.presto.plugin.EventExplorerListener;
import org.rakam.util.ConditionalModule;

import javax.inject.Inject;

import static io.airlift.configuration.ConfigBinder.configBinder;

@AutoService(RakamModule.class)
@ConditionalModule(config = "store.adapter", value = "presto")
public class PrestoModule extends RakamModule {
    @Override
    protected void setup(Binder binder) {
        configBinder(binder).bindConfig(MetadataConfig.class);
        configBinder(binder).bindConfig(PrestoConfig.class);

        binder.bind(QueryExecutor.class).to(PrestoQueryExecutor.class);
        binder.bind(ContinuousQueryService.class).to(PrestoContinuousQueryService.class);
        binder.bind(MaterializedViewService.class).to(PrestoMaterializedViewService.class);
        binder.bind(String.class).annotatedWith(TimestampToEpochFunction.class).toInstance("to_unixtime");
        bindJDBCConfig(binder, "presto.metastore.jdbc");

        JDBCPoolDataSource dataSource = bindJDBCConfig(binder, "report.metadata.store.jdbc");
        binder.bind(ApiKeyService.class).toInstance(new PostgresqlApiKeyService(dataSource));

        binder.bind(Metastore.class).to(PrestoMetastore.class);
        if ("postgresql".equals(getConfig("plugin.user.storage"))) {
            binder.bind(AbstractPostgresqlUserStorage.class).to(PrestoExternalUserStorageAdapter.class)
                    .in(Scopes.SINGLETON);
            binder.bind(AbstractUserService.class).to(PrestoUserService.class)
                    .in(Scopes.SINGLETON);
        }

        if (buildConfigObject(EventExplorerConfig.class).isEventExplorerEnabled()) {
            binder.bind(EventExplorerListener.class).asEagerSingleton();
            binder.bind(EventExplorer.class).to(PrestoEventExplorer.class);
        }
        UserPluginConfig userPluginConfig = buildConfigObject(UserPluginConfig.class);

        if(userPluginConfig.getEnableUserMapping()) {
            binder.bind(UserMergeTableHook.class).asEagerSingleton();
        }

        if (userPluginConfig.isFunnelAnalysisEnabled()) {
            binder.bind(FunnelQueryExecutor.class).to(PrestoFunnelQueryExecutor.class);
        }

        if (userPluginConfig.isRetentionAnalysisEnabled()) {
            binder.bind(RetentionQueryExecutor.class).to(PrestoRetentionQueryExecutor.class);
        }

        Multibinder<EventMapper> timeMapper = Multibinder.newSetBinder(binder, EventMapper.class);
        timeMapper.addBinding().to(TimestampEventMapper.class).in(Scopes.SINGLETON);
    }

    @Override
    public String name() {
        return "PrestoDB backend for Rakam";
    }

    @Override
    public String description() {
        return "Rakam backend for high-throughput systems.";
    }

    private JDBCPoolDataSource bindJDBCConfig(Binder binder, String config) {
        JDBCPoolDataSource dataSource = JDBCPoolDataSource.getOrCreateDataSource(
                buildConfigObject(JDBCConfig.class, config));
        binder.bind(JDBCPoolDataSource.class)
                .annotatedWith(Names.named(config))
                .toInstance(dataSource);
        return dataSource;
    }

    public static class UserMergeTableHook {
        private final PrestoQueryExecutor executor;

        @Inject
        public UserMergeTableHook(PrestoQueryExecutor executor) {
            this.executor = executor;
        }

        @Subscribe
        public void onCreateProject(SystemEvents.ProjectCreatedEvent event) {
            executor.executeRawStatement(String.format("CREATE TABLE %s(id VARCHAR, _user VARCHAR, created_at DATE, merged_at DATE)",
                    executor.formatTableReference(event.project, QualifiedName.of("_anonymous_id_mapping"))));
        }
    }
}
