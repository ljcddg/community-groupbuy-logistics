package com.community.common.util;

import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Neo4j 查询结果映射工具。
 *
 * <h3>为什么需要它（踩坑记录）</h3>
 * <p>spring-data-neo4j 7.x 的 {@code DefaultNeo4jClient} 在
 * {@code fetchAs(Map.class)} 时走的是 {@code SingleValueMappingFunction}，
 * 只有当 RETURN 子句<b>只返回一个值</b>时才能自动转换；
 * 一旦 RETURN 了多个列（如 pickupId / distance / name），
 * 就会抛：
 * <pre>
 * java.lang.IllegalArgumentException:
 *   Records with more than one value cannot be converted without a mapper
 * </pre>
 * <p>正确姿势是显式提供 mapper：{@code .mappedBy((typeSystem, record) -> ...)}，
 * 自己从 {@link Record} 里按列名取值。本工具把这个样板封装成
 * 一行调用，避免每个 DAO 方法都写一遍 lambda。
 *
 * <h3>用法</h3>
 * <pre>{@code
 * List<Map<String, Object>> rows = Neo4jRecordUtil.queryMaps(
 *         neo4jClient, cypher,
 *         Map.of("pickupId", pickupId),
 *         "pickupId", "pickupName", "remainCapacity", "distance");
 * }</pre>
 */
public final class Neo4jRecordUtil {

    private Neo4jRecordUtil() {
    }

    /**
     * 执行 Cypher 并把每行映射成一个 Map（列名 -> 值）。
     *
     * @param client       Neo4jClient
     * @param cypher       Cypher 语句
     * @param params       命名参数（key = 占位符名，不含 $）
     * @param returnFields RETURN 的列名，顺序即 Map 顺序
     */
    public static List<Map<String, Object>> queryMaps(Neo4jClient client,
                                                      String cypher,
                                                      Map<String, Object> params,
                                                      String... returnFields) {
        return queryMaps(client, cypher, params, Neo4jRecordUtil::plainValue, returnFields);
    }

    /**
     * 执行 Cypher 并把每行映射成 Map，值经过 {@code converter} 转换。
     *
     * <p>spring-data-neo4j 会把 Neo4j 的整数映射成 {@code Long}，
     * 若业务实体字段是 {@code Integer}，直接强转会 ClassCastException，
     * 这时就要自定义 converter（例如 {@code Neo4jRecordUtil::toInt}）。
     */
    public static <T> List<Map<String, Object>> queryMaps(Neo4jClient client,
                                                          String cypher,
                                                          Map<String, Object> params,
                                                          Function<Object, T> converter,
                                                          String... returnFields) {
        // 注意：Neo4jClient.query(...) 返回 UnboundRunnableSpec；
        // 一旦调用 .bind(...) 就退化为 RunnableSpec（两者不构成继承关系，不能互相赋值）
        Neo4jClient.RunnableSpec spec = client.query(cypher);
        if (params != null) {
            for (Map.Entry<String, Object> e : params.entrySet()) {
                spec = spec.bind(e.getValue()).to(e.getKey());
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        spec.fetchAs(Map.class)
                .mappedBy((typeSystem, record) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (String field : returnFields) {
                        row.put(field, converter.apply(readValue(record, field)));
                    }
                    return row;
                })
                .all()
                .forEach(rows::add);
        return rows;
    }

    /** 从 Record 安全取值：列不存在返回 null */
    private static Object readValue(Record record, String field) {
        if (!record.containsKey(field)) {
            return null;
        }
        Value v = record.get(field);
        return v == null || v.isNull() ? null : v.asObject();
    }

    /** 默认转换：原样返回（Neo4j 数值多为 Long / Double） */
    public static Object plainValue(Object raw) {
        return raw;
    }

    /** 转 Long */
    public static Long toLong(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.valueOf(raw.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 转 Integer */
    public static Integer toInt(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.valueOf(raw.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 转 String */
    public static String toStr(Object raw) {
        return raw == null ? null : raw.toString();
    }
}
