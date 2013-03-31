package com.facebook.presto.server;

import com.facebook.presto.ResultsIterator;
import com.facebook.presto.cli.ClientSession;
import com.facebook.presto.cli.HttpQueryClient;
import com.facebook.presto.execution.QueryInfo;
import com.facebook.presto.operator.Operator;
import com.facebook.presto.tuple.TupleInfo;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Uninterruptibles;
import io.airlift.http.client.AsyncHttpClient;
import io.airlift.http.server.HttpServerInfo;
import io.airlift.json.JsonCodec;

import javax.inject.Inject;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import java.net.URI;
import java.util.Iterator;
import java.util.List;

import static com.facebook.presto.PrestoHeaders.PRESTO_CATALOG;
import static com.facebook.presto.PrestoHeaders.PRESTO_SCHEMA;
import static com.facebook.presto.PrestoHeaders.PRESTO_USER;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

@Path("/v1/execute")
public class ExecuteResource
{
    private final HttpServerInfo serverInfo;
    private final AsyncHttpClient httpClient;
    private final JsonCodec<QueryInfo> queryInfoCodec;

    @Inject
    public ExecuteResource(HttpServerInfo serverInfo, @ForExecute AsyncHttpClient httpClient, JsonCodec<QueryInfo> queryInfoCodec)
    {
        this.serverInfo = checkNotNull(serverInfo, "serverInfo is null");
        this.httpClient = checkNotNull(httpClient, "httpClient is null");
        this.queryInfoCodec = checkNotNull(queryInfoCodec, "queryInfoCodec is null");
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Response createQuery(
            String query,
            @HeaderParam(PRESTO_USER) String user,
            @HeaderParam(PRESTO_CATALOG) String catalog,
            @HeaderParam(PRESTO_SCHEMA) String schema)
    {
        checkNotNull(query, "query is null");

        ClientSession session = new ClientSession(serverUri(), user, catalog, schema, false);

        HttpQueryClient queryClient = new HttpQueryClient(session, query, httpClient, queryInfoCodec);

        QueryInfo queryInfo = waitForResults(queryClient);
        Operator operator = queryClient.getResultsOperator();

        List<String> fieldNames = ImmutableList.copyOf(queryInfo.getFieldNames());
        List<TupleInfo.Type> fieldTypes = getFieldTypes(operator.getTupleInfos());
        List<Column> columns = createColumnList(fieldNames, fieldTypes);

        ResultsIterator resultsIterator = new ResultsIterator(operator);
        QueryResults results = new QueryResults(columns, resultsIterator);

        return Response.ok(results, MediaType.APPLICATION_JSON_TYPE).build();
    }

    private URI serverUri()
    {
        checkState(serverInfo.getHttpUri() != null, "No HTTP URI for this server (HTTP disabled?)");
        return serverInfo.getHttpUri();
    }

    private static QueryInfo waitForResults(HttpQueryClient queryClient)
    {
        QueryInfo queryInfo = waitForQuery(queryClient);
        if (queryInfo == null) {
            throw new RuntimeException("Query is gone (server restarted?)");
        }
        if (queryInfo.getState().isDone()) {
            switch (queryInfo.getState()) {
                case CANCELED:
                    throw new RuntimeException(format("Query was canceled (#%s)", queryInfo.getQueryId()));
                case FAILED:
                    throw new RuntimeException(failureMessage(queryInfo));
                default:
                    throw new RuntimeException(format("Query finished with no output (#%s)", queryInfo.getQueryId()));
            }
        }
        return queryInfo;
    }

    private static QueryInfo waitForQuery(HttpQueryClient queryClient)
    {
        int errors = 0;
        while (true) {
            try {
                QueryInfo queryInfo = queryClient.getQueryInfo(false);

                // if query is no longer running, finish
                if ((queryInfo == null) || queryInfo.getState().isDone()) {
                    return queryInfo;
                }

                // check if there is there is pending output
                if (queryInfo.resultsPending()) {
                    return queryInfo;
                }

                // TODO: add a blocking method on server
                Uninterruptibles.sleepUninterruptibly(100, MILLISECONDS);
            }
            catch (Exception e) {
                errors++;
                if (errors > 10) {
                    throw new RuntimeException("Error waiting for query results", e);
                }
            }
        }
    }

    private static String failureMessage(QueryInfo queryInfo)
    {
        if (queryInfo.getFailureInfo() == null) {
            return format("Query failed for an unknown reason (#%s)", queryInfo.getQueryId());
        }
        return format("Query failed (#%s): %s", queryInfo.getQueryId(), queryInfo.getFailureInfo().getMessage());
    }

    private static List<TupleInfo.Type> getFieldTypes(List<TupleInfo> tupleInfos)
    {
        ImmutableList.Builder<TupleInfo.Type> list = ImmutableList.builder();
        for (TupleInfo tupleInfo : tupleInfos) {
            list.addAll(tupleInfo.getTypes());
        }
        return list.build();
    }

    private static List<Column> createColumnList(List<String> names, List<TupleInfo.Type> types)
    {
        checkArgument(names.size() == types.size(), "names and types size mismatch");
        ImmutableList.Builder<Column> list = ImmutableList.builder();
        for (int i = 0; i < names.size(); i++) {
            list.add(new Column(names.get(i), types.get(i)));
        }
        return list.build();
    }

    public static class QueryResults
    {
        private final List<Column> columns;
        private final Iterator<List<Object>> data;

        public QueryResults(List<Column> columns, Iterator<List<Object>> data)
        {
            this.columns = checkNotNull(columns, "columns is null");
            this.data = checkNotNull(data, "data is null");
        }

        @JsonProperty
        public List<Column> getColumns()
        {
            return columns;
        }

        @JsonProperty
        public Iterator<List<Object>> getData()
        {
            return data;
        }
    }

    public static class Column
    {
        private final String name;
        private final TupleInfo.Type type;

        public Column(String name, TupleInfo.Type type)
        {
            this.name = checkNotNull(name, "name is null");
            this.type = checkNotNull(type, "type is null");
        }

        @JsonProperty
        public String getName()
        {
            return name;
        }

        @JsonProperty
        public String getType()
        {
            switch (type) {
                case FIXED_INT_64:
                    return "bigint";
                case DOUBLE:
                    return "double";
                case VARIABLE_BINARY:
                    return "varchar";
            }
            throw new IllegalArgumentException("unhandled type: " + type);
        }
    }
}
