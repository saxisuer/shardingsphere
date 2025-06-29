/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.proxy.backend.mysql.handler.admin.executor.information;

import org.apache.shardingsphere.infra.metadata.database.resource.ResourceMetaData;
import org.apache.shardingsphere.infra.metadata.database.resource.unit.StorageUnit;
import org.apache.shardingsphere.proxy.backend.context.ProxyContext;
import org.apache.shardingsphere.proxy.backend.handler.admin.executor.AbstractDatabaseMetaDataExecutor;
import org.apache.shardingsphere.proxy.backend.handler.admin.executor.AbstractDatabaseMetaDataExecutor.DefaultDatabaseMetaDataExecutor;
import org.apache.shardingsphere.proxy.backend.session.ConnectionSession;
import org.apache.shardingsphere.sql.parser.statement.core.segment.dml.item.ColumnProjectionSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.dml.item.ProjectionSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.dml.item.ShorthandProjectionSegment;
import org.apache.shardingsphere.sql.parser.statement.core.statement.type.dml.SelectStatement;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Schemata query executor, used to query the schemata table.
 */
public final class SelectInformationSchemataExecutor extends DefaultDatabaseMetaDataExecutor {
    
    public static final String SCHEMA_NAME = "SCHEMA_NAME";
    
    public static final String DEFAULT_CHARACTER_SET_NAME = "DEFAULT_CHARACTER_SET_NAME";
    
    public static final String DEFAULT_COLLATION_NAME = "DEFAULT_COLLATION_NAME";
    
    public static final String CATALOG_NAME = "CATALOG_NAME";
    
    public static final String SQL_PATH = "SQL_PATH";
    
    public static final String DEFAULT_ENCRYPTION = "DEFAULT_ENCRYPTION";
    
    private static final Collection<String> SCHEMA_WITHOUT_DATA_SOURCE = new LinkedHashSet<>();
    
    private final SelectStatement sqlStatement;
    
    private String schemaNameAlias = SCHEMA_NAME;
    
    private boolean queryDatabase;
    
    public SelectInformationSchemataExecutor(final SelectStatement sqlStatement, final String sql, final List<Object> parameters) {
        super(sql, parameters);
        this.sqlStatement = sqlStatement;
    }
    
    @Override
    protected void postProcess() {
        removeDuplicatedRow();
    }
    
    private void removeDuplicatedRow() {
        if (queryDatabase) {
            Collection<Map<String, Object>> reservedRow = getRows().stream()
                    .collect(Collectors.groupingBy(each -> Optional.ofNullable(each.get(schemaNameAlias)), Collectors.toCollection(LinkedList::new)))
                    .values().stream().map(LinkedList::getFirst).collect(Collectors.toList());
            reservedRow.forEach(each -> getRows().removeIf(row -> !getRows().contains(each)));
        }
    }
    
    @Override
    protected Collection<String> getDatabaseNames(final ConnectionSession connectionSession) {
        Collection<String> databaseNames = ProxyContext.getInstance().getAllDatabaseNames().stream()
                .filter(each -> isAuthorized(each, connectionSession.getConnectionContext().getGrantee())).collect(Collectors.toList());
        SCHEMA_WITHOUT_DATA_SOURCE.addAll(databaseNames.stream().filter(each -> !hasDataSource(each)).collect(Collectors.toSet()));
        Collection<String> result = databaseNames.stream().filter(AbstractDatabaseMetaDataExecutor::hasDataSource).collect(Collectors.toList());
        if (!SCHEMA_WITHOUT_DATA_SOURCE.isEmpty()) {
            fillSchemasWithoutDataSource();
        }
        return result;
    }
    
    private void fillSchemasWithoutDataSource() {
        if (SCHEMA_WITHOUT_DATA_SOURCE.isEmpty()) {
            return;
        }
        Map<String, String> defaultRowData = getTheDefaultRowData();
        SCHEMA_WITHOUT_DATA_SOURCE.forEach(each -> {
            Map<String, Object> row = new LinkedHashMap<>(defaultRowData);
            row.replace(schemaNameAlias, each);
            getRows().add(row);
        });
        SCHEMA_WITHOUT_DATA_SOURCE.clear();
    }
    
    private Map<String, String> getTheDefaultRowData() {
        Collection<ProjectionSegment> projections = sqlStatement.getProjections().getProjections();
        if (projections.stream().anyMatch(ShorthandProjectionSegment.class::isInstance)) {
            return Stream.of(CATALOG_NAME, SCHEMA_NAME, DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME, SQL_PATH, DEFAULT_ENCRYPTION)
                    .collect(Collectors.toMap(each -> each, each -> "", (oldValue, currentValue) -> oldValue, LinkedHashMap::new));
        }
        return getDefaultRowsFromProjections(projections);
    }
    
    private Map<String, String> getDefaultRowsFromProjections(final Collection<ProjectionSegment> projections) {
        Map<String, String> result = new LinkedHashMap<>(projections.size(), 1F);
        for (ProjectionSegment each : projections) {
            if (!each.getClass().isAssignableFrom(ColumnProjectionSegment.class)) {
                continue;
            }
            if (((ColumnProjectionSegment) each).getAlias().isPresent()) {
                String alias = ((ColumnProjectionSegment) each).getAlias().get().getValue();
                if (((ColumnProjectionSegment) each).getColumn().getIdentifier().getValue().equalsIgnoreCase(SCHEMA_NAME)) {
                    schemaNameAlias = alias;
                }
                result.put(alias, "");
                continue;
            }
            result.put(((ColumnProjectionSegment) each).getColumn().getIdentifier().getValue().toUpperCase(), "");
        }
        return result;
    }
    
    @Override
    protected void preProcess(final String databaseName, final Map<String, Object> rows, final Map<String, String> alias) throws SQLException {
        ResourceMetaData resourceMetaData = ProxyContext.getInstance().getContextManager().getMetaDataContexts().getMetaData().getDatabase(databaseName).getResourceMetaData();
        Collection<String> catalogs = getCatalogs(resourceMetaData);
        schemaNameAlias = alias.getOrDefault(SCHEMA_NAME, alias.getOrDefault(schemaNameAlias, schemaNameAlias));
        String rowValue = rows.getOrDefault(schemaNameAlias, "").toString();
        queryDatabase = !rowValue.isEmpty();
        if (catalogs.contains(rowValue)) {
            rows.replace(schemaNameAlias, databaseName);
        } else {
            rows.clear();
        }
    }
    
    private Collection<String> getCatalogs(final ResourceMetaData resourceMetaData) throws SQLException {
        Optional<StorageUnit> storageUnit = resourceMetaData.getStorageUnits().values().stream().findFirst();
        if (!storageUnit.isPresent()) {
            return Collections.emptySet();
        }
        try (Connection connection = storageUnit.get().getDataSource().getConnection()) {
            return Collections.singleton(connection.getCatalog());
        }
    }
}
