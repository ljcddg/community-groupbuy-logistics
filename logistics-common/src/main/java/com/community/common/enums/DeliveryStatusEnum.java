package com.community.common.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 配送单状态机 —— 枚举 + 转移表。
 *
 * <p>面试点（这是被追问最多的地方，务必讲清）：
 * <ol>
 *   <li><b>为什么用枚举而不是纯数据库配置？</b> 状态集合有限且与代码强耦合，
 *       枚举可编译期检查、IDE 可跳转；转移表用静态 Map 描述"谁能到谁"，一眼看清全部合法路径。</li>
 *   <li><b>怎么防非法流转？</b> {@link #canTransferTo} 在应用层先校验一次（快速失败，给出友好提示），
 *       数据库层再用 {@code UPDATE ... WHERE status = #{expectStatus}} 做 CAS 兜底，
 *       两层防护——应用层防误操作，DB 层防并发。</li>
 *   <li><b>并发截单/并发更新怎么办？</b> 不用行锁，用乐观锁（status 当版本号），
 *       affected rows = 0 即认为被别人抢先改过，返回"正被处理"。</li>
 * </ol>
 */
@Getter
public enum DeliveryStatusEnum {

    /** 待分配：配送单已生成，尚未指派司机 */
    PENDING_ASSIGN(0, "待分配"),
    /** 待发车：已指派司机，等待出发 */
    ASSIGNED(1, "已分配司机"),
    /** 配送中 */
    DELIVERING(2, "配送中"),
    /** 已送达自提点 */
    ARRIVED(3, "已送达自提点"),
    /** 已完成（自提点签收） */
    COMPLETED(4, "已完成"),
    /** 异常（破损/丢失/拒收） */
    EXCEPTION(5, "配送异常"),
    /** 已取消 */
    CANCELLED(6, "已取消"),
    /** 补发中（异常单走补发流程） */
    RESHEDDING(7, "补发中");

    private final Integer code;
    private final String desc;

    DeliveryStatusEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    private static final Map<Integer, DeliveryStatusEnum> CODE_MAP = new HashMap<>();

    /** 转移表：key = 当前状态，value = 允许流转到的目标状态集合 */
    private static final Map<DeliveryStatusEnum, Set<DeliveryStatusEnum>> TRANSFER_TABLE =
            new EnumMap<>(DeliveryStatusEnum.class);

    static {
        for (DeliveryStatusEnum e : values()) {
            CODE_MAP.put(e.code, e);
        }

        TRANSFER_TABLE.put(PENDING_ASSIGN, EnumSet.of(ASSIGNED, CANCELLED));
        TRANSFER_TABLE.put(ASSIGNED, EnumSet.of(DELIVERING, CANCELLED));
        TRANSFER_TABLE.put(DELIVERING, EnumSet.of(ARRIVED, EXCEPTION));
        TRANSFER_TABLE.put(ARRIVED, EnumSet.of(COMPLETED));
        TRANSFER_TABLE.put(EXCEPTION, EnumSet.of(RESHEDDING, CANCELLED, COMPLETED));
        TRANSFER_TABLE.put(RESHEDDING, EnumSet.of(DELIVERING, CANCELLED));
        // 终态：COMPLETED / CANCELLED 无后继
        TRANSFER_TABLE.put(COMPLETED, Collections.emptySet());
        TRANSFER_TABLE.put(CANCELLED, Collections.emptySet());
    }

    public static DeliveryStatusEnum of(Integer code) {
        return code == null ? null : CODE_MAP.get(code);
    }

    /** 当前状态是否允许流转到 target */
    public boolean canTransferTo(DeliveryStatusEnum target) {
        if (target == null) {
            return false;
        }
        // 幂等：同状态重复提交视为合法（no-op），避免 MQ 重投时误判
        if (this == target) {
            return true;
        }
        return TRANSFER_TABLE.getOrDefault(this, Collections.emptySet()).contains(target);
    }

    /** 当前状态可流转的全部目标状态（用于前端按钮渲染 / 排错） */
    public Set<DeliveryStatusEnum> nextStates() {
        return TRANSFER_TABLE.getOrDefault(this, Collections.emptySet());
    }

    public boolean isFinal() {
        return this == COMPLETED || this == CANCELLED;
    }

    /** 调试用：打印全量转移表 */
    public static String dumpTable() {
        StringBuilder sb = new StringBuilder("DeliveryStatus transfer table:\n");
        for (DeliveryStatusEnum s : values()) {
            sb.append("  ").append(s.name())
                    .append(" -> ").append(s.nextStates())
                    .append('\n');
        }
        return sb.toString();
    }

    /** 保留 Arrays 引用以避免未使用导入告警（列表化输出用） */
    public static Set<DeliveryStatusEnum> all() {
        return Collections.unmodifiableSet(EnumSet.allOf(DeliveryStatusEnum.class));
    }

    static {
        // 触发一次校验：确保每个状态都在转移表中有定义
        assert Arrays.stream(values()).allMatch(TRANSFER_TABLE::containsKey)
                : "转移表定义不完整，请检查 DeliveryStatusEnum";
    }
}
