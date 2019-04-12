package www.kailing.pub;

import org.junit.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.util.List;

/**
 * 测试ISOLATION_REPEATABLE_READ(可重复读)事务隔离级别下的 幻读问题
 * 测试步骤：先在testTxA()适当位置加上断点，然后启动事务A，接着启动事务B，然后释放事务A的断点
 *
 * @author: kl @kailing.pub
 * @date: 2019/4/4
 */
public class RepeatableRead_Phantom_Test extends BaseTest {
    /**
     * 事务A：
     * 事务A先去查找记录ID=30的记录，如果不存在就插入，此时，如果查询出来不存在，在是事务A保存记录的间隙事务B新增了ID为30的记录
     * 那么事务A的插入就会失败，但是明明刚刚查询出来的记录不存在的，这就出现了幻读的问题，就像幻觉一样
     */
    @Test
    public void testTxA() {
        DataSourceTransactionManager dataSourceTransactionManager = new DataSourceTransactionManager(jdbcTemplate.getDataSource());
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        TransactionStatus status = dataSourceTransactionManager.getTransaction(def);
        try {
            List list = jdbcTemplate.queryForList("SELECT * FROM user WHERE id = 31");
            if (list.size() == 0) {//此处需要断点模拟操作间隙。等待事务B提交完成
                jdbcTemplate.execute("INSERT INTO USER (id,name,card,age) VALUES (31,'kl','00',66)");
            }
            dataSourceTransactionManager.commit(status);
        } catch (Exception e) {
            System.err.println(e.fillInStackTrace().toString());
            dataSourceTransactionManager.rollback(status);
        }
    }

    /**
     * 事务B：在事务A已经打开的情况下，事务B后开启但是先完成事务提交。会导致事务A发生幻读现象
     */
    @Test
    public void testTxB() {
        DataSourceTransactionManager dataSourceTransactionManager = new DataSourceTransactionManager(jdbcTemplate.getDataSource());
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        TransactionStatus status = dataSourceTransactionManager.getTransaction(def);
        try {
            List list = jdbcTemplate.queryForList("SELECT * FROM user WHERE id = 31");
            if (0 == list.size()) {
                jdbcTemplate.execute("INSERT INTO USER (id,name,card,age) VALUES (31,'kl','00',66)");
            }
            dataSourceTransactionManager.commit(status);
        } catch (Exception e) {
            System.err.println(e.fillInStackTrace().toString());
            dataSourceTransactionManager.rollback(status);
        }
    }

}

