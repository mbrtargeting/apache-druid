/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.sql.calcite.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import org.apache.calcite.DataContext;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.avatica.remote.TypedValue;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.Numbers;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.math.expr.ExprMacroTable;
import org.apache.druid.query.BaseQuery;
import org.apache.druid.query.QueryContext;
import org.apache.druid.server.security.Access;
import org.apache.druid.server.security.AuthenticationResult;
import org.apache.druid.server.security.ResourceAction;
import org.apache.druid.sql.calcite.rel.VirtualColumnRegistry;
import org.apache.druid.sql.calcite.run.QueryMaker;
import org.apache.druid.sql.calcite.schema.DruidSchemaCatalog;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Interval;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Like {@link PlannerConfig}, but that has static configuration and this class contains dynamic, per-query
 * configuration.
 */
public class PlannerContext
{
  // query context keys
  public static final String CTX_SQL_QUERY_ID = BaseQuery.SQL_QUERY_ID;
  public static final String CTX_SQL_CURRENT_TIMESTAMP = "sqlCurrentTimestamp";
  public static final String CTX_SQL_TIME_ZONE = "sqlTimeZone";
  public static final String CTX_SQL_STRINGIFY_ARRAYS = "sqlStringifyArrays";

  // This context parameter is an undocumented parameter, used internally, to allow the web console to
  // apply a limit without having to rewrite the SQL query.
  public static final String CTX_SQL_OUTER_LIMIT = "sqlOuterLimit";

  // DataContext keys
  public static final String DATA_CTX_AUTHENTICATION_RESULT = "authenticationResult";

  private final String sql;
  private final DruidOperatorTable operatorTable;
  private final ExprMacroTable macroTable;
  private final ObjectMapper jsonMapper;
  private final PlannerConfig plannerConfig;
  private final DateTime localNow;
  private final DruidSchemaCatalog rootSchema;
  private final QueryContext queryContext;
  private final String sqlQueryId;
  private final boolean stringifyArrays;
  private final CopyOnWriteArrayList<String> nativeQueryIds = new CopyOnWriteArrayList<>();
  // bindings for dynamic parameters to bind during planning
  private List<TypedValue> parameters = Collections.emptyList();
  // result of authentication, providing identity to authorize set of resources produced by validation
  private AuthenticationResult authenticationResult;
  // set of datasources and views which must be authorized, initialized to null so we can detect if it has been set.
  private Set<ResourceAction> resourceActions;
  // result of authorizing set of resources against authentication identity
  private Access authorizationResult;
  // error messages encountered while planning the query
  @Nullable
  private String planningError;
  private QueryMaker queryMaker;
  private VirtualColumnRegistry joinExpressionVirtualColumnRegistry;

  private PlannerContext(
      final String sql,
      final DruidOperatorTable operatorTable,
      final ExprMacroTable macroTable,
      final ObjectMapper jsonMapper,
      final PlannerConfig plannerConfig,
      final DateTime localNow,
      final boolean stringifyArrays,
      final DruidSchemaCatalog rootSchema,
      final QueryContext queryContext
  )
  {
    this.sql = sql;
    this.operatorTable = operatorTable;
    this.macroTable = macroTable;
    this.jsonMapper = jsonMapper;
    this.plannerConfig = Preconditions.checkNotNull(plannerConfig, "plannerConfig");
    this.rootSchema = rootSchema;
    this.queryContext = queryContext;
    this.localNow = Preconditions.checkNotNull(localNow, "localNow");
    this.stringifyArrays = stringifyArrays;

    String sqlQueryId = (String) this.queryContext.get(CTX_SQL_QUERY_ID);
    // special handling for DruidViewMacro, normal client will allocate sqlid in SqlLifecyle
    if (Strings.isNullOrEmpty(sqlQueryId)) {
      sqlQueryId = UUID.randomUUID().toString();
    }
    this.sqlQueryId = sqlQueryId;
  }

  public static PlannerContext create(
      final String sql,
      final DruidOperatorTable operatorTable,
      final ExprMacroTable macroTable,
      final ObjectMapper jsonMapper,
      final PlannerConfig plannerConfig,
      final DruidSchemaCatalog rootSchema,
      final QueryContext queryContext
  )
  {
    final DateTime utcNow;
    final DateTimeZone timeZone;
    final boolean stringifyArrays;

    final Object stringifyParam = queryContext.get(CTX_SQL_STRINGIFY_ARRAYS);
    final Object tsParam = queryContext.get(CTX_SQL_CURRENT_TIMESTAMP);
    final Object tzParam = queryContext.get(CTX_SQL_TIME_ZONE);

    if (tsParam != null) {
      utcNow = new DateTime(tsParam, DateTimeZone.UTC);
    } else {
      utcNow = new DateTime(DateTimeZone.UTC);
    }

    if (tzParam != null) {
      timeZone = DateTimes.inferTzFromString(String.valueOf(tzParam));
    } else {
      timeZone = plannerConfig.getSqlTimeZone();
    }

    if (stringifyParam != null) {
      stringifyArrays = Numbers.parseBoolean(stringifyParam);
    } else {
      stringifyArrays = true;
    }

    return new PlannerContext(
        sql,
        operatorTable,
        macroTable,
        jsonMapper,
        plannerConfig.withOverrides(queryContext),
        utcNow.withZone(timeZone),
        stringifyArrays,
        rootSchema,
        queryContext
    );
  }

  public DruidOperatorTable getOperatorTable()
  {
    return operatorTable;
  }

  public ExprMacroTable getExprMacroTable()
  {
    return macroTable;
  }

  public ObjectMapper getJsonMapper()
  {
    return jsonMapper;
  }

  public PlannerConfig getPlannerConfig()
  {
    return plannerConfig;
  }

  public DateTime getLocalNow()
  {
    return localNow;
  }

  public DateTimeZone getTimeZone()
  {
    return localNow.getZone();
  }

  @Nullable
  public String getSchemaResourceType(String schema, String resourceName)
  {
    return rootSchema.getResourceType(schema, resourceName);
  }

  public QueryContext getQueryContext()
  {
    return queryContext;
  }

  public boolean isStringifyArrays()
  {
    return stringifyArrays;
  }

  public List<TypedValue> getParameters()
  {
    return parameters;
  }

  public AuthenticationResult getAuthenticationResult()
  {
    return Preconditions.checkNotNull(authenticationResult, "Authentication result not available");
  }

  public String getSql()
  {
    return sql;
  }

  public String getSqlQueryId()
  {
    return sqlQueryId;
  }

  public CopyOnWriteArrayList<String> getNativeQueryIds()
  {
    return nativeQueryIds;
  }

  public void addNativeQueryId(String queryId)
  {
    this.nativeQueryIds.add(queryId);
  }

  @Nullable
  public String getPlanningError()
  {
    return planningError;
  }

  /**
   * Sets the planning error in the context that will be shown to the user if the SQL query cannot be translated
   * to a native query. This error is often a hint and thus should be phrased as such. Also, the final plan can
   * be very different from SQL that user has written. So again, the error should be phrased to indicate this gap
   * clearly.
   */
  public void setPlanningError(String formatText, Object... arguments)
  {
    planningError = StringUtils.nonStrictFormat(formatText, arguments);
  }

  public DataContext createDataContext(final JavaTypeFactory typeFactory, List<TypedValue> parameters)
  {
    class DruidDataContext implements DataContext
    {
      private final Map<String, Object> base_context = ImmutableMap.of(
          DataContext.Variable.UTC_TIMESTAMP.camelName, localNow.getMillis(),
          DataContext.Variable.CURRENT_TIMESTAMP.camelName, localNow.getMillis(),
          DataContext.Variable.LOCAL_TIMESTAMP.camelName, new Interval(
              new DateTime("1970-01-01T00:00:00.000", localNow.getZone()),
              localNow
          ).toDurationMillis(),
          DataContext.Variable.TIME_ZONE.camelName, localNow.getZone().toTimeZone().clone()
      );
      private final Map<String, Object> context;

      DruidDataContext()
      {
        ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
        builder.putAll(base_context);
        int i = 0;
        for (TypedValue parameter : parameters) {
          builder.put("?" + i, parameter.value);
          i++;
        }
        if (authenticationResult != null) {
          builder.put(DATA_CTX_AUTHENTICATION_RESULT, authenticationResult);
        }
        context = builder.build();
      }

      @Override
      public SchemaPlus getRootSchema()
      {
        throw new UnsupportedOperationException();
      }

      @Override
      public JavaTypeFactory getTypeFactory()
      {
        return typeFactory;
      }

      @Override
      public QueryProvider getQueryProvider()
      {
        throw new UnsupportedOperationException();
      }

      @Override
      public Object get(final String name)
      {
        return context.get(name);
      }
    }

    return new DruidDataContext();
  }


  public Access getAuthorizationResult()
  {
    return Preconditions.checkNotNull(authorizationResult, "Authorization result not available");
  }

  public void setParameters(List<TypedValue> parameters)
  {
    this.parameters = Preconditions.checkNotNull(parameters, "parameters");
  }

  public void setAuthenticationResult(AuthenticationResult authenticationResult)
  {
    if (this.authenticationResult != null) {
      // It's a bug if this happens, because setAuthenticationResult should be called exactly once.
      throw new ISE("Authentication result has already been set");
    }

    this.authenticationResult = Preconditions.checkNotNull(authenticationResult, "authenticationResult");
  }

  public void setAuthorizationResult(Access access)
  {
    if (this.authorizationResult != null) {
      // It's a bug if this happens, because setAuthorizationResult should be called exactly once.
      throw new ISE("Authorization result has already been set");
    }

    this.authorizationResult = Preconditions.checkNotNull(access, "authorizationResult");
  }

  public Set<ResourceAction> getResourceActions()
  {
    return Preconditions.checkNotNull(resourceActions, "Resources not available");
  }

  public void setResourceActions(Set<ResourceAction> resourceActions)
  {
    if (this.resourceActions != null) {
      // It's a bug if this happens, because setResourceActions should be called exactly once.
      throw new ISE("Resources have already been set");
    }

    this.resourceActions = Preconditions.checkNotNull(resourceActions, "resourceActions");
  }

  public void setQueryMaker(QueryMaker queryMaker)
  {
    if (this.queryMaker != null) {
      // It's a bug if this happens, because setQueryMaker should be called exactly once.
      throw new ISE("QueryMaker has already been set");
    }

    this.queryMaker = Preconditions.checkNotNull(queryMaker, "queryMaker");
  }

  public QueryMaker getQueryMaker()
  {
    return Preconditions.checkNotNull(queryMaker, "QueryMaker not available");
  }

  public VirtualColumnRegistry getJoinExpressionVirtualColumnRegistry()
  {
    return joinExpressionVirtualColumnRegistry;
  }

  public void setJoinExpressionVirtualColumnRegistry(VirtualColumnRegistry joinExpressionVirtualColumnRegistry)
  {
    this.joinExpressionVirtualColumnRegistry = joinExpressionVirtualColumnRegistry;
  }
}
