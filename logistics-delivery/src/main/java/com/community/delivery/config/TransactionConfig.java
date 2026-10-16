package com.community.delivery.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.Configuration;

/**
 * 事务管理器仲裁 —— 把 JDBC 的 {@code transactionManager} 提升为"默认候选"，
 * 解决 JDBC 与 Neo4j 双事务管理器导致 {@code @Transactional} 无法确定用哪个的问题。
 *
 * <h3>问题现象（踩坑记录）</h3>
 * <p>delivery 服务同时依赖 MySQL（MyBatis-Plus）与 Neo4j（spring-data-neo4j），
 * 两个 starter 各自注册了一个事务管理器 Bean：
 * <pre>
 *   transactionManager           (JDBC DataSourceTransactionManager, MySQL)
 *   reactiveTransactionManager   (Neo4j ReactiveTransactionManager)
 * </pre>
 * <p>于是调用任何裸 {@code @Transactional} 方法时抛：
 * <pre>
 * org.springframework.beans.factory.NoUniqueBeanDefinitionException:
 *   No qualifying bean of type 'org.springframework.transaction.TransactionManager'
 *   available: expected single matching bean but found 2:
 *   transactionManager,reactiveTransactionManager
 * </pre>
 *
 * <h3>为什么用 BeanFactoryPostProcessor 而不是 @Primary @Bean</h3>
 * <p>直接再声明一个 {@code @Bean @Primary DataSourceTransactionManager(dataSource)}
 * 会有两个问题：
 * <ol>
 *   <li>与自动配置已注册的 {@code transactionManager} 同名，触发
 *       bean-definition-overriding 告警甚至覆盖行为不确定；</li>
 *   <li>多创建一个事务管理器实例，语义上冗余。</li>
 * </ol>
 * <p>更精准的做法是<b>不加新 Bean，只修改已有 BeanDefinition 的 primary 标志</b>。
 * {@link BeanFactoryPostProcessor} 恰好在 Bean 实例化之前运行，
 * 能拿到 {@link DefaultListableBeanFactory} 修改元数据，零副作用。
 *
 * <p><b>为什么让 JDBC 优先？</b> 本系统所有写操作都在 MySQL
 * （订单、配送单、轨迹、异常记录），Neo4j 只做只读图查询（距离矩阵、相邻点）。
 * 事务语义必须落在业务权威库上 —— <b>"@Primary 给权威库"</b>是可复用的判断准则。
 *
 * <p><b>面试点</b>：多持久化技术共存时，"按类型注入"会因候选不唯一而失败。
 * 解决路径两条：注入点显式指定（精确但每处都要写），或声明默认候选
 * （省事但要求全局语义统一）。本项目选后者，因为写库天然唯一 —— 现实中的 MySQL。
 */
@Configuration
public class TransactionConfig implements BeanFactoryPostProcessor {

    /** JDBC 事务管理器的 Bean 名（Spring Boot 自动配置的默认名） */
    private static final String JDBC_TX_MANAGER = "transactionManager";

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (beanFactory instanceof DefaultListableBeanFactory dlbf) {
            if (dlbf.containsBeanDefinition(JDBC_TX_MANAGER)) {
                // 把 JDBC 事务管理器标记为 primary：
                // 此后所有未显式指定 transactionManager 的 @Transactional
                // 以及按 PlatformTransactionManager 类型的注入，都默认选它。
                dlbf.getBeanDefinition(JDBC_TX_MANAGER).setPrimary(true);
            }
        }
    }
}
