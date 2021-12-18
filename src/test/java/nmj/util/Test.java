

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Predicate;

/**
 * 打印完整可执行sql
 */
@Intercepts({
        @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class, CacheKey.class, BoundSql.class}),
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})
})
public class MybatisSqlPrinter implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(MybatisSqlPrinter.class);

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /**
     * 是否启用本插件的功能
     */
    private boolean enable = true;

    /**
     * 是否只打印超时的sql, 默认全部打印
     */
    private boolean onlyWarnSql = true;

    /**
     * 打印警告标志如果执行时间超过多久(单位毫秒), 默认500毫秒
     */
    private long warnSqlMs = 500;

    /**
     * 警告标志, 默认为 ===== SLOW SQL =====
     */
    private String warnSqlMark = "===== SLOW SQL =====";

    /**
     * 打印过滤器, 返回true为打印
     */
    private List<Predicate<MappedStatement>> printFilters = new ArrayList<>();

    public void setEnable(boolean enable) {
        this.enable = enable;
    }

    public void setOnlyWarnSql(boolean onlyWarnSql) {
        this.onlyWarnSql = onlyWarnSql;
    }

    public void setWarnSqlMs(long warnSqlMs) {
        this.warnSqlMs = warnSqlMs;
    }

    public void setWarnSqlMark(String warnSqlMark) {
        this.warnSqlMark = warnSqlMark;
    }

    public void addPrintFilters(Predicate<MappedStatement> printFilter) {
        if (printFilter == null) {
            throw new IllegalArgumentException();
        }
        this.printFilters.add(printFilter);
    }

    @Autowired
    private void init(List<SqlSessionFactory> sqlSessionFactories) throws Throwable {
        for (SqlSessionFactory sqlSessionFactory : sqlSessionFactories) {
            initPlugin(sqlSessionFactory);
        }
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if (!enable) {
            // 不启用就尽可能减少消耗
            return invocation.proceed();
        }
        long t0 = System.currentTimeMillis();
        Object result = null;
        try {
            result = invocation.proceed();
        } finally {
            try {
                long t1 = System.currentTimeMillis();
                SqlInfo sqlInfo = getSqlInfo(invocation);
                long t2 = System.currentTimeMillis();
                if (sqlInfo != null) {
                    printSqlInfo(result, t0, t1, t2, sqlInfo);
                }
            } catch (Throwable e) {
                log.error("print sql error", e);
            }
        }
        return result;
    }

    @Override
    public Object plugin(Object target) {
        if (target instanceof Executor) {
            return Plugin.wrap(target, this);
        }
        return target;
    }

    @Override
    public void setProperties(Properties properties) {
    }

    private static final class SqlInfo {
        private final String sql;
        private final MappedStatement mappedStatement;

        public SqlInfo(String sql, MappedStatement mappedStatement) {
            this.sql = sql;
            this.mappedStatement = mappedStatement;
        }

        public String getSql() {
            return sql;
        }

        public MappedStatement getMappedStatement() {
            return mappedStatement;
        }
    }

    private SqlInfo getSqlInfo(Invocation invocation) {
        Object[] args = invocation.getArgs();
        MappedStatement mappedStatement = ((MappedStatement) args[0]);

        // 加入动态过滤功能, 支持用户自定义过滤逻辑
        boolean doPrint = true;
        for (Predicate<MappedStatement> printFilter : printFilters) {
            if (!printFilter.test(mappedStatement)) {
                doPrint = false;
                break;
            }
        }
        if (!doPrint) {
            return null;
        }

        Object parameter = args.length > 1 ? args[1] : null;
        BoundSql boundSql = mappedStatement.getBoundSql(parameter);
        List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
        Object parameterObject = boundSql.getParameterObject();
        if (parameterMappings == null || parameterMappings.isEmpty() || parameterObject == null) {
            String sql = beautify(boundSql.getSql());
            return new SqlInfo(sql, mappedStatement);
        }
        SqlPrinter sqlPrinter = new SqlPrinter(boundSql.getSql());
        Configuration configuration = mappedStatement.getConfiguration();
        TypeHandlerRegistry typeHandlerRegistry = configuration.getTypeHandlerRegistry();
        if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
            sqlPrinter.setNextValue(null, parameterObject);
        } else {
            MetaObject metaObject = configuration.newMetaObject(parameterObject);
            for (ParameterMapping parameterMapping : parameterMappings) {
                String name = parameterMapping.getProperty();
                Object value = metaObject.hasGetter(name) ? metaObject.getValue(name) : boundSql.getAdditionalParameter(name);
                sqlPrinter.setNextValue(name, value);
            }
        }
        String sql = sqlPrinter.getSql();
        return new SqlInfo(sql, mappedStatement);
    }

    private void printSqlInfo(Object result, long t0, long t1, long t2, SqlInfo sqlInfo) {
        long total = t2 - t0;
        boolean isWarn = total >= warnSqlMs;
        if (onlyWarnSql && isWarn) {
            return;
        }
        long sqlCost = t1 - t0;
        long printCost = t2 - t1;
        String constInfo = String.format("total %5s ms = %5s ms(mybatis) + %2s ms(print)", total, sqlCost, printCost);
        if (isWarn) {
            constInfo = warnSqlMark + constInfo;
        }
        MappedStatement mappedStatement = sqlInfo.getMappedStatement();
        boolean isCount = (result instanceof Integer) && (SqlCommandType.SELECT != mappedStatement.getSqlCommandType());
        Logger logger = LoggerFactory.getLogger(mappedStatement.getId());
        if (isCount) {
            logger.info("{} affect count {} method {}\n  /* MybatisSqlPrinter */ {}", constInfo, result, mappedStatement.getId(), sqlInfo.getSql());
        } else {
            logger.info("{} method {}\n  /* MybatisSqlPrinter */ {}", constInfo, mappedStatement.getId(), sqlInfo.getSql());
        }
    }

    public static final class SqlPrinter {

        private final String originalSql;
        private final StringBuilder buildSql;
        private int end;

        public SqlPrinter(String originalSql) {
            this.originalSql = beautify(originalSql);
            int originalSqlLength = originalSql.length();
            this.buildSql = new StringBuilder(originalSqlLength);
            this.end = 0;
        }

        public void setNextValue(String name, Object value) {
            String displayValue = getDisplayValue(name, value);
            int index = originalSql.indexOf('?', end);
            buildSql.append(originalSql, end, index)
                    .append(' ')
                    .append(displayValue);
            this.end = index + 1;
        }

        public String getSql() {
            String sql = buildSql
                    .append(' ')
                    .append(originalSql.substring(end))
                    .toString();
            return beautify(sql);
        }

        private String getDisplayValue(String name, Object value) {
            String result;
            if (value == null) {
                result = "NULL";
            } else if (value instanceof Date) {
                result = "'" + DATE_TIME_FORMATTER.format(LocalDateTime.now()) + "'";
            } else if (value instanceof String) {
                result = "'" + value + "'";
            } else {
                result = value + "";
            }
            if (name == null || name.isEmpty() || name.contains("frch_")) {
                return result;
            } else {
                return result + " /*" + name + "*/";
            }
        }
    }

    // 去除所有多余的空格和换行
    private static String beautify(String originalSql) {
        StringBuilder sb = new StringBuilder(originalSql.length());
        char[] chars = originalSql.toCharArray();
        boolean lastIsWhiteSpace = true;
        for (char aChar : chars) {
            if (aChar == '\n') {
                aChar = ' ';
            }
            boolean whitespace = Character.isWhitespace(aChar);
            if (lastIsWhiteSpace && whitespace) {
                lastIsWhiteSpace = true;
                continue;
            }
            lastIsWhiteSpace = whitespace;
            sb.append(aChar);
        }
        return sb.toString();
    }

    private void initPlugin(SqlSessionFactory sqlSessionFactory) throws IllegalAccessException {
        Configuration configuration = sqlSessionFactory.getConfiguration();
        Field interceptorChainF = ReflectionUtils.findField(configuration.getClass(), "interceptorChain");
        interceptorChainF.setAccessible(true);
        Object interceptorChain = interceptorChainF.get(configuration);
        Field interceptorsF = ReflectionUtils.findField(interceptorChain.getClass(), "interceptors");
        interceptorsF.setAccessible(true);
        List<Interceptor> interceptors = (List<Interceptor>) interceptorsF.get(interceptorChain);
        List<Interceptor> newInterceptors = new ArrayList<>();
        // sql打印插件总是在第一个
        newInterceptors.add(this);
        if (interceptors != null) {
            for (Interceptor interceptor : interceptors) {
                if (!Objects.equals(interceptor, this)) {
                    // 防止有多个重复的插件
                    newInterceptors.add(interceptor);
                }
            }
        }
        interceptorsF.set(interceptorChain, newInterceptors);
    }
}
